package controllers
import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.Play.current
import play.api.data._
import play.api.data.Forms._
import play.api.libs.ws._
import play.api.libs.ws.ning.NingAsyncHttpClientConfigBuilder
import scala.concurrent._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import com.github.nscala_time.time.Imports._
import Highchart._
import models._

object Realtime extends Controller {
  val overTimeLimit = 6
  case class MonitorTypeStatus(desp: String, value: String, unit: String, instrument: String, status: String, classStr: String, order: Int)
  def MonitorTypeStatusList() = Security.Authenticated.async {
    implicit request =>
      import MonitorType._

      implicit val mtsWrite = Json.writes[MonitorTypeStatus]

      Future {
        Ok("")
      }
    //      val result =
    //        for (dataMap <- DataCollectManager.getLatestData()) yield {
    //          val list =
    //            for {
    //              mt <- MonitorType.realtimeMtvList
    //              recordOpt = dataMap.get(mt)
    //            } yield {
    //              val mCase = map(mt)
    //
    //              if (recordOpt.isDefined) {
    //                val record = recordOpt.get
    //                val duration = new Duration(record.time, DateTime.now())
    //                val (overInternal, overLaw) = overStd(mt, record.value)
    //                val status = if (duration.getStandardSeconds <= overTimeLimit)
    //                  MonitorStatus.map(record.status).desp
    //                else
    //                  "通訊中斷"
    //
    //                MonitorTypeStatus(mCase.desp, format(mt, Some(record.value)), mCase.unit, "",
    //                  MonitorStatus.map(record.status).desp,
    //                  MonitorStatus.getCssClassStr(record.status, overInternal, overLaw), mCase.order)
    //              } else {
    //                MonitorTypeStatus(mCase.desp, format(mt, None), mCase.unit, "",
    //                  "通訊中斷",
    //                  "abnormal_status", mCase.order)
    //              }
    //            }
    //          Ok(Json.toJson(list))
    //        }
    //
    //      result
  }

  def realtimeStatus = Security.Authenticated {
    Ok(views.html.realtimeStatusA(""))
  }

  def realtimeMonitorTypeValues() = Security.Authenticated.async {
    implicit request =>
      val latestRecord = Record.getLatestRecordListFuture(Record.MinCollection)

      for (records <- latestRecord) yield {
        if (records.isEmpty) {
          import org.mongodb.scala.bson._
          val emptyRecordList = Record.RecordList(DateTime.now().getMillis, Seq.empty[Record.MtRecord], new ObjectId())
          Ok(Json.toJson(emptyRecordList))
        } else {
          val recordList = records.head
          Ok(Json.toJson(recordList))
        }
      }

  }
  def realtimeStatusContent() = Security.Authenticated.async {
    implicit request =>
      import MonitorType._
      val user = request.user
      val latestRecordMap = Record.getLatestRecordMapFuture(Record.MinCollection)

      for {
        userOpt <- User.getUserByIdFuture(user.id) if userOpt.isDefined
        groupInfo = Group.getGroupInfo(userOpt.get.groupId)
        map <- latestRecordMap
      } yield {
        Ok(views.html.realtimeStatus(map, groupInfo.privilege))
      }
  }

  case class CellData(v: String, cellClassName: String)
  case class RowData(cellData: Seq[CellData])
  case class DataTab(columnNames: Seq[String], rows: Seq[RowData])

  implicit val cellWrite = Json.writes[CellData]
  implicit val rowWrite = Json.writes[RowData]
  implicit val dtWrite = Json.writes[DataTab]

  def realtimeData() = Security.Authenticated.async {
    implicit request =>
      import MonitorType._
      val user = request.user
      val latestRecordMapF = Record.getLatestRecordMap2Future(Record.HourCollection)
      val targetTime = (DateTime.now() - 2.hour).withMinuteOfHour(0).withSecond(0).withMillisOfSecond(0)
      val ylMonitors = Monitor.mvList filter { Monitor.map(_).indParkName == "台塑六輕工業園區" }
      for {
        map <- latestRecordMapF
        yulinMap = map.filter { kv =>
          Monitor.map(kv._1).indParkName == "台塑六輕工業園區"
        }
      } yield {
        var yulinFullMap = yulinMap
        for (m <- ylMonitors) {
          if (!yulinFullMap.contains(m))
            yulinFullMap += (m -> (targetTime, Map.empty[MonitorType.Value, Record]))
        }

        val mtColumns =
          for (mt <- MonitorType.activeMtvList) yield s"${MonitorType.map(mt).desp}"

        val columns = "測站" +: "資料時間" +: mtColumns
        val rows = for {
          (monitor, recordPair) <- yulinFullMap
          (time, recordMap) = recordPair
        } yield {
          val monitorCell = CellData(s"${Monitor.map(monitor).dp_no}", "")
          val timeCell = CellData(s"${time.toLocalTime().toString("HH:mm")}", "")
          val valueCells =
            for {
              mt <- MonitorType.activeMtvList
              v = MonitorType.formatRecord(mt, recordMap.get(mt))
              styleStr = MonitorType.getCssClassStr(mt, recordMap.get(mt))
            } yield CellData(v, styleStr)
          RowData(monitorCell +: timeCell +: valueCells)
        }

        Ok(Json.toJson(DataTab(columns, rows.toSeq)))
      }
  }

  def getCurrentMonitor() = Security.Authenticated {
    implicit request =>
      val m = Monitor.withName(Monitor.monitorId(Selector.get))
      Ok(Json.toJson(Monitor.map(m)))
  }

  def setCurrentMonitor(id: Int) = Security.Authenticated.async {
    implicit request =>
      Logger.info(s"Selector set to ${id}")
      Selector.set(id)
      import java.util.Timer

      Future {
        blocking { Thread.sleep(1500) }
        Ok("")
      }
  }
}