package models

import com.github.nscala_time.time.Imports._
import com.github.s7connector.api.factory.{S7ConnectorFactory, S7SerializerFactory}
import com.github.s7connector.api.{S7Connector, S7Serializer}
import com.serotonin.modbus4j.code.DataType
import com.serotonin.modbus4j.ip.IpParameters
import com.serotonin.modbus4j.locator.BaseLocator
import models.ModelHelper._
import models.Record.MtRecord
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

  // 0 is local
  def exportLocalModeToPLC(gcConfig: GcConfig, mode: Int) = {
    for (plcConfig <- gcConfig.plcConfig) {
      var connectorOpt: Option[S7Connector] = None
      try {
        connectorOpt =
          Some(S7ConnectorFactory
            .buildTCPConnector()
            .withHost(plcConfig.host)
            .withRack(plcConfig.rack.getOrElse(0))
            .withSlot(plcConfig.slot.getOrElse(1))
            .build())
        for (connector <- connectorOpt) {
          val serializer = S7SerializerFactory.buildSerializer(connector)
          // local is 0 remote is 1
          if (plcConfig.exportMap.contains("local")) {
            val entry = plcConfig.exportMap("local")
            Logger.info(s"(local/remote 0/1) ${mode != 0}=>DB${entry.db}.${entry.offset}.${entry.bitOffset}")
            setBit(serializer, entry.db, entry.offset, entry.bitOffset, mode != 0)
          }
        }
      } catch {
        case ex: Exception =>
          Logger.error(ex.getMessage, ex)
      } finally {
        for (connector <- connectorOpt)
          connector.close()
      }
    }
  }

  def exportRealtimeData(gcConfig: GcConfig) = {
    val path = Paths.get(current.path.getAbsolutePath + "/export/realtime.txt")
    var buffer = ""
    buffer += s"Selector,${gcConfig.selector.get}\n"
    val monitors = Monitor.getMonitorsByGcName(gcConfig.gcName) map {
      _._id
    }
    val latestRecord = Record.getLatestFixedRecordListFuture(Record.MinCollection, monitors)(1)

    for (records <- latestRecord if records.nonEmpty) yield {
      val data = records.head

      val dateTime = new DateTime(data.time)

      latestDateTime = dateTime

      //Export to modbus
      if (exportLocalModbus) {
        Logger.debug("Export to modbus")
        writeModbusSlave(gcConfig: GcConfig, data)
      }

      // Export to plc if properly configured
      exportDataToPLC(data)

      // Export to ao if configured
      exportDataToAO(data)
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

      val gcOffset = gcConfig.index * 52
      //Selector
      writeShort(gcOffset, (gcConfig.selector.get.toShort).toShort)
      for ((mtData, idx) <- data.mtDataList.zipWithIndex) {
        writeDouble(gcOffset + idx * 4 + 1, mtData.value)
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

    for {gcConfig <- GcAgent.gcConfigList.find(gcConfig => gcConfig.gcName == gcName)
         plcConfig <- gcConfig.plcConfig
         } {
      Logger.info(s"${selector.dp_no} write to PLC ${plcConfig.host}")
      var connectorOpt: Option[S7Connector] = None
      try {
        connectorOpt =
          Some(S7ConnectorFactory
            .buildTCPConnector()
            .withHost(plcConfig.host)
            .withRack(plcConfig.rack.getOrElse(0))
            .withSlot(plcConfig.slot.getOrElse(1))
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

  def exportDataToAO(data: Record.RecordList) = {
    val selector: Monitor = Monitor.map(Monitor.withName(data.monitor))
    val gcName = selector.gcName

    for {gcConfig <- GcAgent.gcConfigList.find(gcConfig => gcConfig.gcName == gcName)
         aoConfigList <- gcConfig.aoConfigList
         aoConfig <- aoConfigList
         } {
      def writeDataToAdam6224(mtDataList: Seq[MtRecord]) = {
        Future {
          blocking {
            try {
              if (mtDataList.exists(mtData => aoConfig.exportMap.contains(mtData.mtName))) {
                val ipParameters = new IpParameters()
                ipParameters.setHost(aoConfig.host);
                ipParameters.setPort(502);
                val modbusFactory = new ModbusFactory()

                val master = modbusFactory.createTcpMaster(ipParameters, false)
                master.setTimeout(4000)
                master.setRetries(1)
                master.setConnected(true)
                master.init();

                Logger.info(s"export ao to ${aoConfig.host}")
                for {mtData <- data.mtDataList
                     aoEntry <- aoConfig.exportMap.get(mtData.mtName)
                     offset = aoEntry.idx
                     } {
                  val locator = BaseLocator.holdingRegister(1, offset, DataType.TWO_BYTE_INT_UNSIGNED)
                  if(mtData.value > aoEntry.max)
                    Logger.error(s"${mtData.mtName} ${mtData.value} is larger than AO max => D${aoEntry.idx} ${aoEntry.max}")

                  if(mtData.value < aoEntry.min) {
                    Logger.error(s"${mtData.mtName} ${mtData.value} is less than AO min => D${aoEntry.idx} ${aoEntry.min}")
                    master.setValue(locator, 0)
                  }else if(mtData.value >= aoEntry.max){
                    Logger.error(s"${mtData.mtName} ${mtData.value} is exceed AO max => D${aoEntry.idx} ${aoEntry.max}")
                    master.setValue(locator, 4096 - 1)
                  }else{
                    val value: Int = ((mtData.value - aoEntry.min) / (aoEntry.max - aoEntry.min) * 4096).toInt
                    master.setValue(locator, value)
                  }
                }
                master.destroy()
              }
            } catch {
              case ex: Exception =>
                Logger.error(ex.getMessage, ex)
            }
          }
        }
      }

      writeDataToAdam6224(data.mtDataList)
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
            Logger.info(s"set alarm ${!alarm} =>DB${entry.db}.${entry.offset}.${entry.bitOffset}")
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

  def notifySelectorChange(gcConfig: GcConfig, selector: Int): Unit = {
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
          if (plcConfig.exportMap.contains("selector")) {
            val entry = plcConfig.exportMap("selector")
            Logger.info(s"set selector ${selector} =>DB${entry.db}.${entry.offset}.${entry.bitOffset}")
            val mtDataBean = new MtDataBean()
            mtDataBean.value = selector.toFloat
            serializer.store(mtDataBean, entry.db, entry.offset)
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