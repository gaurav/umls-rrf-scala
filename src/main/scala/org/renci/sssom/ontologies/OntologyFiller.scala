package org.renci.sssom.ontologies

import java.io.File
import java.net.{HttpURLConnection, URL}

import org.apache.jena.graph.{Graph, Node, NodeFactory, Triple}
import org.apache.jena.ontology.OntModelSpec
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.sparql.graph.GraphFactory
import org.apache.jena.vocabulary.RDFS
import org.renci.sssom.SSSOMFiller
import org.renci.sssom.SSSOMFiller.Row

import scala.jdk.CollectionConverters._

/**
  * A generic class for filling in SSSOM files with terms from an ontology.
  */
case class OntologyFiller(
  ontology: File,
  predicates: Set[Node] = Set(
    RDFS.label.asNode(),
    NodeFactory.createURI("http://www.geneontology.org/formats/oboInOwl#hasExactSynonym")
  )
) extends SSSOMFiller {
  val owlModelSpec = new OntModelSpec(OntModelSpec.OWL_LITE_MEM)
  val owlModel = ModelFactory.createOntologyModel(owlModelSpec)
  owlModel.read(ontology.getAbsolutePath)
  val ontologyGraph: Graph = owlModel.getGraph
  scribe.info(s"Loaded ontology $ontology containing ${owlModel.getGraph.size()} triples.")

  // Add an 'rdfs:label' in lowercase so we don't have to worry about casing issues.
  val indexes = GraphFactory.createDefaultGraph()
  ontologyGraph
    .find()
    .filterKeep(tr => predicates.contains(tr.getPredicate))
    .forEachRemaining(tr => {
      val lowercased = tr.getObject.getLiteralLexicalForm.toLowerCase()
      // scribe.info(s"Translated label '${tr.getObject.getLiteralLexicalForm}' to '${lowercased}'")

      indexes
        .add(new Triple(tr.getSubject, RDFS.label.asNode(), NodeFactory.createLiteral(lowercased)))
    })
  indexes.find().forEachRemaining(tr => ontologyGraph.add(tr))
  scribe.info(s"Added consistent lowercase labels.")

  /**
    * Extract subject label for a particular row
    */
  def extractSubjectLabelsFromRow(row: Row): Seq[String] = {
    row.getOrElse("subject_label", "").split("\\s*\\|\\s*").toSeq
  }

  /**
    * Find all subjects that match a particular row.
    */
  def identifyResultsForRow(row: Row): Seq[SSSOMFiller.Result] = {
    val triples = for {
      subjectLabel <- extractSubjectLabelsFromRow(row).distinct
      triple <-
        ontologyGraph.find(null, null, NodeFactory.createLiteral(subjectLabel.toLowerCase)).asScala
      if predicates.contains(triple.getPredicate)
    } yield triple

    triples.map(triple => {
      val subject = triple.getSubject
      val subjectURI = subject.getURI
      val subjectLabels = ontologyGraph
        .find(subject, RDFS.label.asNode(), null)
        .asScala
        .map(_.getObject.toString(false))
      SSSOMFiller.Result(
        row,
        row + ("object_id" -> subjectURI)
          + ("object_label" -> subjectLabels.mkString("|"))
          + ("predicate_id" -> "skos:exactMatch")
          + ("predicate_label" -> "The subject and the object can, with a high degree of confidence, be used interchangeably across a wide range of information retrieval applications.")
          + ("comment" -> s"Ontology $ontology contains triple asserting $triple")
          + ("match_type" -> "http://purl.org/sssom/type/Complex")
          + ("mapping_set_id" -> ontology.getName),
        this
      )
    })
  }

  /**
    * Fill in the input row. The list of all headers is also provided.
    *
    * @return None if this row could not be filled, and Some[Row] if it can.
    */
  override def fillRow(row: Row, headers: List[String]): Option[Seq[SSSOMFiller.Result]] = {
    val results = identifyResultsForRow(row)
    if (results.isEmpty) None else Some(results)
  }
}

object OntologyFiller {
  def getRedirectedURL(sourceURL: URL): URL = {
    // Find out where this URL redirects to.
    val conn = sourceURL.openConnection().asInstanceOf[HttpURLConnection]
    conn.setInstanceFollowRedirects(false)
    if (
      conn.getResponseCode == HttpURLConnection.HTTP_MOVED_TEMP || conn.getResponseCode == HttpURLConnection.HTTP_MOVED_PERM
    ) {
      val location = conn.getHeaderField("Location")
      if (location == null)
        throw new RuntimeException(s"Redirect without 'Location' provided: ${conn}")
      else getRedirectedURL(new URL(location))
    } else sourceURL
  }
}
