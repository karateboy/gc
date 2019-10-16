package models
import play.api._
import play.api.libs._
import akka.actor._
import play.api.libs.json._
import scala.concurrent.ExecutionContext.Implicits.global

case class InEvent(msgType: String)
case class OutEvent(mutation: Option[String], alarms: Seq[Alarm])
object GcWebSocketActor {
  var actorList = List.empty[ActorRef]
  import play.api.mvc.WebSocket.FrameFormatter

  implicit val alarmFmt = Json.format[Alarm]
  implicit val inEventFmt = Json.format[InEvent]
  implicit val outEventFmt = Json.format[OutEvent]
  implicit val inEventFrameFormatter = FrameFormatter.jsonFrame[InEvent]
  implicit val outEventFrameFormatter = FrameFormatter.jsonFrame[OutEvent]

  val MUTATION_REPORT_ALARM = Some("ReportAlarm")

  def props(out: ActorRef) = {
    val ref = Props(new GcWebSocketActor(out))
    ref
  }

  case object ReportAlarm
  
  def notifyAllActors = {
    actorList.foreach(_!ReportAlarm)
  }
}

class GcWebSocketActor(out: ActorRef) extends Actor {
  import GcWebSocketActor._
  Logger.info("Websocket actor start!")
  actorList = actorList :+ self
  self ! ReportAlarm

  def receive = {
    case ReportAlarm =>
      import java.time.Instant
      for {
        lastReadDate <- SysConfig.getAlarmLastRead()
        alarms <- Alarm.getList(lastReadDate.getTime, Instant.now().toEpochMilli())
      } {
        val evt = OutEvent(MUTATION_REPORT_ALARM, alarms)
        out ! evt
      }

    case evt: InEvent =>
      {
         evt.msgType match{
           case "alarmRead" =>
             Logger.info(s"alarm read!")
             SysConfig.setAlarmLastRead()           
         }
      }
    case msg: String =>
      Logger.info(s"recv ${msg}")
  }

  override def postStop() {
    actorList = actorList.filter(ref => ref != self)
  }
}