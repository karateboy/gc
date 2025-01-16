package controllers

import com.github.nscala_time.time.Imports._
import models._
import org.mongodb.scala.bson.ObjectId
import play.api._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.mvc._

import javax.inject.Inject
import scala.concurrent._

class Realtime @Inject()(monitorOp: MonitorOp,
                         recordOp: RecordOp,
                         exporter: Exporter,
                         sysConfig: SysConfig,
                         monitorTypeOp: MonitorTypeOp) extends Controller {
  def MonitorTypeStatusList(): Action[AnyContent] = Security.Authenticated.async {
      Future.successful(Ok(""))
  }

  case class GcLatestStatus(monitor: String, time: Long, mtDataList: Seq[MtRecord], pdfReport: ObjectId, executeCount: Int)
  import models.ObjectIdUtil._
  def latestValues(gcFilter: String): Action[AnyContent] = Security.Authenticated.async {
    implicit request =>
      val gcConfig = GcAgent.gcConfigList.find(_.gcName == gcFilter).get
      val monitors = monitorOp.getMonitorByGcFilter(gcFilter)
      val latestRecord = recordOp.getLatestRecordFuture(RecordOp.MinCollection, monitors, rename = true)
      implicit val mtRecordWrite = Json.writes[MtRecord]
      implicit val gcLatestStatusWrite: OWrites[GcLatestStatus] = Json.writes[GcLatestStatus]

      for (records <- latestRecord) yield {
        val gcLatestStatus =
          if (records.isEmpty) {
            GcLatestStatus("-", DateTime.now().getMillis, Seq.empty[MtRecord], new ObjectId(), gcConfig.executeCount)
          } else {
            val recordList = records.head
            GcLatestStatus(recordList.monitor,
              recordList.time,
              recordList.mtDataList.sortBy(mtRecord=>
                monitorTypeOp.map(monitorTypeOp.getMonitorTypeValueByName(mtRecord.mtName)).order),
              recordList.pdfReport, gcConfig.executeCount)
          }
        Ok(Json.toJson(gcLatestStatus))
      }
  }

  case class CellData(v: String, cellClassName: String)

  case class RowData(cellData: Seq[CellData])

  case class DataTab(columnNames: Seq[String], rows: Seq[RowData])

  implicit val cellWrite: OWrites[CellData] = Json.writes[CellData]
  implicit val rowWrite: OWrites[RowData] = Json.writes[RowData]
  implicit val dtWrite: OWrites[DataTab] = Json.writes[DataTab]

  import monitorOp._
  def getGcMonitors: Action[AnyContent] = Security.Authenticated.async {
      var gcNameMonitorMap = Map.empty[String, Seq[Monitor]]

      for (gcNameMap <- sysConfig.getGcNameMap) yield {
        for (monitor <- monitorOp.map.values.toList.sortBy(_.selector)) {
          val gcMonitorList = gcNameMonitorMap.getOrElse(gcNameMap(monitor.gcName), Seq.empty[Monitor])
          gcNameMonitorMap = gcNameMonitorMap + (gcNameMap(monitor.gcName) -> gcMonitorList.:+(monitor))
        }
        Ok(Json.toJson(gcNameMonitorMap))
      }

  }

  def getCurrentMonitor(): Action[AnyContent] = Security.Authenticated {
    implicit request =>
      val monitors =
        for (gcConfig <- GcAgent.gcConfigList) yield {
          monitorOp.withName(monitorOp.monitorId(gcConfig.gcName, gcConfig.selector.get))
        }
      val monitorCase =monitors map {
        monitorOp.map
      }
      Ok(Json.toJson(monitorCase))
  }

  def setCurrentMonitor(monitorId: String): Action[AnyContent] = Security.Authenticated.async {
    implicit request =>
      val tokens = monitorId.split(":")
      val gcName = tokens(0)
      val selector = tokens(1).toInt
      Logger.info(s"$gcName Selector set to ${selector}")
      for (config <- GcAgent.gcConfigList.find(config => config.gcName == gcName)) {
        config.selector.set(selector)
        exporter.notifySelectorChange(config, selector)
      }

      Future {
        blocking {
          Thread.sleep(1000)
        }
        Ok("")
      }
  }
}