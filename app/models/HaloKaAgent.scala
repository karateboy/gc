package models

import akka.actor.{Actor, Cancellable, Props}
import com.github.nscala_time.time.Imports.Duration
import com.google.inject.assistedinject.Assisted
import org.joda.time.DateTime
import org.mongodb.scala.result.UpdateResult
import play.api.Logger

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{FiniteDuration, MINUTES, SECONDS}
import scala.concurrent.{Future, blocking}

object HaloKaAgent {
  //def prof(config: HaloKaConfig, gcConfig: GcConfig) = Props(classOf[HaloKaAgent], config, gcConfig)

  trait Factory {
    def apply(@Assisted("config") config: HaloKaConfig, @Assisted("gcConfig") gcConfig: GcConfig): Actor
  }

  private case object OpenCom

  private case object ReadData
}

class HaloKaAgent @Inject()(monitorOp: MonitorOp, monitorTypeOp: MonitorTypeOp, recordOp: RecordOp)
                           (@Assisted("config") config: HaloKaConfig, @Assisted("gcConfig") gcConfig: GcConfig) extends Actor {
  Logger.info(s"HaloKa Agent com${config.com} speed:${config.speed} ${config.MonitorType}")
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
              val readCmd = s"CONC\r"
              serial.os.write(readCmd.getBytes())

              var ret = serial.getLine2
              val startTime = DateTime.now
              while (ret.isEmpty) {
                val elapsedTime = new Duration(startTime, DateTime.now)
                if (elapsedTime.getStandardSeconds > 3) {
                  throw new Exception("Read timeout!")
                }
                Thread.sleep(100)
                ret = serial.getLine2
              }
              if (ret.nonEmpty) {
                try{
                  val value = ret.head.trim.toDouble
                  insertRecord(value)
                }catch{
                  case ex:Throwable=>
                    Logger.info(ex.getMessage)
                    self ! ReadData
                }

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

    val monitor = monitorOp.getMonitorValueByName(gcConfig.gcName, gcConfig.selector.get)
    val mt = monitorTypeOp.getMonitorTypeValueByName(config.MonitorType, "ppb")
    val dt = DateTime.now.withSecondOfMinute(0).withMillisOfSecond(0)
    val doc = recordOp.toDocument(monitor, dt, List((mt, (mtValue, MonitorStatus.NormalStat))))
    recordOp.upsertRecord(doc)(RecordOp.MinCollection)
  }

  override def postStop(): Unit = {
    for (t <- timer)
      t.cancel()

    super.postStop()
  }
}
