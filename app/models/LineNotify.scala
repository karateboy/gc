package models

import play.api.Logger
import play.api.libs.ws.WSClient
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
@Singleton
class LineNotify @Inject()(WSClient: WSClient) {
  val logger: Logger = play.api.Logger(getClass)
  def notify(token:String, msg:String): Future[Boolean] ={
    val f = WSClient.url("https://notify-api.line.me/api/notify").
      withHeaders("Authorization"-> s"Bearer $token",
        "Content-Type"->"application/x-www-form-urlencoded")
      .post(Map("message" -> Seq(msg)))

    for(ret<-f) yield {
      if(ret.status != 200)
        logger.error(ret.body)

      ret.status == 200
    }
  }
}
