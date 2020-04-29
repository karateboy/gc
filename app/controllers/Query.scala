package controllers
import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.json.Json
import play.api.Play.current
import play.api.data._
import play.api.data.Forms._
import play.api.libs.ws._
import play.api.libs.ws.ning.NingAsyncHttpClientConfigBuilder

import scala.concurrent.Future
import play.api.libs.json._
import com.github.nscala_time.time.Imports._
import Highchart._
import models._
import models.ModelHelper._

import scala.concurrent.ExecutionContext.Implicits.global
import models.ObjectIdUtil._
import java.nio.file.Files

import models.MonitorType.map

case class Stat(
  avg:        Option[Double],
  min:        Option[Double],
  max:        Option[Double],
  count:      Int,
  total:      Int,
  overCount:  Int,
  hour_count: Option[Int]    = None,
  hour_total: Option[Int]    = None) {
  val effectPercent = {
    if (total > 0)
      Some(count.toDouble * 100 / total)
    else
      None
  }

  val hourEffectPercent = {
    for {
      h_count <- hour_count
      h_total <- hour_total
    } yield h_count.toDouble * 100 / h_total
  }

  val isEffective = {
    effectPercent.isDefined && effectPercent.get > 75
  }
  val overPercent = {
    if (count > 0)
      Some(overCount.toDouble * 100 / total)
    else
      None
  }
}

object Query extends Controller {
  def windAvg(sum_sin: Double, sum_cos: Double) = {
    val degree = Math.toDegrees(Math.atan2(sum_sin, sum_cos))
    if (degree >= 0)
      degree
    else
      degree + 360
  }

  def windAvg(windSpeed: Seq[Record], windDir: Seq[Record]): Double = {
    if (windSpeed.length != windDir.length)
      Logger.error(s"windSpeed #=${windSpeed.length} windDir #=${windDir.length}")

    val windRecord = windSpeed.zip(windDir)
    val wind_sin = windRecord.map(v => v._1.value * Math.sin(Math.toRadians(v._2.value))).sum
    val wind_cos = windRecord.map(v => v._1.value * Math.cos(Math.toRadians(v._2.value))).sum
    windAvg(wind_sin, wind_cos)
  }

  def windAvg(windSpeed: List[Double], windDir: List[Double]): Double = {
    if (windSpeed.length != windDir.length)
      Logger.error(s"windSpeed #=${windSpeed.length} windDir #=${windDir.length}")

    val windRecord = windSpeed.zip(windDir)
    val wind_sin = windRecord.map(v => v._1 * Math.sin(Math.toRadians(v._2))).sum
    val wind_cos = windRecord.map(v => v._1 * Math.cos(Math.toRadians(v._2))).sum
    windAvg(wind_sin, wind_cos)
  }

  def getPeriods(start: DateTime, endTime: DateTime, d: Period): List[DateTime] = {
    import scala.collection.mutable.ListBuffer

    val buf = ListBuffer[DateTime]()
    var current = start
    while (current < endTime) {
      buf.append(current)
      current += d
    }

    buf.toList
  }

  def getPeriodCount(start: DateTime, endTime: DateTime, p: Period) = {
    var count = 0
    var current = start
    while (current < endTime) {
      count += 1
      current += p
    }

    count
  }

  def getPeriodReportMap(monitor: Monitor.Value, mt: MonitorType.Value, tabType: TableType.Value, period: Period,
                         statusFilter: MonitorStatusFilter.Value = MonitorStatusFilter.ValidData)(start: DateTime, end: DateTime) = {
    val recordList = Record.getRecordMap(TableType.mapCollection(tabType))(List(mt), monitor, start, end)(mt)
    def periodSlice(period_start: DateTime, period_end: DateTime) = {
      recordList.dropWhile { _.time < period_start }.takeWhile { _.time < period_end }
    }
    val pairs =
      if (period.getHours == 1) {
        recordList.filter { r => MonitorStatusFilter.isMatched(statusFilter, r.status) }.map { r => r.time -> r.value }
      } else {
        for {
          period_start <- getPeriods(start, end, period)
          records = periodSlice(period_start, period_start + period) if records.length > 0
        } yield {
          val values = records.map { r => r.value }
          period_start -> values.sum / values.length
        }
      }

    Map(pairs: _*)
  }

  def getPeriodBoxReport(monitor: Monitor.Value, mt: MonitorType.Value, tabType: TableType.Value, period: Period,
                         statusFilter: MonitorStatusFilter.Value = MonitorStatusFilter.ValidData)(start: DateTime, end: DateTime) = {
    val recordList = Record.getRecordMap(TableType.mapCollection(tabType))(List(mt), monitor, start, end)(mt)
    def periodSlice(period_start: DateTime, period_end: DateTime) = {
      recordList.dropWhile { _.time < period_start }.takeWhile { _.time < period_end }
    }
    val data =
      if (period.getHours == 1) {
        recordList.filter { r => MonitorStatusFilter.isMatched(statusFilter, r.status) }.map { r => r.value }
      } else {
        for {
          period_start <- getPeriods(start, end, period)
          records = periodSlice(period_start, period_start + period) if records.length > 0
        } yield {
          val values = records.map { r => r.value }
          values.sum / values.length
        }
      }
    val sorted = data.sorted
    val min = if (sorted.length >= 1)
      Some(sorted.head)
    else
      None
    val max = if (sorted.length >= 1)
      Some(sorted.last)
    else
      None
    val med = if (sorted.length >= 3)
      Some(sorted(sorted.length / 2))
    else
      None

    val low_q = if (sorted.length >= 4)
      Some(sorted(sorted.length / 4))
    else
      None

    val high_q = if (sorted.length >= 4)
      Some(sorted(sorted.length * 3 / 4))
    else
      None

    Seq(min, low_q, med, high_q, max)
  }

  def getPeriodStatReportMap(recordListMap: Map[MonitorType.Value, Seq[Record]], period: Period, statusFilter: List[String] = List("010"))(start: DateTime, end: DateTime) = {
    val mTypes = recordListMap.keys.toList

    if (period.getHours == 1) {
      throw new Exception("小時區間無Stat報表")
    }

    def periodSlice(recordList: Seq[Record], period_start: DateTime, period_end: DateTime) = {
      recordList.dropWhile { _.time < period_start }.takeWhile { _.time < period_end }
    }

    def getPeriodStat(records: Seq[Record], mt: MonitorType.Value, period_start: DateTime) = {
      if (records.length == 0)
        Stat(None, None, None, 0, 0, 0)
      else {
        val values = records.map { r => r.value }
        val min = values.min
        val max = values.max
        val sum = values.sum
        val count = records.filter { r => statusFilter.contains(r.status) }.length
        val total = new Duration(period_start, period_start + period).getStandardHours.toInt
        val overCount = if (MonitorType.map(mt).std_law.isDefined) {
          values.count { _ > MonitorType.map(mt).std_law.get }
        } else
          0

        val avg = {
          sum / total
        }
        Stat(
          avg = Some(avg),
          min = Some(min),
          max = Some(max),
          total = total,
          count = count,
          overCount = overCount)
      }
    }
    val pairs = {
      for {
        mt <- mTypes
      } yield {
        val timePairs =
          for {
            period_start <- getPeriods(start, end, period)
            records = periodSlice(recordListMap(mt), period_start, period_start + period)
          } yield {
            period_start -> getPeriodStat(records, mt, period_start)
          }
        mt -> Map(timePairs: _*)
      }
    }

    Map(pairs: _*)
  }

  def trendHelper(monitors: Array[Monitor.Value], monitorTypes: Array[MonitorType.Value], tabType: TableType.Value,
                  reportUnit: ReportUnit.Value, start: DateTime, end: DateTime)(statusFilter: MonitorStatusFilter.Value) = {

    val period: Period =
      reportUnit match {
        case ReportUnit.Min =>
          1.minute
        case ReportUnit.TenMin =>
          10.minute
        case ReportUnit.Hour =>
          1.hour
        case ReportUnit.Day =>
          1.day
        case ReportUnit.Month =>
          1.month
        case ReportUnit.Quarter =>
          3.month
        case ReportUnit.Year =>
          1.year
      }

    val timeList = getPeriods(start, end, period)
    val timeSeq = timeList

    def getSeries() = {

      val monitorReportPairs =
        for {
          monitor <- monitors
        } yield {
          val pair =
            for {
              mt <- monitorTypes
              reportMap = getPeriodReportMap(monitor, mt, tabType, period, statusFilter)(start, end)
            } yield mt -> reportMap
          monitor -> pair.toMap
        }

      val monitorReportMap = monitorReportPairs.toMap
      for {
        m <- monitors
        mt <- monitorTypes
        valueMap = monitorReportMap(m)(mt)
        timeData = timeSeq.map { time =>

          if (valueMap.contains(time))
            Seq(Some(time.getMillis.toDouble), Some(valueMap(time).toDouble))
          else
            Seq(Some(time.getMillis.toDouble), None)
        }
      } yield {
        if (monitorTypes.length > 1) {
          seqData(s"${Monitor.map(m).selector}_${MonitorType.map(mt).desp}", timeData)
        } else {
          seqData(s"${Monitor.map(m).selector}_${MonitorType.map(mt).desp}", timeData)
        }
      }
    }

    val series = getSeries()

    val downloadFileName = {
      val startName = start.toString("YYMMdd")
      val mtNames = monitorTypes.map { MonitorType.map(_).desp }
      startName + mtNames.mkString
    }

    val title =
      reportUnit match {
        case ReportUnit.Min =>
          s"趨勢圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"
        case ReportUnit.TenMin =>
          s"趨勢圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"
        case ReportUnit.Hour =>
          s"趨勢圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"
        case ReportUnit.Day =>
          s"趨勢圖 (${start.toString("YYYY年MM月dd日")}~${end.toString("YYYY年MM月dd日")})"
        case ReportUnit.Month =>
          s"趨勢圖 (${start.toString("YYYY年MM月")}~${end.toString("YYYY年MM月dd日")})"
        case ReportUnit.Quarter =>
          s"趨勢圖 (${start.toString("YYYY年MM月")}~${end.toString("YYYY年MM月dd日")})"
        case ReportUnit.Year =>
          s"趨勢圖 (${start.toString("YYYY年")}~${end.toString("YYYY年")})"
      }

    def getAxisLines(mt: MonitorType.Value) = {
      val mtCase = MonitorType.map(mt)
      val std_law_line =
        if (mtCase.std_law.isEmpty)
          None
        else
          Some(AxisLine("#FF0000", 2, mtCase.std_law.get, Some(AxisLineLabel("right", "法規值"))))

      val lines = Seq(std_law_line, None).filter { _.isDefined }.map { _.get }
      if (lines.length > 0)
        Some(lines)
      else
        None
    }

    val xAxis = {
      val duration = new Duration(start, end)
      if (duration.getStandardDays > 2)
        XAxis(None, gridLineWidth = Some(1), None)
      else
        XAxis(None)
    }

    val chart =
      if (monitorTypes.length == 1) {
        val mt = monitorTypes(0)
        val mtCase = MonitorType.map(monitorTypes(0))

        HighchartData(
          Map("type" -> "line"),
          Map("text" -> title),
          xAxis,

          Seq(YAxis(None, AxisTitle(Some(Some(s"${mtCase.desp} (${mtCase.unit})"))), getAxisLines(mt))),
          series,
          Some(downloadFileName))
      } else {
        val yAxis =
          Seq(YAxis(None, AxisTitle(Some(None)), None))

        HighchartData(
          Map("type" -> "line"),
          Map("text" -> title),
          xAxis,
          yAxis,
          series,
          Some(downloadFileName))
      }

    chart
  }

  def trendHelper2(monitors: Array[Monitor.Value], monitorTypes: Array[MonitorType.Value], tabType: TableType.Value,
                   start: DateTime, end: DateTime)(statusFilter: MonitorStatusFilter.Value) = {

    val recordMapF = Record.getMonitorRecordMapF(TableType.mapCollection(tabType))(monitorTypes.toList, monitors, start, end)
    recordMapF.onFailure(errorHandler)

    def getAxisLines(mt: MonitorType.Value) = {
      val mtCase = MonitorType.map(mt)
      val std_law_line =
        if (mtCase.std_law.isEmpty)
          None
        else
          Some(AxisLine("#FF0000", 2, mtCase.std_law.get, Some(AxisLineLabel("right", "法規值"))))

      val lines = Seq(std_law_line, None).filter { _.isDefined }.map { _.get }
      if (lines.length > 0)
        Some(lines)
      else
        None
    }

    val downloadFileName = {
      val startName = start.toString("YYMMdd")
      val mtNames = monitorTypes.map { MonitorType.map(_).desp }
      startName + mtNames.mkString
    }

    for (recordMap <- recordMapF) yield {
      val timeSeq = recordMap.keys.toSeq.sorted
      val series = {
        for {
          m <- monitors
          mt <- monitorTypes
          //valueMap = monitorReportMap(m)(mt)
          timeData = timeSeq.map { time =>

            if (recordMap.contains(time) && recordMap(time).contains(m) && recordMap(time)(m).contains(mt))
              Seq(Some(time.getMillis.toDouble), Some(recordMap(time)(m)(mt).value))
            else
              Seq(Some(time.getMillis.toDouble), None)
          }
        } yield {
          if (monitorTypes.length > 1) {
            seqData(s"${Monitor.map(m).dp_no}_${MonitorType.map(mt).desp}", timeData)
          } else {
            seqData(s"${Monitor.map(m).dp_no}_${MonitorType.map(mt).desp}", timeData)
          }
        }
      }
      val title =
        s"趨勢圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"

      val xAxis = {
        val duration = new Duration(start, end)
        if (duration.getStandardDays > 2)
          XAxis(None, gridLineWidth = Some(1), None)
        else
          XAxis(None)
      }

      val chart =
        if (monitorTypes.length == 1) {
          val mt = monitorTypes(0)
          val mtCase = MonitorType.map(monitorTypes(0))

          HighchartData(
            Map("type" -> "line"),
            Map("text" -> title),
            xAxis,

            Seq(YAxis(None, AxisTitle(Some(Some(s"${mtCase.desp} (${mtCase.unit})"))), getAxisLines(mt))),
            series,
            Some(downloadFileName))
        } else {
          val yAxis =
            Seq(YAxis(None, AxisTitle(Some(None)), None))

          HighchartData(
            Map("type" -> "line"),
            Map("text" -> title),
            xAxis,
            yAxis,
            series,
            Some(downloadFileName))
        }

      chart
    }
  }

  def boxHelper(monitors: Array[Monitor.Value], monitorTypes: Array[MonitorType.Value], tabType: TableType.Value,
                reportUnit: ReportUnit.Value, start: DateTime, end: DateTime)(statusFilter: MonitorStatusFilter.Value) = {

    val period: Period =
      reportUnit match {
        case ReportUnit.Min =>
          1.minute
        case ReportUnit.TenMin =>
          10.minute
        case ReportUnit.Hour =>
          1.hour
        case ReportUnit.Day =>
          1.day
        case ReportUnit.Month =>
          1.month
        case ReportUnit.Quarter =>
          3.month
        case ReportUnit.Year =>
          1.year
      }

    val timeList = getPeriods(start, end, period)
    val timeSeq = timeList

    def getSeries() = {
      val monitorReportPairs =
        for {
          monitor <- monitors
        } yield {
          val pair =
            for {
              mt <- monitorTypes
              boxReport = getPeriodBoxReport(monitor, mt, tabType, period, statusFilter)(start, end)
            } yield mt -> boxReport
          monitor -> pair.toMap
        }

      val monitorReportMap = monitorReportPairs.toMap
      for {
        m <- monitors
        boxReport = monitorTypes map { mt => monitorReportMap(m)(mt) }
      } yield {
        seqData(s"${Monitor.map(m).selector}", boxReport)
      }
    }

    val series = getSeries()

    val downloadFileName = {
      val startName = start.toString("YYMMdd")
      val mtNames = monitorTypes.map { MonitorType.map(_).desp }
      startName + mtNames.mkString
    }

    val title =
      reportUnit match {
        case ReportUnit.Min =>
          s"盒鬚圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"
        case ReportUnit.TenMin =>
          s"盒鬚圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"
        case ReportUnit.Hour =>
          s"盒鬚圖 (${start.toString("YYYY年MM月dd日 HH:mm")}~${end.toString("YYYY年MM月dd日 HH:mm")})"
        case ReportUnit.Day =>
          s"盒鬚圖 (${start.toString("YYYY年MM月dd日")}~${end.toString("YYYY年MM月dd日")})"
        case ReportUnit.Month =>
          s"盒鬚圖 (${start.toString("YYYY年MM月")}~${end.toString("YYYY年MM月dd日")})"
        case ReportUnit.Quarter =>
          s"盒鬚圖 (${start.toString("YYYY年MM月")}~${end.toString("YYYY年MM月dd日")})"
        case ReportUnit.Year =>
          s"盒鬚圖 (${start.toString("YYYY年")}~${end.toString("YYYY年")})"
      }

    def getAxisLines(mt: MonitorType.Value) = {
      val mtCase = MonitorType.map(mt)
      val std_law_line =
        if (mtCase.std_law.isEmpty)
          None
        else
          Some(AxisLine("#FF0000", 2, mtCase.std_law.get, Some(AxisLineLabel("right", "法規值"))))

      val lines = Seq(std_law_line, None).filter { _.isDefined }.map { _.get }
      if (lines.length > 0)
        Some(lines)
      else
        None
    }

    val xAxis = {
      val names =
        for {
          mt <- monitorTypes
        } yield s"${MonitorType.map(mt).desp}"

      XAxis(Some(names))
    }

    val chart =
      if (monitorTypes.length == 1) {
        val mt = monitorTypes(0)
        val mtCase = MonitorType.map(monitorTypes(0))

        HighchartData(
          Map("type" -> "boxplot"),
          Map("text" -> title),
          xAxis,
          Seq(YAxis(None, AxisTitle(Some(Some(s"${mtCase.desp} (${mtCase.unit})"))), getAxisLines(mt))),
          series,
          Some(downloadFileName))
      } else {
        val yAxis =
          {
            Seq(YAxis(None, AxisTitle(Some(None)), None))
          }

        HighchartData(
          Map("type" -> "line"),
          Map("text" -> title),
          xAxis,
          yAxis,
          series,
          Some(downloadFileName))
      }

    chart
  }

  def historyTrendChart(monitorStr: String, monitorTypeStr: String, reportUnitStr: String,
                        startLong: Long, endLong: Long, outputTypeStr: String) = Security.Authenticated {
    implicit request =>
      import scala.collection.JavaConverters._
      val monitorStrArray = java.net.URLDecoder.decode(monitorStr, "UTF-8").split(':')
      val monitors = monitorStrArray.map { Monitor.withName }

      val monitorTypeStrArray = monitorTypeStr.split(':')
      val monitorTypes = monitorTypeStrArray.map { MonitorType.withName }
      val reportUnit = ReportUnit.withName(reportUnitStr)
      val statusFilter = MonitorStatusFilter.ValidData
      val (tabType, start, end) =
        if (reportUnit == ReportUnit.Hour || reportUnit == ReportUnit.Min || reportUnit == ReportUnit.TenMin) {
          val tab = if (reportUnit == ReportUnit.Hour)
            TableType.hour
          else
            TableType.min

          (tab, new DateTime(startLong), new DateTime(endLong))
        } else if (reportUnit == ReportUnit.Day) {
          (TableType.hour, new DateTime(startLong), new DateTime(endLong))
        } else {
          (TableType.hour, new DateTime(startLong), new DateTime(endLong))
        }

      val outputType = OutputType.withName(outputTypeStr)

      val chart = trendHelper(monitors, monitorTypes, tabType, reportUnit, start, end)(statusFilter)

      if (outputType == OutputType.excel) {
        import java.nio.file.Files
        def allMoniotorTypes = {
          val mts =
            for (i <- 1 to monitors.length) yield monitorTypes

          mts.flatMap { x => x }
        }
        val excelFile = ExcelUtility.exportChartData(chart, allMoniotorTypes.toArray)
        val downloadFileName =
          if (chart.downloadFileName.isDefined)
            chart.downloadFileName.get
          else
            chart.title("text")

        Ok.sendFile(excelFile, fileName = _ =>
          play.utils.UriEncoding.encodePathSegment(downloadFileName + ".xlsx", "UTF-8"),
          onClose = () => { Files.deleteIfExists(excelFile.toPath()) })
      } else {
        Results.Ok(Json.toJson(chart))
      }
  }

  def historyTrendChart2(monitorStr: String, monitorTypeStr: String, startLong: Long, endLong: Long) = Security.Authenticated.async {
    implicit request =>
      import scala.collection.JavaConverters._
      val monitorStrArray = java.net.URLDecoder.decode(monitorStr, "UTF-8").split(',')
      val monitors = monitorStrArray.map { Monitor.withName }

      val monitorTypeStrArray = java.net.URLDecoder.decode(monitorTypeStr, "UTF-8").split(':')
      val monitorTypes = monitorTypeStrArray.map { MonitorType.withName }
      val statusFilter = MonitorStatusFilter.ValidData
      val (tabType, start, end) = (TableType.min, new DateTime(startLong), new DateTime(endLong))
      val chartFuture = trendHelper2(monitors, monitorTypes, tabType, start, end)(statusFilter)
      chartFuture.onFailure(errorHandler)
      for (chart <- chartFuture) yield {
        Results.Ok(Json.toJson(chart))
      }
  }

  def historyBoxPlot(monitorStr: String, monitorTypeStr: String, reportUnitStr: String,
                     startLong: Long, endLong: Long, outputTypeStr: String) = Security.Authenticated {
    implicit request =>
      import scala.collection.JavaConverters._
      val monitorStrArray = java.net.URLDecoder.decode(monitorStr, "UTF-8").split(':')
      val monitors = monitorStrArray.map { Monitor.withName }

      val monitorTypeStrArray = monitorTypeStr.split(':')
      val monitorTypes = monitorTypeStrArray.map { MonitorType.withName }
      val reportUnit = ReportUnit.withName(reportUnitStr)
      val statusFilter = MonitorStatusFilter.ValidData
      val (tabType, start, end) =
        if (reportUnit == ReportUnit.Hour || reportUnit == ReportUnit.Min || reportUnit == ReportUnit.TenMin) {
          val tab = if (reportUnit == ReportUnit.Hour)
            TableType.hour
          else
            TableType.min

          (tab, new DateTime(startLong), new DateTime(endLong))
        } else if (reportUnit == ReportUnit.Day) {
          (TableType.hour, new DateTime(startLong), new DateTime(endLong))
        } else {
          (TableType.hour, new DateTime(startLong), new DateTime(endLong))
        }

      val outputType = OutputType.withName(outputTypeStr)

      val chart = boxHelper(monitors, monitorTypes, tabType, reportUnit, start, end)(statusFilter)

      if (outputType == OutputType.excel) {
        import java.nio.file.Files
        def allMoniotorTypes = {
          val mts =
            for (i <- 1 to monitors.length) yield monitorTypes

          mts.flatMap { x => x }
        }
        val excelFile = ExcelUtility.exportChartData(chart, allMoniotorTypes.toArray)
        val downloadFileName =
          if (chart.downloadFileName.isDefined)
            chart.downloadFileName.get
          else
            chart.title("text")

        Ok.sendFile(excelFile, fileName = _ =>
          play.utils.UriEncoding.encodePathSegment(downloadFileName + ".xlsx", "UTF-8"),
          onClose = () => { Files.deleteIfExists(excelFile.toPath()) })
      } else {
        Results.Ok(Json.toJson(chart))
      }
  }

  import java.util.Date
  import org.mongodb.scala.bson._
  case class CellData(v: String, cellClassName: String)
  case class RowData(date: Long, cellData: Seq[CellData], pdfReport: ObjectId)
  case class DataTab(columnNames: Seq[String], rows: Seq[RowData])
  def historyData(monitorStr: String, monitorTypeStr: String, startLong: Long, endLong: Long) = Security.Authenticated.async {
    implicit request =>
      import scala.collection.JavaConverters._

      val monitorTypeStrArray = java.net.URLDecoder.decode(monitorTypeStr, "UTF-8").split(',')
      val monitorTypes = monitorTypeStrArray.map { MonitorType.withName }
      val (start, end) = (new DateTime(startLong), new DateTime(endLong))

      Logger.debug(monitorTypeStr)

      implicit val cdWrite = Json.writes[CellData]
      implicit val rdWrite = Json.writes[RowData]
      implicit val dtWrite = Json.writes[DataTab]

      val resultFuture = try {
        val monitor = Monitor.withName(monitorStr)
        Record.getRecordListFuture(monitor, start, end)(Record.MinCollection)
      } catch {
        case _: Throwable =>
          Record.getRecordListFuture(start, end)(Record.MinCollection)
      }

      for (recordList <- resultFuture) yield {
        val rows = recordList map {
          r =>
            val mtCellData = monitorTypes.toSeq map { mt =>
              Logger.debug(r.mtDataList.toString)
              val mtDataOpt = r.mtDataList.find(mtdt => mtdt.mtName == mt.toString())
              if (mtDataOpt.isDefined) {
                val mtData = mtDataOpt.get
                val mt = MonitorType.withName(mtData.mtName)
                CellData(MonitorType.format(mt, Some(mtData.value)), "")
              } else {
                CellData("-", "")
              }
            }

            val cellData = mtCellData.+:(CellData(r.monitor, ""))
            RowData(r.time, cellData, r.pdfReport)
        }

        val mtColumnNames = monitorTypes.toSeq map { MonitorType.map(_).desp }
        val columnNames = mtColumnNames.+:("GC/選擇器")
        Ok(Json.toJson(DataTab(columnNames, rows)))
      }
  }

  def historyDataExcel(monitorStr: String, monitorTypeStr: String, startLong: Long, endLong: Long) = Security.Authenticated.async {
    implicit request =>
      import scala.collection.JavaConverters._

      val monitorTypeStrArray = java.net.URLDecoder.decode(monitorTypeStr, "UTF-8").split(',')
      val monitorTypes = monitorTypeStrArray.map { MonitorType.withName }
      val (start, end) = (new DateTime(startLong), new DateTime(endLong))

      implicit val cdWrite = Json.writes[CellData]
      implicit val rdWrite = Json.writes[RowData]
      implicit val dtWrite = Json.writes[DataTab]

      val resultFuture = try {
        val monitor = Monitor.withName(monitorStr)
        Record.getRecordListFuture(monitor, start, end)(Record.MinCollection)
      } catch {
        case _: Throwable =>
          Record.getRecordListFuture(start, end)(Record.MinCollection)
      }

      for (recordList <- resultFuture) yield {
        val excelFile = ExcelUtility.createHistoryData(recordList, monitorTypes)
        Ok.sendFile(excelFile, fileName = _ =>
          play.utils.UriEncoding.encodePathSegment("歷史資料.xlsx", "UTF-8"),
          onClose = () => { Files.deleteIfExists(excelFile.toPath()) })
      }
  }

  def last10Data() = Security.Authenticated.async {
    implicit request =>
      import scala.collection.JavaConverters._

      implicit val cdWrite = Json.writes[CellData]
      implicit val rdWrite = Json.writes[RowData]
      implicit val dtWrite = Json.writes[DataTab]

      for (recordList <- Record.getLatestRecordListFuture(Record.MinCollection)(10)) yield {
        val rows = recordList map {
          r =>
            implicit val ord  = new Ordering[MonitorType.Value] {
              override def compare(x: MonitorType.Value, y: MonitorType.Value) : Int = {
                MonitorType.map(y).order.compareTo(MonitorType.map(x).order)
              }
            }
            val mtCellData = MonitorType.mtvList.sorted map { mt =>
              val mtDataOpt = r.mtDataList.find(mtdt => mtdt.mtName == mt.toString())
              if (mtDataOpt.isDefined) {
                val mtData: Record.MtRecord = mtDataOpt.get
                val mt = MonitorType.withName(mtData.mtName)
                CellData(MonitorType.format(mt, Some(mtData.value)), "")
              } else {
                CellData("-", "")
              }
            }

            val cellData = mtCellData.+:(CellData(r.monitor, ""))
            RowData(r.time, cellData, r.pdfReport)
        }
        val mtColumnNames = MonitorType.mtvList.sorted map { MonitorType.map(_).desp }
        val columnNames = mtColumnNames.+:("GC/選擇器")
        Ok(Json.toJson(DataTab(columnNames, rows)))
      }
  }

  def recordList(mStr: String, mtStr: String, startLong: Long, endLong: Long) = Security.Authenticated {
    val monitor = Monitor.withName(java.net.URLDecoder.decode(mStr, "UTF-8"))
    val monitorType = MonitorType.withName(java.net.URLDecoder.decode(mtStr, "UTF-8"))

    val (start, end) = (new DateTime(startLong), new DateTime(endLong))

    val recordMap = Record.getRecordMap(Record.HourCollection)(List(monitorType), monitor, start, end)
    Ok(Json.toJson(recordMap(monitorType)))
  }

  import java.nio.file.Files

  import java.util.Date
  import Record._
  case class QueryParam(dataType: String, monitors: Seq[String], monitorTypes: Seq[String], start: Long, end: Long)

  def alarmData(start: Long, end: Long) = Security.Authenticated.async {
    implicit request =>

      val alarmListF = Alarm.getList(start, end)

      for (alarmList <- alarmListF) yield {
        implicit val cellWrite = Json.writes[CellData]
        implicit val rowWrite = Json.writes[RowData]
        implicit val dtWrite = Json.writes[DataTab]
        val columnNames = Seq("選擇器", "測項", "說明")
        val rows =
          for {
            alarm <- alarmList
          } yield {
            val monitorDesp = if (alarm.monitor.isDefined)
              alarm.monitor.get
            else
              "-"
            val mtDesp = if (alarm.monitorType.isDefined)
              alarm.monitorType.get
            else
              "-"

            val cellData = Seq(
              CellData(monitorDesp, ""),
              CellData(mtDesp, ""),
              CellData(alarm.desc, ""))
            RowData(alarm.time.getTime, cellData, new ObjectId())
          }
        Ok(Json.toJson(DataTab(columnNames, rows)))
      }
  }

  def pdfReport(id: String) = Security.Authenticated.async {
    import org.mongodb.scala.bson._

    val f = PdfReport.getPdf(new ObjectId(id))
    for (ret <- f) yield {
      Ok(ret.content).as("application/pdf")
    }

  }
}