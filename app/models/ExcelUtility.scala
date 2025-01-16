package models

import com.github.nscala_time.time.Imports._
import controllers.Query
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.ss.usermodel.{BorderStyle, FillPatternType, IndexedColors}
import org.apache.poi.xssf.usermodel.{XSSFCellStyle, XSSFColor, XSSFSheet, XSSFWorkbook}
import play.api.{Configuration, Environment, Logger}
import play.api.Play.current

import java.io.{File, FileInputStream, FileOutputStream}
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import javax.inject.Inject

@javax.inject.Singleton
class ExcelUtility @Inject()(environment: Environment,
                             monitorTypeOp: MonitorTypeOp,
                             recordOp: RecordOp) {
  val docRoot = "/report_template/"

  private def prepareTemplate(templateFile: String) = {
    val templatePath = Paths.get(environment.rootPath.getAbsolutePath + docRoot + templateFile)
    val reportFilePath = Files.createTempFile("temp", ".xlsx");

    Files.copy(templatePath, reportFilePath, StandardCopyOption.REPLACE_EXISTING)

    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(reportFilePath.toAbsolutePath.toString))
    val wb = new XSSFWorkbook(pkg);

    (reportFilePath, pkg, wb)
  }

  private def finishExcel(reportFilePath: Path, pkg: OPCPackage, wb: XSSFWorkbook) = {
    val out = new FileOutputStream(reportFilePath.toAbsolutePath.toString);
    wb.write(out);
    out.close();
    pkg.close();

    new File(reportFilePath.toAbsolutePath.toString)
  }

  private def createStyle(mt: monitorTypeOp.Value)(implicit wb: XSSFWorkbook) = {
    val prec = monitorTypeOp.map(mt).prec
    val format_str = "0." + "0" * prec
    val style = wb.createCellStyle();
    val format = wb.createDataFormat();
    // Create a new font and alter it.
    val font = wb.createFont();
    font.setFontHeightInPoints(10);
    font.setFontName("標楷體");

    style.setFont(font)
    style.setDataFormat(format.getFormat(format_str))
    style.setBorderBottom(BorderStyle.THIN);
    style.setBottomBorderColor(IndexedColors.BLACK.getIndex());
    style.setBorderLeft(BorderStyle.THIN);
    style.setLeftBorderColor(IndexedColors.BLACK.getIndex());
    style.setBorderRight(BorderStyle.THIN);
    style.setRightBorderColor(IndexedColors.BLACK.getIndex());
    style.setBorderTop(BorderStyle.THIN);
    style.setTopBorderColor(IndexedColors.BLACK.getIndex());
    style
  }

  private def createColorStyle(fgColors: Array[XSSFColor], mt: monitorTypeOp.Value)(implicit wb: XSSFWorkbook): Array[XSSFCellStyle] = {
    fgColors.map {
      color =>
        val style = createStyle(mt)
        style.setFillForegroundColor(color)
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND)
        style
    }
  }

  def getStyle(tag: String, normalStyle: XSSFCellStyle, abnormalStyles: Array[XSSFCellStyle]) = {
    import MonitorStatus._
    val info = MonitorStatus.getTagInfo(tag)
    info.statusType match {
      case StatusType.Internal => {
        if (isValid(tag))
          normalStyle
        else if (isCalbration(tag))
          abnormalStyles(0)
        else if (isMaintenance(tag))
          abnormalStyles(1)
        else
          abnormalStyles(2)
      }
      case StatusType.Auto =>
        abnormalStyles(3)
      case StatusType.Manual =>
        abnormalStyles(4)
    }
  }

  import controllers.Highchart._

  def exportChartData(chart: HighchartData, monitorTypes: Array[MonitorTypeOp#Value]): File = {
    val precArray = monitorTypes.map { mt => monitorTypeOp.map(mt).prec }
    exportChartData(chart, precArray)
  }

  def exportChartData(chart: HighchartData, precArray: Array[Int]): File = {
    val (reportFilePath, pkg, wb) = prepareTemplate("chart_export.xlsx")
    val evaluator = wb.getCreationHelper().createFormulaEvaluator()
    val format = wb.createDataFormat();

    val sheet = wb.getSheetAt(0)
    val headerRow = sheet.createRow(0)
    headerRow.createCell(0).setCellValue("時間")

    var pos = 0
    for {
      col <- 1 to chart.series.length
      series = chart.series(col - 1)
    } {
      headerRow.createCell(pos + 1).setCellValue(series.name)
      pos += 1
    }

    val styles = precArray.map { prec =>
      val format_str = "0." + "0" * prec
      val style = wb.createCellStyle();
      style.setDataFormat(format.getFormat(format_str))
      style
    }

    // Categories data
    if (chart.xAxis.categories.isDefined) {
      val timeList = chart.xAxis.categories.get
      for (row <- timeList.zipWithIndex) {
        val rowNo = row._2 + 1
        val thisRow = sheet.createRow(rowNo)
        thisRow.createCell(0).setCellValue(row._1)

        for {
          col <- 1 to chart.series.length
          series = chart.series(col - 1)
        } {
          val cell = thisRow.createCell(col)
          cell.setCellStyle(styles(col - 1))

          val pair = series.data(rowNo - 1)
          if (pair.length == 2 && pair(1).isDefined) {
            cell.setCellValue(pair(1).get)
          }
          //val pOpt = series.data(rowNo-1)
          //if(pOpt.isDefined){
          //  cell.setCellValue(pOpt.get)
          //}

        }
      }
    } else {
      val rowMax = chart.series.map(s => s.data.length).max
      for (row <- 1 to rowMax) {
        val thisRow = sheet.createRow(row)
        val timeCell = thisRow.createCell(0)
        pos = 0
        for {
          col <- 1 to chart.series.length
          series = chart.series(col - 1)
        } {
          val cell = thisRow.createCell(pos + 1)
          pos += 1
          cell.setCellStyle(styles(col - 1))

          val pair = series.data(row - 1)
          if (col == 1) {
            val dt = new DateTime(pair(0).get.toLong)
            timeCell.setCellValue(dt.toString("YYYY/MM/dd HH:mm"))
          }
          if (pair(1).isDefined) {
            cell.setCellValue(pair(1).get)
          }
        }
      }
    }

    finishExcel(reportFilePath, pkg, wb)
  }

  import recordOp._
  def createHistoryData(recordList: Seq[RecordList], monitorTypes: Seq[MonitorTypeOp#Value]): File = {
    implicit val (reportFilePath, pkg, wb) = prepareTemplate("historyData.xlsx")
    val format = wb.createDataFormat();
    val sheet = wb.getSheetAt(0)

    //Create header

    var row = 0
    val header = sheet.createRow(0)
    header.createCell(0).setCellValue("日期")
    header.createCell(1).setCellValue("選擇器")
    for ((mt, col) <- monitorTypes.zip(2 to 1 + monitorTypes.length)) {
      header.createCell(col).setCellValue(monitorTypeOp.map(mt).desp)
    }
    val dateStyle = wb.createCellStyle()
    dateStyle.setDataFormat(format.getFormat("yyyy-mm-dd hh:mm"))

    for ((r, rowNum) <- recordList.zip(1 to recordList.size)) {
      val row = sheet.createRow(rowNum)
      val dateCell = row.createCell(0)
      dateCell.setCellStyle(dateStyle)
      dateCell.setCellValue(new DateTime(r.time).toDate())
      row.createCell(1).setCellValue(r.monitor)
      for ((mt, colNum) <- monitorTypes.zip(2 to 1 + monitorTypes.length)) {
        val mtDataOpt = r.mtDataList.find(mtdt => mtdt.mtName == mt.toString())
        val cell = row.createCell(colNum)
        if (mtDataOpt.isDefined) {
          cell.setCellValue(mtDataOpt.get.value)
        } else {
          cell.setCellValue("-")
        }
      }
    }

    wb.setActiveSheet(0)
    finishExcel(reportFilePath, pkg, wb)
  }

  def excelForm(map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): File = {
    implicit val (reportFilePath, pkg, wb) = prepareTemplate("form.xlsx")
    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      var sheet: XSSFSheet = wb.getSheetAt(0)

      def fillMtContent(mtName: String, rowN: Int, cellN: Int, limitN: Int): Unit = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        var limitStr = ""
        if (mtMap.contains(mt)) {
          val limit = try {
            limitStr = sheet.getRow(rowN).getCell(limitN).
              getStringCellValue
            limitStr.replaceAll("^\\d+", "").toDouble
          } catch {
            case _: Throwable =>
              0d
          }
          if (mtMap(mt).value == 0 || mtMap(mt).value < limit)
            sheet.getRow(rowN).getCell(cellN).setCellValue(limitStr)
          else
            sheet.getRow(rowN).getCell(cellN).setCellValue(mtMap(mt).value)
        }
      }

      def fillThcContent(rowN: Int, cellN: Int, limitN: Int): Unit = {
        val ch4 = monitorTypeOp.getMonitorTypeValueByName("CH4")
        val c3h8 = monitorTypeOp.getMonitorTypeValueByName("C3H8")
        var limitStr = ""
        if (mtMap.contains(ch4) && mtMap.contains(c3h8)) {
          val limit = try {
            limitStr = sheet.getRow(rowN).getCell(limitN).
              getStringCellValue
            limitStr.replaceAll("^\\d+", "").toDouble
          } catch {
            case _: Throwable =>
              0d
          }
          val sum = mtMap(ch4).value + mtMap(c3h8).value
          if (sum == 0 || sum < limit)
            sheet.getRow(rowN).getCell(cellN).setCellValue(limitStr)
          else
            sheet.getRow(rowN).getCell(cellN).setCellValue(sum)
        }
      }

      def fillSheetUPO(): Unit = {
        val sheet = wb.getSheetAt(0)
        sheet.getRow(10).getCell(4).setCellValue(dt.toString("YYYY/MM/dd"))
        sheet.getRow(11).getCell(4).setCellValue(dt.toString("YYYY/MM/dd"))
        for (sampleName <- sampleNameOpt)
          sheet.getRow(12).getCell(5).setCellValue(sampleName)

        fillMtContent("H2O", 16, 4, 9)
        fillMtContent("CO", 17, 4, 9)
        fillMtContent("CO2", 18, 4, 9)
        fillMtContent("H2", 19, 4, 9)
        fillMtContent("N2", 20, 4, 9)
        fillMtContent("Ar", 21, 4, 9)
        fillThcContent(22, 4, 9)
      }

      def fillSheetWithAr(sheetN:Int): Unit = {
        sheet = wb.getSheetAt(sheetN)
        sheet.getRow(7).getCell(9).setCellValue(dt.toString("YYYY/MM/dd"))
        sheet.getRow(8).getCell(9).setCellValue(dt.toString("YYYY/MM/dd"))
        for (sampleName <- sampleNameOpt)
          sheet.getRow(13).getCell(9).setCellValue(sampleName)

        fillMtContent("H2O", 18, 4, 9)
        fillMtContent("CO", 19, 4, 9)
        fillMtContent("CO2", 20, 4, 9)
        fillMtContent("H2", 21, 4, 9)
        fillMtContent("N2", 22, 4, 9)
        fillMtContent("Ar", 23, 4, 9)
        fillThcContent(24, 4, 9)
      }

      def fillSheetWithoutAr(sheetN:Int): Unit = {
        sheet = wb.getSheetAt(sheetN)
        sheet.getRow(7).getCell(9).setCellValue(dt.toString("YYYY/MM/dd"))
        sheet.getRow(8).getCell(9).setCellValue(dt.toString("YYYY/MM/dd"))
        for (sampleName <- sampleNameOpt)
          sheet.getRow(13).getCell(9).setCellValue(sampleName)

        fillMtContent("H2O", 18, 4, 9)
        fillMtContent("CO", 19, 4, 9)
        fillMtContent("CO2", 20, 4, 9)
        fillMtContent("H2", 21, 4, 9)
        fillMtContent("N2", 22, 4, 9)
        fillThcContent(23, 4, 9)
      }
      def fillSheetWithCh4(sheetN:Int): Unit = {
        sheet = wb.getSheetAt(sheetN)
        sheet.getRow(7).getCell(9).setCellValue(dt.toString("YYYY/MM/dd"))
        sheet.getRow(8).getCell(9).setCellValue(dt.toString("YYYY/MM/dd"))
        for (sampleName <- sampleNameOpt)
          sheet.getRow(13).getCell(9).setCellValue(sampleName)

        fillMtContent("H2O", 18, 4, 9)
        fillMtContent("CO", 19, 4, 9)
        fillMtContent("CO2", 20, 4, 9)
        fillMtContent("H2", 21, 4, 9)
        fillMtContent("N2", 22, 4, 9)
        fillThcContent(23, 4, 9)
        fillMtContent("CH4", 24, 4, 9)
      }

      fillSheetUPO()
      fillSheetWithAr(1)
      fillSheetWithoutAr(2)
      fillSheetWithAr(3)
      fillSheetWithAr(4)
      fillSheetWithAr(5)
      fillSheetWithAr(6)
      fillSheetWithAr(7)
      fillSheetWithCh4(8)
      fillSheetWithoutAr(9)
    }

    wb.setActiveSheet(0)
    finishExcel(reportFilePath, pkg, wb)
  }

  def excelFormN2(map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]) = {
    implicit val (reportFilePath, pkg, wb) = prepareTemplate("formN2.xlsx")
    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      var sheet: XSSFSheet = wb.getSheetAt(0)

      def fillMtContent(mtName: String, rowN: Int, cellN: Int, limitN: Int): Unit = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        var limitStr = ""
        if (mtMap.contains(mt)) {
          val limit = try {
            limitStr = sheet.getRow(rowN).getCell(limitN).
              getStringCellValue
            limitStr.reverse.drop(3).reverse.toInt
          } catch {
            case _: Throwable =>
              0
          }
          if (mtMap(mt).value == 0 || mtMap(mt).value * 1000 < limit)
            sheet.getRow(rowN).getCell(cellN).setCellValue(s"<$limit")
          else
            sheet.getRow(rowN).getCell(cellN).setCellValue(mtMap(mt).value * 1000)
        }
      }

      def fillThcContent(rowN: Int, cellN: Int, limitN: Int): Unit = {
        val ch4 = monitorTypeOp.getMonitorTypeValueByName("CH4")
        val c3h8 = monitorTypeOp.getMonitorTypeValueByName("C3H8")
        var limitStr = ""
        if (mtMap.contains(ch4) && mtMap.contains(c3h8)) {
          val limit = try {
            limitStr = sheet.getRow(rowN).getCell(limitN).
              getStringCellValue
            limitStr.replaceAll("\\p{Alpha}+", "").toInt
          } catch {
            case _: Throwable =>
              0
          }
          val sum = mtMap(ch4).value + mtMap(c3h8).value
          if (sum == 0 || sum * 1000 < limit)
            sheet.getRow(rowN).getCell(cellN).setCellValue(s"<$limit")
          else
            sheet.getRow(rowN).getCell(cellN).setCellValue(sum * 1000)
        }
      }

      def fillSheetByMt(sheetN: Int, startRow: Int, mtSeq: Seq[String]) = {
        sheet = wb.getSheetAt(sheetN)
        sheet.getRow(6).getCell(4).setCellValue(dt.toString("YYYY/MM/dd"))
        //for (sampleName <- sampleNameOpt)
        //  sheet.getRow(13).getCell(9).setCellValue(sampleName)

        for ((mt, idx) <- mtSeq.zipWithIndex) {
          if (mt.toUpperCase != "THC")
            fillMtContent(mt, startRow + idx, 4, 8)
          else
            fillThcContent(startRow + idx, 4, 8)
        }
      }

      fillSheetByMt(0, 10, Seq("Ar", "H2", "CO", "CH4", "CO2", "THC", "H2O", "O2"))
    }

    wb.setActiveSheet(0)
    finishExcel(reportFilePath, pkg, wb)
  }

  def excelFormAr(map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]) = {
    implicit val (reportFilePath, pkg, wb) = prepareTemplate("formAr.xlsx")
    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      var sheet: XSSFSheet = wb.getSheetAt(0)

      def fillMtContent(mtName: String, rowN: Int, cellN: Int, limitN: Int): Unit = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        var limitStr = ""
        if (mtMap.contains(mt)) {
          val limit = try {
            limitStr = sheet.getRow(rowN).getCell(limitN).
              getStringCellValue
            limitStr.reverse.drop(3).reverse.toInt
          } catch {
            case _: Throwable =>
              0
          }
          if (mtMap(mt).value == 0 || mtMap(mt).value * 1000 < limit)
            sheet.getRow(rowN).getCell(cellN).setCellValue(s"<$limit")
          else
            sheet.getRow(rowN).getCell(cellN).setCellValue(mtMap(mt).value * 1000)
        }
      }

      def fillThcContent(rowN: Int, cellN: Int, limitN: Int): Unit = {
        val ch4 = monitorTypeOp.getMonitorTypeValueByName("CH4")
        val c3h8 = monitorTypeOp.getMonitorTypeValueByName("C3H8")
        var limitStr = ""
        if (mtMap.contains(ch4) && mtMap.contains(c3h8)) {
          val limit = try {
            limitStr = sheet.getRow(rowN).getCell(limitN).
              getStringCellValue
            limitStr.replaceAll("\\p{Alpha}+", "").toInt
          } catch {
            case _: Throwable =>
              0
          }
          val sum = mtMap(ch4).value + mtMap(c3h8).value
          if (sum == 0 || sum * 1000 < limit)
            sheet.getRow(rowN).getCell(cellN).setCellValue(s"<$limit")
          else
            sheet.getRow(rowN).getCell(cellN).setCellValue(sum * 1000)
        }
      }

      def fillSheetByMt(sheetN: Int, startRow: Int, mtSeq: Seq[String]) = {
        sheet = wb.getSheetAt(sheetN)
        sheet.getRow(6).getCell(4).setCellValue(dt.toString("YYYY/MM/dd"))
        //for (sampleName <- sampleNameOpt)
        //  sheet.getRow(13).getCell(9).setCellValue(sampleName)

        for ((mt, idx) <- mtSeq.zipWithIndex) {
          if (mt.toUpperCase != "THC")
            fillMtContent(mt, startRow + idx, 4, 8)
          else
            fillThcContent(startRow + idx, 4, 8)
        }
      }

      fillSheetByMt(0, 10, Seq("H2", "N2", "CO", "CO2", "CH4", "THC", "H2O", "O2"))
    }

    wb.setActiveSheet(0)
    finishExcel(reportFilePath, pkg, wb)
  }
}
