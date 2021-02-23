package models

import play.api._
import akka.actor._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import scala.concurrent.ExecutionContext.Implicits.global
import akka.pattern.{ask, pipe}

case object IssueCPcmd

case object ReadCurrentStreamNum

case class SetStreamNum(v: Int)

class ViciUeaSelector(gcName: String, config: Configuration) extends SelectorModel {
  val max = config.getInt("max").get
  val comPort = config.getInt("com").get
  val worker = Akka.system.actorOf(Props(new UeaSelectorWorker(this)), name = s"selector_${gcName}")

  def getGcName = gcName

  @volatile var streamNum = 1

  def getStreamNum(): Int = streamNum

  def setStreamNum(v: Int) {
    if(v <= max)
      worker ! SetStreamNum(v)
  }

  val canSetStream = true

  def modifyStreamNum(v: Int) {
    streamNum = v
  }

}

class UeaSelectorWorker(selector: ViciUeaSelector) extends Actor {
  val max = selector.max
  for (id <- 1 to max) {
    Monitor.getMonitorValueByName(selector.getGcName, id)
  }

  val comPort = selector.comPort
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
      timer = context.system.scheduler.scheduleOnce(
        scala.concurrent.duration.Duration(500, scala.concurrent.duration.MICROSECONDS),
        self, IssueCPcmd)

    case SetStreamNum(v) =>
      try {
        import java.util.Locale
        val setCmd = s"GO%02d\r".format(v)
        serial.os.write(setCmd.getBytes)
        selector.modifyStreamNum(v)

        // Read current position after 2 seconds
        timer.cancel()
        timer = context.system.scheduler.scheduleOnce(
          scala.concurrent.duration.Duration(2, scala.concurrent.duration.SECONDS),
          self, IssueCPcmd)
      } catch {
        case ex: Throwable =>
          Logger.error("write GO cmd failed", ex)
      }

  }

  override def postStop() {
    timer.cancel()
  }
}