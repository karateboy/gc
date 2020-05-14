package models

import play.api._
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.util.Date

import play.api.Play.current

import scala.concurrent.ExecutionContext.Implicits.global
import com.github.nscala_time.time.Imports._

import scala.collection.JavaConverters._
import scala.concurrent._
import ModelHelper._
import com.github.s7connector.api.factory.S7ConnectorFactory

object Exporter {
  val activeMonitorType = List(MonitorType.mtvList: _*)

  def exportActiveMonitorType = {
    val path = Paths.get(current.path.getAbsolutePath + "/export/activeMonitor.txt")
    val monitorTypeStrList = activeMonitorType map {
      MonitorType.map(_)._id
    }
    val ret = monitorTypeStrList.fold("")((a, b) => {
      if (a.isEmpty())
        b
      else
        a + "\r" + b
    }).getBytes
    Files.write(path, ret, StandardOpenOption.CREATE)
  }

  var latestDateTime = new DateTime(0)
  Logger.info(s"latestDateTime=${latestDateTime.toString()}")

  import com.serotonin.modbus4j._

  var masterOpt: Option[ModbusMaster] = None

  val exportLocalModbus = Play.current.configuration.getBoolean("exportLocalModbus").getOrElse(false)

  val modbusPort = Play.current.configuration.getInt("modbus_port").getOrElse(503)


  def writeModbusSlave(gcConfig: GcConfig, data: Record.RecordList) = {
    import com.serotonin.modbus4j.ip.IpParameters

    def connectHost() {
      val ipParameters = new IpParameters()
      ipParameters.setHost("127.0.0.1");
      ipParameters.setPort(modbusPort);
      val modbusFactory = new ModbusFactory()

      val master = modbusFactory.createTcpMaster(ipParameters, true)
      master.setTimeout(4000)
      master.setRetries(1)
      master.setConnected(true)
      master.init();
      masterOpt = Some(master)
    }

    def writeReg(master: ModbusMaster) = {
      import com.serotonin.modbus4j.msg._
      import com.serotonin.modbus4j.locator.BaseLocator
      import com.serotonin.modbus4j.code.DataType;
      import java.nio._
      val slaveID = 1

      def writeShort(offset: Int, value: Short) = {
        val request = new WriteRegisterRequest(slaveID, offset, value)
        master.send(request)
      }

      def writeLong(offset: Int, value: Long) = {
        val locator = BaseLocator.holdingRegister(slaveID, offset, DataType.EIGHT_BYTE_INT_SIGNED);
        master.setValue(locator, value);
      }

      def writeDouble(offset: Int, value: Double) = {
        val locator = BaseLocator.holdingRegister(slaveID, offset, DataType.EIGHT_BYTE_FLOAT);
        master.setValue(locator, value);
      }

      //Selector
      writeShort(0, (gcConfig.selector.get.toShort).toShort)
      for ((mtData, idx) <- data.mtDataList.zipWithIndex) {
        writeDouble(idx * 4 + 1, mtData.value)
      }

    }

    Future {
      blocking {
        try {
          if (masterOpt.isEmpty)
            connectHost

          masterOpt map {
            writeReg
          }
        } catch {
          case ex: Exception =>
            Logger.error(ex.getMessage, ex)
        }
      }
    } onFailure errorHandler
  }

  import com.github.s7connector.api._
  import java.nio.ByteBuffer

  def writePlc(data: Record.RecordList) = {
    val tokens = data.monitor.split(":")
    val gcName = tokens(0)
    Logger.info(s"write PLC ${gcName}")

    for {gcConfig <- GcAgent.gcConfigList.find(gcConfig => gcConfig.gcName == gcName)
         plcConfig <- gcConfig.plcConfig
         } {
      val connector =
        S7ConnectorFactory
          .buildTCPConnector()
          .withHost(plcConfig.host)
          .build()

      def toHexString(bytes: Seq[Byte]): String = bytes.map("%02X" format _).mkString


      if (plcConfig.exportMap.contains("selector")) {
        import com.github.s7connector.impl.serializer.converter.IntegerConverter
        val converter = new IntegerConverter()
        val buffer = new Array[Byte](4)
        converter.insert(gcConfig.selector.get, buffer, 0, 0, buffer.size)

        val entry = plcConfig.exportMap("selector")
        connector.write(DaveArea.DB, entry.db, entry.offset, buffer)
        Logger.info(s"PLC Selector ${gcConfig.selector.get} =>DB${entry.db}.${entry.offset}")

        val readBack = connector.read(DaveArea.DB, entry.db, 4, entry.offset)
        val v: Integer = converter.extract(classOf[Integer], readBack, 0, 0)
        Logger.info(s"Selector DB${entry.db}.${entry.offset} read=>" + v)
      }

      if (plcConfig.exportMap.contains("datetime")) {
        val dateTime = new DateTime(data.time)
        val buffer = ByteBuffer.allocate(8).putLong(data.time).array()
        val entry = plcConfig.exportMap("datetime")
        connector.write(DaveArea.DB, entry.db, entry.offset, buffer)
        Logger.info(s"dateTime ${dateTime.toString} =>DB${entry.db}.${entry.offset}")
        val readBack = connector.read(DaveArea.DB, entry.db, 8, 0)
      }
      for (mtData <- data.mtDataList) {
        if (plcConfig.exportMap.contains(mtData.mtName)) {
          val buffer = ByteBuffer.allocate(4).putFloat(mtData.value.toFloat).array()
          val entry = plcConfig.exportMap(mtData.mtName)
          connector.write(DaveArea.DB, entry.db, entry.offset, buffer)
          Logger.info(s"${mtData.mtName} ${mtData.value}=>DB${entry.db}.${entry.offset}")
          val readBack = connector.read(DaveArea.DB, entry.db, 4, entry.offset)
        }
      }
      connector.close()
    }
  }

  def exportRealtimeData(gcConfig: GcConfig) = {
    val path = Paths.get(current.path.getAbsolutePath + "/export/realtime.txt")
    var buffer = ""
    buffer += s"Selector,${gcConfig.selector.get}\n"
    val latestRecord = Record.getLatestRecordListFuture(Record.MinCollection)(1)

    for (records <- latestRecord) yield {
      val data =
        if (records.isEmpty) {
          import org.mongodb.scala.bson._
          val mtRecordList = MonitorType.mtvList map { mt => Record.MtRecord(MonitorType.map(mt).desp, 0, MonitorStatus.NormalStat, "") }
          Record.RecordList("-", DateTime.now().getMillis, mtRecordList, new ObjectId())
        } else {
          records.head
        }

      val dateTime = new DateTime(data.time)
      if (true) {

        Logger.info(s"export Data ${dateTime.toString}")

        latestDateTime = dateTime

        //Export to modbus
        if (exportLocalModbus) {
          Logger.debug("Export to modbus")
          writeModbusSlave(gcConfig: GcConfig, data)
        }

        //Export to plc if properly configured
        //writePlc(data)

        buffer += s"InjectionDate, ${data.time}\r"
        val mtStrs = data.mtDataList map { mt_data => s"${mt_data.mtName}, ${mt_data.value}" }
        val mtDataStr = mtStrs.foldLeft("")((a, b) => {
          if (a.length() == 0)
            b
          else
            a + "\n" + b
        })
        buffer += mtDataStr
        val ret = Files.write(path, buffer.getBytes, StandardOpenOption.CREATE, StandardOpenOption.SYNC, StandardOpenOption.TRUNCATE_EXISTING)
        true
      } else
        false
    }
  }
}