package models

import com.github.nscala_time.time.Imports._
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.ss.usermodel.{BorderStyle, FillPatternType, IndexedColors}
import org.apache.poi.xssf.usermodel.{XSSFCellStyle, XSSFColor, XSSFSheet, XSSFWorkbook}
import play.api.Environment

import java.io.{File, FileInputStream, FileOutputStream, FileWriter, IOException}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.Comparator
import java.util.zip.{ZipEntry, ZipOutputStream}
import javax.inject.Inject
import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.xml.XML

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

      def fillSheetWithAr(sheetN: Int): Unit = {
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

      def fillSheetWithoutAr(sheetN: Int): Unit = {
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

      def fillSheetWithCh4(sheetN: Int): Unit = {
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

          if (mtName == "H2O") {
            if (mtMap(mt).value == 0 || mtMap(mt).value < limit)
              sheet.getRow(rowN).getCell(cellN).setCellValue(s"<$limit")
            else
              sheet.getRow(rowN).getCell(cellN).setCellValue(mtMap(mt).value)
          } else {
            if (mtMap(mt).value == 0 || mtMap(mt).value * 1000 < limit)
              sheet.getRow(rowN).getCell(cellN).setCellValue(s"<$limit")
            else
              sheet.getRow(rowN).getCell(cellN).setCellValue(mtMap(mt).value * 1000)
          }
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

      def fillSheetByMt(sheetN: Int, startRow: Int, mtSeq: Seq[String]): Unit = {
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
          if (mtName == "H2O") {
            if (mtMap(mt).value == 0 || mtMap(mt).value < limit)
              sheet.getRow(rowN).getCell(cellN).setCellValue(s"<$limit")
            else
              sheet.getRow(rowN).getCell(cellN).setCellValue(mtMap(mt).value)
          } else {
            if (mtMap(mt).value == 0 || mtMap(mt).value * 1000 < limit)
              sheet.getRow(rowN).getCell(cellN).setCellValue(s"<$limit")
            else
              sheet.getRow(rowN).getCell(cellN).setCellValue(mtMap(mt).value * 1000)
          }
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

  /**
   * 建立暫存資料夾，並將目標資料夾的所有內容複製進去。
   *
   * @param sourceDir 要複製的目標（來源）資料夾路徑
   * @param prefix    暫存資料夾名稱的前綴
   * @return 建立好且複製完成的暫存資料夾 Path
   */
  def copyToTempDir(sourceDir: Path, prefix: String): Path = {
    // 1. 建立系統預設路徑下的暫存資料夾
    val tempDir = Files.createTempDirectory(prefix)

    val walkStream = Files.walk(sourceDir)
    try {
      val paths = walkStream.iterator().asScala

      paths.foreach { sourcePath =>
        // 2. 計算出目標檔案在暫存資料夾中的相對路徑
        val relativePath = sourceDir.relativize(sourcePath)
        val targetPath = tempDir.resolve(relativePath)

        // 3. 執行複製（如果資料夾不存在會自動建立，檔案存在則覆蓋）
        if (Files.isDirectory(sourcePath)) {
          if (!Files.exists(targetPath)) {
            Files.createDirectories(targetPath)
          }
        } else {
          // StandardCopyOption.REPLACE_EXISTING 確保覆蓋，COPY_ATTRIBUTES 複製檔案屬性
          Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        }
      }

      // 回傳建立好的暫存資料夾路徑
      tempDir

    } catch {
      case e: IOException =>
        // 如果複製過程失敗，嘗試清理剛建立的暫存區，避免留下垃圾檔案
        try {
          deleteFolder(tempDir)
        } catch {
          case _: Exception =>
        }
        throw e
    } finally {
      if (walkStream != null) walkStream.close()
    }
  }

  // 輔助方法：遞迴刪除資料夾（用於失敗時清理）
  private def deleteFolder(folderPath: Path): Unit = {
    if (!Files.exists(folderPath)) return
    val walkStream = Files.walk(folderPath)
    try {
      walkStream.iterator().asScala.toSeq
        .sortWith(_.compareTo(_) > 0)
        .foreach(path => Files.delete(path))
    } finally {
      if (walkStream != null) walkStream.close()
    }
  }


  // 1. 壓縮資料夾的方法
  def zipFolder(sourceDir: Path, zipFilePath: Path): Unit = {
    val outputStream = Files.newOutputStream(zipFilePath)
    val walkStream = Files.walk(sourceDir)

    val zs = new ZipOutputStream(outputStream)

    try {
      // 將 Java Stream 轉換為 Scala Iterator
      val paths = walkStream.iterator().asScala

      paths.filter(path => !path.equals(sourceDir)).foreach { path =>
        // 統一將路徑分隔符換成正斜線，確保跨平台相容性
        var zipEntryName = sourceDir.relativize(path).toString.replace("\\", "/")
        if (Files.isDirectory(path)) {
          zipEntryName += "/"
        }

        val zipEntry = new ZipEntry(zipEntryName)
        zs.putNextEntry(zipEntry)
        if (Files.isRegularFile(path)) {
          Files.copy(path, zs)
        }
        zs.closeEntry()
      }
    } finally {
      // 確保不論成功或發生例外，都會關閉所有資源
      if (zs != null) zs.close()
      if (walkStream != null) walkStream.close()
    }
  }


  private def handleQM061(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("CoA format").resolve("W-QM-061_F04.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg);
    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      def fillMtContent(value: Double,
                        rowN: Int,
                        cellN: Int,
                        limitN: Int): Unit = {
        var limitStr: String = ""

        val limit = try {
          limitStr = sheet.getRow(rowN).getCell(limitN).getStringCellValue
          limitStr.take(3).toDouble
        } catch {
          case _: Throwable =>
            0
        }

        if (value == 0 || value < limit)
          sheet.getRow(rowN).getCell(cellN).setCellValue("<" + limitStr)
        else
          sheet.getRow(rowN).getCell(cellN).setCellValue(value)

      }

      sheet.getRow(18).getCell(3).setCellValue(s"${dt.toString("YYYY/MM/dd")}")
      fillMtContent(getValue("O2") + getValue("Ar"), 24, 4, 8)
      fillMtContent(getValue("N2"), 25, 4, 8)
      fillMtContent(getValue("CO"), 26, 4, 8)
      fillMtContent(getValue("CO2"), 27, 4, 8)
      fillMtContent(getValue("CH4"), 28, 4, 8)
      fillMtContent(getValue("H2O"), 29, 4, 8)
    }
    finishExcel(file, pkg, wb)
  }

  private def handleQM081(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("CoA format").resolve("W-QM-081_F01.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      def fillMtContent(value: Double,
                        rowN: Int,
                        cellN: Int,
                        limitN: Int): Unit = {
        var limitStr: String = ""

        val limit = try {
          limitStr = sheet.getRow(rowN).getCell(limitN).getStringCellValue
          limitStr.take(3).toDouble
        } catch {
          case _: Throwable =>
            0
        }

        if (value == 0 || value < limit)
          sheet.getRow(rowN).getCell(cellN).setCellValue("<" + limitStr)
        else
          sheet.getRow(rowN).getCell(cellN).setCellValue(value)

      }

      sheet.getRow(8).getCell(6).setCellValue(s"分析日期(Date of Analysis): ${dt.toString("YYYY/MM/dd")}")
      fillMtContent(getValue("N2"), 18, 4, 9)
      fillMtContent(getValue("CO"), 19, 4, 9)
      fillMtContent(getValue("CO2"), 20, 4, 9)
      fillMtContent(getValue("CH4"), 21, 4, 9)
      fillMtContent(getValue("H2O"), 22, 4, 9)
      fillMtContent(getValue("Ar") + getValue("O2") , 23, 4, 9)
    }
    finishExcel(file, pkg, wb)
  }

  private def handleMTB1(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("MTB").resolve("MTB台中美光.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      def fillMtContent(value: Double,
                        rowN: Int,
                        cellN: Int,
                        limitN: Int): Unit = {
        var limitStr: String = ""

        val limit = try {
          limitStr = sheet.getRow(rowN).getCell(limitN).getStringCellValue
          limitStr.take(3).toDouble
        } catch {
          case _: Throwable =>
            0
        }

        if (value == 0 || value < limit)
          sheet.getRow(rowN).getCell(cellN).setCellValue("<" + limitStr)
        else
          sheet.getRow(rowN).getCell(cellN).setCellValue(value)

      }

      sheet.getRow(16).getCell(3).setCellValue(dt.toDate)
      sheet.getRow(17).getCell(3).setCellValue(dt.toDate)
      fillMtContent(getValue("H2"), 23, 4, 8)
      fillMtContent(getValue("N2"), 24, 4, 8)
      fillMtContent(getValue("Ar"), 25, 4, 8)
      fillMtContent(getValue("CO"), 26, 4, 8)
      fillMtContent(getValue("CO2"), 27, 4, 8)
      fillMtContent(getValue("CH4")+getValue("C2H6")+getValue("C3H8")+getValue("C4H10"), 28, 4, 8)
      fillMtContent(getValue("O2"), 29, 4, 8)
      fillMtContent(getValue("H2O"), 30, 4, 8)
    }
    finishExcel(file, pkg, wb)
  }

  private def handleMTB2(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("MTB").resolve("MTB_1048699_130-02088_20260821_A_1.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      def fillMtContent(value: Double,
                        rowN: Int,
                        cellN: Int,
                        limitN: Int): Unit = {
        var limitStr: String = ""

        val limit = try {
          limitStr = sheet.getRow(rowN).getCell(limitN).getStringCellValue
          limitStr.take(3).toDouble
        } catch {
          case _: Throwable =>
            0
        }

        if (value == 0 || value < limit)
          sheet.getRow(rowN).getCell(cellN).setCellValue(limitStr)
        else
          sheet.getRow(rowN).getCell(cellN).setCellValue(value)

      }

      sheet.getRow(2).getCell(3).setCellValue(dt.toDate)
      fillMtContent(getValue("H2O"), 8, 9, 10)
      fillMtContent(getValue("CO2"), 9, 9, 10)
      fillMtContent(getValue("CH4")+getValue("C2H6")+getValue("C3H8")+getValue("C4H10"), 10, 9, 10)
      fillMtContent(getValue("N2"), 11, 9, 10)
      fillMtContent(getValue("O2"), 12, 9, 10)
      fillMtContent(getValue("H2"), 13, 9, 10)
      fillMtContent(getValue("Ar"), 14, 9, 10)
      fillMtContent(getValue("CO"), 15, 9, 10)

    }
    finishExcel(file, pkg, wb)
  }

  private def handleNaya1(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("Nanya").resolve("南亞科技_260618P06_RN-2302-20260702.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      def fillMtContent(value: Double,
                        rowN: Int,
                        cellN: Int,
                        limitN: Int): Unit = {
        var limitStr: String = ""

        val limit = try {
          limitStr = sheet.getRow(rowN).getCell(limitN).getStringCellValue
          limitStr.take(3).toDouble
        } catch {
          case _: Throwable =>
            0
        }

        if (value == 0 || value < limit)
          sheet.getRow(rowN).getCell(cellN).setCellValue(s"<$limit")
        else
          sheet.getRow(rowN).getCell(cellN).setCellValue(value)

      }

      sheet.getRow(16).getCell(3).setCellValue(dt.toDate)
      sheet.getRow(17).getCell(3).setCellValue(dt.toDate)
      fillMtContent(getValue("H2"), 23, 4, 8)
      fillMtContent(getValue("N2"), 24, 4, 8)
      fillMtContent(getValue("CO") + getValue("CO2"), 25, 4, 8)
      fillMtContent(getValue("CH4")+getValue("C2H6")+getValue("C3H8")+getValue("C4H10"), 26, 4, 8)
      fillMtContent(getValue("O2"), 27, 4, 8)
      fillMtContent(getValue("H2O"), 28, 4, 8)
      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def handleNaya2(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("Nanya").resolve("BAL260702001_1.xml")

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      val xml = {
        s"""
        <?xml version="1.0" encoding="UTF-8"?>
          <NTC_COA>
            <Header>
              <BasicInfoField FieldName="Purno" FieldValue="N/A" />
              <BasicInfoField FieldName="COAnumber" FieldValue="BAL260702001_1" />
              <BasicInfoField FieldName="Materialtype" FieldValue="Bulk Gas" />
              <BasicInfoField FieldName="MaterialName" FieldValue="He" />
              <BasicInfoField FieldName="MaterialNumber" FieldValue="CHEINP64" />
              <BasicInfoField FieldName="Supplier" FieldValue="ALFE_Taichung_Taiwan" />
              <BasicInfoField FieldName="Agency" FieldValue="ALFE" />
              <BasicInfoField FieldName="DataSource" FieldValue="ALFE" />
              <BasicInfoField FieldName="Company_UID" FieldValue="22522453" />
              <BasicInfoField FieldName="LotNumber" FieldValue="RN-2302;20260702" />
              <BasicInfoField FieldName="FAB" FieldValue="E" />
              <BasicInfoField FieldName="LotQuantity" FieldValue="1" />
              <BasicInfoField FieldName="QuantityUn" FieldValue="Trailer" />
              <BasicInfoField FieldName="ManufacturingSite" FieldValue="ALFE_Taichung_Taiwan" />
              <BasicInfoField FieldName="Productiondate " FieldValue="${dt.toString("YYYY-MM-dd")}" />
              <BasicInfoField FieldName="ExpiredDate" FieldValue="2028-06-18" />
              <BasicInfoField FieldName="Expect_FABInDate" FieldValue="2026-07-02" />
              <BasicInfoField FieldName="Author" FieldValue="ALFE-EMCT-QC" />
              <BasicInfoField FieldName="Author_Email" FieldValue="howard.su@airliquide.com;alvin.chen@airliquide.com;misaki.tsai@airliquide.com;jos.wu@airliquide.com" />
              <BasicInfoField FieldName="DataInDate" FieldValue="2026-07-01" />
              <BasicInfoField FieldName="Remark" FieldValue="RN-2302" />
            </Header>
            <Content>
              <InspectionItem ItemName="Purity">
                <ResultItem ResultName="Unit" Value="%" />
                <ResultItem ResultName="Specification" Value="99.999" />
                <ResultItem ResultName="DetectionLimit" Value="N/A" />
                <ResultItem ResultName="Inspection" Value="99.999" />
              </InspectionItem>
              <InspectionItem ItemName="H2">
                <ResultItem ResultName="Unit" Value="ppmv" />
                <ResultItem ResultName="Specification" Value="1" />
                <ResultItem ResultName="DetectionLimit" Value="0.01" />
                <ResultItem ResultName="Inspection" Value="${getValue("H2")}" />
              </InspectionItem>
              <InspectionItem ItemName="N2">
                <ResultItem ResultName="Unit" Value="ppmv" />
                <ResultItem ResultName="Specification" Value="4" />
                <ResultItem ResultName="DetectionLimit" Value="0.01" />
                <ResultItem ResultName="Inspection" Value="${getValue("N2")}" />
              </InspectionItem>
              <InspectionItem ItemName="CO+CO2">
                <ResultItem ResultName="Unit" Value="ppmv" />
                <ResultItem ResultName="Specification" Value="0.6" />
                <ResultItem ResultName="DetectionLimit" Value="0.01" />
                <ResultItem ResultName="Inspection" Value="${getValue("CO") + getValue("CO2")}" />
              </InspectionItem>
              <InspectionItem ItemName="Total Hydrocarbons CH4">
                <ResultItem ResultName="Unit" Value="ppmv" />
                <ResultItem ResultName="Specification" Value="0.3" />
                <ResultItem ResultName="DetectionLimit" Value="0.02" />
                <ResultItem ResultName="Inspection" Value="${getValue("CH4")}" />
              </InspectionItem>
              <InspectionItem ItemName="O2">
                <ResultItem ResultName="Unit" Value="ppmv" />
                <ResultItem ResultName="Specification" Value="1" />
                <ResultItem ResultName="DetectionLimit" Value="0.01" />
                <ResultItem ResultName="Inspection" Value="${getValue("O2")}" />
              </InspectionItem>
              <InspectionItem ItemName="H2O">
                <ResultItem ResultName="Unit" Value="ppmv" />
                <ResultItem ResultName="Specification" Value="1" />
                <ResultItem ResultName="DetectionLimit" Value="0.01" />
                <ResultItem ResultName="Inspection" Value="${getValue("H2O")}" />
              </InspectionItem>
            </Content>
            <Remark />
          </NTC_COA>
        """
      }



      try {
        Files.write(file, xml.getBytes(StandardCharsets.UTF_8))
      } finally {

      }
    }
  }

  private def handleTSMCF7(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("CoA").resolve("TSMC台積電-F7_260819P04_EN-2109.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      def fillMtContent(value: Double,
                        rowN: Int,
                        cellN: Int,
                        limitN: Int): Unit = {
        var limitStr: String = ""

        val limit = try {
          limitStr = sheet.getRow(rowN).getCell(limitN).getStringCellValue
          limitStr.take(3).toDouble
        } catch {
          case _: Throwable =>
            0
        }

        if (value == 0 || value < limit)
          sheet.getRow(rowN).getCell(cellN).setCellValue(s"<$limit")
        else
          sheet.getRow(rowN).getCell(cellN).setCellValue(value)

      }

      sheet.getRow(16).getCell(3).setCellValue(dt.toDate)
      sheet.getRow(17).getCell(3).setCellValue(dt.toDate)
      fillMtContent(getValue("H2"), 23, 4, 8)
      fillMtContent(getValue("N2"), 24, 4, 8)
      fillMtContent(getValue("CO"), 25, 4, 8)
      fillMtContent(getValue("CO2"), 26, 4, 8)
      fillMtContent(getValue("CH4"), 27, 4, 8)
      fillMtContent(getValue("O2"), 28, 4, 8)
      fillMtContent(getValue("H2O"), 29, 4, 8)
      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def handleTSMCF8(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("CoA").resolve("TSMC台積電-F8_260804P04_28E026.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      def fillMtContent(value: Double,
                        rowN: Int,
                        cellN: Int,
                        limitN: Int): Unit = {
        var limitStr: String = ""

        val limit = try {
          limitStr = sheet.getRow(rowN).getCell(limitN).getStringCellValue
          limitStr.take(3).toDouble
        } catch {
          case _: Throwable =>
            0
        }

        if (value == 0 || value < limit)
          sheet.getRow(rowN).getCell(cellN).setCellValue(s"<$limit")
        else
          sheet.getRow(rowN).getCell(cellN).setCellValue(value)

      }

      sheet.getRow(16).getCell(3).setCellValue(dt.toDate)
      sheet.getRow(17).getCell(3).setCellValue(dt.toDate)
      fillMtContent(getValue("H2"), 23, 4, 8)
      fillMtContent(getValue("N2"), 24, 4, 8)
      fillMtContent(getValue("CO"), 25, 4, 8)
      fillMtContent(getValue("CO2"), 26, 4, 8)
      fillMtContent(getValue("CH4"), 27, 4, 8)
      fillMtContent(getValue("CH4")+getValue("C2H6")+getValue("C3H8")+getValue("C4H10"), 28, 4, 8)
      fillMtContent(getValue("O2"), 29, 4, 8)
      fillMtContent(getValue("H2O"), 30, 4, 8)
      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def handleTSMCF12(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("CoA").resolve("TSMC台積電-F12P8_260818P04_EN-2208.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      def fillMtContent(value: Double,
                        rowN: Int,
                        cellN: Int,
                        limitN: Int): Unit = {
        var limitStr: String = ""

        val limit = try {
          limitStr = sheet.getRow(rowN).getCell(limitN).getStringCellValue
          limitStr.take(3).toDouble
        } catch {
          case _: Throwable =>
            0
        }

        if (value == 0 || value < limit)
          sheet.getRow(rowN).getCell(cellN).setCellValue(s"<$limit")
        else
          sheet.getRow(rowN).getCell(cellN).setCellValue(value)

      }

      sheet.getRow(16).getCell(3).setCellValue(dt.toDate)
      sheet.getRow(17).getCell(3).setCellValue(dt.toDate)
      fillMtContent(getValue("H2"), 23, 4, 8)
      fillMtContent(getValue("N2"), 24, 4, 8)
      fillMtContent(getValue("CO"), 25, 4, 8)
      fillMtContent(getValue("CO2"), 26, 4, 8)
      fillMtContent(getValue("CH4")+getValue("C2H6")+getValue("C3H8")+getValue("C4H10"), 27, 4, 8)
      fillMtContent(getValue("O2"), 28, 4, 8)
      fillMtContent(getValue("H2O"), 29, 4, 8)
      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def handleTSMCF15(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("CoA").resolve("TSMC台積電-F15_260821P03_RN-2302.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      def fillMtContent(value: Double,
                        rowN: Int,
                        cellN: Int,
                        limitN: Int): Unit = {
        var limitStr: String = ""

        val limit = try {
          limitStr = sheet.getRow(rowN).getCell(limitN).getStringCellValue
          limitStr.take(3).toDouble
        } catch {
          case _: Throwable =>
            0
        }

        if (value == 0 || value < limit)
          sheet.getRow(rowN).getCell(cellN).setCellValue(s"<$limit")
        else
          sheet.getRow(rowN).getCell(cellN).setCellValue(value)

      }

      sheet.getRow(16).getCell(3).setCellValue(dt.toDate)
      sheet.getRow(17).getCell(3).setCellValue(dt.toDate)
      fillMtContent(getValue("H2"), 23, 4, 8)
      fillMtContent(getValue("N2"), 24, 4, 8)
      fillMtContent(getValue("CO"), 25, 4, 8)
      fillMtContent(getValue("CO2"), 26, 4, 8)
      fillMtContent(getValue("CH4")+getValue("C2H6")+getValue("C3H8")+getValue("C4H10"), 27, 4, 8)
      fillMtContent(getValue("O2"), 28, 4, 8)
      fillMtContent(getValue("H2O"), 29, 4, 8)
      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def handleTSMCF15_1(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("CoA").resolve("TSMC台積電-F15_260824P04_NK-2485.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      def fillMtContent(value: Double,
                        rowN: Int,
                        cellN: Int,
                        limitN: Int): Unit = {
        var limitStr: String = ""

        val limit = try {
          limitStr = sheet.getRow(rowN).getCell(limitN).getStringCellValue
          limitStr.take(3).toDouble
        } catch {
          case _: Throwable =>
            0
        }

        if (value == 0 || value < limit)
          sheet.getRow(rowN).getCell(cellN).setCellValue(s"<$limit")
        else
          sheet.getRow(rowN).getCell(cellN).setCellValue(value)

      }

      sheet.getRow(16).getCell(3).setCellValue(dt.toDate)
      sheet.getRow(17).getCell(3).setCellValue(dt.toDate)
      fillMtContent(getValue("H2"), 23, 4, 8)
      fillMtContent(getValue("N2"), 24, 4, 8)
      fillMtContent(getValue("CO"), 25, 4, 8)
      fillMtContent(getValue("CO2"), 26, 4, 8)
      fillMtContent(getValue("CH4")+getValue("C2H6")+getValue("C3H8")+getValue("C4H10"), 27, 4, 8)
      fillMtContent(getValue("O2"), 28, 4, 8)
      fillMtContent(getValue("H2O"), 29, 4, 8)
      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def handleTSMCF18(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("CoA").resolve("TSMC台積電-F18P4_260820P09_RN-2303.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      def fillMtContent(value: Double,
                        rowN: Int,
                        cellN: Int,
                        limitN: Int): Unit = {
        var limitStr: String = ""

        val limit = try {
          limitStr = sheet.getRow(rowN).getCell(limitN).getStringCellValue
          limitStr.take(3).toDouble
        } catch {
          case _: Throwable =>
            0
        }

        if (value == 0 || value < limit)
          sheet.getRow(rowN).getCell(cellN).setCellValue(s"<$limit")
        else
          sheet.getRow(rowN).getCell(cellN).setCellValue(value)

      }

      sheet.getRow(16).getCell(3).setCellValue(dt.toDate)
      sheet.getRow(17).getCell(3).setCellValue(dt.toDate)
      fillMtContent(getValue("H2"), 23, 4, 8)
      fillMtContent(getValue("N2"), 24, 4, 8)
      fillMtContent(getValue("CO"), 25, 4, 8)
      fillMtContent(getValue("CO2"), 26, 4, 8)
      fillMtContent(getValue("CH4")+getValue("C2H6")+getValue("C3H8")+getValue("C4H10"), 27, 4, 8)
      fillMtContent(getValue("O2"), 28, 4, 8)
      fillMtContent(getValue("H2O"), 29, 4, 8)
      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def handleTSMCF18_1(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("CoA").resolve("TSMC台積電-F18P5_260821P04_EN-2001.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      def fillMtContent(value: Double,
                        rowN: Int,
                        cellN: Int,
                        limitN: Int): Unit = {
        var limitStr: String = ""

        val limit = try {
          limitStr = sheet.getRow(rowN).getCell(limitN).getStringCellValue
          limitStr.take(3).toDouble
        } catch {
          case _: Throwable =>
            0
        }

        if (value == 0 || value < limit)
          sheet.getRow(rowN).getCell(cellN).setCellValue(s"<$limit")
        else
          sheet.getRow(rowN).getCell(cellN).setCellValue(value)

      }

      sheet.getRow(16).getCell(3).setCellValue(dt.toDate)
      sheet.getRow(17).getCell(3).setCellValue(dt.toDate)
      fillMtContent(getValue("H2"), 23, 4, 8)
      fillMtContent(getValue("N2"), 24, 4, 8)
      fillMtContent(getValue("CO"), 25, 4, 8)
      fillMtContent(getValue("CO2"), 26, 4, 8)
      fillMtContent(getValue("CH4")+getValue("C2H6")+getValue("C3H8")+getValue("C4H10"), 27, 4, 8)
      fillMtContent(getValue("O2"), 28, 4, 8)
      fillMtContent(getValue("H2O"), 29, 4, 8)
      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def handleTSMCF18_2(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("CoA").resolve("TSMC台積電-F18P6_260821P05_RN-2305.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      def fillMtContent(value: Double,
                        rowN: Int,
                        cellN: Int,
                        limitN: Int): Unit = {
        var limitStr: String = ""

        val limit = try {
          limitStr = sheet.getRow(rowN).getCell(limitN).getStringCellValue
          limitStr.take(3).toDouble
        } catch {
          case _: Throwable =>
            0
        }

        if (value == 0 || value < limit)
          sheet.getRow(rowN).getCell(cellN).setCellValue(s"<$limit")
        else
          sheet.getRow(rowN).getCell(cellN).setCellValue(value)

      }

      sheet.getRow(16).getCell(3).setCellValue(dt.toDate)
      sheet.getRow(17).getCell(3).setCellValue(dt.toDate)
      fillMtContent(getValue("H2"), 23, 4, 8)
      fillMtContent(getValue("N2"), 24, 4, 8)
      fillMtContent(getValue("CO"), 25, 4, 8)
      fillMtContent(getValue("CO2"), 26, 4, 8)
      fillMtContent(getValue("CH4")+getValue("C2H6")+getValue("C3H8")+getValue("C4H10"), 27, 4, 8)
      fillMtContent(getValue("O2"), 28, 4, 8)
      fillMtContent(getValue("H2O"), 29, 4, 8)
      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def handleTSMCF20(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("CoA").resolve("TSMC台積電-F20P1_260821P07_EN-2106.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      def fillMtContent(value: Double,
                        rowN: Int,
                        cellN: Int,
                        limitN: Int): Unit = {
        var limitStr: String = ""

        val limit = try {
          limitStr = sheet.getRow(rowN).getCell(limitN).getStringCellValue
          limitStr.take(3).toDouble
        } catch {
          case _: Throwable =>
            0
        }

        if (value == 0 || value < limit)
          sheet.getRow(rowN).getCell(cellN).setCellValue(s"<$limit")
        else
          sheet.getRow(rowN).getCell(cellN).setCellValue(value)

      }

      sheet.getRow(16).getCell(3).setCellValue(dt.toDate)
      sheet.getRow(17).getCell(3).setCellValue(dt.toDate)
      fillMtContent(getValue("H2"), 23, 4, 8)
      fillMtContent(getValue("N2"), 24, 4, 8)
      fillMtContent(getValue("CO"), 25, 4, 8)
      fillMtContent(getValue("CO2"), 26, 4, 8)
      fillMtContent(getValue("CH4")+getValue("C2H6")+getValue("C3H8")+getValue("C4H10"), 27, 4, 8)
      fillMtContent(getValue("O2"), 28, 4, 8)
      fillMtContent(getValue("H2O"), 29, 4, 8)
      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def handleTSMCF20_1(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("CoA").resolve("TSMC台積電-F20P2_260821P06_EN-2205.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      def fillMtContent(value: Double,
                        rowN: Int,
                        cellN: Int,
                        limitN: Int): Unit = {
        var limitStr: String = ""

        val limit = try {
          limitStr = sheet.getRow(rowN).getCell(limitN).getStringCellValue
          limitStr.take(3).toDouble
        } catch {
          case _: Throwable =>
            0
        }

        if (value == 0 || value < limit)
          sheet.getRow(rowN).getCell(cellN).setCellValue(s"<$limit")
        else
          sheet.getRow(rowN).getCell(cellN).setCellValue(value)

      }

      sheet.getRow(16).getCell(3).setCellValue(dt.toDate)
      sheet.getRow(17).getCell(3).setCellValue(dt.toDate)
      fillMtContent(getValue("H2"), 23, 4, 8)
      fillMtContent(getValue("N2"), 24, 4, 8)
      fillMtContent(getValue("CO"), 25, 4, 8)
      fillMtContent(getValue("CO2"), 26, 4, 8)
      fillMtContent(getValue("CH4")+getValue("C2H6")+getValue("C3H8")+getValue("C4H10"), 27, 4, 8)
      fillMtContent(getValue("O2"), 28, 4, 8)
      fillMtContent(getValue("H2O"), 29, 4, 8)
      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def handleTSMCFB30(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("CoA").resolve("TSMC台積電-FB30_260818P02_28E034.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      def fillMtContent(value: Double,
                        rowN: Int,
                        cellN: Int,
                        limitN: Int): Unit = {
        var limitStr: String = ""

        val limit = try {
          limitStr = sheet.getRow(rowN).getCell(limitN).getStringCellValue
          limitStr.take(3).toDouble
        } catch {
          case _: Throwable =>
            0
        }

        if (value == 0 || value < limit)
          sheet.getRow(rowN).getCell(cellN).setCellValue(s"<$limit")
        else
          sheet.getRow(rowN).getCell(cellN).setCellValue(value)

      }

      sheet.getRow(16).getCell(3).setCellValue(dt.toDate)
      sheet.getRow(17).getCell(3).setCellValue(dt.toDate)
      fillMtContent(getValue("H2"), 23, 4, 8)
      fillMtContent(getValue("N2"), 24, 4, 8)
      fillMtContent(getValue("CO"), 25, 4, 8)
      fillMtContent(getValue("CO2"), 26, 4, 8)
      fillMtContent(getValue("CH4"), 27, 4, 8)
      fillMtContent(getValue("CH4")+getValue("C2H6")+getValue("C3H8")+getValue("C4H10"), 28, 4, 8)
      fillMtContent(getValue("O2"), 29, 4, 8)
      fillMtContent(getValue("H2O"), 30, 4, 8)
      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def eCoa879(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("eCoA").resolve("L000879_FB30_20260826.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      sheet.getRow(2).getCell(1).setCellValue(dt.toDate)
      sheet.getRow(30).getCell(6).setCellValue(getValue("H2").toInt)
      sheet.getRow(31).getCell(6).setCellValue(getValue("N2").toInt)
      sheet.getRow(32).getCell(6).setCellValue(getValue("CO").toInt)
      sheet.getRow(33).getCell(6).setCellValue(getValue("CO2").toInt)
      sheet.getRow(34).getCell(6).setCellValue(getValue("CH4").toInt)
      sheet.getRow(35).getCell(6).setCellValue(getValue("O2").toInt)
      sheet.getRow(36).getCell(6).setCellValue(getValue("H2O").toInt)

      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def eCoa070_0080(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("eCoA").resolve("LB00070_0080(FAB 8)_20260827.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      sheet.getRow(2).getCell(1).setCellValue(dt.toDate)
      sheet.getRow(24).getCell(6).setCellValue(getValue("H2").toInt)
      sheet.getRow(25).getCell(6).setCellValue(getValue("N2").toInt)
      sheet.getRow(26).getCell(6).setCellValue(getValue("CO").toInt)
      sheet.getRow(27).getCell(6).setCellValue(getValue("CO2").toInt)
      sheet.getRow(28).getCell(6).setCellValue(getValue("CH4").toInt)
      sheet.getRow(29).getCell(6).setCellValue(getValue("O2").toInt)
      sheet.getRow(30).getCell(6).setCellValue(getValue("H2O").toInt)

      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def eCoa070_AP06(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("eCoA").resolve("LB00070_AP06(A6A0)_20260825-2.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      sheet.getRow(2).getCell(1).setCellValue(dt.toDate)
      sheet.getRow(24).getCell(6).setCellValue(getValue("H2").toInt)
      sheet.getRow(25).getCell(6).setCellValue(getValue("N2").toInt)
      sheet.getRow(26).getCell(6).setCellValue(getValue("CO").toInt)
      sheet.getRow(27).getCell(6).setCellValue(getValue("CO2").toInt)
      sheet.getRow(28).getCell(6).setCellValue(getValue("CH4").toInt)
      sheet.getRow(29).getCell(6).setCellValue(getValue("O2").toInt)
      sheet.getRow(30).getCell(6).setCellValue(getValue("H2O").toInt)

      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def eCoa070_AP6B(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("eCoA").resolve("LB00070_AP6B(A6B0)_20260824.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      sheet.getRow(2).getCell(1).setCellValue(dt.toDate)
      sheet.getRow(24).getCell(6).setCellValue(getValue("H2").toInt)
      sheet.getRow(25).getCell(6).setCellValue(getValue("N2").toInt)
      sheet.getRow(26).getCell(6).setCellValue(getValue("CO").toInt)
      sheet.getRow(27).getCell(6).setCellValue(getValue("CO2").toInt)
      sheet.getRow(28).getCell(6).setCellValue(getValue("CH4").toInt)
      sheet.getRow(29).getCell(6).setCellValue(getValue("O2").toInt)
      sheet.getRow(30).getCell(6).setCellValue(getValue("H2O").toInt)

      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def eCoa070_F7(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("eCoA").resolve("LB00070_F7(0070)_20260824.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      sheet.getRow(2).getCell(1).setCellValue(dt.toDate)
      sheet.getRow(24).getCell(6).setCellValue(getValue("H2").toInt)
      sheet.getRow(25).getCell(6).setCellValue(getValue("N2").toInt)
      sheet.getRow(26).getCell(6).setCellValue(getValue("CO").toInt)
      sheet.getRow(27).getCell(6).setCellValue(getValue("CO2").toInt)
      sheet.getRow(28).getCell(6).setCellValue(getValue("CH4").toInt)
      sheet.getRow(29).getCell(6).setCellValue(getValue("O2").toInt)
      sheet.getRow(30).getCell(6).setCellValue(getValue("H2O").toInt)

      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def eCoa070_12P3(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("eCoA").resolve("LB10070_12P3_20260824.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      sheet.getRow(2).getCell(1).setCellValue(dt.toDate)
      sheet.getRow(24).getCell(6).setCellValue(getValue("H2").toInt)
      sheet.getRow(25).getCell(6).setCellValue(getValue("N2").toInt)
      sheet.getRow(26).getCell(6).setCellValue(getValue("CO").toInt)
      sheet.getRow(27).getCell(6).setCellValue(getValue("CO2").toInt)
      sheet.getRow(28).getCell(6).setCellValue(getValue("CH4").toInt)
      sheet.getRow(29).getCell(6).setCellValue(getValue("O2").toInt)
      sheet.getRow(30).getCell(6).setCellValue(getValue("H2O").toInt)

      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def eCoa070_12P8(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("eCoA").resolve("LB10070_12P8_20260820.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      sheet.getRow(2).getCell(1).setCellValue(dt.toDate)
      sheet.getRow(24).getCell(6).setCellValue(getValue("H2").toInt)
      sheet.getRow(25).getCell(6).setCellValue(getValue("N2").toInt)
      sheet.getRow(26).getCell(6).setCellValue(getValue("CO").toInt)
      sheet.getRow(27).getCell(6).setCellValue(getValue("CO2").toInt)
      sheet.getRow(28).getCell(6).setCellValue(getValue("CH4").toInt)
      sheet.getRow(29).getCell(6).setCellValue(getValue("O2").toInt)
      sheet.getRow(30).getCell(6).setCellValue(getValue("H2O").toInt)

      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def eCoa070_15P1(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("eCoA").resolve("LB10070_15P1_20260826.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      sheet.getRow(2).getCell(1).setCellValue(dt.toDate)
      sheet.getRow(24).getCell(6).setCellValue(getValue("H2").toInt)
      sheet.getRow(25).getCell(6).setCellValue(getValue("N2").toInt)
      sheet.getRow(26).getCell(6).setCellValue(getValue("CO").toInt)
      sheet.getRow(27).getCell(6).setCellValue(getValue("CO2").toInt)
      sheet.getRow(28).getCell(6).setCellValue(getValue("CH4").toInt)
      sheet.getRow(29).getCell(6).setCellValue(getValue("O2").toInt)
      sheet.getRow(30).getCell(6).setCellValue(getValue("H2O").toInt)

      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def eCoa070_18P4(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("eCoA").resolve("LB10070_18P4_20260827.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      sheet.getRow(2).getCell(1).setCellValue(dt.toDate)
      sheet.getRow(24).getCell(6).setCellValue(getValue("H2").toInt)
      sheet.getRow(25).getCell(6).setCellValue(getValue("N2").toInt)
      sheet.getRow(26).getCell(6).setCellValue(getValue("CO").toInt)
      sheet.getRow(27).getCell(6).setCellValue(getValue("CO2").toInt)
      sheet.getRow(28).getCell(6).setCellValue(getValue("CH4").toInt)
      sheet.getRow(29).getCell(6).setCellValue(getValue("O2").toInt)
      sheet.getRow(30).getCell(6).setCellValue(getValue("H2O").toInt)

      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def eCoa070_18P5(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("eCoA").resolve("LB10070_18P5_20260827.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      sheet.getRow(2).getCell(1).setCellValue(dt.toDate)
      sheet.getRow(24).getCell(6).setCellValue(getValue("H2").toInt)
      sheet.getRow(25).getCell(6).setCellValue(getValue("N2").toInt)
      sheet.getRow(26).getCell(6).setCellValue(getValue("CO").toInt)
      sheet.getRow(27).getCell(6).setCellValue(getValue("CO2").toInt)
      sheet.getRow(28).getCell(6).setCellValue(getValue("CH4").toInt)
      sheet.getRow(29).getCell(6).setCellValue(getValue("O2").toInt)
      sheet.getRow(30).getCell(6).setCellValue(getValue("H2O").toInt)

      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def eCoa070_18P6(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("eCoA").resolve("LB10070_18P6_20260827.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      sheet.getRow(2).getCell(1).setCellValue(dt.toDate)
      sheet.getRow(24).getCell(6).setCellValue(getValue("H2").toInt)
      sheet.getRow(25).getCell(6).setCellValue(getValue("N2").toInt)
      sheet.getRow(26).getCell(6).setCellValue(getValue("CO").toInt)
      sheet.getRow(27).getCell(6).setCellValue(getValue("CO2").toInt)
      sheet.getRow(28).getCell(6).setCellValue(getValue("CH4").toInt)
      sheet.getRow(29).getCell(6).setCellValue(getValue("O2").toInt)
      sheet.getRow(30).getCell(6).setCellValue(getValue("H2O").toInt)

      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }

  private def eCoa070_F20P2(folder: Path, map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Unit = {
    val file = folder.resolve("tsmc").resolve("eCoA").resolve("LB10070_F20P2_20260827.xlsx")
    //Open Excel
    val pkg = OPCPackage.open(new FileInputStream(file.toAbsolutePath.toFile))
    val wb = new XSSFWorkbook(pkg)

    for (entry <- map) {
      val (dt, sampleNameOpt, mtMap) = entry._2
      val sheet: XSSFSheet = wb.getSheetAt(0)

      def getValue(mtName: String): Double = {
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName)
        if (mtMap.contains(mt))
          mtMap(mt).value
        else
          0d
      }

      sheet.getRow(2).getCell(1).setCellValue(dt.toDate)
      sheet.getRow(24).getCell(6).setCellValue(getValue("H2").toInt)
      sheet.getRow(25).getCell(6).setCellValue(getValue("N2").toInt)
      sheet.getRow(26).getCell(6).setCellValue(getValue("CO").toInt)
      sheet.getRow(27).getCell(6).setCellValue(getValue("CO2").toInt)
      sheet.getRow(28).getCell(6).setCellValue(getValue("CH4").toInt)
      sheet.getRow(29).getCell(6).setCellValue(getValue("O2").toInt)
      sheet.getRow(30).getCell(6).setCellValue(getValue("H2O").toInt)

      sheet.setForceFormulaRecalculation(true)
    }
    finishExcel(file, pkg, wb)
  }
  def createCoA(map: Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]): Path = {
    val coaTemplatePath = Paths.get(environment.rootPath.getAbsolutePath).resolve("report_template").resolve("Coa")
    val tempFolder = copyToTempDir(coaTemplatePath, "coa")
    org.apache.poi.openxml4j.util.ZipSecureFile.setMinInflateRatio(0)
    handleQM061(tempFolder, map)
    handleQM081(tempFolder, map)
    handleMTB1(tempFolder, map)
    handleMTB2(tempFolder, map)
    handleNaya1(tempFolder, map)
    handleNaya2(tempFolder, map)
    handleTSMCF7(tempFolder, map)
    handleTSMCF8(tempFolder, map)
    handleTSMCF12(tempFolder, map)
    handleTSMCF15(tempFolder, map)
    handleTSMCF15_1(tempFolder, map)
    handleTSMCF18(tempFolder, map)
    handleTSMCF18_1(tempFolder, map)
    handleTSMCF18_2(tempFolder, map)
    handleTSMCF20(tempFolder, map)
    handleTSMCF20_1(tempFolder, map)
    handleTSMCFB30(tempFolder, map)
    // eCoA
    eCoa879(tempFolder, map)
    eCoa070_0080(tempFolder, map)
    eCoa070_AP06(tempFolder, map)
    eCoa070_AP6B(tempFolder, map)
    eCoa070_F7(tempFolder, map)
    eCoa070_12P3(tempFolder, map)
    eCoa070_12P8(tempFolder, map)
    eCoa070_15P1(tempFolder, map)
    eCoa070_18P4(tempFolder, map)
    eCoa070_18P5(tempFolder, map)
    eCoa070_18P6(tempFolder, map)
    eCoa070_F20P2(tempFolder, map)

    val zipTempFile = Files.createTempFile("coa", ".zip")
    zipFolder(tempFolder, zipTempFile)
    zipTempFile

  }

}
