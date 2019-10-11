package models

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.implicitConversions
import org.mongodb.scala._
import com.github.nscala_time.time.Imports._

import ModelHelper._
import play.api._
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.libs.json.Reads._


case class Alarm(time: DateTime, monitor: Option[Monitor.Value], monitorType: Option[MonitorType.Value], desc: String)
object Alarm {
  implicit val write = Json.writes[Alarm]

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

  import org.mongodb.scala.model.Filters._
  import org.mongodb.scala.model.Projections._
  import org.mongodb.scala.model.Sorts._

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

  def getAlarmsFuture(monitorList: List[Monitor.Value], monitorTypeList: List[MonitorType.Value], start: DateTime, end: DateTime) = {
    import org.mongodb.scala.bson.BsonDateTime
    val startB: BsonDateTime = start
    val endB: BsonDateTime = end
    
    val timeFilter = Seq(Some(gte("time", startB)), Some(lt("time", endB)))
    val monitorFilter = if(monitorList.length == 0)
      None
    else {
      val monitorStrList = monitorList map { _.toString }
      Some(in("monitor", monitorStrList: _*))
    }
    
    val monitorTypeFilter = 
    if(monitorTypeList.length == 0)
      None
    else{
      val monitorTypeStrList = monitorTypeList map { _.toString }
      Some(in("monitorType", monitorTypeStrList: _*))
    }  

    val filters = Seq(Some(gte("time", startB)), Some(lt("time", endB)), monitorFilter, monitorTypeFilter).flatMap(x => x)
    
    val f = collection.find(and(filters: _*)).sort(ascending("time")).toFuture()
    f.failed.foreach(errorHandler)
    f
  }

  private def checkForDuplicatelog(ar: Alarm) {
    import org.mongodb.scala.bson.BsonDateTime
    //None blocking...
    val start: BsonDateTime = ar.time - 30.minutes
    val end: BsonDateTime = ar.time

    val countObserver = collection.countDocuments(and(gte("time", start), lt("time", end),
      equal("monitor", ar.monitor.toString), equal("monitorType", ar.monitorType.toString), equal("desc", ar.desc)))

    countObserver.subscribe(
      (count: Long) => {
        if (count == 0) {
          val f = collection.insertOne(ar).toFuture()
        }
      }, // onNext
      (ex: Throwable) => Logger.error("Alarm failed:", ex), // onError
      () => {} // onComplete
      )
  }

  def log(monitor: Option[Monitor.Value], monitorType: Option[MonitorType.Value], desc: String)= {
    val ar = Alarm(DateTime.now(), monitor, monitorType, desc)
    collection.insertOne(ar).toFuture()
  }
}