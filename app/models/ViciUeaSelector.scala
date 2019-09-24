package models
import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import scala.concurrent.ExecutionContext.Implicits.global

case object IssueCPcmd
case object ReadCurrentStreamNum
case class SetStreamNum(v: Int)

class ViciUeaSelector extends SelectorModel {

  val worker = Akka.system.actorOf(Props(new UeaSelectorWorker(this)), name = "selectorAgent")
  worker ! IssueCPcmd

  @volatile private var streamNum = 1
  def getStreamNum(): Int = streamNum
  def setStreamNum(v: Int) {
    worker ! SetStreamNum(v)
  }

}

class UeaSelectorWorker(selector: ViciUeaSelector) extends Actor {
  val comPort = Play.current.configuration.getInt("selector.viciUea.com").get
  Logger.info("UEA is set to ${comPort}")
  val serial = SerialComm.open(comPort)

  def receive = {
    case IssueCPcmd =>
      try {
        val readCmd = s"CP\r"
        serial.os.write(readCmd.getBytes)
      } catch {
        case ex: Throwable =>
          Logger.error("write CP cmd failed", ex)
      }
      import scala.concurrent.duration._
      context.system.scheduler.scheduleOnce(
        scala.concurrent.duration.Duration(1, scala.concurrent.duration.SECONDS),
        self, ReadCurrentStreamNum)

    case ReadCurrentStreamNum =>
      try {
        val ret = serial.getLine2
        if (!ret.isEmpty) {
          val cpNum = Integer.valueOf(ret.head.substring(2), 10).toInt
          if (cpNum != selector.getStreamNum()) {
            Logger.info(s"Selector stream number change to $cpNum")
            selector.setStreamNum(cpNum)
          }
        }
      } catch {
        case ex: Throwable =>
          Logger.error("read stream failed", ex)
      }
      context.system.scheduler.scheduleOnce(
        scala.concurrent.duration.Duration(1, scala.concurrent.duration.SECONDS),
        self, IssueCPcmd)

    case SetStreamNum(v) =>
      try {
        import java.util.Locale
        val setCmd = s"GO%02d\r".format(v)
        serial.os.write(setCmd.getBytes)
      } catch {
        case ex: Throwable =>
          Logger.error("write CP cmd failed", ex)
      }
  }
}