package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import scala.concurrent.ExecutionContext.Implicits.global
import akka.pattern.{ ask, pipe }

case object IssueCPcmd
case object ReadCurrentStreamNum
case class SetStreamNum(v: Int)

class ViciUeaSelector extends SelectorModel {
  val max = current.configuration.getInt("selector.viciUea.max").get
  val worker = Akka.system.actorOf(Props(new UeaSelectorWorker(this)), name = "selectorAgent")

  @volatile var streamNum = 1
  def getStreamNum(): Int = streamNum
  def setStreamNum(v: Int) {
    worker ! SetStreamNum(v)
  }
  val canSetStream = true

  def modifyStreamNum(v: Int) {
    streamNum = v
  }

}

class UeaSelectorWorker(selector: ViciUeaSelector) extends Actor {
  val max = current.configuration.getInt("selector.viciUea.max").get
  for (id <- 1 to max) {
    Monitor.getMonitorValueByName(id)
  }

  val comPort = Play.current.configuration.getInt("selector.viciUea.com").get
  Logger.info(s"UEA is set to ${comPort}")
  val serial = SerialComm.open(comPort)

  var timer = context.system.scheduler.scheduleOnce(
    scala.concurrent.duration.Duration(0, scala.concurrent.duration.MICROSECONDS),
    self, IssueCPcmd)

  def receive = {
    case IssueCPcmd =>
      try {
        val readCmd = s"CP\r"
        serial.os.write(readCmd.getBytes)
      } catch {
        case ex: Throwable =>
          Logger.error("write CP cmd failed", ex)
      }
      timer = context.system.scheduler.scheduleOnce(
        scala.concurrent.duration.Duration(500, scala.concurrent.duration.MICROSECONDS),
        self, ReadCurrentStreamNum)

    case ReadCurrentStreamNum =>
      try {
        val ret = serial.getLine2
        if (!ret.isEmpty) {
          val cpNum = Integer.valueOf(ret.head.substring(2), 10).toInt
          if (cpNum != selector.getStreamNum()) {
            Logger.info(s"Selector stream number change to $cpNum")
            selector.modifyStreamNum(cpNum)
          }
        }
      } catch {
        case ex: Throwable =>
          Logger.error("read stream failed", ex)
      }
      timer = context.system.scheduler.scheduleOnce(
        scala.concurrent.duration.Duration(500, scala.concurrent.duration.MICROSECONDS),
        self, IssueCPcmd)

    case SetStreamNum(v) =>
      try {
        import java.util.Locale
        val setCmd = s"GO%02d\r".format(v)
        serial.os.write(setCmd.getBytes)
        selector.modifyStreamNum(v)
      } catch {
        case ex: Throwable =>
          Logger.error("write GO cmd failed", ex)
      }

  }
  override def postStop() {
    timer.cancel()
  }
}