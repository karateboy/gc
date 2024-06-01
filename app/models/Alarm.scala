package models

import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import play.api.libs.json._

import java.util.Date
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.implicitConversions

case class Alarm(time: Date, monitor: Option[String], monitorType: Option[String], desc: String)

@javax.inject.Singleton
class AlarmOp @javax.inject.Inject()(mongoDB: MongoDB, monitorOp: MonitorOp, monitorTypeOp: MonitorTypeOp) {
  implicit val write = Json.writes[Alarm]

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._
  import org.mongodb.scala.model.Filters._
  import org.mongodb.scala.model.Sorts._

  val colName = "alarms"
  val codecRegistry = fromRegistries(fromProviders(classOf[Alarm]), DEFAULT_CODEC_REGISTRY)
  val collection = mongoDB.database.getCollection[Alarm](colName).withCodecRegistry(codecRegistry)


  if (!mongoDB.colNames.contains(colName)) {
    val f = mongoDB.database.createCollection(colName).toFuture()
    f.onFailure(errorHandler)
    f.onSuccess({
      case _ =>
        collection.createIndex(org.mongodb.scala.model.Indexes.ascending("time", "monitor", "monitorType")).toFuture()
    })
  }


  def getAlarms(monitorList: List[monitorOp.Value], monitorTypeList: List[monitorTypeOp.Value], start: DateTime, end: DateTime) = {
    import org.mongodb.scala.bson.BsonDateTime
    val startB: BsonDateTime = start
    val endB: BsonDateTime = end
    val monitorStrList = monitorList map {
      _.toString
    }
    val monitorTypeStrList = monitorTypeList map {
      _.toString
    }

    val f = collection.find(and(gte("time", startB), lt("time", endB),
      in("monitor", monitorStrList: _*), in("monitorType", monitorTypeStrList: _*))).sort(ascending("time")).toFuture()

    waitReadyResult(f)
  }

  def getList(start: Long, end: Long) = {
    import java.time.Instant
    import java.util.Date
    val startDate = Date.from(Instant.ofEpochMilli(start))
    val endDate = Date.from(Instant.ofEpochMilli(end))
    val f = collection.find(and(gte("time", startDate), lt("time", endDate))).sort(ascending("time")).toFuture()
    f.failed.foreach(errorHandler)
    f
  }

  def getAlarmsFuture(monitorList: List[monitorOp.Value], monitorTypeList: List[monitorTypeOp.Value], start: DateTime, end: DateTime) = {
    import org.mongodb.scala.bson.BsonDateTime
    val startB: BsonDateTime = start
    val endB: BsonDateTime = end

    val timeFilter = Seq(Some(gte("time", startB)), Some(lt("time", endB)))
    val monitorFilter = if (monitorList.isEmpty)
      None
    else {
      val monitorStrList = monitorList map {
        _.toString
      }
      Some(in("monitor", monitorStrList: _*))
    }

    val monitorTypeFilter =
      if (monitorTypeList.isEmpty)
        None
      else {
        val monitorTypeStrList = monitorTypeList map {
          _.toString
        }
        Some(in("monitorType", monitorTypeStrList: _*))
      }

    val filters = Seq(Some(gte("time", startB)), Some(lt("time", endB)), monitorFilter, monitorTypeFilter).flatMap(x => x)

    val f = collection.find(and(filters: _*)).sort(ascending("time")).toFuture()
    f.failed.foreach(errorHandler)
    f
  }

  private def checkForDuplicatelog(ar: Alarm, noDuplicateMin: Int = 30) = {
    //None blocking...
    import java.time.Instant
    import java.time.temporal._
    import scala.util.Success
    val start = Date.from(Instant.ofEpochMilli(ar.time.getTime).minus(noDuplicateMin, ChronoUnit.MINUTES))
    val end = ar.time

    val monitorFilter = equal("monitor", ar.monitor.orNull)

    val f1 = collection.countDocuments(and(gte("time", start), lt("time", end),
      equal("monitor", ar.monitor.orNull), equal("monitorType", ar.monitorType.orNull), equal("desc", ar.desc))).toFuture()

    for (count <- f1) yield {
      if (count == 0) {
        val f2 = collection.insertOne(ar).toFuture()
        f2.andThen({
          case Success(x) =>
            GcWebSocketActor.notifyAllActors
        })
      }
    }

    f1.onFailure(errorHandler())
    f1
  }

  def log(monitor: Option[String], monitorType: Option[String],
          desc: String, noDuplicateMin: Int = 30) = {
    import java.time.Instant
    val ar = Alarm(Date.from(Instant.now()), monitor, monitorType, desc)
    checkForDuplicatelog(ar, noDuplicateMin)
  }
}