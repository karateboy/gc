package controllers

import play.api.mvc._
import play.api.libs.json._
import models.UserOp

import javax.inject.Inject
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
case class Credential(userName: String, password: String)

/**
 * @author user
 */
class Login @Inject()(userOp: UserOp) extends Controller {
  implicit val credentialReads: Reads[Credential] = Json.reads[Credential]
  
  def authenticate: Action[JsValue] = Action.async(BodyParsers.parse.json) {
    implicit request =>
      val credential = request.body.validate[Credential]
      credential.fold(
        {
          error =>
            Future {
              BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error)))
            }
        },
        crd => {
          for (optUser <- userOp.getUserByIdFuture(crd.userName)) yield {
            if (optUser.isEmpty || optUser.get.password != crd.password)
              Ok(Json.obj("ok" -> false, "msg" -> "密碼或帳戶錯誤"))
            else {
              val user = optUser.get
              implicit val userInfoWrite: OWrites[UserInfo] = Json.writes[UserInfo]
              val userInfo = UserInfo(user._id, user.name, "Admin")
              Ok(Json.obj("ok" -> true, "user" -> userInfo)).withSession(Security.setUserinfo(request, userInfo))
            }
          }
        })
  }

  def getUserInfo: Action[AnyContent] = Security.Authenticated {
    implicit request =>
      val user = request.user
      implicit val writes: OWrites[UserInfo] = Json.writes[UserInfo]
      Ok(Json.toJson(user))
  }
  
  def logout: Action[AnyContent] = Action {
    Ok(Json.obj("ok" -> true)).withNewSession
  }
}