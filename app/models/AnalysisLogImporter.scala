package models

import akka.actor.{Actor, Cancellable}
import com.github.tototoshi.csv.CSVReader
import models.AnalysisLogImporter.ImportLog
import org.joda.time.format.DateTimeFormat
import org.joda.time.{LocalDate, LocalTime}
import play.api.Logger

import java.io.File
import javax.inject.Inject
import scala.language.postfixOps

//import scala.concurrent.ExecutionContext.Implicits.global
private object AnalysisLogImporter {
  private case object ImportLog
}

@javax.inject.Singleton
class AnalysisLogImporter @Inject()(calibrationOp: CalibrationOp, sysConfig: SysConfig,
                                    monitorOp: MonitorOp, monitorTypeOp: MonitorTypeOp) extends Actor {

  import context.dispatcher

  val calibrationMonitor: Monitor = monitorOp.getMonitorList.last

  private def importLog(): Unit = {
    for {logPath <- sysConfig.getAnalysisLogPath
         skip <- sysConfig.getLogSkip
         } {
      try {
        if (logPath.nonEmpty) {
          val file = new File(logPath)
          if (!file.exists())
            throw new Exception(s"Analysis log file $logPath not found")


          val recordMapList = CSVReader.open(file).allWithHeaders()
          logger.info(s"Import ${recordMapList.length} records skip $skip")
          if (recordMapList.length > skip) {
            val newSkip = recordMapList.length
            val calibrations = recordMapList.zipWithIndex.drop(skip) flatMap { pair =>
              try {
                val (recordMap, _) = pair
                val date = LocalDate.parse(recordMap("Sample Date"), DateTimeFormat.forPattern("MM/dd/yyyy"))
                val timeFmt = DateTimeFormat.forPattern("HH:mm:ss")
                val time = LocalTime.parse(recordMap("Sample Time"), timeFmt)
                val dateTime = date.toDateTime(time)
                val mtList = Seq("H2", "ArO2", "N2", "CO", "CH4", "CO2", "H20")
                val mtDataList = recordMap flatMap { pair =>
                  val (key, value) = pair
                  if (mtList.contains(key)) {
                    val mt = monitorTypeOp.getMonitorTypeValueByName(key)
                    val mtCase = monitorTypeOp.map(mt)
                    val status = if(mtCase.cal_high.isDefined && value.toDouble > mtCase.cal_high.get)
                      MonitorStatus.OverNormalStat
                    else if(mtCase.cal_low.isDefined && value.toDouble < mtCase.cal_low.get)
                      MonitorStatus.OverNormalStat
                    else
                      MonitorStatus.NormalStat

                    Some(MtRecord(mt.toString, value.toDouble, status, ""))
                  } else
                    None
                }
                Some(Calibration(CalibrationId(calibrationMonitor._id, dateTime.toDate),
                  mtDataList = mtDataList.toList,
                  sampleName = recordMap.get("Sample Name"),
                  fileName = recordMap.get("Filename"),
                  containerId = recordMap.get("Container ID")))
              } catch {
                case ex: Exception =>
                  logger.error(s"Failed to parse record LL${pair._2}", ex)
                  None
              }
            }
            calibrationOp.upsertMany(calibrations)
            sysConfig.setLogSkip(newSkip)
          }
        }
      }
      catch {
        case ex: Exception =>
          Logger.error("skip to import analysis log", ex)
      }

    }
  }

  val logger: Logger = play.api.Logger(getClass)

  logger.info("AnalysisLogImporter started")

  val timer: Cancellable = {
    import scala.concurrent.duration._
    context.system.scheduler.schedule(0 seconds, 3 minutes, self, ImportLog)
  }


  override def receive: Receive = {
    case ImportLog =>
      importLog()
  }

  override def postStop(): Unit = {
    timer.cancel()
  }
}
