package models
import play.api._
import play.api.libs._
import akka.actor._
object GcWebSocketActor {
  def props(out: ActorRef) = Props(new GcWebSocketActor(out))

}

class GcWebSocketActor(out:ActorRef) extends Actor {
  import GcWebSocketActor._
  Logger.info("Websocket actor start!")

  def receive = {
    case msg: String =>
      Logger.info(s"recv ${msg}")
  }
}