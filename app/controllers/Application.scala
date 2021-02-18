package controllers

import play.api._
import play.api.mvc._
import play.api.mvc.WebSocket
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.Play.current
import play.api.data._
import play.api.data.Forms._
import play.api.libs.ws._
import play.api.libs.ws.ning.NingAsyncHttpClientConfigBuilder
import scala.concurrent.Future
import play.api.libs.json._
import com.github.nscala_time.time.Imports._
import Highchart._
import models._
import ModelHelper._

object Application extends Controller {

  val title = "特殊性工業區監測系統"

  def newUser = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      adminOnly({
        val newUserParam = request.body.validate[User]

        newUserParam.fold(
          error => {
            Logger.error(JsError.toJson(error).toString())
            Future {
              BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
            }
          },
          param => {
            val f = User.newUser(param)
            val requestF =
              for (result <- f) yield {
                Ok(Json.obj("ok" -> true))
              }

            requestF.recover({
              case _: Throwable =>
                Logger.info("recover from newUser error...")
                Ok(Json.obj("ok" -> false))
            })
          })
      })
  }

  def deleteUser(email: String) = Security.Authenticated.async {
    implicit request =>
      adminOnly({
        val f = User.deleteUser(email)
        val requestF =
          for (result <- f) yield {
            Ok(Json.obj("ok" -> (result.getDeletedCount == 1)))
          }

        requestF.recover({
          case _: Throwable =>
            Logger.info("recover from deleteUser error...")
            Ok(Json.obj("ok" -> false))
        })
      })
  }

  def updateUser(id: String) = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      val userParam = request.body.validate[User]

      userParam.fold(
        error => {
          Future {
            Logger.error(JsError.toJson(error).toString())
            BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
          }
        },
        param => {
          val f = User.updateUser(param)
          for (ret <- f) yield {
            Ok(Json.obj("ok" -> (ret.getMatchedCount == 1)))
          }
        })
  }

  def getAllUsers = Security.Authenticated.async {
    val userF = User.getAllUsersFuture()
    for (users <- userF) yield Ok(Json.toJson(users))
  }

  def adminOnly[A, B <: UserInfo](permited: Future[Result])(implicit request: play.api.mvc.Security.AuthenticatedRequest[A, B]) = {
    val userInfoOpt = Security.getUserinfo(request)
    if (userInfoOpt.isEmpty)
      Future {
        Forbidden("No such user!")
      }
    else {
      val userInfo = userInfoOpt.get
      val userF = User.getUserByIdFuture(userInfo.id)
      val userOpt = waitReadyResult(userF)
      if (userOpt.isEmpty || userOpt.get.groupId != Group.adminID)
        Future {
          Forbidden("無權限!")
        }
      else {
        permited
      }
    }
  }

  import scala.concurrent.ExecutionContext.Implicits.global

  def getGroupInfoList = Action {
    val infoList = Group.getInfoList
    implicit val write = Json.writes[GroupInfo]
    Ok(Json.toJson(infoList))
  }

  val path = current.path.getAbsolutePath + "/importEPA/"

  def importEpa103 = Action {
    Epa103Importer.importData(path)
    Ok(s"匯入 $path")
  }

  case class EditData(id: String, data: String)

  def saveMonitorTypeConfig() = Security.Authenticated {
    implicit request =>
      try {
        val mtForm = Form(
          mapping(
            "id" -> text,
            "data" -> text)(EditData.apply)(EditData.unapply))

        val mtData = mtForm.bindFromRequest.get
        val mtInfo = mtData.id.split(":")
        val mt = MonitorType.withName(mtInfo(0))

        MonitorType.updateMonitorType(mt, mtInfo(1), mtData.data)

        Ok(mtData.data)
      } catch {
        case ex: Throwable =>
          Logger.error(ex.getMessage, ex)
          BadRequest(ex.toString)
      }
  }

  def saveMonitorConfig() = Security.Authenticated {
    implicit request =>
      try {
        val mtForm = Form(
          mapping(
            "id" -> text,
            "data" -> text)(EditData.apply)(EditData.unapply))

        val mtData = mtForm.bindFromRequest.get
        val mtInfo = mtData.id.split(":")
        val m = Monitor.withName(mtInfo(0))

        Monitor.updateMonitor(m, mtInfo(1), mtData.data)

        Ok(mtData.data)
      } catch {
        case ex: Throwable =>
          Logger.error(ex.getMessage, ex)
          BadRequest(ex.toString)
      }
  }

  def monitorTypeList = Security.Authenticated {
    implicit request =>
      val monitorTypes = MonitorType.mtvList map {
        MonitorType.map
      }
      Ok(Json.toJson(monitorTypes))
  }

  def monitorList = Security.Authenticated {
    implicit request =>
      val monitors = Monitor.mvList map {
        Monitor.map
      }
      // val actualMonitors = monitors.filter(m => m._id.toInt <= Selector.model.max)
      Ok(Json.toJson(monitors))
  }

  case class GcName(key: String, name: String)

  def gcList = Security.Authenticated.async {
    implicit request =>
      implicit val writes = Json.writes[GcName]

      for (gcNameList <- SysConfig.getGcNameList()) yield {
        val gcList = Monitor.indParkList
        val keyName: Seq[GcName] = gcList.zip(gcNameList).map { entry => GcName(entry._1, entry._2) }
        Ok(Json.toJson(keyName))
      }
  }

  def indParkList = Security.Authenticated.async {
    implicit request =>
      val userOptF = User.getUserByIdFuture(request.user.id)
      for {
        userOpt <- userOptF if userOpt.isDefined
        groupInfo = Group.getGroupInfo(userOpt.get.groupId)
      } yield {

        val indParks =
          groupInfo.privilege.allowedIndParks

        Ok(Json.toJson(indParks))
      }
  }

  def reportUnitList = Security.Authenticated {
    implicit val ruWrite = Json.writes[ReportUnit]
    Ok(Json.toJson(ReportUnit.values.toList.sorted.map {
      ReportUnit.map
    }))
  }

  def updateMonitorType = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val mtResult = request.body.validate[MonitorType]

      mtResult.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        mt => {
          MonitorType.upsertMonitorType(mt)
          MonitorType.refreshMtv
          Ok(Json.obj("ok" -> true))
        })
  }

  def upsertMonitorType(id: String) = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val mtResult = request.body.validate[MonitorType]

      mtResult.fold(
        error => {
          Logger.error(JsError.toJson(error).toString())
          BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
        },
        mt => {
          MonitorType.upsertMonitorType(mt)
          MonitorType.refreshMtv
          Ok(Json.obj("ok" -> true))
        })
  }

  def updateMonitor = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      val mtResult = request.body.validate[Monitor]

      mtResult.fold(
        error => {
          Future {
            Logger.error(JsError.toJson(error).toString())
            BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
          }
        },
        monitor => {
          for (ret <- Monitor.upsert(monitor)) yield {
            Monitor.refresh
            Ok(Json.obj("ok" -> true))
          }
        })
  }

  def upsertMonitor(id: String) = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      val mtResult = request.body.validate[Monitor]

      mtResult.fold(
        error => {
          Future {
            Logger.error(JsError.toJson(error).toString())
            BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
          }
        },
        monitor => {
          for (ret <- Monitor.upsert(monitor)) yield {
            Monitor.refresh
            Ok(Json.obj("ok" -> true))
          }
        })
  }

  def menuRightList = Security.Authenticated.async {
    implicit request =>
      val userOptF = User.getUserByIdFuture(request.user.id)
      for {
        userOpt <- userOptF if userOpt.isDefined
        groupInfo = Group.getGroupInfo(userOpt.get.groupId)
      } yield {
        val menuRightList =
          groupInfo.privilege.allowedMenuRights.map { v => MenuRight(v, MenuRight.map(v)) }

        Ok(Json.toJson(menuRightList))
      }
  }

  def testAlarm = Security.Authenticated.async {
    implicit request =>
      for {
        ret <- Alarm.log(None, None, "Test alarm")
      } yield {
        Ok("Test alarm!")
      }
  }

  //Websocket

  import GcWebSocketActor._

  def gcWebSocket = WebSocket.acceptWithActor[InEvent, OutEvent] { request =>
    out =>
      GcWebSocketActor.props(out)
  }

  def getDataPeriod = Security.Authenticated.async {
    for (ret <- SysConfig.getDataPeriod()) yield Ok(Json.toJson(ret))
  }

  case class ParamInt(value: Int)

  def setDataPeriod = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      implicit val reads = Json.reads[ParamInt]
      val ret = request.body.validate[ParamInt]
      ret.fold(
        error => {
          Future {
            Logger.error(JsError.toJson(error).toString())
            BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
          }
        },
        param => {
          for (ret <- SysConfig.setDataPeriod(param.value)) yield {
            Ok(Json.obj("ok" -> true))
          }
        })
  }

  def getOperationMode() = Security.Authenticated.async {
    for (ret <- SysConfig.getOperationMode()) yield {
      Ok(Json.obj("mode" -> ret))
    }
  }

  case class OpMode(mode:Int)
  def putOperationMode= Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      implicit val reads = Json.reads[OpMode]
      val modeParam = request.body.validate[OpMode]

      modeParam.fold(
        error => {
          Future {
            Logger.error(JsError.toJson(error).toString())
            BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
          }
        },
        param => {
          Logger.info(s"set operation mode = ${param.mode}")
          for(gcConfig <- GcAgent.gcConfigList)
            Exporter.exportPlc(gcConfig, param.mode)

          for (ret <- SysConfig.setOperationMode(param.mode)) yield {
            Ok(Json.obj("mode" -> param.mode))
          }
        })

  }

  def setGcName() = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      implicit val reads = Json.reads[GcName]
      val gcParam = request.body.validate[GcName]

      gcParam.fold(
        error => {
          Future {
            Logger.error(JsError.toJson(error).toString())
            BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
          }
        },
        param => {
          for (gcList: Seq[String] <- SysConfig.getGcNameList()) yield {
            val idx = Monitor.indParkList.indexOf(param.key)
            val newGcList: Seq[String] = gcList.patch(idx, Seq(param.name), 1)
            SysConfig.setGcNameList(newGcList)
            Ok(Json.obj("ok" -> true))
          }
        })
  }

  case class StopWarn(stopWarn: Boolean)

  def getStopWarn() = Security.Authenticated.async {
    implicit request =>
      implicit val writes = Json.writes[StopWarn]
      for (stopWarn <- SysConfig.getStopWarn()) yield {
        Ok(Json.toJson(StopWarn(stopWarn)))
      }
  }

  def setStopWarn() = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      implicit val reads = Json.reads[StopWarn]
      val ret = request.body.validate[StopWarn]

      ret.fold(
        error => {
          Future {
            Logger.error(JsError.toJson(error).toString())
            BadRequest(Json.obj("ok" -> false, "msg" -> JsError.toJson(error).toString()))
          }
        },
        param => {
          for (ret <- SysConfig.setStopWarn(param.stopWarn)) yield
            Ok(Json.obj("ok" -> true))
        }
      )
  }

  def redirectRoot(ignore:String) = Action {
    Redirect("/")
  }
}
