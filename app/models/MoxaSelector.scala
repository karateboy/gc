package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import ModelHelper._
import scala.concurrent.ExecutionContext.Implicits.global
case object ConnectHost
case object Collect
class MoxaSelector(gcName:String, config:Configuration) extends SelectorModel {
  val host = config.getString("host").get
  val max = config.getInt("max").get
  for (id <- 1 to max) {
    Monitor.getMonitorValueByName(gcName, id)
  }
  val worker = Akka.system.actorOf(Props(new MoxaE1212Collector(host, max, this)), name = s"moxaAgent_${gcName}")
  worker ! ConnectHost

  @volatile var streamNum = 1
  def getStreamNum(): Int = streamNum
  def setStreamNum(v: Int) {}
  val canSetStream = false
  
  def modifyStreamNum(v: Int) {
    streamNum = v
  }

}

class MoxaE1212Collector(host: String, maxStreamNum: Int, selector: MoxaSelector) extends Actor {

  import java.io.BufferedReader
  import java.io._

  var cancelable: Cancellable = _

  import scala.concurrent._
  import com.serotonin.modbus4j._
  import com.serotonin.modbus4j.ip.IpParameters

  def receive = handler(MonitorStatus.NormalStat, None)

  def handler(collectorState: String, masterOpt: Option[ModbusMaster]): Receive = {
    case ConnectHost =>
      Logger.info(s"connect to E1212")
      Future {
        blocking {
          try {
            val ipParameters = new IpParameters()
            ipParameters.setHost(host);
            ipParameters.setPort(502);
            val modbusFactory = new ModbusFactory()

            val master = modbusFactory.createTcpMaster(ipParameters, true)
            master.setTimeout(4000)
            master.setRetries(1)
            master.setConnected(true)
            master.init();
            context become handler(collectorState, Some(master))
            import scala.concurrent.duration._
            cancelable = Akka.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, Collect)
          } catch {
            case ex: Exception =>
              Logger.error(ex.getMessage, ex)
              Logger.info("Try again 1 min later...")
              //Try again
              import scala.concurrent.duration._
              cancelable = Akka.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost)
          }

        }
      } onFailure errorHandler

    case Collect =>
      Future {
        blocking {
          try {
            import com.serotonin.modbus4j.BatchRead
            import com.serotonin.modbus4j.locator.BaseLocator
            import com.serotonin.modbus4j.code.DataType

            // DI Value ...
            {
              val batch = new BatchRead[Integer]
              for (idx <- 0 to maxStreamNum - 1)
                batch.addLocator(idx, BaseLocator.inputStatus(1, idx))

              batch.setContiguousRequests(true)

              val rawResult = masterOpt.get.send(batch)
              val result =
                for (idx <- 0 to maxStreamNum - 1) yield rawResult.getValue(idx).asInstanceOf[Boolean]

              val trueIdxSeq = result.toSeq.zipWithIndex
              val trueNum = trueIdxSeq.filter(b => b._1).length
              if (trueNum != 1)
                Alarm.log(None, None, s"${trueNum}個通道打開!")
              else {
                val (v, pos) = trueIdxSeq.find(t => t._1).get
                selector.modifyStreamNum(pos + 1)
              }
            }

            import scala.concurrent.duration._
            cancelable = Akka.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(3, SECONDS), self, Collect)
          } catch {
            case ex: Throwable =>
              Logger.error("Read reg failed", ex)
              masterOpt.get.destroy()
              context become handler(collectorState, None)
              self ! ConnectHost
          }
        }
      } onFailure errorHandler

  }

  override def postStop(): Unit = {
    if (cancelable != null)
      cancelable.cancel()
  }
}
