package models

import com.github.nscala_time.time.Imports._
import com.github.s7connector.api.factory.{S7ConnectorFactory, S7SerializerFactory}
import com.github.s7connector.api.{S7Connector, S7Serializer}
import models.ModelHelper._
import play.api.Play.current
import play.api._

import java.nio.file.{Files, Paths, StandardOpenOption}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._

object Exporter {
  val activeMonitorType = List(MonitorType.mtvList: _*)
  val exportLocalModbus = Play.current.configuration.getBoolean("exportLocalModbus").getOrElse(false)
  val modbusPort = Play.current.configuration.getInt("modbus_port").getOrElse(503)

  import com.serotonin.modbus4j._

  var latestDateTime = new DateTime(0)
  var masterOpt: Option[ModbusMaster] = None

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

  def exportLocalModeToPLC(gcConfig: GcConfig, mode: Int) = {
    for(plcConfig <- gcConfig.plcConfig){
      var connectorOpt : Option[S7Connector]= None
      try{
        connectorOpt =
          Some(S7ConnectorFactory
            .buildTCPConnector()
            .withHost(plcConfig.host)
            .build())
        for(connector <- connectorOpt){
          val serializer = S7SerializerFactory.buildSerializer(connector)

          if (plcConfig.exportMap.contains("local")) {
            val entry = plcConfig.exportMap("local")
            Logger.info(s"isLocal ${mode == 0} =>DB${entry.db}.${entry.offset}")
            setBit(serializer, entry.db, entry.offset, entry.bitOffset, mode == 0)
          }
        }
      }catch{
        case ex:Exception=>
          Logger.error(ex.getMessage, ex)
      }finally {
        for(connector <- connectorOpt)
          connector.close()
      }
    }
  }

  def setBit(serializer: S7Serializer, db: Int, byteOffset: Int, bitOffset: Int, v: Boolean) = {
    bitOffset match {
      case 0 =>
        val bean = new Bit0Bean()
        bean.value = v
        serializer.store(bean, db, byteOffset)
      case 1 =>
        val bean = new Bit1Bean()
        bean.value = v
        serializer.store(bean, db, byteOffset)
      case 2 =>
        val bean = new Bit2Bean()
        bean.value = v
        serializer.store(bean, db, byteOffset)
      case 3 =>
        val bean = new Bit3Bean()
        bean.value = v
        serializer.store(bean, db, byteOffset)
      case 4 =>
        val bean = new Bit4Bean()
        bean.value = v
        serializer.store(bean, db, byteOffset)
      case 5 =>
        val bean = new Bit5Bean()
        bean.value = v
        serializer.store(bean, db, byteOffset)
      case 6 =>
        val bean = new Bit6Bean()
        bean.value = v
        serializer.store(bean, db, byteOffset)
      case 7 =>
        val bean = new Bit7Bean()
        bean.value = v
        serializer.store(bean, db, byteOffset)
    }

  }

  def exportRealtimeData(gcConfig: GcConfig) = {
    val path = Paths.get(current.path.getAbsolutePath + "/export/realtime.txt")
    var buffer = ""
    buffer += s"Selector,${gcConfig.selector.get}\n"
    val latestRecord = Record.getLatestFixedRecordListFuture(Record.MinCollection)(1)

    for (records <- latestRecord if records.nonEmpty) yield {
      val data = records.head

      val dateTime = new DateTime(data.time)

      Logger.info(s"export Data ${dateTime.toString}")
      latestDateTime = dateTime

      //Export to modbus
      if (exportLocalModbus) {
        Logger.debug("Export to modbus")
        writeModbusSlave(gcConfig: GcConfig, data)
      }

      //Export to plc if properly configured
      exportDataToPLC(data)

      buffer += s"InjectionDate, ${data.time}\r"
      val mtStrs = data.mtDataList map { mt_data => s"${mt_data.mtName}, ${mt_data.value}" }
      val mtDataStr = mtStrs.foldLeft("")((a, b) => {
        if (a.length() == 0)
          b
        else
          a + "\n" + b
      })
      buffer += mtDataStr
      Files.write(path, buffer.getBytes, StandardOpenOption.CREATE, StandardOpenOption.SYNC, StandardOpenOption.TRUNCATE_EXISTING)
    }
  }

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
      import com.serotonin.modbus4j.code.DataType
      import com.serotonin.modbus4j.locator.BaseLocator
      import com.serotonin.modbus4j.msg._
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

  def exportDataToPLC(data: Record.RecordList) = {
    val selector: Monitor = Monitor.map(Monitor.withName(data.monitor))
    val gcName = selector.gcName
    Logger.info(s"write PLC ${selector.dp_no}")

    for {gcConfig <- GcAgent.gcConfigList.find(gcConfig => gcConfig.gcName == gcName)
         plcConfig <- gcConfig.plcConfig
         } {
      var connectorOpt: Option[S7Connector] = None
      try {
        connectorOpt =
          Some(S7ConnectorFactory
            .buildTCPConnector()
            .withHost(plcConfig.host)
            .build())
        for (connector <- connectorOpt) {
          val serializer: S7Serializer = S7SerializerFactory.buildSerializer(connector)

          if (plcConfig.exportMap.contains("datetime")) {
            val dateTime = new DateTime(data.time)
            val entry = plcConfig.exportMap("datetime")
            Logger.info(s"dateTime ${dateTime.toString} =>DB${entry.db}.${entry.offset}")
            val dateTimeBean = new DateTimeBean()
            dateTimeBean.value = dateTime.toDate
            serializer.store(dateTimeBean, entry.db, entry.offset)
          }

          for (mtData <- data.mtDataList) {
            if (plcConfig.exportMap.contains(mtData.mtName)) {
              val entry = plcConfig.exportMap(mtData.mtName)
              Logger.info(s"${mtData.mtName} ${mtData.value}=>DB${entry.db}.${entry.offset}")
              val mtDataBean = new MtDataBean()
              mtDataBean.value = mtData.value.toFloat
              serializer.store(mtDataBean, entry.db, entry.offset)
            }
          }
        }
      } catch {
        case ex: Exception =>
          Logger.error(ex.getMessage, ex)
      } finally {
        for (connector <- connectorOpt) {
          connector.close()
        }
      }
    }
  }

  def notifyAlarm(alarm: Boolean): Unit = {
    for (gcConfig <- GcAgent.gcConfigList) {
      notifyAlarm(gcConfig, alarm)
    }
  }

  def notifyAlarm(gcConfig: GcConfig, alarm: Boolean): Unit = {
    for (plcConfig <- gcConfig.plcConfig) {
      var connectorOpt: Option[S7Connector] = None
      try {
        connectorOpt =
          Some(S7ConnectorFactory
            .buildTCPConnector()
            .withHost(plcConfig.host)
            .build())
        for (connector <- connectorOpt) {
          val serializer: S7Serializer = S7SerializerFactory.buildSerializer(connector)
          if (plcConfig.exportMap.contains("alarm")) {
            val entry = plcConfig.exportMap("alarm")
            Logger.info(s"PLC alarm ${!alarm} =>DB${entry.db}.${entry.offset}.${entry.bitOffset}")
            setBit(serializer, entry.db, entry.offset, entry.bitOffset, !alarm)
          }
        }
      } catch {
        case ex: Exception =>
          Logger.error(ex.getMessage, ex)
      } finally {
        for (connector <- connectorOpt) {
          connector.close()
        }
      }
    }
  }
}