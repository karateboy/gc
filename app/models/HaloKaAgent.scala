package models

import akka.actor.{Actor, Cancellable, Props}
import com.github.nscala_time.time.Imports.Duration
import org.joda.time.DateTime
import org.mongodb.scala.result.UpdateResult
import play.api.Logger

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{FiniteDuration, MINUTES, SECONDS}
import scala.concurrent.{Future, blocking}

object HaloKaAgent {
  def prof(config: HaloKaConfig, gcConfig: GcConfig) = Props(classOf[HaloKaAgent], config, gcConfig)

  case object OpenCom

  case object ReadData
}

class HaloKaAgent(config: HaloKaConfig, gcConfig: GcConfig) extends Actor {
  Logger.info(s"HaloKaAget com${config.com} speed:${config.speed} ${config.MonitorType}")
  import HaloKaAgent._

  self ! OpenCom
  var serialOpt: Option[SerialComm] = None
  var timer: Option[Cancellable] = None

  override def receive: Receive = {
    case OpenCom =>
      try {
        serialOpt = Some(SerialComm.open(config.com, config.speed))
        timer = Some(context.system.scheduler.schedule(FiniteDuration(1, SECONDS), FiniteDuration(1, MINUTES), self, ReadData))
      } catch {
        case ex: Throwable =>
          Logger.error("Fail to open com port try 1 min later.", ex)
          context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, OpenCom)
      }
    case ReadData =>
      Future {
        blocking {
          try {
            for (serial <- serialOpt) {
              val readCmd = s"CONC\n"
              serial.os.write(readCmd.getBytes())

              var ret = serial.getLine2
              val startTime = DateTime.now
              while (ret.isEmpty) {
                val elapsedTime = new Duration(startTime, DateTime.now)
                if (elapsedTime.getStandardSeconds > 1) {
                  throw new Exception("Read timeout!")
                }
                ret = serial.getLine2
              }
              if (!ret.isEmpty) {
                val value = ret.head.trim.toDouble
                insertRecord(value)
              }
            }
          } catch {
            case ex: Throwable =>
              Logger.error("failed to read data", ex)
          }
        }
      }
  }

  def insertRecord(mtValue: Double): Future[UpdateResult] = {
    import org.mongodb.scala.model._

    val monitor = Monitor.getMonitorValueByName(gcConfig.gcName, gcConfig.selector.get)
    val mt = MonitorType.getMonitorTypeValueByName(config.MonitorType, "ppb")
    val dt = DateTime.now.withSecondOfMinute(0).withMillisOfSecond(0)
    val doc = Record.toDocument(monitor, dt, List((mt, (mtValue, MonitorStatus.NormalStat))))
    Record.upsertRecord(doc)(Record.MinCollection)
  }

  override def postStop(): Unit = {
    for (t <- timer)
      t.cancel()

    super.postStop()
  }
}
