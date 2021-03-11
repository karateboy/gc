package models

import akka.actor._
import com.github.s7connector.api.S7Connector
import com.github.s7connector.api.factory.{S7ConnectorFactory, S7SerializerFactory}
import models.ModelHelper._
import org.mongodb.scala.model._
import play.api.Play.current
import play.api._
import play.api.libs.concurrent.Akka

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global

case class ExportEntry(db: Int, offset: Int, bitOffset: Int)

case class SiemensPlcConfig(host: String, exportMap: Map[String, ExportEntry], importMap: Map[String, ExportEntry])

case class ComputedMeasureType(_id: String, sum: Seq[String])

case class GcConfig(index: Int, inputDir: String, selector: Selector,
                    plcConfig: Option[SiemensPlcConfig],
                    computedMtList: Option[Seq[ComputedMeasureType]], var latestDataTime: com.github.nscala_time.time.Imports.DateTime) {
  val gcName = GcAgent.getGcName(index)
}

import scala.collection.JavaConverters._

object GcAgent {
  val gcAgent_check_period = Play.current.configuration.getInt("gcAgent_check_period").getOrElse(60)
  val export_period = Play.current.configuration.getInt("export_period").getOrElse(60)

  val gcConfigList: mutable.Seq[GcConfig] = {
    val configList = Play.current.configuration.getConfigList("gcConfigList").get.asScala
    for ((config, idx) <- configList.zipWithIndex) yield {
      val inputDir = config.getString("inputDir", None).get
      Logger.info(config.toString)
      val selector = new Selector(getGcName(idx), config.getConfig("selector").get)

      val plcConfig: Option[SiemensPlcConfig] =
        for (config <- config.getConfig("plcConfig")) yield {
          val host = config.getString("host").get

          def getMapping(entriesOpt: Option[java.util.List[Configuration]]): Map[String, ExportEntry] = {
            if (entriesOpt.isEmpty)
              Map.empty[String, ExportEntry]
            else {
              val entries = entriesOpt.get.asScala
              val pairs =
                for (entry <- entries) yield {
                  val item = entry.getString("item").get
                  val db = entry.getInt("db").get
                  val offset = entry.getInt("offset").get
                  val bitOffset = entry.getInt("bitOffset").getOrElse(0)
                  item -> ExportEntry(db, offset, bitOffset)
                }
              pairs.toMap
            }
          }

          val exportMap = getMapping(config.getConfigList("exportMap"))
          val importMap = getMapping(config.getConfigList("importMap"))
          SiemensPlcConfig(host, exportMap, importMap)
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

  def getGcName(idx: Int) = s"gc${idx + 1}"

  def startup() = {
    // Init export local mode to PLC
    for (mode <- SysConfig.getOperationMode()) {
      for (gcConfig <- gcConfigList)
        Exporter.exportLocalModeToPLC(gcConfig, mode)
    }

    receiver = Akka.system.actorOf(Props(classOf[GcAgent]), name = "gcAgent")
    receiver ! ParseReport
  }

  case object ParseReport
  case object ExportData
}

class GcAgent extends Actor {

  import GcAgent._

  Logger.info("GcAgent started.")

  val MAX_RETRY_COUNT = 30

  import java.io.File

  var retryMap = Map.empty[String, Int]

  self ! ExportData

  var counter = 0
  def receive = {
    case ParseReport =>
      try {
        for (gcConfig <- gcConfigList) {
          val parserThread = new Thread {
            override def run {
              processInputPath(gcConfig, parser)
            }
          }
          parserThread.start()


          val checkThread = new Thread {
            override def run {
              checkNoDataPeriod(gcConfig)
              for (operationMode <- SysConfig.getOperationMode()) {
                if (operationMode == 1 && gcConfig.plcConfig.nonEmpty) //remote mode
                  readPlcStatus(gcConfig)
              }
            }
          }
          checkThread.start
        }
      } catch {
        case ex: Throwable =>
          Logger.error("process InputPath failed", ex)
      }
      context.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(gcAgent_check_period, scala.concurrent.duration.SECONDS), self, ParseReport)

    case ExportData =>
      try {
        counter += 1
        var pos = -1
        for (gcConfig <- gcConfigList) {
          pos += 1
          if(pos == counter % gcConfigList.size)
            Exporter.exportRealtimeData(gcConfig)
        }
      } catch {
        case ex: Throwable =>
          Logger.error("export data failed", ex)
      }
      context.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(export_period, scala.concurrent.duration.SECONDS), self, ExportData)
  }

  def parser(gcConfig: GcConfig, reportDir: File): Boolean = {
    import org.mongodb.scala.bson._

    import java.nio.charset.StandardCharsets
    import java.nio.file.{Files, Paths}
    import scala.collection.JavaConverters._
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
          assert(!name.contains("="))
          assert(!name.contains("-"))
          assert(!name.isEmpty())
          assert(name.charAt(0).isLetter)

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
      } //End of process report.txt

      def insertComputedTypes = {
        if (gcConfig.computedMtList.isDefined) {
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
      Exporter.exportRealtimeData(gcConfig)
    } //End of process report.txt

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

  def processInputPath(gcConfig: GcConfig, parser: (GcConfig, File) => Boolean) = {
    import java.io.File

    def setArchive(f: File) {
      import java.nio.file._
      import java.nio.file.attribute.DosFileAttributeView

      val path = Paths.get(f.getAbsolutePath)
      val dfav = Files.getFileAttributeView(path, classOf[DosFileAttributeView])
      dfav.setArchive(true)
    }

    val dirs = listDirs(gcConfig.inputDir)
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
            Logger.warn(s"${absPath} is not ready...")
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
        Exporter.notifyAlarm(gcConfig, true)
      }
    }
  }

  def readPlcStatus(gcConfig: GcConfig): Unit = {
    gcConfig.plcConfig map {
      plcConfig =>
        var connectorOpt: Option[S7Connector] = None
        try {
          connectorOpt =
            Some(S7ConnectorFactory
              .buildTCPConnector()
              .withHost(plcConfig.host)
              .build())

          for (connector <- connectorOpt) {
            val serializer = S7SerializerFactory.buildSerializer(connector)
            if (plcConfig.importMap.contains("selector")) {
              val entry = plcConfig.importMap("selector")
                def notifyPLC(pos:Int)={
                  if (plcConfig.exportMap.contains("selector")) {
                    val entry = plcConfig.exportMap("selector")
                    Logger.info(s"set selector ${pos} =>DB${entry.db}.${entry.offset}.${entry.bitOffset}")
                    val mtDataBean = new MtDataBean()
                    mtDataBean.value = pos.toFloat
                    serializer.store(mtDataBean, entry.db, entry.offset)
                  }
                }
                if (entry.bitOffset == 0 || entry.bitOffset == 12) {
                  val readBack = serializer.dispense(classOf[SelectorBean], entry.db, entry.offset)
                  if (gcConfig.selector.get != readBack.getPos) {
                    Logger.info(s"PLC Selector DB${entry.db}.${entry.offset} => selector")
                    Logger.info("selector set =>" + readBack.getPos)
                    gcConfig.selector.set(readBack.getPos)
                    notifyPLC(readBack.getPos)
                  }
                } else if (entry.bitOffset == 8) {
                  val readBack = serializer.dispense(classOf[Selector8Bean], entry.db, entry.offset)
                  if (gcConfig.selector.get != readBack.getPos) {
                    Logger.info(s"PLC Selector DB${entry.db}.${entry.offset} => selector")
                    Logger.info("selector set =>" + readBack.getPos)
                    gcConfig.selector.set(readBack.getPos)
                    notifyPLC(readBack.getPos)
                  }
                }
            }
          }
        } catch {
          case ex: Exception =>
        } finally {
          for (connector <- connectorOpt) {
            connector.close()
          }
        }
    }
  }

  override def postStop = {

  }
}