package models
import play.api._
import akka.actor._
import play.api.Play.current

abstract class SelectorModel() {
  def getStreamNum(): Int
  def setStreamNum(v: Int)
  val canSetStream: Boolean
}

object VirtualSelector extends SelectorModel {
  private var currentStream = 1
  val max = current.configuration.getInt("selector.virtual.max").get
  for (id <- 1 to max) {
    Monitor.getMonitorValueByName(id)
  }

  def getStreamNum(): Int = currentStream
  def setStreamNum(v: Int) { currentStream = v }
  val canSetStream = true
}

object Selector {
  val selectorModel = Play.current.configuration.getString("selector.model").get
  val model: SelectorModel = selectorModel match {
    case "virtual" => VirtualSelector
    case "VICI_UEA" =>
      Logger.info("VICI Universial Electric Actuator is selected")
      new ViciUeaSelector()
      
    case "MOXA" =>
      Logger.info("MOXA E1212 is selected")
      new MoxaSelector()
    case unknown =>
      Logger.error(s"Unknown model $unknown")
      ???
  }

  def get = model.getStreamNum()
  def set(v: Int) = model.setStreamNum(v)

}



