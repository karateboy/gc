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

import scala.collection.mutable

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
  }

  def latestValues() = Security.Authenticated.async {
    implicit request =>
      val latestRecord = Record.getLatestRecordListFuture(Record.MinCollection, true)(1)

      for (records <- latestRecord) yield {
        if (records.isEmpty) {
          import org.mongodb.scala.bson._
          val emptyRecordList = Record.RecordList("-", DateTime.now().getMillis, Seq.empty[Record.MtRecord], new ObjectId())
          Ok(Json.toJson(emptyRecordList))
        } else {
          val recordList = records.head
          Ok(Json.toJson(recordList))
        }
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
      val ylMonitors = Monitor.mvList filter {
        Monitor.map(_).gcName == "台塑六輕工業園區"
      }
      for {
        map <- latestRecordMapF
        yulinMap = map.filter { kv =>
          Monitor.map(kv._1).gcName == "台塑六輕工業園區"
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
          val monitorCell = CellData(s"${Monitor.map(monitor).selector}", "")
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

  def getGcMonitors() = Security.Authenticated.async {
    implicit request =>
      var gcNameMonitorMap = Map.empty[String, Seq[Monitor]]

      for (gcNameMap <- Monitor.getGcNameMap()) yield {
        for (monitor <- Monitor.mvList map {
          Monitor.map
        }) {
          val gcMonitorList = gcNameMonitorMap.getOrElse(gcNameMap(monitor.gcName), Seq.empty[Monitor])
          gcNameMonitorMap = gcNameMonitorMap + (gcNameMap(monitor.gcName) -> gcMonitorList.:+(monitor))
        }
        Ok(Json.toJson(gcNameMonitorMap))
      }

  }

  def getCurrentMonitor() = Security.Authenticated {
    implicit request =>
      val monitors: mutable.Seq[_root_.models.Monitor.Value] =
        for (gcConfig <- GcAgent.gcConfigList) yield {
          Monitor.withName(Monitor.monitorId(gcConfig.gcName, gcConfig.selector.get))
        }
      val monitorCase: mutable.Seq[Monitor] = monitors map {
        Monitor.map
      }
      Ok(Json.toJson(monitorCase))
  }

  def setCurrentMonitor(monitorId: String) = Security.Authenticated.async {
    implicit request =>
      val tokens = monitorId.split(":")
      val gcName = tokens(0)
      val selector = tokens(1).toInt
      Logger.info(s"$gcName Selector set to ${selector}")
      for (config <- GcAgent.gcConfigList.find(config => config.gcName == gcName)) {
        config.selector.set(selector)
        Exporter.notifySelectorChange(config, selector)
      }

      import java.util.Timer

      Future {
        blocking {
          Thread.sleep(1000)
        }
        Ok("")
      }
  }
}