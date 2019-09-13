package models

import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import scala.concurrent.ExecutionContext.Implicits.global
import ModelHelper._

object GcAgent {
  case object ParseReport

  val inputPath = Play.current.configuration.getString("inputPath").getOrElse("C:/gc/")

  Logger.info(s"inputPath =$inputPath")

  var receiver: ActorRef = _
  def startup() = {
    receiver = Akka.system.actorOf(Props(classOf[GcAgent]), name = "gcAgent")
    receiver ! ParseReport
  }

  def parseOutput = {
    receiver ! ParseReport
  }
}

class GcAgent extends Actor {
  import GcAgent._

  def receive = {
    case ParseReport =>
      try {
        processInputPath(parser)
      } catch {
        case ex: Throwable =>
          Logger.error("process InputPath failed", ex)
      }
      import scala.concurrent.duration._
      context.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(1, scala.concurrent.duration.MINUTES), self, ParseReport)
  }

  import java.io.File
  def parser(reportDir: File): Boolean = {
    import java.nio.file.{ Paths, Files, StandardOpenOption }
    import java.nio.charset.{ StandardCharsets }
    import scala.collection.JavaConverters._
    import org.mongodb.scala.bson._
    val pdfReportFile =
      reportDir.listFiles().toList.filter(_.getName.toLowerCase.endsWith("pdf")).head

    val pdfObjId = new ObjectId()
    val pdfReport = PdfReport(pdfObjId, pdfReportFile.getName,
      Files.readAllBytes(Paths.get(pdfReportFile.getAbsolutePath)))

    val f1 = PdfReport.collection.insertOne(pdfReport).toFuture()
    f1.onFailure(errorHandler)

    val reports = reportDir.listFiles().toList.filter(f => {
      val name = f.getName.toLowerCase
      name.startsWith("report") && name.endsWith("csv")
    })
    import com.github.nscala_time.time.Imports._
    import java.time.LocalDateTime
    import java.time.format._
    
    val monitor = Monitor.getMonitorValueByName(Selector.get)
    val mDate: DateTime = {
      val report00 = reportDir.listFiles().toList.filter(p => p.getName.toLowerCase().startsWith("report00")).head
      Logger.info(s"read ${report00.getName}")

      val lines =
        Files.readAllLines(Paths.get(report00.getAbsolutePath), StandardCharsets.UTF_16LE).asScala
      val injectionLine = lines.filter(row => row.contains("Injection Date")).head
      Logger.info(injectionLine)
      val mDate = injectionLine.substring(18, 37)      
      Logger.info("parse " + mDate)
      //10-Jun-19, 05:08:35
      //val ldt = LocalDateTime.parse(mDate, DateTimeFormatter.p)
      import java.util.Locale
      DateTime.parse(mDate, DateTimeFormat.forPattern("d-MMM-CC, HH:mm:ss").withLocale(Locale.US))
    }

    //    def insertRecord() = {
    //      import scala.collection.mutable.Map
    //      val recordMap = Map.empty[Monitor.Value, Map[DateTime, Map[MonitorType.Value, (Double, String)]]]
    //      //val mDate = DateTime.parse(s"${date.text} ${time.text}", DateTimeFormat.forPattern("YYYY-MM-dd HHmmss"))
    //
    //      val monitorType = MonitorType.getMonitorTypeValueByName(desp.text.trim(), unit.text.trim())
    //      val timeMap = recordMap.getOrElseUpdate(monitor, Map.empty[DateTime, Map[MonitorType.Value, (Double, String)]])
    //      val mtMap = timeMap.getOrElseUpdate(mDate, Map.empty[MonitorType.Value, (Double, String)])
    //      val mtValue = try {
    //        value.text.toDouble
    //      } catch {
    //        case _: NumberFormatException =>
    //          0.0
    //      }
    //
    //      mtMap.put(monitorType, (mtValue, status.text.trim))
    //
    //    }
    //
    /*
    val lines =
      try {
        Files.readAllLines(Paths.get(f.getAbsolutePath), StandardCharsets.ISO_8859_1).asScala
      } catch {
        case ex: Throwable =>
          Logger.error("failed to read all lines", ex)
          Seq.empty[String]
      }

    if (lines.isEmpty) {
      false
    } else {
      def recordParser(unparsed: scala.collection.Seq[String]): List[DateTime] = {
        ???
      }

      val records = recordParser(lines)
      Logger.info(s"record = ${records.length}")
      true
    }
    *
    */
    true
  }

  def listDirs(files_path: String) = {
    //import java.io.FileFilter
    val path = new java.io.File(files_path)
    if (path.exists() && path.isDirectory()) {
      def isArchive(f: File) = {
        import java.nio.file._
        import java.nio.file.attribute.DosFileAttributes

        val dfa = Files.readAttributes(Paths.get(f.getAbsolutePath), classOf[DosFileAttributes])
        dfa.isArchive()
      }

      val allFiles = new java.io.File(files_path).listFiles().toList
      allFiles.filter(p => p != null && p.isDirectory() && !isArchive(p))
    } else {
      Logger.warn(s"invalid input path ${files_path}")
      List.empty[File]
    }
  }

  def processInputPath(parser: (File) => Boolean) = {
    import org.apache.commons.io.FileUtils
    import java.io.File
    import org.apache.commons.io.filefilter.DirectoryFileFilter
    import scala.collection.JavaConverters._

    def setArchive(f: File) {
      import java.nio.file._
      import java.nio.file.attribute.DosFileAttributeView

      val path = Paths.get(f.getAbsolutePath)
      val dfav = Files.getFileAttributeView(path, classOf[DosFileAttributeView])
      dfav.setArchive(true)
    }
    val dirs = listDirs(GcAgent.inputPath)
    val output =
      for (dir <- dirs) yield {
        Logger.info(s"Processing ${dir.getName}")
        try {
          if (parser(dir))
            setArchive(dir)
        } catch {
          case ex: Throwable =>
            Logger.info(s"${dir.getName} is not ready...", ex)
            0
        }
      }
  }

  override def postStop = {

  }
}