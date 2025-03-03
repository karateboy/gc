package controllers

import models.ModelHelper._
import models._
import play.api._
import play.api.data.Forms._
import play.api.data._
import play.api.libs.json._
import play.api.mvc._

import javax.inject._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class Application @Inject()(userOp: UserOp,
                            monitorTypeOp: MonitorTypeOp,
                            monitorOp: MonitorOp,
                            exporter: Exporter,
                            alarmOp: AlarmOp,
                            lineNotify: LineNotify,
                            sysConfig: SysConfig) extends Controller {

  import userOp._

  def newUser: Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      adminOnly({
        val newUserParam = request.body.validate[User]

        newUserParam.fold(
          error => handleJsonValidateErrorFuture(error),
          param => {
            val f = userOp.newUser(param)
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

  def deleteUser(email: String): Action[AnyContent] = Security.Authenticated.async {
    implicit request =>
      adminOnly({
        val f = userOp.deleteUser(email)
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

  def updateUser(id: String): Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      val userParam = request.body.validate[User]

      userParam.fold(
        error => handleJsonValidateErrorFuture(error),
        param => {
          val f = userOp.updateUser(param)
          for (ret <- f) yield {
            Ok(Json.obj("ok" -> (ret.getMatchedCount == 1)))
          }
        })
  }

  def getAllUsers: Action[AnyContent] = Security.Authenticated.async {
    val userF = userOp.getAllUsersFuture
    for (users <- userF) yield Ok(Json.toJson(users))
  }

  private def adminOnly[A, B <: UserInfo](permited: Future[Result])(implicit request: play.api.mvc.Security.AuthenticatedRequest[A, B]): Future[Result] = {
    val userInfoOpt = Security.getUserinfo(request)
    if (userInfoOpt.isEmpty)
      Future {
        Forbidden("No such user!")
      }
    else {
      val userInfo = userInfoOpt.get
      val userF = userOp.getUserByIdFuture(userInfo.id)
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

  def getGroupInfoList: Action[AnyContent] = Action {
    val infoList = Group.getInfoList
    implicit val write: OWrites[GroupInfo] = Json.writes[GroupInfo]
    Ok(Json.toJson(infoList))
  }

  private case class EditData(id: String, data: String)

  def saveMonitorTypeConfig(): Action[AnyContent] = Security.Authenticated {
    implicit request =>
      try {
        val mtForm = Form(
          mapping(
            "id" -> text,
            "data" -> text)(EditData.apply)(EditData.unapply))

        val mtData = mtForm.bindFromRequest.get
        val mtInfo = mtData.id.split(":")
        val mt = monitorTypeOp.withName(mtInfo(0))

        monitorTypeOp.updateMonitorType(mt, mtInfo(1), mtData.data)

        Ok(mtData.data)
      } catch {
        case ex: Throwable =>
          Logger.error(ex.getMessage, ex)
          BadRequest(ex.toString)
      }
  }

  def saveMonitorConfig(): Action[AnyContent] = Security.Authenticated {
    implicit request =>
      try {
        val mtForm = Form(
          mapping(
            "id" -> text,
            "data" -> text)(EditData.apply)(EditData.unapply))

        val mtData = mtForm.bindFromRequest.get
        val mtInfo = mtData.id.split(":")
        val m = monitorOp.withName(mtInfo(0))

        monitorOp.updateMonitor(m, mtInfo(1), mtData.data)

        Ok(mtData.data)
      } catch {
        case ex: Throwable =>
          Logger.error(ex.getMessage, ex)
          BadRequest(ex.toString)
      }
  }

  import monitorOp._
  import monitorTypeOp._

  def monitorTypeList: Action[AnyContent] = Security.Authenticated {
    implicit request =>
      val monitorTypes = monitorTypeOp.mtvList map {
        monitorTypeOp.map
      }
      Ok(Json.toJson(monitorTypes.sortBy(mt=>mt.order)))
  }

  def monitorList: Action[AnyContent] = Security.Authenticated {
    implicit request =>
      Ok(Json.toJson(monitorOp.getMonitorList.sortBy(m => m.selector)))
  }

  private case class GcName(key: String, name: String)

  def gcList: Action[AnyContent] = Security.Authenticated.async {
    implicit request =>
      implicit val writes: OWrites[GcName] = Json.writes[GcName]

      for (gcNameList <- sysConfig.getGcNameList) yield {
        val gcList = monitorOp.indParkList
        val keyName: Seq[GcName] = gcList.zip(gcNameList).map { entry => GcName(entry._1, entry._2) }
        Ok(Json.toJson(keyName))
      }
  }

  def reportUnitList: Action[AnyContent] = Security.Authenticated {
    implicit val ruWrite: OWrites[ReportUnit] = Json.writes[ReportUnit]
    Ok(Json.toJson(ReportUnit.values.toList.sorted.map {
      ReportUnit.map
    }))
  }

  def updateMonitorType(): Action[JsValue] = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val mtResult = request.body.validate[MonitorType]

      mtResult.fold(
        error => handleJsonValidateError(error),
        mt => {
          monitorTypeOp.upsertMonitorType(mt)
          monitorTypeOp.refreshMtv()
          Ok(Json.obj("ok" -> true))
        })
  }

  def upsertMonitorType(id: String): Action[JsValue] = Security.Authenticated(BodyParsers.parse.json) {
    implicit request =>
      val mtResult = request.body.validate[MonitorType]

      mtResult.fold(
        error => handleJsonValidateError(error),
        mt => {
          monitorTypeOp.upsertMonitorType(mt)
          monitorTypeOp.refreshMtv()
          Ok(Json.obj("ok" -> true))
        })
  }

  def updateMonitor(): Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      val mtResult = request.body.validate[Monitor]

      mtResult.fold(
        error => handleJsonValidateErrorFuture(error),
        monitor => {
          for (ret <- monitorOp.upsert(monitor)) yield {
            monitorOp.refresh()
            Ok(Json.obj("ok" -> true))
          }
        })
  }

  def upsertMonitor(id: String): Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      val mtResult = request.body.validate[Monitor]

      mtResult.fold(
        error => handleJsonValidateErrorFuture(error),
        monitor => {
          for (ret <- monitorOp.upsert(monitor)) yield {
            monitorOp.refresh()
            Ok(Json.obj("ok" -> true))
          }
        })
  }

  def testAlarm: Action[AnyContent] = Security.Authenticated.async {
    implicit request =>
      for {
        _ <- alarmOp.log(None, None, "Test alarm")
      } yield {
        Ok("Test alarm!")
      }
  }

  //Websocket

  //  def gcWebSocket: WebSocket[InEvent, OutEvent] = WebSocket.acceptWithActor[InEvent, OutEvent] { request =>
  //    out =>
  //      GcWebSocketActor.props(out)
  //  }

  def getDataPeriod: Action[AnyContent] = Security.Authenticated.async {
    for (ret <- sysConfig.getDataPeriod) yield Ok(Json.toJson(ret))
  }

  private case class ParamInt(value: Int)

  private case class ParamStr(value: String, test: Option[Boolean])

  def setDataPeriod(): Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      implicit val reads: Reads[ParamInt] = Json.reads[ParamInt]
      val ret = request.body.validate[ParamInt]
      ret.fold(
        error => handleJsonValidateErrorFuture(error),
        param => {
          for (_ <- sysConfig.setDataPeriod(param.value)) yield {
            Ok(Json.obj("ok" -> true))
          }
        })
  }

  def getOperationMode: Action[AnyContent] = Security.Authenticated.async {
    for (ret <- sysConfig.getOperationMode) yield {
      Ok(Json.obj("mode" -> ret))
    }
  }

  private case class OpMode(mode: Int)

  def putOperationMode: Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      implicit val reads: Reads[OpMode] = Json.reads[OpMode]
      val modeParam = request.body.validate[OpMode]

      modeParam.fold(
        error => handleJsonValidateErrorFuture(error),
        param => {
          Logger.info(s"set operation mode = ${param.mode}")
          for (gcConfig <- GcAgent.gcConfigList)
            exporter.exportLocalModeToPLC(gcConfig, param.mode)

          for (ret <- sysConfig.setOperationMode(param.mode)) yield {
            Ok(Json.obj("mode" -> param.mode))
          }
        })

  }

  def setGcName(): Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      implicit val reads: Reads[GcName] = Json.reads[GcName]
      val gcParam = request.body.validate[GcName]

      gcParam.fold(
        error => handleJsonValidateErrorFuture(error),
        param => {
          for (gcList: Seq[String] <- sysConfig.getGcNameList) yield {
            val idx = monitorOp.indParkList.indexOf(param.key)
            val newGcList: Seq[String] = gcList.patch(idx, Seq(param.name), 1)
            sysConfig.setGcNameList(newGcList)
            Ok(Json.obj("ok" -> true))
          }
        })
  }

  private case class StopWarn(stopWarn: Boolean)

  def getStopWarn: Action[AnyContent] = Security.Authenticated.async {
    implicit request =>
      implicit val writes: OWrites[StopWarn] = Json.writes[StopWarn]
      for (stopWarn <- sysConfig.getStopWarn) yield {
        Ok(Json.toJson(StopWarn(stopWarn)))
      }
  }

  def setStopWarn(): Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      implicit val reads: Reads[StopWarn] = Json.reads[StopWarn]
      val ret = request.body.validate[StopWarn]

      ret.fold(
        error => handleJsonValidateErrorFuture(error),
        param => {
          for (_ <- sysConfig.setStopWarn(param.stopWarn)) yield
            Ok(Json.obj("ok" -> true))
        }
      )
  }

  def getCleanCount: Action[AnyContent] = Security.Authenticated.async {
    for (ret <- sysConfig.getCleanCount) yield {
      Ok(Json.toJson(ret))
    }
  }

  def setCleanCount(): Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      implicit val reads: Reads[ParamInt] = Json.reads[ParamInt]
      val ret = request.body.validate[ParamInt]

      ret.fold(
        error => handleJsonValidateErrorFuture(error),
        param => {
          for (_ <- sysConfig.setCleanCount(param.value)) yield
            Ok(Json.obj("ok" -> true))
        }
      )
  }

  def getLineToken: Action[AnyContent] = Security.Authenticated.async {
    for (ret <- sysConfig.getLineToken) yield {
      Ok(Json.toJson(ret))
    }
  }

  def setLineToken(): Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      implicit val reads: Reads[ParamStr] = Json.reads[ParamStr]
      val ret = request.body.validate[ParamStr]

      ret.fold(
        error => handleJsonValidateErrorFuture(error),
        param => {
          if (param.test.getOrElse(false)) {
            Logger.info(s"test line token = ${param.value}")
            for {
              ret <- lineNotify.notify(param.value, "LINE Notify 測試訊息")
            } yield
              if (ret)
                Ok(Json.obj("ok" -> true))
              else
                Ok(Json.obj("ok" -> false))
          } else {
            for (_ <- sysConfig.setLineToken(param.value)) yield
              Ok(Json.obj("ok" -> true))
          }
        }
      )
  }

  def getAnalysisLogPath: Action[AnyContent] = Security.Authenticated.async {
    for (ret <- sysConfig.getAnalysisLogPath) yield {
      Ok(Json.toJson(ret))
    }
  }

  def setAnalysisLogPath(): Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json) {
    implicit request =>
      implicit val reads: Reads[ParamStr] = Json.reads[ParamStr]
      val ret = request.body.validate[ParamStr]

      ret.fold(
        error => handleJsonValidateErrorFuture(error),
        param => {
          for (_ <- sysConfig.setAnalysisLogPath(param.value)) yield
            Ok(Json.obj("ok" -> true))
        }
      )
  }

  def redirectRoot(ignore: String): Action[AnyContent] = Action {
    Redirect("/")
  }

  import models.CalibrationTarget._
  def getCOA: Action[AnyContent] = Security.Authenticated.async {
    for {
      coa <- sysConfig.getCalibrationTarget()
    } yield {
      Ok(Json.toJson(coa))
    }
  }

  def setCOA(): Action[JsValue] = Security.Authenticated.async(BodyParsers.parse.json){
    implicit request =>

      val ret = request.body.validate[CalibrationTarget]
      ret.fold(
        error => handleJsonValidateErrorFuture(error),
        param => {
          for (_ <- sysConfig.setCalibrationTarget(param)) yield
            Ok(Json.obj("ok" -> true))
        }
      )
  }
}
