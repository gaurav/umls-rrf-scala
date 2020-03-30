package org.renci.umls

import java.io.File
import java.io.File

import org.rogach.scallop._
import org.rogach.scallop.exceptions._
import com.typesafe.scalalogging.{LazyLogging, Logger}
import org.renci.umls.rrf.RRFDir

/**
  * Map terms from one code system to another.
  */
object CodeMapper extends App with LazyLogging {
  /**
    * Command line configuration for CodeMapper.
    */
  class Conf(arguments: Seq[String], logger: Logger) extends ScallopConf(arguments) {
    override def onError(e: Throwable): Unit = e match {
      case ScallopException(message) =>
        printHelp
        logger.error(message)
        System.exit(1)
      case ex => super.onError(ex)
    }

    val version = getClass.getPackage.getImplementationVersion
    version(s"CodeMapper: map from one source to another (v$version)")

    val rrfDir: ScallopOption[File] = opt[File](
      descr = "Directory of RRF files (called 'META' in the UMLS download)",
      default = Some(new File("./META"))
    )

    verify()
  }

  // Parse command line arguments.
  val conf = new Conf(args.toIndexedSeq, logger)

  // Read RRF directory.
  val rrfDir = new RRFDir(conf.rrfDir())
  logger.info("Loaded directory for release: " + rrfDir.releaseInfo)

  rrfDir.cols.asColumns.foreach(col => logger.info(" - Column: " + col))
}
