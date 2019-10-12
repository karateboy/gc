package models

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.implicitConversions
import com.github.nscala_time.time.Imports._

import ModelHelper._
import play.api._
import play.api.libs.json._
import java.util.Date

case class Alarm(time: Date, monitor: Option[Monitor.Value], monitorType: Option[MonitorType.Value], desc: String)
object Alarm {
  implicit val write = Json.writes[Alarm]
  import org.mongodb.scala.model.Filters._
  import org.mongodb.scala.model.Projections._
  import org.mongodb.scala.model.Sorts._
  import org.mongodb.scala.bson.codecs.Macros._
  import org.mongodb.scala.bson.codecs.DEFAULT_CODEC_REGISTRY
  import org.bson.codecs.configuration.CodecRegistries.{ fromRegistries, fromProviders }

  val colName = "alarms"
  val codecRegistry = fromRegistries(fromProviders(classOf[Alarm]), DEFAULT_CODEC_REGISTRY)
  val collection = MongoDB.database.getCollection[Alarm](colName).withCodecRegistry(codecRegistry)

  def init(colNames: Seq[String]) {
    import org.mongodb.scala.model.Indexes._
    if (!colNames.contains(colName)) {
      val f = MongoDB.database.createCollection(colName).toFuture()
      f.onFailure(errorHandler)
      f.onSuccess({
        case _: Seq[_] =>
          collection.createIndex(ascending("time", "monitor", "monitorType")).toFuture()
      })
    }
  }

  def getAlarms(monitorList: List[Monitor.Value], monitorTypeList: List[MonitorType.Value], start: DateTime, end: DateTime) = {
    import org.mongodb.scala.bson.BsonDateTime
    val startB: BsonDateTime = start
    val endB: BsonDateTime = end
    val monitorStrList = monitorList map { _.toString }
    val monitorTypeStrList = monitorTypeList map { _.toString }

    val f = collection.find(and(gte("time", startB), lt("time", endB),
      in("monitor", monitorStrList: _*), in("monitorType", monitorTypeStrList: _*))).sort(ascending("time")).toFuture()

    waitReadyResult(f)
  }

  def getList(start: Long, end: Long) = {
    import java.util.Date
    import java.time.Instant
    val startDate = Date.from(Instant.ofEpochMilli(start))
    val endDate = Date.from(Instant.ofEpochMilli(end))
    val f = collection.find(and(gte("time", startDate), lt("time", endDate))).sort(ascending("time")).toFuture()
    f.failed.foreach(errorHandler)
    f
  }

  def getAlarmsFuture(monitorList: List[Monitor.Value], monitorTypeList: List[MonitorType.Value], start: DateTime, end: DateTime) = {
    import org.mongodb.scala.bson.BsonDateTime
    val startB: BsonDateTime = start
    val endB: BsonDateTime = end

    val timeFilter = Seq(Some(gte("time", startB)), Some(lt("time", endB)))
    val monitorFilter = if (monitorList.length == 0)
      None
    else {
      val monitorStrList = monitorList map { _.toString }
      Some(in("monitor", monitorStrList: _*))
    }

    val monitorTypeFilter =
      if (monitorTypeList.length == 0)
        None
      else {
        val monitorTypeStrList = monitorTypeList map { _.toString }
        Some(in("monitorType", monitorTypeStrList: _*))
      }

    val filters = Seq(Some(gte("time", startB)), Some(lt("time", endB)), monitorFilter, monitorTypeFilter).flatMap(x => x)

    val f = collection.find(and(filters: _*)).sort(ascending("time")).toFuture()
    f.failed.foreach(errorHandler)
    f
  }

  private def checkForDuplicatelog(ar: Alarm)={
    //None blocking...
    import scala.util.{Try, Success}
    import java.time.Instant
    import java.time.temporal._
    val start = Date.from(Instant.ofEpochMilli(ar.time.getTime).minus(30, ChronoUnit.MINUTES))
    val end = ar.time

    val monitorFilter = equal("monitor", ar.monitor.getOrElse(null))
        
    
    val f1 = collection.countDocuments(and(gte("time", start), lt("time", end),
      equal("monitor", ar.monitor.getOrElse(null)), equal("monitorType", ar.monitorType.getOrElse(null)), equal("desc", ar.desc))).toFuture()

    f1.andThen({
      case Success(count)=>
        if(count == 0)
          collection.insertOne(ar).toFuture()
    })
    
  }

  def log(monitor: Option[Monitor.Value], monitorType: Option[MonitorType.Value], desc: String) = {
    import java.time.Instant
    val ar = Alarm(Date.from(Instant.now()), monitor, monitorType, desc)
    checkForDuplicatelog(ar)
  }
}