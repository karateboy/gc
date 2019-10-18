package models
import play.api._
import java.nio.file.{ Paths, Files, StandardOpenOption }
import play.api.Play.current
import scala.concurrent.ExecutionContext.Implicits.global
import com.github.nscala_time.time.Imports._
import scala.concurrent._
import ModelHelper._
object Exporter {
  val activeMonitorType = List(MonitorType.mtvList: _*)
  def exportActiveMonitorType = {
    val path = Paths.get(current.path.getAbsolutePath + "/export/activeMonitor.txt")
    val monitorTypeStrList = activeMonitorType map { MonitorType.map(_)._id }
    val ret = monitorTypeStrList.fold("")((a, b) => {
      if (a.isEmpty())
        b
      else
        a + "\r" + b
    }).getBytes
    Files.write(path, ret, StandardOpenOption.CREATE)
  }

  var latestDateTime = new DateTime(0)
  import com.serotonin.modbus4j._
  var masterOpt: Option[ModbusMaster] = None

  def writeModbusSlave(data: Record.RecordList) = {
    import com.serotonin.modbus4j.ip.IpParameters

    def connectHost() {
      val ipParameters = new IpParameters()
      ipParameters.setHost("127.0.0.1");
      ipParameters.setPort(502);
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
      writeShort(0, Selector.get.toShort)
      for ((mtData, idx) <- data.mtDataList.zipWithIndex) {
        writeDouble(idx * 4 + 1, mtData.value)
      }

    }

    Future {
      blocking {
        try {
          if (masterOpt.isEmpty)
            connectHost

          masterOpt map { writeReg }
        } catch {
          case ex: Exception =>
            Logger.error(ex.getMessage, ex)
        }
      }
    } onFailure errorHandler
  }

  def exportRealtimeData = {
    val path = Paths.get(current.path.getAbsolutePath + "/export/realtime.txt")
    var buffer = ""
    buffer += s"Selector,${Selector.get}\r"
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
      if (latestDateTime < dateTime) {
        latestDateTime = dateTime
        
        //Export to modbus
        writeModbusSlave(data)
        
        buffer += s"InjectionDate, ${data.time}\r"
        val mtStrs = data.mtDataList map { mt_data => s"${mt_data.mtName}, ${mt_data.value}" }
        val mtDataStr = mtStrs.foldLeft("")((a, b) => {
          if (a.length() == 0)
            b
          else
            a + "\r" + b
        })
        buffer += mtDataStr
        val ret = Files.write(path, buffer.getBytes, StandardOpenOption.CREATE, StandardOpenOption.SYNC, StandardOpenOption.TRUNCATE_EXISTING)
        true
      } else
        false
    }
  }
}