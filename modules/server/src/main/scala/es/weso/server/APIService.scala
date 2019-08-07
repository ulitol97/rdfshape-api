package es.weso.server

import cats.effect._
import cats.implicits._
import es.weso.rdf.jena.RDFAsJenaModel
import es.weso.schema._
import es.weso.server.ApiHelper._
import es.weso.server.QueryParams._
import es.weso.server.helper.DataFormat
import es.weso.server.utils.Http4sUtils._
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
import fs2._
import org.http4s._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.dsl.Http4sDsl
import org.http4s.headers._
import org.http4s.server.staticcontent.{ResourceService, resourceService}
import org.log4s.getLogger

import scala.concurrent.duration._

class APIService[F[_]:ConcurrentEffect: Timer](blocker: Blocker,
                                               client: Client[F])(implicit cs: ContextShift[F])
  extends Http4sDsl[F] {

  private val relativeBase = Defaults.relativeBase
  private val logger = getLogger
  val api = "api"

  val wikidataEntityUrl = "http://www.wikidata.org/entity/Q"

  //  val version = "1.0"

  private val swagger =
    resourceService[F](ResourceService.Config("/swagger", blocker))

  val availableDataFormats = DataFormats.formatNames.toList
  val defaultDataFormat = DataFormats.defaultFormatName
  val availableSchemaFormats = Schemas.availableFormats
  val defaultSchemaFormat = Schemas.defaultSchemaFormat
  val availableSchemaEngines = Schemas.availableSchemaNames
  val defaultSchemaEngine = Schemas.defaultSchemaName
  val availableTriggerModes = Schemas.availableTriggerModes
  val defaultTriggerMode = Schemas.defaultTriggerMode
  val defaultSchemaEmbedded = false

  val routes = HttpRoutes.of[F] {

    case GET -> Root / `api` / "schema" / "engines" => {
      val engines = Schemas.availableSchemaNames
      val json = Json.fromValues(engines.map(str => Json.fromString(str)))
      Ok(json)
    }

    case GET -> Root / `api` / "schema" / "engines" / "default" => {
      val schemaEngine = Schemas.defaultSchemaName
      val json = Json.fromString(schemaEngine)
      Ok(json)
    }

    case GET -> Root / `api` / "schema" / "formats" => {
      val formats = Schemas.availableFormats
      val json = Json.fromValues(formats.map(str => Json.fromString(str)))
      Ok(json)
    }

    case GET -> Root / `api` / "schema" / "triggerModes" => {
      val triggerModes = ValidationTrigger.triggerValues.map(_._1)
      val json = Json.fromValues(triggerModes.map(Json.fromString(_)))
      Ok(json)
    }

    case GET -> Root / `api` / "data" / "formats" => {
      val formats = DataFormats.formatNames
      val json = Json.fromValues(formats.map(str => Json.fromString(str)))
      Ok(json)
    }

    case GET -> Root / `api` / "data" / "formats" / "default" => {
      val dataFormat = DataFormats.defaultFormatName
      Ok(Json.fromString(dataFormat))
    }

    case req @ GET -> Root / `api` / "test" :? NameParam(name) => {
      val default = Ok(s"Hello ${name.getOrElse("World")}")
      req.headers.get(`Accept-Language`) match {
        case Some(al) => {
          al match {
            case _ if (al.satisfiedBy(LanguageTag("es"))) =>
              Ok(s"Hola ${name.getOrElse("Mundo")}!")
            case _ => default
          }
        }
        case None => default
      }
    }

    case req @ GET -> Root / `api` / "dataUrl" / "info" :?
      OptDataURLParam(optDataUrl) +&
      DataFormatParam(optDataFormat) => {
      val dataFormat = optDataFormat.getOrElse(DataFormats.defaultFormatName)
      optDataUrl match {
          case None => BadRequest(s"Must provide a dataUrl")
          case Some(dataUrl) => client.expect[String](dataUrl).flatMap(data => {
            val either = for {
              rdf <- RDFAsJenaModel.fromChars(data, dataFormat, None)
              subjs <- rdf.subjects
              objs <- rdf.iriObjects
              preds <- rdf.predicates
              ls = subjs ++ objs ++ preds
            } yield (rdf, ls.map(_.toString).toList)
            either match {
              case Left(e) => BadRequest(s"Error: $e\nRdf string: $data")
              case Right(pair) => {
                val (rdf,nodes) = pair
                val jsonNodes: Json = Json.fromValues(nodes.map(str => Json.fromString(str)))
                val pm: Json = prefixMap2Json(rdf.getPrefixMap)
                val result = DataInfoResult(data, dataFormat, jsonNodes, pm).asJson
                Ok(result).map(_.withContentType(`Content-Type`(MediaType.application.json)))
              }
            }
          })
     }
    }


    case req @ GET -> Root / `api` / "data" / "info" :?
      DataParameter(data) +&
      DataFormatParam(optDataFormat) => {
      val dataFormat = optDataFormat.getOrElse(DataFormats.defaultFormatName)
	  val either = for {
	    rdf <- RDFAsJenaModel.fromChars(data, dataFormat, None)
		subjs <- rdf.subjects
		objs <- rdf.iriObjects
		preds <- rdf.predicates
		ls = subjs ++ objs ++ preds
	  } yield (rdf, ls.map(_.toString).toList)
	  either match {
        case Left(e) => BadRequest(s"Error: $e\nRdf string: $data")
        case Right(pair) => {
		  val (rdf,nodes) = pair
          val jsonNodes: Json = Json.fromValues(nodes.map(str => Json.fromString(str)))
          val pm: Json = prefixMap2Json(rdf.getPrefixMap)
          val result = DataInfoResult(data, dataFormat, jsonNodes, pm).asJson
          Ok(result).map(_.withContentType(`Content-Type`(MediaType.application.json)))
        }
      }
    }

    case req @ GET -> Root / `api` / "schema" / "info" :?
      OptSchemaParam(optSchema) +&
      SchemaFormatParam(optSchemaFormat) +&
      SchemaEngineParam(optSchemaEngine) => {
      val schemaEngine = optSchemaEngine.getOrElse(Schemas.defaultSchemaName)
      val schemaFormat = optSchemaFormat.getOrElse(Schemas.defaultSchemaFormat)
      val schemaStr = optSchema match {
        case None => ""
        case Some(schema) => schema
      }
      Schemas.fromString(schemaStr, schemaFormat, schemaEngine, None) match {
        case Left(e) => BadRequest(s"Error reading schema: $e\nString: $schemaStr")
        case Right(schema) => {
          val shapes: List[String] = schema.shapes
          val jsonShapes = Json.fromValues(shapes.map(Json.fromString(_)))
          val pm: Json = prefixMap2Json(schema.pm)
          //          implicit val encoder: EntityEncoder[SchemaInfoResult] = ???
          val result = SchemaInfoResult(schemaStr, schemaFormat, schemaEngine, jsonShapes, pm).asJson
          Ok(result).map(_.withContentType(`Content-Type`(MediaType.application.json)))
        }
      }
    }

    case req @ GET -> Root / `api` / "data" / "convert" :?
      DataParameter(data) +&
      DataFormatParam(optDataFormat) +&
      TargetDataFormatParam(optResultDataFormat) => {
      DataConverter.dataConvert(data,optDataFormat,optResultDataFormat).fold(
        e => BadRequest(s"Error: $e"),
        result => result.resultFormat match {
          case "SVG" => {
            Ok(result.result).map(_.withContentType(`Content-Type`(MediaType.image.`svg+xml`)))
          }
          case "PNG" => Ok(result.result).map(_.withContentType(`Content-Type`(MediaType.text.html)))
          case _ => {
          val default = Ok(result.asJson)
            .map(_.withContentType(`Content-Type`(MediaType.application.json)))
          req.headers.get(`Accept`) match {
            case Some(ah) => {
              logger.info(s"Accept header: $ah")
              val hasHTML: Boolean = ah.values.exists(mr => mr.mediaRange.satisfiedBy(MediaType.text.html))
              if (hasHTML) {
                Ok(result.toHTML).map(_.withContentType(`Content-Type`(MediaType.text.html)))
              } else default
            }
            case None => default
          }
        }
       }
      )
    }

    case req @ GET -> Root / `api` / "schema" / "convert" :?
      OptSchemaParam(optSchema) +&
      SchemaFormatParam(optSchemaFormat) +&
      SchemaEngineParam(optSchemaEngine) +&
      TargetSchemaFormatParam(optResultSchemaFormat) +&
      TargetSchemaEngineParam(optResultSchemaEngine) => {
      val schemaEngine = optSchemaEngine.getOrElse(Schemas.defaultSchemaName)
      val schemaFormat = optSchemaFormat.getOrElse(Schemas.defaultSchemaFormat)
      val resultSchemaFormat = optResultSchemaFormat.getOrElse(Schemas.defaultSchemaFormat)
      val resultSchemaEngine = optResultSchemaEngine.getOrElse(Schemas.defaultSchemaName)

      val schemaStr = optSchema match {
        case None => ""
        case Some(schema) => schema
      }
      Schemas.fromString(schemaStr, schemaFormat, schemaEngine, None) match {
        case Left(e) => BadRequest(s"Error reading schema: $e\nString: $schemaStr")
        case Right(schema) => {
          if (schemaEngine.toUpperCase == resultSchemaEngine.toUpperCase) {
            schema.serialize(resultSchemaFormat) match {
              case Right(resultStr) => {
                val result = SchemaConversionResult(schemaStr, schemaFormat, schemaEngine,
                  resultSchemaFormat, resultSchemaEngine, resultStr)
                val default = Ok(result.asJson)
                  .map(_.withContentType(`Content-Type`(MediaType.application.json)))
                req.headers.get(`Accept`) match {
                  case Some(ah) => {
                    logger.info(s"Accept header: $ah")
                    val hasHTML: Boolean = ah.values.exists(mr => mr.mediaRange.satisfiedBy(MediaType.text.html))
                    if (hasHTML) {
                      Ok(result.toHTML).map(_.withContentType(`Content-Type`(MediaType.text.html)))
                    } else default
                  }
                  case None => default
                }
              }
              case Left(e) =>
                BadRequest(s"Error serializing $schemaStr with $resultSchemaFormat/$resultSchemaEngine: $e")
            }
          } else {
            BadRequest(s"Conversion between different schema engines not implemented yet: $schemaEngine/$resultSchemaEngine")
          }
        }
      }
    }

    case req @ (GET | POST) -> Root / `api` / "validate" :?
      OptDataParam(optData) +&
        OptDataURLParam(optDataURL) +&
        DataFormatParam(maybeDataFormat) +&
        OptSchemaParam(optSchema) +&
        SchemaURLParam(optSchemaURL) +&
        SchemaFormatParam(optSchemaFormat) +&
        SchemaEngineParam(optSchemaEngine) +&
        OptTriggerModeParam(optTriggerMode) +&
        NodeParam(optNode) +&
        ShapeParam(optShape) +&
        ShapeMapParameterAlt(optShapeMapAlt) +&
        ShapeMapParameter(optShapeMap) +&
        ShapeMapURLParameter(optShapeMapURL) +&
        ShapeMapFileParameter(optShapeMapFile) +&  // This parameter seems unnecessary...maybe for keeping the state only?
        ShapeMapFormatParam(optShapeMapFormat) +&
        SchemaEmbedded(optSchemaEmbedded) +&
        InferenceParam(optInference) +&
        OptEndpointParam(optEndpoint) +&
        OptActiveDataTabParam(optActiveDataTab) +&
        OptActiveSchemaTabParam(optActiveSchemaTab) +&
        OptActiveShapeMapTabParam(optActiveShapeMapTab) => {
      val either: Either[String, Option[DataFormat]] = for {
        df <- maybeDataFormat.map(DataFormat.fromString(_)).sequence
      } yield df

      either match {
        case Left(str) => BadRequest(str)
        case Right(optDataFormat) => {
          val baseUri = req.uri
          logger.info(s"BaseURI: $baseUri")
          logger.info(s"Endpoint: $optEndpoint")
          val dp = DataParam(optData, optDataURL, None, optEndpoint, optDataFormat, optDataFormat, None, optInference, None, optActiveDataTab)
          val sp = SchemaParam(optSchema, optSchemaURL, None, optSchemaFormat, optSchemaFormat, optSchemaFormat, optSchemaEngine, optSchemaEmbedded, None, None, optActiveSchemaTab)
          val collectShapeMap = (optShapeMap,optShapeMapAlt) match {
            case (None,None) => None
            case (None, Some(sm)) => Some(sm)
            case (Some(sm), None) => Some(sm)
            case (Some(sm1), Some(sm2)) => if (sm1 == sm2) Some(sm1)
              else {
                val msg = (s"2 shape-map paramters with different values: $sm1 and $sm2. We use: $sm1")
                logger.error(msg)
                println(msg)
                Some(sm1)
              }
           }
          println(s"#### optShapeMap: ${collectShapeMap}")
          val tp = TriggerModeParam(
            optTriggerMode,
            collectShapeMap,
            optShapeMapFormat,
            optShapeMapURL,
            optShapeMapFormat, // TODO: Maybe a more specific param for URL format?
            optShapeMapFile,
            optShapeMapFormat, // TODO: Maybe a more specific param for File format?
            optActiveShapeMapTab
          )
          val (dataStr, eitherRDF) = dp.getData(relativeBase)

          val eitherResult: Either[String, F[Response[F]]] = for {
            rdf <- eitherRDF
            (schemaStr, eitherSchema) = sp.getSchema(Some(rdf))
            schema <- eitherSchema
          } yield {
            println(s"RDF: $rdf")
            println(s"Schema: $schema")
            val (result, maybeTrigger, time) = validate(rdf, dp, schema, sp, tp, relativeBase)
            println(s"maybeTrigger: $maybeTrigger")
            println(s"result: $result")
            Ok(result.toJson)
          }
          eitherResult.fold(e => BadRequest(s"Error: $e"), identity)
        }
      }
    }

    case req @ GET -> Root / `api` / "wikidata" / "entity" :?
      OptEntityParam(optEntity) => {
       println(s"Wikidata entity: $optEntity")
       optEntity match {
        case None => Ok("No entity provided")
        case Some(entity) => {
          getUri(entity).fold(
            e => Ok(Json.fromString(e)),
            uri => for {
            either <- resolveStream[F](uri,client)
            resp <- either.fold(e =>
              Ok(Json.fromString(e)),
              stream => Ok(stream))
          } yield resp
          )
        }
      }
    }

    // Contents on /swagger are directly mapped to /swagger
    case r @ GET -> _ if r.pathInfo.startsWith("/swagger/") => swagger(r).getOrElseF(NotFound())

  }

  private def drip: Stream[F, String] =
    Stream.awakeEvery[F](100.millis).map(_.toString).take(10)

  private def getUri(entity: String): Either[String, Uri] = {
    println(s"getUri: $entity")
    val q = """Q(\d*)""".r
    entity match {
      case q(n) => Uri.fromString(wikidataEntityUrl + n).leftMap(f => s"Error creating URI for entity ${n}: ${f}")
      case _ => Uri.fromString(entity).leftMap(f => s"Error creating URI from $entity: $f")
    }
  }

}

object APIService {
  def apply[F[_]: ConcurrentEffect: ContextShift: Timer](blocker: Blocker, client: Client[F]): APIService[F] =
    new APIService[F](blocker, client)
}
