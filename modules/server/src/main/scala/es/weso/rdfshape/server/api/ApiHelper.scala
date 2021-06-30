package es.weso.rdfshape.server.api

import cats.effect._
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import es.weso.rdf.jena.RDFAsJenaModel
import es.weso.rdf.nodes._
import es.weso.rdf.{PrefixMap, RDFBuilder, RDFReasoner}
import es.weso.rdfshape.server.api.Defaults._
import es.weso.rdfshape.server.api.format._
import es.weso.rdfshape.server.api.results._
import es.weso.rdfshape.server.utils.json.JsonUtilsServer._
import es.weso.schema._
import es.weso.schemaInfer._
import es.weso.shacl.converter.Shacl2ShEx
import es.weso.shapemaps.{NodeSelector, ResultShapeMap, ShapeMap}
import es.weso.uml._
import es.weso.utils.IOUtils._
import io.circe._
import org.http4s._
import org.http4s.client.{Client, JavaNetClientBuilder}

object ApiHelper extends LazyLogging {

  private val NoTime = 0L

  private val options = PlantUMLOptions(
    watermark = Some("Generated by [[https://rdfshape.weso.es rdfshape]]")
  )

  def result2json(result: Result): IO[Json] = for {
    emptyRes <- RDFAsJenaModel.empty
    json     <- emptyRes.use(emptyBuilder => result.toJson(emptyBuilder))
  } yield json

  /** Get base URI
    * @return default URI obtained from current folder
    */
  private[api] def getBase: Option[String] = Defaults.relativeBase.map(_.str)

  private[api] def prefixMap2Json(pm: PrefixMap): Json = {
    Json.fromFields(pm.pm.map { case (prefix, iri) =>
      (prefix.str, Json.fromString(iri.getLexicalForm))
    })
  }

  private[api] def resolveUri(baseUri: Uri, urlStr: String): IO[String] = {
    logger.info(s"Handling Uri: $urlStr")
    // TODO: handle timeouts
    Uri
      .fromString(urlStr)
      .fold(
        fail => {
          logger.info(s"Error parsing $urlStr")
          IO.raiseError[String](
            new RuntimeException(
              s"Error resolving $urlStr as URL: ${fail.message}"
            )
          )
        },
        uri => {
          // TODO: The following code is unsafe...
          // implicit val cs: ContextShift[IO] = IO.contextShift(global)
          // implicit val timer: Timer[IO] = IO.timer(global)
          // val blockingPool = Executors.newFixedThreadPool(5)
          // val blocker = Blocker.liftExecutorService(blockingPool)
          val httpClient: Client[IO] = JavaNetClientBuilder[IO].create
          val resolvedUri            = baseUri.resolve(uri)
          logger.info(s"Resolved: $resolvedUri")
          httpClient.expect[String](resolvedUri)
        }
      )
  }

  private[api] def schemaConvert(
      optSchema: Option[String],
      optSchemaFormat: Option[String],
      optSchemaEngine: Option[String],
      optTargetSchemaFormat: Option[String],
      optTargetSchemaEngine: Option[String],
      base: Option[String]
  ): IO[Option[String]] =
    optSchema match {
      case None => IO(None)
      case Some(schemaStr) =>
        val schemaFormat =
          optSchemaFormat.getOrElse(Schemas.defaultSchemaFormat)
        val schemaEngine = optSchemaEngine.getOrElse(Schemas.defaultSchemaName)
        /* val x: EitherT[IO,String,Schema] = Schemas.fromString(schemaStr,
         * schemaFormat, schemaEngine, base) */
        for {
          schema <- Schemas.fromString(
            schemaStr,
            schemaFormat,
            schemaEngine,
            base
          )
          result <- schema.convert(
            optTargetSchemaFormat,
            optTargetSchemaEngine,
            base.map(IRI(_))
          )
        } yield Some(result)
    }

  private[api] def validateStr(
      data: String,
      optDataFormat: Option[DataFormat],
      optSchema: Option[String],
      optSchemaFormat: Option[SchemaFormat],
      optSchemaEngine: Option[String],
      tp: TriggerModeParam,
      optInference: Option[String],
      relativeBase: Option[IRI],
      builder: RDFBuilder
  ): IO[(Result, Option[ValidationTrigger], Long)] = {
    val dp = DataParam.empty.copy(
      data = Some(data),
      dataFormatTextarea = optDataFormat,
      inference = optInference
    )
    val sp = SchemaParam.empty.copy(
      schema = optSchema,
      schemaFormatTextArea = optSchemaFormat,
      schemaEngine = optSchemaEngine
    )

    val result: IO[(Result, Option[ValidationTrigger], Long)] = for {
      pair <- dp.getData(relativeBase)
      (maybeStr, resourceRdf) = pair
      result <- resourceRdf.use(rdf =>
        for {
          pairSchema <- sp.getSchema(Some(rdf))
          (_, eitherSchema) = pairSchema
          schema <- IO.fromEither(
            eitherSchema.leftMap(s =>
              new RuntimeException(s"Error obtaining schema: $s")
            )
          )
          res <- validate(rdf, dp, schema, sp, tp, relativeBase, builder)
        } yield res
      )
    } yield result

    result.attempt.flatMap(_.fold(e => err(e.getMessage), IO.pure))
  }

  private[api] def validate(
      rdf: RDFReasoner,
      dp: DataParam,
      schema: Schema,
      sp: SchemaParam,
      tp: TriggerModeParam,
      relativeBase: Option[IRI],
      builder: RDFBuilder
  ): IO[(Result, Option[ValidationTrigger], Long)] = {
    logger.debug(s"APIHelper: validate")

    val base        = relativeBase.map(_.str) // Some(FileUtils.currentFolderURL)
    val triggerMode = tp.triggerMode
    for {
      pm <- rdf.getPrefixMap
      p  <- tp.getShapeMap(pm, schema.pm)
      (optShapeMapStr, eitherShapeMap) = p
      pair <-
        ValidationTrigger.findTrigger(
          triggerMode.getOrElse(Defaults.defaultTriggerMode),
          optShapeMapStr.getOrElse(""),
          base,
          None,
          None,
          pm,
          schema.pm
        ) match {
          case Left(msg) =>
            err(
              s"Cannot obtain trigger: $triggerMode\nshapeMap: $optShapeMapStr\nmsg: $msg"
            )
          case Right(trigger) =>
            val run = for {
              startTime <- IO { System.nanoTime() }
              result    <- schema.validate(rdf, trigger, builder)
              endTime   <- IO { System.nanoTime() }
              time: Long = endTime - startTime
            } yield (result, Some(trigger), time)
            run.handleErrorWith(e => {
              val msg = s"Error validating: ${e.getMessage}"
              logger.error(msg)
              err(s"Error validating: ${e.getMessage}")
            })
        }
    } yield pair
  }

  private def err(msg: String) =
    IO((Result.errStr(s"Error: $msg"), None, NoTime))

  /* private[server] def query(data: String, optDataFormat: Option[DataFormat],
   * optQuery: Option[String], optInference: Option[String] ): IO[Json] = {
   * optQuery match { case None => IO(Json.Null) case Some(queryStr) => val
   * dataFormat = optDataFormat.getOrElse(defaultDataFormat) val base =
   * Some(IRI(FileUtils.currentFolderURL)) for { basicRdf <-
   * RDFAsJenaModel.fromChars(data, dataFormat.name, base) rdf <-
   * basicRdf.applyInference(optInference.getOrElse("None")) json <-
   * rdf.queryAsJson(queryStr) } yield json } } */
  private[api] def dataExtract(
      rdf: RDFReasoner,
      optData: Option[String],
      optDataFormat: Option[DataFormat],
      optNodeSelector: Option[String],
      optInference: Option[String],
      optEngine: Option[String],
      optSchemaFormat: Option[SchemaFormat],
      optLabelName: Option[String],
      relativeBase: Option[IRI]
  ): IO[DataExtractResult] = {
    val base         = relativeBase.map(_.str)
    val engine       = optEngine.getOrElse(defaultSchemaEngine)
    val schemaFormat = optSchemaFormat.getOrElse(defaultSchemaFormat)
    optNodeSelector match {
      case None =>
        IO.pure(
          DataExtractResult.fromMsg("DataExtract: Node selector not specified")
        )
      case Some(nodeSelector) =>
        val es: ESIO[(Schema, ResultShapeMap)] = for {
          pm       <- io2es(rdf.getPrefixMap)
          selector <- either2es(NodeSelector.fromString(nodeSelector, base, pm))
          eitherResult <- {
            logger.debug(s"Node selector: $selector")

            val inferOptions: InferOptions = InferOptions(
              inferTypePlainNode = true,
              addLabelLang = Some(Lang("en")),
              possiblePrefixMap = PossiblePrefixes.wikidataPrefixMap,
              maxFollowOn = 1,
              followOnLs = List(),
              followOnThreshold = Some(1),
              sortFunction = InferOptions.orderByIRI
            )
            io2es(
              SchemaInfer.runInferSchema(
                rdf,
                selector,
                engine,
                optLabelName.map(IRI(_)).getOrElse(defaultShapeLabel),
                inferOptions
              )
            )
          }
          pair <- either2es(eitherResult)
          str  <- io2es(pair._1.serialize("ShExC"))
          _    <- io2es(IO(logger.debug(s"Extracted; $str")))
        } yield {
          pair
        }
        for {
          either <- run_es(es)
        } yield either.fold(
          err => DataExtractResult.fromMsg(err),
          pair => {
            val (schema, resultShapeMap) = pair
            DataExtractResult.fromExtraction(
              optData,
              optDataFormat,
              schemaFormat.name,
              engine,
              schema,
              resultShapeMap
            )
          }
        )
    }
  }

  private[api] def convertSchema(
      schema: Schema,
      schemaStr: Option[String],
      schemaFormat: SchemaFormat,
      schemaEngine: String,
      optTargetSchemaFormat: Option[SchemaFormat],
      optTargetSchemaEngine: Option[String]
  ): IO[SchemaConversionResult] = {
    val result: IO[SchemaConversionResult] = for {
      pair <- doSchemaConversion(
        schema,
        optTargetSchemaFormat.map(_.name),
        optTargetSchemaEngine
      )
      sourceStr <- schemaStr match {
        case None         => schema.serialize(schemaFormat.name)
        case Some(source) => IO(source)
      }
      (resultStr, resultShapeMap) = pair
    } yield SchemaConversionResult.fromConversion(
      sourceStr,
      schemaFormat.name,
      schemaEngine,
      optTargetSchemaFormat.map(_.name),
      optTargetSchemaEngine,
      resultStr,
      resultShapeMap
    )

    for {
      either <- result.attempt
    } yield either.fold(
      err => SchemaConversionResult.fromMsg(s"error converting schema: $err"),
      identity
    )
  }

  private def doSchemaConversion(
      schema: Schema,
      targetSchemaFormat: Option[String],
      optTargetSchemaEngine: Option[String]
  ): IO[(String, ShapeMap)] = {
    logger.debug(
      s"Schema conversion, name: ${schema.name}, targetSchema: $targetSchemaFormat"
    )
    val default = for {
      str <- schema.convert(targetSchemaFormat, optTargetSchemaEngine, None)
    } yield (str, ShapeMap.empty)
    schema match {
      case shacl: ShaclexSchema =>
        optTargetSchemaEngine.map(_.toUpperCase()) match {
          case Some("SHEX") =>
            logger.debug("Schema conversion: SHACLEX -> SHEX")
            Shacl2ShEx
              .shacl2ShEx(shacl.schema)
              .fold(
                e =>
                  IO.raiseError(
                    new RuntimeException(
                      s"Error converting SHACL -> ShEx: $e"
                    )
                  ),
                pair => {
                  val (schema, shapeMap) = pair
                  logger.debug(s"shapeMap: $shapeMap")
                  for {
                    emptyBuilder <- RDFAsJenaModel.empty
                    str <- emptyBuilder.use(builder =>
                      es.weso.shex.Schema.serialize(
                        schema,
                        targetSchemaFormat.getOrElse("SHEXC"),
                        None,
                        builder
                      )
                    )
                  } yield (str, shapeMap)
                }
              )
          case _ => default
        }
      case _ => default
    }
  }

  private[api] def shapeInfer(
      rdf: RDFReasoner,
      optNodeSelector: Option[String],
      optInference: Option[String],
      optEngine: Option[String],
      optSchemaFormat: Option[SchemaFormat],
      optLabelName: Option[String],
      relativeBase: Option[IRI],
      withUml: Boolean
  ): ESIO[Json] = {
    val base         = relativeBase.map(_.str)
    val engine       = optEngine.getOrElse(defaultSchemaEngine)
    val schemaFormat = optSchemaFormat.getOrElse(defaultSchemaFormat)
    optNodeSelector match {
      case None => ok_es(Json.Null)
      case Some(nodeSelector) =>
        for {
          pm       <- io2es(rdf.getPrefixMap)
          selector <- either2es(NodeSelector.fromString(nodeSelector, base, pm))
          eitherResult <- io2es {
            logger.debug(s"Selector: $selector")

            SchemaInfer.runInferSchema(
              rdf,
              selector,
              engine,
              optLabelName.map(IRI(_)).getOrElse(defaultShapeLabel)
            )
          }
          result <- either2es(eitherResult)
          (schemaInfer, resultMap) = result
          maybePair <-
            if(withUml)
              either2es(Schema2UML.schema2UML(schemaInfer).map(Some(_)))
            else ok_es(None)
          maybeSvg <- io2es(maybePair match {
            case None => IO.pure(None)
            case Some(pair) =>
              val (uml, warnings) = pair
              uml.toSVG(options).map(Some(_))
          })
          str <- io2es(schemaInfer.serialize(schemaFormat.name))
        } yield Json.fromFields(
          List(
            ("inferredShape", Json.fromString(str)),
            ("format", Json.fromString(schemaFormat.name)),
            ("engine", Json.fromString(engine)),
            ("nodeSelector", Json.fromString(nodeSelector))
          ) ++
            maybeField(
              maybePair,
              "uml",
              (pair: (UML, List[String])) => {
                val (uml, warnings) = pair
                Json.fromString(uml.toPlantUML(options))
              }
            ) ++
            maybeField(maybeSvg, "svg", Json.fromString)
        )
    }
  }

  private[api] def dataFormatOrDefault(df: Option[String]): String =
    df.getOrElse(DataFormats.defaultFormatName)

  private[api] def dataInfoFromString(
      data: String,
      dataFormatStr: String
  ): IO[Json] = {
    val either: ESIO[Json] = for {
      dataFormat <- either2es(DataFormat.fromString(dataFormatStr))
      json <- io2es(
        RDFAsJenaModel
          .fromChars(data, dataFormat.name)
          .flatMap(_.use(rdf => dataInfo(rdf, Some(data), Some(dataFormat))))
      )
    } yield json

    either.fold(e => DataInfoResult.fromMsg(e).toJson, identity)
  }

  /* private[server] def getSchema(sv: SchemaValue): EitherT[IO,String,Schema] =
   * { val schemaEngine = sv.currentSchemaEngine val schemaFormat =
   * sv.currentSchemaFormat val schemaStr = sv.schema.getOrElse("") val base =
   * Some(FileUtils.currentFolderURL) Schemas.fromString(schemaStr,
   * schemaFormat.name, schemaEngine, base) } */

  private[api] def dataInfo(
      rdf: RDFReasoner,
      data: Option[String],
      dataFormat: Option[DataFormat]
  ): IO[Json] = {
    val either: IO[Either[Throwable, DataInfoResult]] = (for {
      numberStatements <- rdf.getNumberOfStatements()
      predicates       <- rdf.predicates().compile.toList
      pm               <- rdf.getPrefixMap
    } yield DataInfoResult.fromData(
      data,
      dataFormat,
      predicates.toSet,
      numberStatements,
      pm
    )).attempt
    either.map(
      _.fold(e => DataInfoResult.fromMsg(e.getMessage).toJson, _.toJson)
    )
  }

  private[api] def schemaInfo(schema: Schema): Json = {
    val info = schema.info
    SchemaInfoReply(
      Some(info.schemaName),
      Some(info.schemaEngine),
      info.isWellFormed,
      schema.shapes,
      schema.pm.pm.toList.map { case (prefix, iri) => (prefix.str, iri.str) },
      info.errors
    ).toJson
  }

  private[api] def schemaCytoscape(schema: Schema): Json = {
    val eitherJson = for {
      pair <- Schema2UML.schema2UML(schema)
    } yield {
      val (uml, warnings) = pair
      uml.toJson
    }
    eitherJson.fold(
      e =>
        Json.fromFields(
          List(
            ("error", Json.fromString(s"Error converting to schema 2 JSON: $e"))
          )
        ),
      identity
    )
  }

  private[api] def schemaVisualize(schema: Schema): IO[Json] = for {
    pair <- schema2SVG(schema)
  } yield {
    val (svg, plantuml) = pair
    val info            = schema.info
    val fields: List[(String, Json)] =
      List(
        ("schemaName", Json.fromString(info.schemaName)),
        ("schemaEngine", Json.fromString(info.schemaEngine)),
        ("wellFormed", Json.fromBoolean(info.isWellFormed)),
        ("errors", Json.fromValues(info.errors.map(Json.fromString))),
        ("parsed", Json.fromString("Parsed OK")),
        ("svg", Json.fromString(svg)),
        ("plantUML", Json.fromString(plantuml))
      )
    Json.fromFields(fields)
  }

  private[api] def schema2SVG(schema: Schema): IO[(String, String)] = {
    val eitherUML = Schema2UML.schema2UML(schema)
    eitherUML.fold(
      e => IO.pure((s"SVG conversion: $e", s"Error converting UML: $e")),
      pair => {
        val (uml, warnings) = pair
        logger.debug(s"UML converted: $uml")
        (for {
          str <- uml.toSVG(options)
        } yield {
          (str, uml.toPlantUML(options))
        }).handleErrorWith(e =>
          IO.pure(
            (
              s"SVG conversion error: ${e.getMessage}",
              uml.toPlantUML(options)
            )
          )
        )
      }
    )
  }

  private[api] def mkJsonErr(msg: String) =
    Json.fromFields(List(("error", Json.fromString(msg))))

  case class SchemaInfoReply(
      schemaName: Option[String],
      schemaEngine: Option[String],
      wellFormed: Boolean,
      shapes: List[String],
      shapesPrefixMap: List[(String, String)],
      errors: List[String]
  ) {
    def toJson: Json = Json.fromFields(
      List(
        ("schemaName", schemaName.fold(Json.Null)(Json.fromString)),
        ("schemaEngine", schemaEngine.fold(Json.Null)(Json.fromString)),
        ("wellFormed", Json.fromBoolean(wellFormed)),
        ("shapes", Json.fromValues(shapes.map(Json.fromString))),
        (
          "shapesPrefixMap",
          Json.fromValues(
            shapesPrefixMap.map(pair =>
              Json.fromFields(
                List(
                  ("prefix", Json.fromString(pair._1)),
                  ("uri", Json.fromString(pair._2))
                )
              )
            )
          )
        ),
        ("errors", Json.fromValues(errors.map(Json.fromString)))
      )
    )
  }

  /* private[server] def getSchemaEmbedded(sp: SchemaParam): Boolean = {
   * sp.schemaEmbedded match { case Some(true) => true case Some(false) => false
   * case None => defaultSchemaEmbedded } } */

  object SchemaInfoReply {
    def fromError(msg: String): SchemaInfoReply = {
      logger.debug(s"SchemaInfoReply from $msg")
      SchemaInfoReply(None, None, wellFormed = false, List(), List(), List(msg))
    }
  }

}
