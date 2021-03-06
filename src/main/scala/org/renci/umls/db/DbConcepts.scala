package org.renci.umls.db

import java.io.File

import org.apache.commons.dbcp2.ConnectionFactory

import scala.util.Try
import org.renci.umls.rrf._
import scalacache.caffeine._
import scalacache.memoization._
import scalacache.modes.sync._

import scala.concurrent.duration._
import scala.io.Source

/** A wrapper for RRFConcepts that uses SQLite */
class DbConcepts(db: ConnectionFactory, file: File, filename: String)
    extends RRFConcepts(file, filename) {
  val cacheTimePeriod = None // Some(2.hours)
  implicit val halfMapSeqCache = CaffeineCache[Seq[HalfMap]]
  implicit val halfMapSetCache = CaffeineCache[Set[HalfMap]]
  implicit val stringSetCache = CaffeineCache[Set[String]]
  implicit val stringMapCache = CaffeineCache[Map[String, Seq[String]]]

  /** The name of the table used to store this information. We include the SHA-256 hash so we reload it if it changes. */
  val tableName: String = "MRCONSO_" + sha256

  /* Check to see if the MRCONSO_ table seems up to date. If not, load it into memory from the file. */
  val conn1 = db.createConnection()
  val checkCount = conn1.createStatement()
  val results = Try { checkCount.executeQuery(s"SELECT COUNT(*) AS cnt FROM $tableName") }
  val rowsFromDb = results.map(_.getInt(1)).getOrElse(-1)
  conn1.close()

  if (rowsFromDb > 0 && rowsFromDb == rowCount) {
    scribe.info(s"Concept table $tableName has $rowsFromDb rows.")
  } else {
    scribe.info(s"Concept table $tableName is not present or is out of sync. Regenerating.")

    val conn = db.createConnection()
    val regenerate = conn.createStatement()
    regenerate.execute(s"DROP TABLE IF EXISTS $tableName")
    regenerate.execute(s"""CREATE TABLE $tableName (
      |CUI TEXT,
      |LAT TEXT,
      |TS TEXT,
      |LUI TEXT,
      |STT TEXT,
      |SUI TEXT,
      |ISPREF TEXT,
      |AUI TEXT,
      |SAUI TEXT,
      |SCUI TEXT,
      |SDUI TEXT,
      |SAB TEXT,
      |TTY TEXT,
      |CODE TEXT,
      |STR TEXT,
      |SRL TEXT,
      |SUPPRESS TEXT,
      |CVF TEXT
      )""".stripMargin)

    val insertStmt = conn.prepareStatement(
      s"INSERT INTO $tableName (CUI, LAT, TS, LUI, STT, SUI, ISPREF, AUI, SAUI, SCUI, SDUI, SAB, TTY, CODE, STR, SRL, SUPPRESS, CVF) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    )

    var count = 0
    Source.fromFile(file).getLines.map(_.split("\\|", -1).toIndexedSeq) foreach { row =>
      insertStmt.clearParameters()

      (1 until 19) foreach ({ index =>
        insertStmt.setString(index, row(index - 1))
      })
      insertStmt.addBatch()

      count += 1
      if (count % 100000 == 0) {
        val percentage = count.toFloat / rowCount * 100
        scribe.info(f"Batched $count rows out of $rowCount ($percentage%.2f%%), executing.")
        insertStmt.executeBatch()
        insertStmt.clearBatch()
      }
    }
    insertStmt.executeBatch()

    // Add indexes.
    regenerate.execute(s"CREATE INDEX INDEX_MRCONSO_SAB ON $tableName (SAB);")
    regenerate.execute(s"CREATE INDEX INDEX_MRCONSO_CODE ON $tableName (CODE);")

    conn.close()
  }

  // Okay, we're ready to go!
  def getSources(): Seq[(String, Int)] = {
    val conn = db.createConnection()
    val query = conn.createStatement()
    val rs = query.executeQuery(
      s"SELECT SAB, COUNT(*) AS count FROM $tableName GROUP BY SAB ORDER BY count DESC;"
    )

    var results = Seq[(String, Int)]()
    while (rs.next()) {
      results = results :+ (
        rs.getString(1),
        rs.getInt(2)
      )
    }

    conn.close()
    results
  }

  /**
    * A "half map" maps a NCImt concept identifier to an identifier from another source.
    * Details are available at https://www.ncbi.nlm.nih.gov/books/NBK9685/#ch03.sec3.3.4
    *
    * @param cui The NCImt concept identifier.
    * @param aui The NCImt atom identifier (https://www.ncbi.nlm.nih.gov/books/NBK9684/#ch02.sec2.3.3).
    * @param source The abbreviated source name (e.g. "AIR" or -- when versioned -- "AIR93").
    * @param code The source-asserted identifier.
    * @param label The label used for this concept.
    */
  case class HalfMap(cui: String, aui: String, source: String, code: String, label: String)

  /** Return all known labels for a set of concepts */
  def getLabelsForCodes(source: String, ids: Seq[String]): Set[String] =
    memoizeSync(cacheTimePeriod) {
      ids.flatMap(getLabelsForCode(source, _)).toSet
    }

  /** Return all known labels for a single concept */
  def getLabelsForCode(source: String, id: String): Set[String] =
    memoizeSync(cacheTimePeriod) {
      getHalfMapsForCodes(source, Seq(id)).map(_.label).toSet
    }

  /** Return all known labels for a single CUI */
  def getLabelsForCUI(cui: String): Set[String] =
    memoizeSync(cacheTimePeriod) {
      getHalfMapsByCUIs(Set(cui)).map(_.label).toSet
    }

  val halfMapCache = CaffeineCache[Seq[HalfMap]]

  /**
    * Return all half-maps for a set of concept identifiers.
    *
    * @param source The abbreviated source name (e.g. "AIR" or -- when versioned -- "AIR93").
    * @param ids The source-asserted identifiers.
    * @return A Seq of HalfMaps for the provided identifiers in the provided source.
    */
  def getHalfMapsForCodes(source: String, ids: Seq[String]): Seq[HalfMap] =
    memoizeSync(cacheTimePeriod) {
      // Retrieve all the fromIds.
      val conn = db.createConnection()
      if (ids.isEmpty) {
        val query =
          conn.prepareStatement(s"SELECT CUI, AUI, SAB, CODE, STR FROM $tableName WHERE SAB=?")
        query.setString(1, source)
        val rs = query.executeQuery()

        scribe.debug(s"Loading halfmaps for $source")
        var halfMap = Seq[HalfMap]()
        var count = 0
        while (rs.next()) {
          halfMap = HalfMap(
            rs.getString(1),
            rs.getString(2),
            rs.getString(3),
            rs.getString(4),
            rs.getString(5)
          ) +: halfMap
          count += 1
          if (count % 100000 == 0) {
            scribe.debug(s"Loaded $count halfmaps.")
          }
        }

        conn.close()
        scribe.debug(s"${halfMap.size} halfmaps loaded.")

        // Load into the cache.
        halfMap
          .groupBy(hm => s"${hm.source}:${hm.code}")
          .foreach({ case (id, hms) => halfMapCache.put(id)(hms) })

        halfMap
      } else {
        scribe.debug(s"Loading halfmaps for $source with identifiers: $ids.")

        val cachedHalfMaps: Seq[HalfMap] =
          ids.flatMap(id => halfMapCache.get(s"$source:$id")).flatten
        val cachedIds: Set[String] = cachedHalfMaps.map(_.code).toSet
        val uncachedIds = ids.filter(!cachedIds.contains(_))

        if (cachedIds.nonEmpty)
          scribe.info(
            s"Halfmaps for ${cachedIds.size} identifiers have been previously cached; loading halfmaps from $source with identifiers: $uncachedIds."
          )

        var halfMap = Seq[HalfMap]()
        var count = 0

        val windowSize = (uncachedIds.size / 10) + 1
        uncachedIds
          .sliding(windowSize, windowSize)
          .foreach(idGroup => {
            val indexedIds = idGroup.toIndexedSeq
            val questions = idGroup.map(_ => "?").mkString(", ")
            val query = conn.prepareStatement(
              s"SELECT DISTINCT CUI, AUI, SAB, CODE, STR FROM $tableName WHERE SAB=? AND CODE IN ($questions)"
            )

            query.setString(1, source)
            (0 until idGroup.size).foreach(id => {
              query.setString(id + 2, indexedIds(id))
            })

            val rs = query.executeQuery()
            while (rs.next()) {
              halfMap = HalfMap(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5)
              ) +: halfMap
              count += 1
            }

            scribe.debug(s"Loaded $count halfmaps.")
          })

        conn.close()
        scribe.debug(s"${halfMap.size} new halfmaps loaded.")

        // Load into the cache.
        halfMap
          .groupBy(hm => s"${hm.source}:${hm.code}")
          .foreach({ case (id, hms) => halfMapCache.put(id)(hms) })

        halfMap ++ cachedHalfMaps
      }
    }

  case class Mapping(
    fromSource: String,
    fromCode: String,
    toSource: String,
    toCode: String,
    conceptIds: Set[String],
    atomIds: Set[String],
    labels: Set[String]
  )
  def getMap(
    fromSource: String,
    fromIds: Seq[String],
    toSource: String,
    toIds: Seq[String]
  ): Seq[Mapping] = {
    val fromHalfMaps = getHalfMapsForCodes(fromSource, fromIds)
    val toHalfMaps = getHalfMapsForCodes(toSource, toIds)

    // Combine the halfmaps so we need to.
    (fromHalfMaps ++ toHalfMaps)
      .groupBy(_.cui)
      .values
      .flatMap({ entries =>
        // Everything in entries is the "same" concept according to MRCONSO.
        // So we partition this based on
        val cuis = entries.map(_.cui).toSet
        val auis = entries.map(_.aui).toSet
        val labels = entries.map(_.label).toSet
        val fromCodes = entries.filter(_.source == fromSource).map(_.code).toSet[String]
        val toCodes = entries.filter(_.source == toSource).map(_.code).toSet[String]

        fromCodes.flatMap(fromCode => {
          toCodes.map(toCode => {
            Mapping(fromSource, fromCode, toSource, toCode, cuis, auis, labels)
          })
        })
      })
      .toSeq
  }

  // Look up maps by CUIs.
  def getHalfMapsByCUIs(cuis: Set[String], toSource: String): Seq[HalfMap] =
    memoizeSync(cacheTimePeriod) {
      if (cuis.isEmpty) return Seq()

      val conn = db.createConnection()
      val indexedSeq = cuis.toIndexedSeq
      val questions = indexedSeq.map(_ => "?").mkString(", ")
      val query = conn.prepareStatement(
        s"SELECT DISTINCT CUI, AUI, SAB, CODE, STR FROM $tableName WHERE SAB=? AND CUI IN ($questions)"
      )
      query.setString(1, toSource)
      (1 to cuis.size).foreach(index => {
        query.setString(index + 1, indexedSeq(index - 1))
      })

      var halfMaps = Seq[HalfMap]()
      val rs = query.executeQuery()
      while (rs.next()) {
        halfMaps = HalfMap(
          rs.getString(1),
          rs.getString(2),
          rs.getString(3),
          rs.getString(4),
          rs.getString(5)
        ) +: halfMaps
      }
      conn.close()

      halfMaps
    }

  def getHalfMapsByCUIs(cuis: Set[String]): Seq[HalfMap] =
    memoizeSync(cacheTimePeriod) {
      if (cuis.isEmpty) return Seq()

      val conn = db.createConnection()
      val indexedSeq = cuis.toIndexedSeq
      val questions = indexedSeq.map(_ => "?").mkString(", ")
      val query = conn.prepareStatement(
        s"SELECT DISTINCT CUI, AUI, SAB, CODE, STR FROM $tableName WHERE CUI IN ($questions)"
      )
      (1 to cuis.size).foreach(index => {
        query.setString(index, indexedSeq(index - 1))
      })

      var halfMaps = Seq[HalfMap]()
      val rs = query.executeQuery()
      while (rs.next()) {
        halfMaps = HalfMap(
          rs.getString(1),
          rs.getString(2),
          rs.getString(3),
          rs.getString(4),
          rs.getString(5)
        ) +: halfMaps
      }
      conn.close()

      halfMaps
    }

  // Get the CUIs for given AUIs.
  def getCUIsForAUI(auis: Seq[String]): Set[String] =
    memoizeSync(cacheTimePeriod) {
      if (auis.isEmpty) return Set()

      val conn = db.createConnection()
      val questions = auis.map(_ => "?").mkString(", ")
      val query =
        conn.prepareStatement(s"SELECT DISTINCT CUI FROM $tableName WHERE AUI IN ($questions)")
      val indexedSeq = auis.toIndexedSeq
      (1 to auis.size).foreach(index => {
        query.setString(index, indexedSeq(index - 1))
      })

      var results = Seq[String]()
      val rs = query.executeQuery()
      while (rs.next()) {
        results = rs.getString(1) +: results
      }
      conn.close()

      results.toSet
    }

  def getAUIsForCUIs(cuis: Seq[String]): Set[String] =
    memoizeSync(cacheTimePeriod) {
      if (cuis.isEmpty) return Set()

      val conn = db.createConnection()
      val questions = cuis.map(_ => "?").mkString(", ")
      val query =
        conn.prepareStatement(s"SELECT DISTINCT AUI FROM $tableName WHERE CUI IN ($questions)")
      val indexedSeq = cuis.toIndexedSeq
      (1 to cuis.size).foreach(index => {
        query.setString(index, indexedSeq(index - 1))
      })

      var results = Seq[String]()
      val rs = query.executeQuery()
      while (rs.next()) {
        results = rs.getString(1) +: results
      }
      conn.close()

      results.toSet
    }

  def getCUIsForCodes(source: String, ids: Seq[String]): Map[String, Seq[String]] =
    memoizeSync(cacheTimePeriod) {
      if (ids.isEmpty) return Map.empty

      val conn = db.createConnection()
      val questions = ids.map(_ => "?").mkString(", ")
      val query = conn.prepareStatement(
        s"SELECT DISTINCT CODE, CUI FROM $tableName WHERE SAB=? AND CODE IN ($questions)"
      )
      query.setString(1, source)
      val indexedSeq = ids.toIndexedSeq
      (1 to ids.size).foreach(index => {
        query.setString(index + 1, indexedSeq(index - 1))
      })

      var results = Seq[(String, String)]()
      val rs = query.executeQuery()
      while (rs.next()) {
        results = (rs.getString(1), rs.getString(2)) +: results
      }
      conn.close()

      results.groupMap(_._1)(_._2)
    }
}

object DbConcepts {

  /** Wrap an RRF file using a database to cache results. */
  def fromDatabase(db: ConnectionFactory, rrfFile: RRFFile) =
    new DbConcepts(db, rrfFile.file, rrfFile.filename)
}
