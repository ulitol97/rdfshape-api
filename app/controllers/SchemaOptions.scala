package controllers

import es.weso.shex._
import es.weso.monads._
import es.weso.rdf._
import xml.Utility.escape
import es.weso.rdfgraph.nodes.RDFNode
import es.weso.rdfgraph.nodes.IRI
import java.io.File

case class SchemaOptions(
      format: String
    , cut: Int
    , withIncoming: Boolean
    , withAny: Boolean
    , opt_iri: Option[IRI]
    , showSchema: Boolean
    ) {
  def extract_iri_str : String = {
    opt_iri.map(_.toString).getOrElse("")
  }
}
    
object SchemaOptions {
  def default : SchemaOptions = 
    SchemaOptions("SHEXC",10, false, false, None,true)
    
  def defaultWithIri(iri: String): SchemaOptions = 
    SchemaOptions("SHEXC",10, false, false, Some(IRI(iri)),true)
    
}
