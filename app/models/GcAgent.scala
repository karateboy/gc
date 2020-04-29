package models

import java.util

import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka

import scala.concurrent.ExecutionContext.Implicits.global
import ModelHelper._
import org.mongodb.scala.bson._
import org.mongodb.scala.model._

import scala.collection.JavaConverters._
import scala.collection.mutable

case class ExportEntry(db: Int, offset: Int)

case class SiemensPlcConfig(host: String, exportMap: Map[String, ExportEntry])

case class ComputedMeasureType(_id: String, sum: Seq[String])

case class GcConfig(index: Int, inputDir: String, selector: Selector,
                    plcConfig: Option[SiemensPlcConfig],
                    computedMtList: Option[Seq[ComputedMeasureType]], var latestDataTime: com.github.nscala_time.time.Imports.DateTime) {
  val gcName = GcAgent.getGcName(index)
}

import scala.collection.JavaConverters._

object GcAgent {

  case object ParseReport

  def getGcName(idx: Int) = s"gc${idx + 1}"

  val gcConfigList: mutable.Seq[GcConfig] = {
    val configList = Play.current.configuration.getConfigList("gcConfigList").get.asScala
    for ((config, idx) <- configList.zipWithIndex) yield {
      val inputDir = config.getString("inputDir", None).get
      Logger.info(config.toString)
      val selector = new Selector(getGcName(idx), config.getConfig("selector").get)

      val plcConfig: Option[SiemensPlcConfig] =
        for (config <- config.getConfig("siemens_plc")) yield {
          val host = config.getString("host").get
          val mapping: mutable.Seq[(String, ExportEntry)] = for (mapConfig <- config.getConfigList("mapping").get.asScala) yield {
            val item = mapConfig.getString("item").get
            val db = mapConfig.getInt("db").get
            val offset = mapConfig.getInt("offset").get
            item -> ExportEntry(db, offset)
          }
          SiemensPlcConfig(host, mapping.toMap)
        }
      val computedTypes: Option[mutable.Buffer[ComputedMeasureType]] =
        for (configList <- config.getConfigList("computedTypes")) yield {
          configList.asScala map {
            config => {
              val id = config.getString("id").get
              val sum: mutable.Seq[String] = config.getStringList("sum").get.asScala
              ComputedMeasureType(id, sum)
            }
          }
        }
      Logger.info(s"${getGcName(idx)} inputDir =$inputDir ")
      Logger.info(s"${plcConfig.toString}")
      GcConfig(idx, inputDir, selector, plcConfig, computedTypes,
        com.github.nscala_time.time.Imports.DateTime.now())
    }
  }

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

  Logger.info("GcAgent started.")

  def receive = {
    case ParseReport =>
      try {
        for (gcConfig <- gcConfigList) {
          processInputPath(gcConfig, parser)
          checkNoDataPeriod(gcConfig)
        }


      } catch {
        case ex: Throwable =>
          Logger.error("process InputPath failed", ex)
      }
      import scala.concurrent.duration._
      context.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(1, scala.concurrent.duration.MINUTES), self, ParseReport)
  }

  //var latestDataTime: com.github.nscala_time.time.Imports.DateTime = com.github.nscala_time.time.Imports.DateTime.now()

  import java.io.File

  def parser(gcConfig: GcConfig, reportDir: File): Boolean = {
    import java.nio.file.{Paths, Files, StandardOpenOption}
    import java.nio.charset.{StandardCharsets}
    import scala.collection.JavaConverters._
    import org.mongodb.scala.bson._
    val pdfReportFile =
      reportDir.listFiles().toList.filter(_.getName.toLowerCase.endsWith("pdf")).head

    val pdfReportId = new ObjectId()
    val pdfReport = PdfReport(pdfReportId, pdfReportFile.getName,
      Files.readAllBytes(Paths.get(pdfReportFile.getAbsolutePath)))

    val f1 = PdfReport.collection.insertOne(pdfReport).toFuture()
    f1.onFailure(errorHandler)
    waitReadyResult(f1)

    import com.github.nscala_time.time.Imports._

    val monitor = Monitor.getMonitorValueByName(gcConfig.gcName, gcConfig.selector.get)

    def insertRecord() = {
      val lines =
        Files.readAllLines(Paths.get(reportDir.getAbsolutePath + "/Report.txt"), StandardCharsets.UTF_16LE).asScala

      val mDate = {
        val mDates =
          for (line <- lines if (line.startsWith("Injection Date"))) yield {
            import java.util.Locale
            val pattern = line.split(":", 2)(1).trim()
            val splitPoint = pattern.indexOf("M")
            val pattern1 = pattern.take(splitPoint + 1)
            DateTime.parse(pattern1, DateTimeFormat.forPattern("M/d/YYYY h:m:s a").withLocale(Locale.US))
          }
        mDates.head
      }

      gcConfig.latestDataTime = DateTime.now()

      def getRecordLines(inputLines: Seq[String]): Seq[String] = {
        val head = inputLines.dropWhile(!_.startsWith("-------")).drop(1)
        val ret = head.takeWhile(!_.startsWith("Totals"))
        val remain = head.dropWhile(!_.startsWith("Totals"))
        if (remain.isEmpty)
          ret
        else
          ret ++ getRecordLines(remain)
      }

      import scala.collection.mutable.Map
      val recordMap = Map.empty[Monitor.Value, Map[DateTime, Map[MonitorType.Value, (Double, String)]]]
      val timeMap = recordMap.getOrElseUpdate(monitor, Map.empty[DateTime, Map[MonitorType.Value, (Double, String)]])

      val rLines = getRecordLines(lines)
      for (rec <- rLines) {
        try {
          val retTime = rec.substring(0, 7).trim()
          val recType = rec.substring(8, 14).trim()
          val area = rec.substring(15, 25).trim()
          val ppm = rec.substring(37, 47).trim()
          val grp = rec.substring(48, 50).trim()
          val name = rec.substring(51).trim()
          assert(!name.contains(" "))
          assert(!name.contains("|"))
          assert(!name.isEmpty())
          val monitorType = MonitorType.getMonitorTypeValueByName(name, "")
          val mtMap = timeMap.getOrElseUpdate(mDate, Map.empty[MonitorType.Value, (Double, String)])

          val mtValue = try {
            ppm.toDouble
          } catch {
            case _: NumberFormatException =>
              0.0
          }

          mtMap.put(monitorType, (mtValue, MonitorStatus.NormalStat))
        } catch {
          case ex: Exception => {
            Logger.warn("skip invalid line-> ${rec}", ex)
          }
        }

        def insertComputedTypes = {
          if(gcConfig.computedMtList.isDefined){
            for (mtMap <- timeMap.values) {
              for (computedType <- gcConfig.computedMtList.get) {
                val computedMt = MonitorType.getMonitorTypeValueByName(computedType._id, "", 1000)
                val values: Seq[Double] = computedType.sum map { mtName =>
                  val mt = MonitorType.getMonitorTypeValueByName(mtName, "")
                  if (mtMap.contains(mt))
                    mtMap(mt)._1
                  else
                    0
                }
                mtMap.put(computedMt, (values.sum, MonitorStatus.NormalStat))
              }
            }
          }
        }

        insertComputedTypes

        val updateModels =
          for {
            monitorMap <- recordMap
            monitor = monitorMap._1
            timeMaps = monitorMap._2
            dateTime <- timeMaps.keys.toList.sorted
            mtMaps = timeMaps(dateTime) if !mtMaps.isEmpty
            doc = Record.toDocument(monitor, dateTime, mtMaps.toList, pdfReportId)
            updateList = doc.toList.map(kv => Updates.set(kv._1, kv._2)) if !updateList.isEmpty
          } yield {
            UpdateOneModel(
              Filters.eq("_id", doc("_id")),
              Updates.combine(updateList: _*), UpdateOptions().upsert(true))
          }

        val collection = MongoDB.database.getCollection(Record.MinCollection)
        val f2 = collection.bulkWrite(updateModels.toList, BulkWriteOptions().ordered(false)).toFuture()
        f2.onFailure(errorHandler)
        waitReadyResult(f2)
        Exporter.exportRealtimeData
      } //End of process report.txt
    }

    insertRecord
    true
  }

  def listDirs(files_path: String): List[File] = {
    //import java.io.FileFilter
    val path = new java.io.File(files_path)
    if (path.exists() && path.isDirectory()) {
      def isArchive(f: File) = {
        import java.nio.file._
        import java.nio.file.attribute.DosFileAttributes

        val dfa = Files.readAttributes(Paths.get(f.getAbsolutePath), classOf[DosFileAttributes])
        dfa.isArchive()
      }

      val allFileAndDirs = new java.io.File(files_path).listFiles().toList
      val dirs = allFileAndDirs.filter(p => p != null && p.isDirectory() && !isArchive(p))
      val resultDirs = dirs.filter(p => p.getName.endsWith(".D"))
      val diveDirs = dirs.filter(p => !p.getName.endsWith(".D"))
      if (diveDirs.isEmpty)
        resultDirs
      else {
        val deepDir = diveDirs flatMap (dir => listDirs(dir.getAbsolutePath))
        resultDirs ++ deepDir
      }
    } else {
      Logger.warn(s"invalid input path ${files_path}")
      List.empty[File]
    }
  }

  var retryMap = Map.empty[String, Int]
  val MAX_RETRY_COUNT = 30

  def processInputPath(gcConfig: GcConfig, parser: (GcConfig, File) => Boolean) = {
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

    val dirs = listDirs(gcConfig.inputDir)
    val output =
      for (dir <- dirs) yield {
        val absPath = dir.getAbsolutePath
        if (!retryMap.contains(absPath))
          Logger.info(s"Processing ${absPath}")

        try {
          if (parser(gcConfig, dir))
            setArchive(dir)
        } catch {
          case ex: Throwable =>
            if (retryMap.contains(absPath)) {
              if (retryMap(absPath) + 1 <= MAX_RETRY_COUNT) {
                retryMap += (absPath -> (retryMap(absPath) + 1))
              } else {
                Logger.info(s"$absPath reach max retries. Give up!")
                retryMap -= absPath
                setArchive(dir)
              }
            } else {
              Logger.warn(s"${absPath} is not ready...", ex)
              retryMap += (absPath -> 1)
            }
        }
      }
  }

  def checkNoDataPeriod(gcConfig: GcConfig) = {
    import com.github.nscala_time.time.Imports._

    for {dataPeriod <- SysConfig.getDataPeriod()
         stopWarn <- SysConfig.getStopWarn()
         } yield {

      if (!stopWarn && (gcConfig.latestDataTime + dataPeriod.minutes) < DateTime.now) {
        val mv = Monitor.getMonitorValueByName(gcConfig.gcName, gcConfig.selector.get)
        val monitor = Monitor.map(mv).dp_no
        Alarm.log(Some(monitor), None, "沒有資料匯入!", dataPeriod)
      }
    }
  }

  override def postStop = {

  }
}