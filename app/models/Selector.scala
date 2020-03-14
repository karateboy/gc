package models
import play.api._
import akka.actor._
import play.api.Play.current

abstract class SelectorModel() {
  def getStreamNum(): Int
  def setStreamNum(v: Int)
  val canSetStream: Boolean
  val max: Int
}

class VirtualSelector(gcName:String, config: Configuration) extends SelectorModel {
  private var currentStream = 1
  val max = config.getInt("max").get
  for (id <- 1 to max) {
    Monitor.getMonitorValueByName(gcName, id)
  }

  def getStreamNum(): Int = currentStream
  def setStreamNum(v: Int) { currentStream = v }
  val canSetStream = true
}

class Selector(gcName:String, config: Configuration) {
  val selectorModel: String = config.getString("model").get
  val model: SelectorModel = selectorModel match {
    case "virtual" => new VirtualSelector(gcName, config.getConfig("virtual").get)
    case "VICI_UEA" =>
      Logger.info("VICI Universial Electric Actuator is selected")
      new ViciUeaSelector(gcName, config.getConfig("viciUea").get)

    case "MOXA" =>
      Logger.info("MOXA E1212 is selected")
      new MoxaSelector(gcName, config.getConfig("MOXA").get)
    case unknown =>
      Logger.error(s"Unknown model $unknown")
      ???
  }

  def get = model.getStreamNum()
  def set(v: Int) = model.setStreamNum(v)

}



