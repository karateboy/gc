package models

import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import org.bson.codecs.configuration.CodecRegistry
import org.mongodb.scala._
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.result.{InsertManyResult, InsertOneResult, UpdateResult}
import play.api._
import play.api.libs.json.{Json, OWrites}

import java.util.Date
import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class CalibrationId(monitor: String, time: Date)

case class Calibration(_id: CalibrationId, mtDataList: Seq[MtRecord],
                       sampleName: Option[String], fileName: Option[String],
                       containerId: Option[String],
                       fromNewGc: Option[Boolean] = None) {
  def mtMap: Map[String, MtRecord] = mtDataList.map { mtRecord =>
    mtRecord.mtName -> mtRecord
  }.toMap
}

object Calibration {
  implicit val mtRecordWrite: OWrites[MtRecord] = Json.writes[MtRecord]
  implicit val calibrationIdWrite: OWrites[CalibrationId] = Json.writes[CalibrationId]
  implicit val calibrationWrite: OWrites[Calibration] = Json.writes[Calibration]
}

@javax.inject.Singleton
class CalibrationOp @Inject()(mongoDB: MongoDB, monitorOp: MonitorOp, monitorTypeOp: MonitorTypeOp) {

  import org.mongodb.scala.model.Indexes._
  import play.api.libs.json._

  implicit val writer: Writes[MtRecord] = Json.writes[MtRecord]
  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._
  import org.mongodb.scala.model._

  val colName = "calibration"
  val codecRegistry: CodecRegistry = fromRegistries(fromProviders(classOf[Calibration],
    classOf[MtRecord], classOf[CalibrationId]), DEFAULT_CODEC_REGISTRY)
  val collection: MongoCollection[Calibration] = mongoDB.database.getCollection[Calibration](colName).withCodecRegistry(codecRegistry)

  if (!mongoDB.colNames.contains(colName)) {
    val f = mongoDB.database.createCollection(colName).toFuture()
    f.onFailure(errorHandler)
    f.onSuccess({
      case _ =>
        val indexOpt = new IndexOptions
        indexOpt.unique(true)
        val cf1 = mongoDB.database.getCollection(colName).createIndex(ascending("_id.time", "_id.monitor"), indexOpt).toFuture()
        val cf2 = mongoDB.database.getCollection(colName).createIndex(ascending("_id.monitor", "_id.time"), indexOpt).toFuture()
        cf1.onFailure(errorHandler)
        cf2.onFailure(errorHandler)
    })
  }


  def insert(doc: Calibration): Future[InsertOneResult] = {
    val f = collection.insertOne(doc).toFuture()
    f.onFailure({
      case ex: Exception => Logger.error(ex.getMessage, ex)
    })
    f
  }

  def upsert(doc: Calibration): Future[UpdateResult] = {
    val f = collection.replaceOne(equal("_id", doc._id), doc, ReplaceOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    f
  }

  import scala.concurrent._

  implicit val calibrationIdWrite: Writes[CalibrationId] = Json.writes[CalibrationId]
  implicit val calibrationWrite: Writes[Calibration] = Json.writes[Calibration]

  def getCalibrationListFuture(monitor: MonitorOp#Value, startTime: DateTime, endTime: DateTime): Future[Seq[Calibration]] = {
    import org.mongodb.scala.model.Filters._

    val filter = and(equal("_id.monitor", monitor.toString),
      gte("_id.time", startTime.toDate),
      lt("_id.time", endTime.toDate))
    val f = collection.find(filter).toFuture()
    f.onFailure({
      case ex: Exception => Logger.error(ex.getMessage, ex)
    })
    f
  }

  def getLatestCalibrationFuture: Future[Option[Calibration]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val filter = exists("_id")
    val f = collection.find(filter).sort(descending("_id.time")).first().toFuture()
    f.onFailure({
      case ex: Exception => Logger.error(ex.getMessage, ex)
    })
    f.map(Option(_))
  }

  def getLastCalibrationFuture(count: Int, newGC: Boolean): Future[Seq[Calibration]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Sorts._

    val filter = if (newGC)
      equal("fromNewGc", true)
    else
      notEqual("fromNewGc", true)

    val f = collection.find(filter).sort(descending("_id.time")).limit(count).toFuture()

    f onFailure {
      case ex: Exception => Logger.error(ex.getMessage, ex)
    }

    for (ret <- f) yield
      ret
  }

  def upsertMany(docs: Seq[Calibration]): Future[InsertManyResult] = {
    val f = collection.deleteMany(Filters.in("_id", docs.map(_.copy(_id = docs.head._id)._id): _*)).toFuture()
    f.failed.foreach(errorHandler)
    val ret = {
      for (_ <- f) yield {
        val f2 = collection.insertMany(docs).toFuture()
        f2.failed.foreach(errorHandler)
        f2
      }
    }
    ret.flatMap(x => x)
  }
}