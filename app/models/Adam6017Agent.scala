package models

import akka.actor.{Actor, Cancellable}
import com.google.inject.assistedinject.Assisted
import com.serotonin.modbus4j.ip.IpParameters
import com.serotonin.modbus4j.{ModbusFactory, ModbusMaster}
import models.ModelHelper.errorHandler
import org.joda.time.DateTime
import play.api.Logger

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}

case class AiChannelCfg(seq: Int, mt: String, max: Double, mtMax: Double, min: Double, mtMin: Double)

case class Adam6017Config(host: String, aiConfigs: Seq[AiChannelCfg])

object Adam6017Agent {
  //def prof(config: Adam6017Config, gcConfig: GcConfig): Props = Props(classOf[Adam6017Agent], config, gcConfig)

  trait Factory {
    def apply(@Assisted("config") config: Adam6017Config, @Assisted("gcConfig") gcConfig: GcConfig): Actor
  }

  case object ConnectHost

  private case object CollectData
}

class Adam6017Agent @Inject()(monitorOp: MonitorOp, monitorTypeOp: MonitorTypeOp, recordOp: RecordOp, exporter: Exporter)
                             (@Assisted("config") config: Adam6017Config, @Assisted("gcConfig") gcConfig: GcConfig) extends Actor {
  Logger.info(s"Adam6017Agent start for ${config.host}")

  import Adam6017Agent._

  self ! ConnectHost

  private def decodeAi(values: Seq[Double]): Unit = {
    val dataPairList =
      for {
        channelCfg <- config.aiConfigs
        rawValue = values(channelCfg.seq)
        mtName = channelCfg.mt
        mtMin = channelCfg.mtMin
        mtMax = channelCfg.mtMax
        max = channelCfg.max
        min = channelCfg.min
      } yield {
        val v = mtMin + (mtMax - mtMin) / (max - min) * (rawValue - min)
        val mt = monitorTypeOp.getMonitorTypeValueByName(mtName, "ppb", 2000)
        (mt, (v, MonitorStatus.NormalStat))
      }

    val now = DateTime.now
    if (now.getSecondOfMinute <= 30) {
      val dt = DateTime.now.withSecondOfMinute(0).withMillisOfSecond(0)
      val monitor = monitorOp.getMonitorValueByName(gcConfig.gcName, gcConfig.selector.get)
      val doc = recordOp.toDocument(monitor, dt, dataPairList.toList)
      recordOp.upsertRecord(doc)(RecordOp.MinCollection)
    }

    val dataList = dataPairList map { pair => MtRecord(pair._1.toString, pair._2._1, pair._2._2, "") }
    exporter.exportMonitorTypeToPLC(gcConfig, dataList)
  }

  def receive: Receive = handler(None)

  @volatile var cancelable: Cancellable = _

  def handler(masterOpt: Option[ModbusMaster]): Receive = {
    case ConnectHost =>
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
            context become handler(Some(master))
            import scala.concurrent.duration._
            cancelable = context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, CollectData)
          } catch {
            case ex: Exception =>
              Logger.error(ex.getMessage, ex)
              Logger.info("Try again 1 min later...")
              //Try again
              import scala.concurrent.duration._
              cancelable = context.system.scheduler.scheduleOnce(FiniteDuration(1, MINUTES), self, ConnectHost)
          }

        }
      } onFailure errorHandler

    case CollectData =>
      Future {
        blocking {
          try {
            import com.serotonin.modbus4j.BatchRead
            import com.serotonin.modbus4j.code.DataType
            import com.serotonin.modbus4j.locator.BaseLocator

            //AI ...
            {
              val batch = new BatchRead[Float]

              for (idx <- 0 to 7)
                batch.addLocator(idx, BaseLocator.holdingRegister(1, 30 + 2 * idx, DataType.FOUR_BYTE_FLOAT_SWAPPED))

              batch.setContiguousRequests(true)

              val rawResult = masterOpt.get.send(batch)
              val result =
                for (idx <- 0 to 7) yield
                  rawResult.getFloatValue(idx).toDouble

              //val actualResult = result map { v => -5.0 + 10 * v / 65535.0 }
              decodeAi(result)
            }

            import scala.concurrent.duration._
            cancelable = context.system.scheduler.scheduleOnce(FiniteDuration(30, SECONDS), self, CollectData)
          } catch {
            case ex: Throwable =>
              Logger.error("Read reg failed", ex)
              masterOpt.get.destroy()
              context become handler(None)
              self ! ConnectHost
          }
        }
      } onFailure errorHandler

  }

  override def postStop(): Unit = {
    if (cancelable != null)
      cancelable.cancel()
  }

}
