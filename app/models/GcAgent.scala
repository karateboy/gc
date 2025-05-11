package models

import akka.actor._
import com.github.s7connector.api.S7Connector
import com.github.s7connector.api.factory.S7SerializerFactory
import models.ModelHelper._
import org.mongodb.scala.model._
import play.api._
import play.api.libs.concurrent.InjectedActorSupport

import javax.inject.Inject
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future, blocking}

case class ExportEntry(db: Int, offset: Int, bitOffset: Int)

case class SiemensPlcConfig(host: String, rack: Option[Int], slot: Option[Int], exportMap: Map[String, ExportEntry], importMap: Map[String, ExportEntry])

case class AoEntry(idx: Int, min: Double, max: Double)

case class AoConfig(host: String, exportMap: Map[String, AoEntry])

case class ComputedMeasureType(_id: String, sum: Seq[String])

case class CleanNotifyConfig(host: String, slaveId: Option[Int], address: Int, delay: Int = 7)

case class GcConfig(index: Int, inputDir: String, selector: Selector,
                    plcConfig: Option[SiemensPlcConfig],
                    aoConfigList: Option[Seq[AoConfig]],
                    haloKaConfig: Option[HaloKaConfig],
                    adam6017Config: Option[Adam6017Config],
                    computedMtList: Option[Seq[ComputedMeasureType]],
                    cleanNotifyConfig: Option[CleanNotifyConfig],
                    var latestDataTime: com.github.nscala_time.time.Imports.DateTime,
                    var executeCount: Int,
                    haloKaConfig1: Option[HaloKaConfig]) {
  val gcName: String = GcAgent.getGcName(index)
}

case class HaloKaConfig(com: Int, speed: Int, MonitorType: String)

import scala.collection.JavaConverters._

object GcAgent {
  def getGcName(idx: Int) = s"gc${idx + 1}"

  var gcConfigList: Seq[GcConfig] = Seq.empty[GcConfig]

  private def initGcConfigList(configuration: Configuration,
                               monitorOp: MonitorOp,
                               alarmOp: AlarmOp,
                               actorSystem: ActorSystem,
                               sysConfig: SysConfig): Unit = {
    val configList = configuration.getConfigList("gcConfigList").get.asScala
    for ((config, idx) <- configList.zipWithIndex) yield {
      val inputDir = config.getString("inputDir", None).get
      Logger.info(config.toString)
      val selector = new Selector(monitorOp, alarmOp, actorSystem)(getGcName(idx), config.getConfig("selector").get)

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

          val rack = config.getInt("rack")
          val slot = config.getInt("slot")
          val exportMap = getMapping(config.getConfigList("exportMap"))
          val importMap = getMapping(config.getConfigList("importMap"))
          SiemensPlcConfig(host, rack, slot, exportMap, importMap)
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

      val aoConfigs: Option[Seq[AoConfig]] =
        for (configList <- config.getConfigList("aoConfigs")) yield {
          def getAoConfig(config: Configuration): AoConfig = {
            val host = config.getString("host").get

            def getMapping(entriesOpt: Option[java.util.List[Configuration]]): Map[String, AoEntry] = {
              if (entriesOpt.isEmpty)
                Map.empty[String, AoEntry]
              else {
                val entries = entriesOpt.get.asScala
                val pairs =
                  for ((entry, idx) <- entries.zipWithIndex) yield {
                    val item = entry.getString("item").get
                    val min = entry.getDouble("min").get
                    val max = entry.getDouble("max").get
                    item -> AoEntry(idx, min, max)
                  }
                pairs.toMap
              }
            }

            val exportMap = getMapping(config.getConfigList("exportMap"))
            AoConfig(host, exportMap)
          }

          configList.asScala map {
            getAoConfig
          }
        }

      val haloKaConfig: Option[HaloKaConfig] =
        for (config <- config.getConfig("haloKaConfig")) yield {
          val com = config.getInt("com").get
          val speed = config.getInt("speed").get
          val monitorType = config.getString("monitorType").get
          HaloKaConfig(com, speed, monitorType)
        }

      val haloKaConfig1: Option[HaloKaConfig] =
        for (config <- config.getConfig("haloKaConfig1")) yield {
          val com = config.getInt("com").get
          val speed = config.getInt("speed").get
          val monitorType = config.getString("monitorType").get
          HaloKaConfig(com, speed, monitorType)
        }

      val adam6017ConfigOpt: Option[Adam6017Config] =
        for (config <- config.getConfig("Adam6017Config")) yield {
          val host = config.getString("host").get
          val aiConfigs =
            for (aiConfig <- config.getConfigList("aiConfigs").get.asScala) yield {
              val seq = aiConfig.getInt("seq").get
              val mt = aiConfig.getString("mt").get
              val max = aiConfig.getDouble("max").get
              val mtMax = aiConfig.getDouble("mtMax").get
              val min = aiConfig.getDouble("min").get
              val mtMin = aiConfig.getDouble("mtMin").get
              AiChannelCfg(seq, mt, max, mtMax, min, mtMin)
            }
          Adam6017Config(host, aiConfigs)
        }

      val cleanNotify: Option[CleanNotifyConfig] = CleanNotify.getConfig(config)

      val executeCount = waitReadyResult(sysConfig.getExecuteCount)
      Logger.info(s"${getGcName(idx)} inputDir =$inputDir ")
      gcConfigList = gcConfigList :+
        GcConfig(idx, inputDir, selector, plcConfig, aoConfigs, haloKaConfig, adam6017ConfigOpt, computedTypes,
          cleanNotify,
          com.github.nscala_time.time.Imports.DateTime.now(), executeCount, haloKaConfig1)
    }
  }

  private case object ParseReport

  private case object ExportData

  private case class NotifyClean(config: CleanNotifyConfig)

  private case class NotifyCleanReset(config: CleanNotifyConfig)

}

@javax.inject.Singleton
class GcAgent @Inject()(configuration: Configuration,
                        mongoDB: MongoDB,
                        sysConfig: SysConfig,
                        monitorOp: MonitorOp,
                        monitorTypeOp: MonitorTypeOp,
                        recordOp: RecordOp,
                        alarmOp: AlarmOp,
                        pdfReportOp: PdfReportOp,
                        exporter: Exporter,
                        haloKaAgentFactory: HaloKaAgent.Factory,
                        adam6017AgentFactory: Adam6017Agent.Factory,
                        calibrationOp: CalibrationOp) extends Actor with InjectedActorSupport {

  import GcAgent._

  Logger.info("GcAgent started.")
  private val gcAgent_check_period: Int = configuration.getInt("gcAgent_check_period").getOrElse(60)
  private val export_period: Int = configuration.getInt("export_period").getOrElse(60)

  initGcConfigList(configuration, monitorOp, alarmOp, context.system, sysConfig)

  for (mode <- sysConfig.getOperationMode) {
    for (gcConfig <- gcConfigList)
      exporter.exportLocalModeToPLC(gcConfig, mode)
  }

  self ! ParseReport

  for (gcConfig <- gcConfigList) {
    for (haloKaConfig <- gcConfig.haloKaConfig) {
      injectedChild(haloKaAgentFactory(haloKaConfig, gcConfig), name = s"haloKaAgent${gcConfig.index}")
    }

    for (haloKaConfig <- gcConfig.haloKaConfig1) {
      injectedChild(haloKaAgentFactory(haloKaConfig, gcConfig), name = s"haloKaAgent1${gcConfig.index}")
    }

    for (adam6017Config <- gcConfig.adam6017Config) {
      injectedChild(adam6017AgentFactory(adam6017Config, gcConfig), name = s"adam6017Agent${gcConfig.index}")
    }
  }

  private val MAX_RETRY_COUNT = 60

  import java.io.File

  self ! ExportData

  var counter = 0

  def receive: Receive = handler(Map.empty[String, Int])

  def handler(retryMap: Map[String, Int]): Receive = {
    case ParseReport =>
      try {
        for (gcConfig <- gcConfigList) {
          val newRetryMap = processInputPath(gcConfig, parser, retryMap)
          context.become(handler(newRetryMap))

          Future {
            blocking {
              checkNoDataPeriod(gcConfig)
              for (operationMode <- sysConfig.getOperationMode) {
                if (operationMode == 1 && gcConfig.plcConfig.nonEmpty) //remote mode
                  readPlcStatus(gcConfig)
              }
            }
          }
        }
      } catch {
        case ex: Throwable =>
          Logger.error("process InputPath failed", ex)
      }
      context.system.scheduler.scheduleOnce(FiniteDuration(gcAgent_check_period, scala.concurrent.duration.SECONDS), self, ParseReport)

    case ExportData =>
      try {
        counter += 1
        var pos = -1
        for (gcConfig <- gcConfigList) {
          pos += 1
          if (pos == counter % gcConfigList.size)
            exporter.exportRealtimeData(gcConfig)
        }
      } catch {
        case ex: Throwable =>
          Logger.error("export data failed", ex)
      }
      context.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(export_period, scala.concurrent.duration.SECONDS), self, ExportData)

    case NotifyClean(cleanNotifyConfig) =>
      CleanNotify.notify(cleanNotifyConfig, value = true)
      context.system.scheduler.scheduleOnce(
        FiniteDuration(5, scala.concurrent.duration.SECONDS), self, NotifyCleanReset(cleanNotifyConfig))

    case NotifyCleanReset(cleanNotifyConfig) =>
      CleanNotify.notify(cleanNotifyConfig, value = false)
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

    val f1 = pdfReportOp.collection.insertOne(pdfReport).toFuture()
    f1.onFailure(errorHandler)
    waitReadyResult(f1)

    import com.github.nscala_time.time.Imports._

    def insertRecord(): Unit = {
      val lines =
        Files.readAllLines(Paths.get(reportDir.getAbsolutePath + "/Report.txt"), StandardCharsets.UTF_16LE).asScala

      val sampleName = lines.find(line => line.startsWith("Sample Name:")).map(line => {
        val tokens = line.split(":")
        tokens(1).trim()
      })

      lines.find(line => line.contains("Location"))
        .foreach(line => {
          val tokens = line.split(":")
          assert(tokens.length == 3)
          val locs = tokens(2).trim.split("\\s+").map(_.trim)
          val pos = locs(0).toInt
          gcConfig.selector.set(pos)
        })

      val monitor = monitorOp.getMonitorValueByName(gcConfig.gcName, gcConfig.selector.get)

      val mDate = {
        val mDates =
          for (line <- lines if (line.startsWith("Injection Date"))) yield {
            import java.util.Locale
            val pattern = line.split(":", 2)(1).trim()
            val splitPoint = pattern.indexOf("M")
            val pattern1 = pattern.take(splitPoint + 1)
            DateTime.parse(pattern1, DateTimeFormat.forPattern("M/d/YYYY h:m:s a").withLocale(Locale.US))
              .withSecondOfMinute(0).withMillisOfSecond(0)
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

      val recordMap = mutable.Map.empty[MonitorOp#Value, mutable.Map[DateTime, mutable.Map[MonitorTypeOp#Value, (Double, String)]]]
      val timeMap = recordMap.getOrElseUpdate(monitor, mutable.Map.empty[DateTime, mutable.Map[MonitorTypeOp#Value, (Double, String)]])

      val rLines = getRecordLines(lines)
      for (rec <- rLines) {
        try {
          val tokens = rec.split("\\s+")
          val ppm = try {
            tokens.reverse(1).toInt
            tokens.reverse(2)
          } catch {
            case _: NumberFormatException =>
              tokens.reverse(1)
          }
          val name = tokens.reverse(0)
          //val ppm = rec.substring(40, 50).trim()
          //val name = rec.substring(54).trim()
          assert(!name.contains(" "))
          assert(!name.contains("|"))
          assert(!name.contains("="))
          assert(name.nonEmpty)
          assert(name.charAt(0).isLetter)

          val monitorType = monitorTypeOp.getMonitorTypeValueByName(name, "")
          val mtMap = timeMap.getOrElseUpdate(mDate, mutable.Map.empty[MonitorTypeOp#Value, (Double, String)])

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

      def insertComputedTypes(): Unit = {
        if (gcConfig.computedMtList.isDefined) {
          for (mtMap <- timeMap.values) {
            for (computedType <- gcConfig.computedMtList.get) {
              val computedMt = monitorTypeOp.getMonitorTypeValueByName(computedType._id, "", 1000)
              val values: Seq[Double] = computedType.sum map { mtName =>
                val mt = monitorTypeOp.getMonitorTypeValueByName(mtName, "")
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

      insertComputedTypes()

      val calibrations = waitReadyResult(calibrationOp.getLastCalibrationFuture(1, newGC = false))

      val updateModels =
        for {
          (monitor, timeMaps) <- recordMap
          dateTime <- timeMaps.keys.toList.sorted
          mtMaps = timeMaps(dateTime) if mtMaps.nonEmpty
          doc = recordOp.toDocument(monitor, dateTime, mtMaps.toList, pdfReportId, sampleName)
          updateList = doc.toList.map(kv => Updates.set(kv._1, kv._2)) if updateList.nonEmpty
        } yield {
          if (sampleName.contains("SPAN")) {
            val cal = Calibration(_id = CalibrationId(monitor.toString, mDate.toDate),
              mtDataList = mtMaps.map(kv => MtRecord(kv._1.toString, kv._2._1, kv._2._2, "")).toList,
              sampleName = sampleName,
              fileName = Some(reportDir.getAbsolutePath),
              containerId = Some("SPAN"),
              fromNewGc = Some(true))
            calibrationOp.upsert(cal)
          }

          val updateListWithWater =
            if (calibrations.nonEmpty) {
              val cal = calibrations.head
              val calTime = new DateTime(cal._id.time)
              if (calTime <= mDate && calTime >= mDate.minusMinutes(10)) {
                updateList :+ Updates.set("H2O", cal.mtDataList
                  .find(_.mtName == "H2O")
                  .map(_.value))
              } else
                updateList
            } else
              updateList

          UpdateOneModel(
            Filters.eq("_id", doc("_id")),
            Updates.combine(updateListWithWater: _*), UpdateOptions().upsert(true))
        }

      if (updateModels.nonEmpty) {
        val collection = mongoDB.database.getCollection(RecordOp.MinCollection)
        val f2 = collection.bulkWrite(updateModels.toList, BulkWriteOptions().ordered(false)).toFuture()
        f2.onFailure(errorHandler)
        f2 onSuccess {
          case _ =>
            gcConfig.executeCount += 1
            sysConfig.setExecuteCount(gcConfig.executeCount)
            for (cleanCount <- sysConfig.getCleanCount) {
              if (cleanCount != 0 && gcConfig.executeCount >= cleanCount) {
                gcConfig.executeCount = gcConfig.executeCount % cleanCount
                for (cleanNotify <- gcConfig.cleanNotifyConfig) {
                  context.system.scheduler.scheduleOnce(
                    FiniteDuration(cleanNotify.delay, scala.concurrent.duration.MINUTES), self, NotifyClean(cleanNotify))
                }
              }
            }
            exporter.exportRealtimeData(gcConfig)
        }
      }
    } //End of process report.txt

    insertRecord()
    true
  }

  def listDirs(files_path: String): List[File] = {
    //import java.io.FileFilter
    val path = new java.io.File(files_path)
    if (path.exists() && path.isDirectory) {
      def isArchive(f: File) = {
        import java.nio.file._
        import java.nio.file.attribute.DosFileAttributes

        val dfa = Files.readAttributes(Paths.get(f.getAbsolutePath), classOf[DosFileAttributes])
        dfa.isArchive
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

  private def processInputPath(gcConfig: GcConfig,
                               parser: (GcConfig, File) => Boolean,
                               _retryMap: Map[String, Int]): Map[String, Int] = {
    import java.io.File

    def setArchive(f: File): Unit = {
      import java.nio.file._
      import java.nio.file.attribute.DosFileAttributeView

      val path = Paths.get(f.getAbsolutePath)
      val dfav = Files.getFileAttributeView(path, classOf[DosFileAttributeView])
      dfav.setArchive(true)
    }

    var newRetryMap = _retryMap

    val dirs = listDirs(gcConfig.inputDir)
    for (dir <- dirs) yield {
      val absPath = dir.getAbsolutePath
      if (!newRetryMap.contains(absPath))
        Logger.info(s"Processing $absPath")

      try {
        if (parser(gcConfig, dir)) {
          setArchive(dir)
          Logger.info(s"Successfully processed: $absPath")
        }
      } catch {
        case ex: Throwable =>
          if (newRetryMap.contains(absPath)) {
            if (newRetryMap(absPath) + 1 <= MAX_RETRY_COUNT) {
              newRetryMap += (absPath -> (newRetryMap(absPath) + 1))
            } else {
              Logger.error(s"$absPath reach max retries. Give up!", ex)
              newRetryMap -= absPath
              setArchive(dir)
            }
          } else
            newRetryMap += (absPath -> 1)
      }
    }
    newRetryMap
  }

  private def checkNoDataPeriod(gcConfig: GcConfig): Future[Unit] = {
    import com.github.nscala_time.time.Imports._

    for {dataPeriod <- sysConfig.getDataPeriod
         stopWarn <- sysConfig.getStopWarn
         } yield {

      if (!stopWarn && (gcConfig.latestDataTime + dataPeriod.minutes) < DateTime.now) {
        val mv = monitorOp.getMonitorValueByName(gcConfig.gcName, gcConfig.selector.get)
        val monitor = monitorOp.map(mv).dp_no
        alarmOp.log(Some(monitor), None, "沒有資料匯入!", dataPeriod)
        exporter.notifyAlarm(gcConfig, alarm = true)
      }
    }
  }

  private def readPlcStatus(gcConfig: GcConfig): Unit = {
    gcConfig.plcConfig foreach {
      plcConfig =>
        var connectorOpt: Option[S7Connector] = None
        try {
          connectorOpt = Exporter.getPlcConnector(plcConfig)
          for (connector <- connectorOpt) {
            val serializer = S7SerializerFactory.buildSerializer(connector)
            if (plcConfig.importMap.contains("selector")) {
              val entry = plcConfig.importMap("selector")

              def notifyPLC(pos: Int): Unit = {
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

  override def postStop: Unit = {

  }
}