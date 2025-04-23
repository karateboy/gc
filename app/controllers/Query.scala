package controllers

import com.github.nscala_time.time
import com.github.nscala_time.time.Imports._
import controllers.Highchart._
import models.ModelHelper._
import models.ObjectIdUtil._
import models._
import play.api._
import play.api.libs.json.Json
import play.api.mvc._

import java.nio.file.Files
import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global

case class Stat(
                 avg: Option[Double],
                 min: Option[Double],
                 max: Option[Double],
                 count: Int,
                 total: Int,
                 overCount: Int,
                 hour_count: Option[Int] = None,
                 hour_total: Option[Int] = None) {
  val effectPercent: Option[Double] = {
    if (total > 0)
      Some(count.toDouble * 100 / total)
    else
      None
  }
}

class Query @Inject()(recordOp: RecordOp,
                      pdfReportOp: PdfReportOp,
                      monitorOp: MonitorOp,
                      monitorTypeOp: MonitorTypeOp,
                      alarmOp: AlarmOp,
                      excelUtility: ExcelUtility,
                      calibrationOp: CalibrationOp) extends Controller {

  import recordOp._

  def windAvg(sum_sin: Double, sum_cos: Double) = {
    val degree = Math.toDegrees(Math.atan2(sum_sin, sum_cos))
    if (degree >= 0)
      degree
    else
      degree + 360
  }

  def historyTrendChart(monitorStr: String, monitorTypeStr: String, reportUnitStr: String,
                        startLong: Long, endLong: Long, outputTypeStr: String) = Security.Authenticated {
    implicit request =>

      val monitorStrArray = java.net.URLDecoder.decode(monitorStr, "UTF-8").split(':')
      val monitors = monitorStrArray.map {
        monitorOp.withName(_).asInstanceOf[MonitorOp#Value]
      }

      val monitorTypeStrArray = monitorTypeStr.split(':')
      val monitorTypes = monitorTypeStrArray.map {
        monitorTypeOp.withName(_).asInstanceOf[MonitorTypeOp#Value]
      }
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

        val excelFile = excelUtility.exportChartData(chart, allMoniotorTypes.toArray)
        val downloadFileName =
          if (chart.downloadFileName.isDefined)
            chart.downloadFileName.get
          else
            chart.title("text")

        Ok.sendFile(excelFile, fileName = _ =>
          play.utils.UriEncoding.encodePathSegment(downloadFileName + ".xlsx", "UTF-8"),
          onClose = () => {
            Files.deleteIfExists(excelFile.toPath())
          })
      } else {
        Results.Ok(Json.toJson(chart))
      }
  }

  private def trendHelper(monitors: Array[MonitorOp#Value],
                          monitorTypes: Array[MonitorTypeOp#Value],
                          tabType: TableType.Value,
                          reportUnit: ReportUnit.Value,
                          start: DateTime, end: DateTime)(statusFilter: MonitorStatusFilter.Value): HighchartData = {

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
          seqData(s"${monitorOp.map(m).selector}_${monitorTypeOp.map(mt).desp}", timeData)
        } else {
          seqData(s"${monitorOp.map(m).selector}_${monitorTypeOp.map(mt).desp}", timeData)
        }
      }
    }

    val series = getSeries()

    val downloadFileName = {
      val startName = start.toString("YYMMdd")
      val mtNames = monitorTypes.map {
        monitorTypeOp.map(_).desp
      }
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

    def getAxisLines(mt: MonitorTypeOp#Value) = {
      val mtCase = monitorTypeOp.map(mt)
      val std_law_line =
        if (mtCase.std_law.isEmpty)
          None
        else
          Some(AxisLine("#FF0000", 2, mtCase.std_law.get, Some(AxisLineLabel("right", "法規值"))))

      val lines = Seq(std_law_line, None).filter {
        _.isDefined
      }.map {
        _.get
      }
      if (lines.length > 0)
        Some(lines)
      else
        None
    }

    val xAxis: XAxis = {
      val duration = new Duration(start, end)
      if (duration.getStandardDays > 2)
        XAxis(None, gridLineWidth = Some(1), None)
      else
        XAxis(None)
    }

    val chart =
      if (monitorTypes.length == 1) {
        val mt = monitorTypes(0)
        val mtCase = monitorTypeOp.map(monitorTypes(0))

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

  private def getPeriodReportMap(monitor: MonitorOp#Value,
                                 mt: MonitorTypeOp#Value,
                                 tabType: TableType.Value,
                                 period: Period,
                                 statusFilter: MonitorStatusFilter.Value = MonitorStatusFilter.ValidData)
                                (start: DateTime, end: DateTime): Map[time.Imports.DateTime, Double] = {
    val recordList = recordOp.getRecordMap(TableType.mapCollection(tabType))(List(mt), monitor, start, end)(mt)

    def periodSlice(period_start: DateTime, period_end: DateTime) = {
      recordList.dropWhile {
        _.time < period_start
      }.takeWhile {
        _.time < period_end
      }
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

  def historyTrendChart2(monitorStr: String, monitorTypeStr: String, startLong: Long, endLong: Long) = Security.Authenticated.async {
    implicit request =>

      val monitorStrArray = java.net.URLDecoder.decode(monitorStr, "UTF-8").split(',')
      val monitors = monitorStrArray.map {
        monitorOp.withName
      }

      val monitorTypeStrArray = java.net.URLDecoder.decode(monitorTypeStr, "UTF-8").split(':')
      val monitorTypes = monitorTypeStrArray.map {
        monitorTypeOp.withName
      }
      val statusFilter = MonitorStatusFilter.ValidData
      val (tabType, start, end) = (TableType.min, new DateTime(startLong), new DateTime(endLong))
      val chartFuture = trendHelper2(monitors, monitorTypes, tabType, start, end)(statusFilter)
      chartFuture.onFailure(errorHandler)
      for (chart <- chartFuture) yield {
        Results.Ok(Json.toJson(chart))
      }
  }

  def trendHelper2(monitors: Array[monitorOp.Value], monitorTypes: Array[monitorTypeOp.Value], tabType: TableType.Value,
                   start: DateTime, end: DateTime)(statusFilter: MonitorStatusFilter.Value) = {

    val recordMapF = recordOp.getMonitorRecordMapF(TableType.mapCollection(tabType))(monitorTypes.toList, monitors, start, end)
    recordMapF.onFailure(errorHandler)

    def getAxisLines(mt: monitorTypeOp.Value) = {
      val mtCase = monitorTypeOp.map(mt)
      val std_law_line =
        if (mtCase.std_law.isEmpty)
          None
        else
          Some(AxisLine("#FF0000", 2, mtCase.std_law.get, Some(AxisLineLabel("right", "法規值"))))

      val lines = Seq(std_law_line, None).filter {
        _.isDefined
      }.map {
        _.get
      }
      if (lines.length > 0)
        Some(lines)
      else
        None
    }

    val downloadFileName = {
      val startName = start.toString("YYMMdd")
      val mtNames = monitorTypes.map {
        monitorTypeOp.map(_).desp
      }
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
            seqData(s"${monitorOp.map(m).dp_no}_${monitorTypeOp.map(mt).desp}", timeData)
          } else {
            seqData(s"${monitorOp.map(m).dp_no}_${monitorTypeOp.map(mt).desp}", timeData)
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
          val mtCase = monitorTypeOp.map(monitorTypes(0))

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

  def historyBoxPlot(monitorStr: String, monitorTypeStr: String, reportUnitStr: String,
                     startLong: Long, endLong: Long, outputTypeStr: String) = Security.Authenticated {
    implicit request =>

      val monitorStrArray = java.net.URLDecoder.decode(monitorStr, "UTF-8").split(':')
      val monitors = monitorStrArray.map {
        monitorOp.withName(_).asInstanceOf[MonitorOp#Value]
      }

      val monitorTypeStrArray = monitorTypeStr.split(':')
      val monitorTypes = monitorTypeStrArray.map {
        monitorTypeOp.withName(_).asInstanceOf[MonitorTypeOp#Value]
      }
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
        def allMonitorTypes = {
          val mts =
            for (i <- 1 to monitors.length) yield monitorTypes

          mts.flatMap { x => x }
        }

        val excelFile = excelUtility.exportChartData(chart, allMonitorTypes.toArray)
        val downloadFileName =
          if (chart.downloadFileName.isDefined)
            chart.downloadFileName.get
          else
            chart.title("text")

        Ok.sendFile(excelFile, fileName = _ =>
          play.utils.UriEncoding.encodePathSegment(downloadFileName + ".xlsx", "UTF-8"),
          onClose = () => {
            Files.deleteIfExists(excelFile.toPath())
          })
      } else {
        Results.Ok(Json.toJson(chart))
      }
  }

  private def boxHelper(monitors: Array[MonitorOp#Value],
                        monitorTypes: Array[MonitorTypeOp#Value],
                        tabType: TableType.Value,
                        reportUnit: ReportUnit.Value,
                        start: DateTime, end: DateTime)
                       (statusFilter: MonitorStatusFilter.Value): HighchartData = {

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
        seqData(s"${monitorOp.map(m).selector}", boxReport)
      }
    }

    val series = getSeries()

    val downloadFileName = {
      val startName = start.toString("YYMMdd")
      val mtNames = monitorTypes.map {
        monitorTypeOp.map(_).desp
      }
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

    def getAxisLines(mt: MonitorTypeOp#Value) = {
      val mtCase = monitorTypeOp.map(mt)
      val std_law_line =
        if (mtCase.std_law.isEmpty)
          None
        else
          Some(AxisLine("#FF0000", 2, mtCase.std_law.get, Some(AxisLineLabel("right", "法規值"))))

      val lines = Seq(std_law_line, None).filter {
        _.isDefined
      }.map {
        _.get
      }
      if (lines.length > 0)
        Some(lines)
      else
        None
    }

    val xAxis = {
      val names =
        for {
          mt <- monitorTypes
        } yield s"${monitorTypeOp.map(mt).desp}"

      XAxis(Some(names))
    }

    val chart =
      if (monitorTypes.length == 1) {
        val mt = monitorTypes(0)
        val mtCase = monitorTypeOp.map(monitorTypes(0))

        HighchartData(
          Map("type" -> "boxplot"),
          Map("text" -> title),
          xAxis,
          Seq(YAxis(None, AxisTitle(Some(Some(s"${mtCase.desp} (${mtCase.unit})"))), getAxisLines(mt))),
          series,
          Some(downloadFileName))
      } else {
        val yAxis = {
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

  private def getPeriodBoxReport(monitor: MonitorOp#Value,
                                 mt: MonitorTypeOp#Value,
                                 tabType: TableType.Value,
                                 period: Period,
                                 statusFilter: MonitorStatusFilter.Value = MonitorStatusFilter.ValidData)
                                (start: DateTime, end: DateTime): Seq[Option[Double]] = {
    val recordList = recordOp.getRecordMap(TableType.mapCollection(tabType))(List(mt), monitor, start, end)(mt)

    def periodSlice(period_start: DateTime, period_end: DateTime) = {
      recordList.dropWhile {
        _.time < period_start
      }.takeWhile {
        _.time < period_end
      }
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

  import org.mongodb.scala.bson._

  def historyData(monitorStr: String, monitorTypeStr: String, startLong: Long, endLong: Long) = Security.Authenticated.async {
    implicit request =>


      val monitorTypeStrArray = java.net.URLDecoder.decode(monitorTypeStr, "UTF-8").split(',')
      val monitorTypes = monitorTypeStrArray.map {
        monitorTypeOp.withName
      }
      val (start, end) = (new DateTime(startLong), new DateTime(endLong))

      implicit val cdWrite = Json.writes[CellData]
      implicit val rdWrite = Json.writes[RowData]
      implicit val dtWrite = Json.writes[DataTab]

      val resultFuture = try {
        val monitor = monitorOp.withName(monitorStr)
        recordOp.getRecordListFuture(monitor, start, end)(RecordOp.MinCollection)
      } catch {
        case _: Throwable =>
          recordOp.getRecordListFuture(start, end)(RecordOp.MinCollection)
      }

      for (recordList <- resultFuture) yield {
        val rows = recordList map {
          r =>
            val mtCellData = monitorTypes.toSeq map { mt =>
              val mtDataOpt = r.mtDataList.find(mtdt => mtdt.mtName == mt.toString())
              if (mtDataOpt.isDefined) {
                val mtData = mtDataOpt.get
                val mt = monitorTypeOp.withName(mtData.mtName)
                CellData(monitorTypeOp.format(mt, Some(mtData.value)), "")
              } else {
                CellData("-", "")
              }
            }

            val cellData = mtCellData.+:(CellData(r.monitor, ""))
            RowData(r.time, cellData, r.pdfReport)
        }

        val mtColumnNames = monitorTypes.toSeq map {
          monitorTypeOp.map(_).desp
        }
        val columnNames = mtColumnNames.+:("GC/選擇器")
        Ok(Json.toJson(DataTab(columnNames, rows)))
      }
  }

  def historyDataExcel(monitorStr: String, monitorTypeStr: String, startLong: Long, endLong: Long) = Security.Authenticated.async {
    implicit request =>


      val monitorTypeStrArray = java.net.URLDecoder.decode(monitorTypeStr, "UTF-8").split(',')
      val monitorTypes = monitorTypeStrArray.map {
        monitorTypeOp.withName
      }
      val (start, end) = (new DateTime(startLong), new DateTime(endLong))

      implicit val cdWrite = Json.writes[CellData]
      implicit val rdWrite = Json.writes[RowData]
      implicit val dtWrite = Json.writes[DataTab]

      val resultFuture = try {
        val monitor = monitorOp.withName(monitorStr)
        recordOp.getRecordListFuture(monitor, start, end)(RecordOp.MinCollection)
      } catch {
        case _: Throwable =>
          recordOp.getRecordListFuture(start, end)(RecordOp.MinCollection)
      }

      for (recordList <- resultFuture) yield {
        val excelFile = excelUtility.createHistoryData(recordList, monitorTypes)
        Ok.sendFile(excelFile, fileName = _ =>
          play.utils.UriEncoding.encodePathSegment("歷史資料.xlsx", "UTF-8"),
          onClose = () => {
            Files.deleteIfExists(excelFile.toPath())
          })
      }
  }

  def calibrationData(monitorStr: String, monitorTypeStr: String, startLong: Long, endLong: Long): Action[AnyContent] =
    Security.Authenticated.async {
    implicit request =>


      val monitorTypeStrArray = java.net.URLDecoder.decode(monitorTypeStr, "UTF-8").split(',')
      val monitorTypes = monitorTypeStrArray.map {
        monitorTypeOp.withName
      }
      val (start, end) = (new DateTime(startLong), new DateTime(endLong))

      implicit val cdWrite = Json.writes[CellData]
      implicit val rdWrite = Json.writes[RowData]
      implicit val dtWrite = Json.writes[DataTab]


        val monitor = monitorOp.withName(monitorStr)
        val f = calibrationOp.getCalibrationListFuture(monitor, start, end)


      for (calibrations <- f) yield {
        val rows = calibrations map {
          cal =>
            val mtCellData = monitorTypes.toSeq map { mt =>
              cal.mtMap.get(mt.toString) match {
                case Some(mtData) =>
                  val css =
                    if (mtData.status == MonitorStatus.OverNormalStat)
                      "text-danger"
                    else
                      ""

                  CellData(monitorTypeOp.format(mt, Some(mtData.value)), css)
                case None =>
                  CellData("-", "")
              }
            }

            val cellData = Seq(CellData(cal._id.monitor, ""),
              CellData(cal.sampleName.getOrElse(""), ""),
              CellData(cal.fileName.getOrElse(""), ""),
              CellData(cal.containerId.getOrElse(""), "")
            ) ++ mtCellData
            RowData(cal._id.time.getTime, cellData, new ObjectId())
        }

        val mtColumnNames = monitorTypes map {
          monitorTypeOp.map(_).desp
        }
        val columnNames = Seq("GC/選擇器", "Sample Name", "FileName", "Container ID") ++ mtColumnNames
        Ok(Json.toJson(DataTab(columnNames, rows)))
      }
  }

  def getLast10CalibrationData(newGC:String): Action[AnyContent] =
    Security.Authenticated.async {
      implicit request =>
        implicit val cdWrite = Json.writes[CellData]
        implicit val rdWrite = Json.writes[RowData]
        implicit val dtWrite = Json.writes[DataTab]

        val f = calibrationOp.getLastCalibrationFuture(10, newGC.toBoolean)

        for (calibrations <- f) yield {
          val monitorTypes = Set.empty[String] ++ calibrations.flatMap(_.mtMap.keys)
          val mtList = monitorTypes.toList.sortBy(mt=> monitorTypeOp.map(monitorTypeOp.withName(mt)).order)
          val rows = calibrations map {
            cal =>
              val mtCellData = mtList map { mt =>
                cal.mtMap.get(mt) match {
                  case Some(mtData) =>
                    val css =
                      if (mtData.status == MonitorStatus.OverNormalStat)
                        "text-danger"
                      else
                        ""

                    CellData(monitorTypeOp.format(monitorTypeOp.withName(mt), Some(mtData.value)), css)
                  case None =>
                    CellData("-", "")
                }
              }

              val cellData = Seq(
                CellData(cal.sampleName.getOrElse(""), ""),
                CellData(cal.fileName.getOrElse(""), ""),
                CellData(cal.containerId.getOrElse(""), "")
              ) ++ mtCellData
              RowData(cal._id.time.getTime, cellData, new ObjectId())
          }

          val mtColumnNames = mtList map { mtName=>
            monitorTypeOp.map(monitorTypeOp.withName(mtName)).desp
          }
          val columnNames = Seq("Sample Name", "FileName", "Container ID") ++ mtColumnNames
          Ok(Json.toJson(DataTab(columnNames, rows)))
        }
    }

  def last10Data(gcFilter: String) = Security.Authenticated.async {
    implicit request =>
      implicit val cdWrite = Json.writes[CellData]
      implicit val rdWrite = Json.writes[RowData]
      implicit val dtWrite = Json.writes[DataTab]

      val monitors = monitorOp.getMonitorByGcFilter(gcFilter)

      for (recordList <- recordOp.getLatestRecordListFuture(RecordOp.MinCollection, monitors)(10)) yield {
        import scala.collection.mutable.Set
        val mtSet = Set.empty[String]
        recordList.foreach(_.mtDataList.foreach(mtData => mtSet.add(mtData.mtName)))
        val mtList = mtSet.toList.sorted
        val rows = recordList map {
          r =>
            val mtCellData = mtList map { mt =>
              val mtDataOpt = r.mtDataList.find(mtdt => mtdt.mtName == mt.toString)
              if (mtDataOpt.isDefined) {
                val mtData: MtRecord = mtDataOpt.get
                val mt = monitorTypeOp.withName(mtData.mtName)
                CellData(monitorTypeOp.format(mt, Some(mtData.value)), "")
              } else {
                CellData("-", "")
              }
            }

            val cellData = mtCellData.+:(CellData(r.monitor, ""))
            RowData(r.time, cellData, r.pdfReport)
        }
        val mtColumnNames = mtList map { id =>
          monitorTypeOp.map(monitorTypeOp.withName(id)).desp
        }
        val columnNames = mtColumnNames.+:("GC/選擇器")
        Ok(Json.toJson(DataTab(columnNames, rows)))
      }
  }

  def recordList(mStr: String, mtStr: String, startLong: Long, endLong: Long) = Security.Authenticated {
    val monitor = monitorOp.withName(java.net.URLDecoder.decode(mStr, "UTF-8"))
    val monitorType = monitorTypeOp.withName(java.net.URLDecoder.decode(mtStr, "UTF-8"))

    val (start, end) = (new DateTime(startLong), new DateTime(endLong))

    val recordMap = recordOp.getRecordMap(RecordOp.HourCollection)(List(monitorType), monitor, start, end)
    Ok(Json.toJson(recordMap(monitorType)))
  }

  def alarmData(start: Long, end: Long) = Security.Authenticated.async {
    implicit request =>

      val alarmListF = alarmOp.getList(start, end)

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

    val f = pdfReportOp.getPdf(new ObjectId(id))
    for (ret <- f) yield {
      Ok(ret.content).as("application/pdf")
    }
  }

  def excelForm(pdfId: String) = Security.Authenticated.async {
    val f = recordOp.getRecordWithPdfID(new ObjectId(pdfId))
    for (map <- f) yield {
      val excelFile = excelUtility.excelForm(map)

      Ok.sendFile(excelFile, fileName = _ =>
        play.utils.UriEncoding.encodePathSegment("報告.xlsx", "UTF-8"),
        onClose = () => {
          Files.deleteIfExists(excelFile.toPath)
        })
    }
  }

  case class CellData(v: String, cellClassName: String)

  case class RowData(date: Long, cellData: Seq[CellData], pdfReport: ObjectId)

  case class DataTab(columnNames: Seq[String], rows: Seq[RowData])

}