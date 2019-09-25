package models
import play.api._
import akka.actor._
import play.api.Play.current

abstract class SelectorModel() {
  def getStreamNum(): Int
  def setStreamNum(v: Int)
}

object VirtualSelector extends SelectorModel {
  private var currentStream = 1
  val max = current.configuration.getInt("selector.virtual.max").get
  for (id <- 1 to max) {
    Monitor.getMonitorValueByName(id)
  }

  def getStreamNum(): Int = currentStream
  def setStreamNum(v: Int) { currentStream = v }
}

object Selector {
  val selectorModel = Play.current.configuration.getString("selector.model").get
  val model: SelectorModel = selectorModel match {
    case "virtual" => VirtualSelector
    case "VICI_UEA" =>
      Logger.info("VICI Universial Electric Actuator is selected")
      new ViciUeaSelector()
    case unknown =>
      Logger.error(s"Unknown model $unknown")
      ???
  }

  def get = model.getStreamNum()
  def set(v: Int) = model.setStreamNum(v)

}



