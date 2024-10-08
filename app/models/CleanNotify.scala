package models

import com.serotonin.modbus4j.ModbusFactory
import com.serotonin.modbus4j.ip.IpParameters
import com.serotonin.modbus4j.locator.BaseLocator
import play.api.{Configuration, Logger}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}

object CleanNotify {
  private val logger: Logger = Logger(getClass)

  def getConfig(config:Configuration): Option[CleanNotifyConfig] = {
      for {config <- config.getConfig("CleanNotifyConfig")
           host <- config.getString("host")
           slaveId = config.getInt("slaveId")
           address <- config.getInt("address")
           } yield {
        val delay = config.getInt("delay").getOrElse(0)
        CleanNotifyConfig(host, slaveId, address, delay)
      }
  }
  def notify(config:CleanNotifyConfig, value:Boolean): Unit = {
    Future {
      blocking {
        try {
          val ipParameters = new IpParameters()
          ipParameters.setHost(config.host)
          ipParameters.setPort(502)
          val modbusFactory = new ModbusFactory()

          val master = modbusFactory.createTcpMaster(ipParameters, true)
          master.setTimeout(4000)
          master.setRetries(1)
          master.setConnected(true)
          master.init();

          val locator = BaseLocator.coilStatus(config.slaveId.getOrElse(1), config.address)
          master.setValue(locator, value)
          logger.info(s"clean notify $value..$config")
        } catch {
          case ex: Exception =>
            logger.error(ex.getMessage, ex)
            logger.error("clean notify failed...")
        }
      }
    }
  }
}
