package models
import play.api._
import akka.actor._
import play.api.Play.current

import javax.inject.Inject

abstract class SelectorModel() {
  def getStreamNum(): Int
  def setStreamNum(v: Int)
  val canSetStream: Boolean
  val max: Int
}

class VirtualSelector @Inject()(monitorOp: MonitorOp)(gcName:String, config: Configuration) extends SelectorModel {
  private var currentStream = 1
  val max = config.getInt("max").get
  for (id <- 1 to max) {
    monitorOp.getMonitorValueByName(gcName, id)
  }

  def getStreamNum(): Int = currentStream
  def setStreamNum(v: Int): Unit = { currentStream = v }
  val canSetStream = true
}

class Selector(monitorOp: MonitorOp, alarmOp: AlarmOp, actorSystem: ActorSystem)(gcName:String, config: Configuration) {
  private val selectorModel: String = config.getString("model").get
  val model: SelectorModel = selectorModel match {
    case "virtual" => new VirtualSelector(monitorOp)(gcName, config.getConfig("virtual").get)
    case "VICI_UEA" =>
      Logger.info("VICI Universial Electric Actuator is selected")
      new ViciUeaSelector(monitorOp, actorSystem)(gcName, config.getConfig("viciUea").get)

    case "MOXA" =>
      Logger.info("MOXA E1212 is selected")
      new MoxaSelector(monitorOp, alarmOp, actorSystem)(gcName, config.getConfig("MOXA").get)

    case "ADAM6250" =>
      Logger.info("Adam 6250 is selected")
      Adam6250Selector(monitorOp, alarmOp, actorSystem)(gcName, config.getConfig("ADAM6250").get)

    case unknown =>
      Logger.error(s"Unknown model $unknown")
      ???
  }

  def get: Int = model.getStreamNum()
  def set(v: Int): Unit = model.setStreamNum(v)
}



