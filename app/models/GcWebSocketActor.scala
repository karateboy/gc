package models
import akka.actor._
import play.api._
import play.api.libs.json._
import play.api.mvc.WebSocket

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global

case class InEvent(msgType: String)
case class OutEvent(mutation: Option[String], alarms: Seq[Alarm])
object GcWebSocketActor {
  private var actorList = List.empty[ActorRef]
  import play.api.mvc.WebSocket.FrameFormatter

  implicit val alarmFmt: OFormat[Alarm] = Json.format[Alarm]
  implicit val inEventFmt: OFormat[InEvent] = Json.format[InEvent]
  implicit val outEventFmt: OFormat[OutEvent] = Json.format[OutEvent]
  //implicit val inEventFrameFormatter: WebSocket.MessageFlowTransformer[InEvent, InEvent] = FrameFormatter.jsonFrame[InEvent]
  //implicit val outEventFrameFormatter: WebSocket.MessageFlowTransformer[OutEvent, OutEvent] = FrameFormatter.jsonFrame[OutEvent]

  private val MUTATION_REPORT_ALARM = Some("ReportAlarm")

  private case object ReportAlarm
  
  def notifyAllActors(): Unit = {
    actorList.foreach(_!ReportAlarm)
  }
}

class GcWebSocketActor @Inject()(sysConfig: SysConfig, alarmOp: AlarmOp, exporter: Exporter)(out: ActorRef)  extends Actor {
  import GcWebSocketActor._
  Logger.info("Websocket actor start!")
  actorList = actorList :+ self
  self ! ReportAlarm

  def receive: Receive = {
    case ReportAlarm =>
      import java.time.Instant
      for {
        lastReadDate <- sysConfig.getAlarmLastRead
        alarms <- alarmOp.getList(lastReadDate.getTime, Instant.now().toEpochMilli)
      } {
        val evt = OutEvent(MUTATION_REPORT_ALARM, alarms)
        out ! evt
      }

    case evt: InEvent =>
      evt.msgType match{
        case "alarmRead" =>
          Logger.info(s"alarm read!")
          sysConfig.setAlarmLastRead()
          exporter.notifyAlarm(false)
      }
    case msg: String =>
      Logger.info(s"recv $msg")
  }

  override def postStop(): Unit = {
    actorList = actorList.filter(ref => ref != self)
  }
}