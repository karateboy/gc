package models

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Cancellable, Props}
import models.ModelHelper._
import play.api.{Configuration, Logger}

import javax.inject.Inject

case class Adam6250Selector(monitorOp: MonitorOp, alarmOp: AlarmOp, actorSystem: ActorSystem)
                           (gcName: String, config: Configuration) extends SelectorModel {
  val host: String = config.getString("host").get
  val max = 8
  for (id <- 1 to max) {
    monitorOp.getMonitorValueByName(gcName, id)
  }
  val worker: ActorRef = actorSystem.actorOf(Props(new Adam6250Collector(alarmOp)(host, max, this)), name = s"moxaAgent_${gcName}")
  worker ! ConnectHost

  @volatile var streamNum = 1

  def getStreamNum(): Int = streamNum

  def setStreamNum(v: Int): Unit = {}

  val canSetStream = false

  def modifyStreamNum(v: Int): Unit = {
    streamNum = v
  }

}

class Adam6250Collector @Inject()(alarmOp: AlarmOp)(host: String, maxStreamNum: Int, selector: Adam6250Selector) extends Actor with ActorLogging {
  var cancelable: Cancellable = _

  import com.serotonin.modbus4j._
  import com.serotonin.modbus4j.ip.IpParameters

  import scala.concurrent._

  def receive: Receive = handler(MonitorStatus.NormalStat, None)

  import context.dispatcher

  var errorCount = 0

  def handler(collectorState: String, masterOpt: Option[ModbusMaster]): Receive = {
    case ConnectHost =>
      Logger.info(s"connect to Adama 6250")
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
            cancelable = context.system.scheduler.scheduleOnce(Duration(3, SECONDS), self, Collect)
          } catch {
            case ex: Exception =>
              Logger.error(ex.getMessage, ex)
              Logger.info("Try again 1 min later...")
              //Try again
              import scala.concurrent.duration._
              cancelable = context.system.scheduler.scheduleOnce(Duration(1, MINUTES), self, ConnectHost)
          }

        }
      } onFailure errorHandler

    case Collect =>
      Future {
        blocking {
          try {
            import com.serotonin.modbus4j.BatchRead
            import com.serotonin.modbus4j.locator.BaseLocator

            // DI Value ...
            {
              val batch = new BatchRead[Integer]
              for (idx <- 0 to 7)
                batch.addLocator(idx, BaseLocator.inputStatus(1, idx))

              batch.setContiguousRequests(true)

              val rawResult = masterOpt.get.send(batch)
              val result =
                for (idx <- 0 to 7) yield rawResult.getValue(idx).asInstanceOf[Boolean]

              result match {
                case Seq(_, _, _, _, _, _, false, true) =>
                  errorCount = 0
                  selector.modifyStreamNum(6)

                case Seq(true, false, true, false, false, false, true, false) =>
                  errorCount = 0
                  selector.modifyStreamNum(1)

                case Seq(false, true, true, false, false, false, true, false) =>
                  errorCount = 0
                  selector.modifyStreamNum(2)

                case Seq(_, _, false, true, false, false, true, false) =>
                  errorCount = 0
                  selector.modifyStreamNum(3)

                case Seq(_, _, false, false, true, false, true, false) =>
                  errorCount = 0
                  selector.modifyStreamNum(4)

                case Seq(_, _, false, false, false, true, true, false) =>
                  errorCount = 0
                  selector.modifyStreamNum(5)

                case _ =>
                  errorCount += 1
                  if (errorCount > 10)
                    alarmOp.log(None, None, s"選樣錯誤! $result")
              }
            }

            import scala.concurrent.duration._
            cancelable = context.system.scheduler.scheduleOnce(scala.concurrent.duration.Duration(3, SECONDS), self, Collect)
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
