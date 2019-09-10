package controllers

import play.api._
import play.api.mvc._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.Logger
import models.User
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
case class Credential(userName: String, password: String)

/**
 * @author user
 */
object Login extends Controller {
  implicit val credentialReads = Json.reads[Credential]
  
  def authenticate = Action.async(BodyParsers.parse.json) {
    implicit request =>
      val credentail = request.body.validate[Credential]
      credentail.fold(
        {
          error =>
            Future {
              BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error)))
            }
        },
        crd => {
          for (optUser <- User.getUserByIdFuture(crd.userName)) yield {
            if (optUser.isEmpty || optUser.get.password != crd.password)
              Ok(Json.obj("ok" -> false, "msg" -> "密碼或帳戶錯誤"))
            else {
              val user = optUser.get
              implicit val userInfoWrite = Json.writes[UserInfo]
              val userInfo = UserInfo(user._id, user.name, "Admin")
              Ok(Json.obj("ok" -> true, "user" -> userInfo)).withSession(Security.setUserinfo(request, userInfo))
            }
          }
        })
  }

  def getUserInfo = Security.Authenticated {
    implicit request =>
      val user = request.user
      implicit val writes = Json.writes[UserInfo]
      Ok(Json.toJson(user))
  }
  
  def logout = Action {
    Ok(Json.obj(("ok" -> true))).withNewSession
  }
}