package models

import akka.actor._
import play.api._

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{FiniteDuration, MINUTES, SECONDS}

case object IssueCP

case object ReadCurrentStreamNum

case class SetStreamNum(v: Int)

class ViciUeaSelector(monitorOp: MonitorOp, actorSystem: ActorSystem)(gcName: String, config: Configuration) extends SelectorModel {
  val max: Int = config.getInt("max").get
  val comPort: Int = config.getInt("com").get
  val worker: ActorRef = actorSystem.actorOf(Props(new UeaSelectorWorker(monitorOp)(this)), name = s"selector_${gcName}")

  def getGcName: String = gcName

  @volatile var streamNum = 1

  def getStreamNum(): Int = streamNum

  def setStreamNum(v: Int): Unit = {
    if (v <= max)
      worker ! SetStreamNum(v)
  }

  val canSetStream = true

  def modifyStreamNum(v: Int): Unit = {
    Logger.info(s"Selector change to $v")
    streamNum = v
  }

}

class UeaSelectorWorker @Inject()(monitorOp: MonitorOp)(selector: ViciUeaSelector) extends Actor {
  val max: Int = selector.max
  for (id <- 1 to max) {
    monitorOp.getMonitorValueByName(selector.getGcName, id)
  }

  private val comPort = selector.comPort
  Logger.info(s"UEA is set to $comPort")
  val serial: SerialComm = SerialComm.open(comPort)

  self ! IssueCP

  private var timerOption: Option[Cancellable] = None

  def receive: Receive = {
    case IssueCP =>
      try {
        val readCmd = s"CP\r"
        serial.os.write(readCmd.getBytes)
        timerOption = Some(context.system.scheduler.scheduleOnce(FiniteDuration(2, SECONDS), self, ReadCurrentStreamNum))
      } catch {
        case ex: Throwable =>
          Logger.error("write CP cmd failed", ex)
      }

    case ReadCurrentStreamNum =>
      try {
        val ret = serial.getLine2
        if (ret.nonEmpty) {
          val cpNum = Integer.valueOf(ret.head.trim.substring(2), 10).toInt
          if (cpNum != selector.getStreamNum()) {
            Logger.info(s"Selector stream number change to $cpNum")
            selector.modifyStreamNum(cpNum)
          }
        }
      } catch {
        case ex: Throwable =>
          Logger.error("read stream failed", ex)
      }
      timerOption = Some(context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, IssueCP))

    case SetStreamNum(v) =>
      try {
        val setCmd = s"GO%02d\r".format(v)
        serial.os.write(setCmd.getBytes)
        selector.modifyStreamNum(v)

        // Read current position after 2 seconds
        for (timer <- timerOption)
          timer.cancel()

        timerOption = Some(context.system.scheduler.scheduleOnce(FiniteDuration(2, SECONDS), self, IssueCP))
      } catch {
        case ex: Throwable =>
          Logger.error("write GO cmd failed", ex)
      }

  }

  override def postStop(): Unit = {
    Logger.info("UEA selector worker stopped")
    serial.close
    for (timer <- timerOption)
      timer.cancel()
  }
}