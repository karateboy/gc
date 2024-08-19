package models

import models.ModelHelper._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.implicitConversions
import com.github.nscala_time.time.Imports._
import org.mongodb.scala.model._
import org.mongodb.scala.bson._
import org.mongodb.scala.result.UpdateResult

import java.util.Date
import javax.inject.Inject
import scala.collection.JavaConverters._
import scala.concurrent.Future

@javax.inject.Singleton
class SysConfig @Inject()(mongoDB: MongoDB, monitorOp: MonitorOp) extends Enumeration {
  val ColName = "sysConfig"
  val collection = mongoDB.database.getCollection(ColName)

  val valueKey = "value"
  private val ALARM_LAST_READ = Value
  private val DATA_PERIOD = Value
  private val OPERATION_MODE = Value
  private val GCNAME_LIST = Value
  private val STOP_WARN = Value
  private val CLEAN_COUNT = Value
  private val LINE_TOKEN = Value
  private val EXECUTE_COUNT = Value

  val LocalMode = 0
  val RemoteMode = 1

  private lazy val defaultConfig = Map(
    ALARM_LAST_READ -> Document(valueKey -> DateTime.parse("2019-10-1").toDate),
    DATA_PERIOD -> Document(valueKey -> 30),
    OPERATION_MODE -> Document(valueKey -> 0),
    STOP_WARN -> Document(valueKey -> false),
    CLEAN_COUNT -> Document(valueKey -> 0),
    LINE_TOKEN -> Document(valueKey -> ""),
    EXECUTE_COUNT -> Document(valueKey -> 0)
  )

  def init(): Unit = {
    if (!mongoDB.colNames.contains(ColName)) {
      val f = mongoDB.database.createCollection(ColName).toFuture()
      f.onFailure(errorHandler)
    }

    val idSet = values map {
      _.toString()
    }
    //Clean up unused
    val f1 = collection.deleteMany(Filters.not(Filters.in("_id", idSet.toList: _*))).toFuture()
    f1.onFailure(errorHandler)
    val updateModels =
      for ((k, defaultDoc) <- defaultConfig) yield {
        UpdateOneModel(
          Filters.eq("_id", k.toString),
          Updates.setOnInsert(valueKey, defaultDoc(valueKey)), UpdateOptions().upsert(true))
      }

    val f2 = collection.bulkWrite(updateModels.toList, BulkWriteOptions().ordered(false)).toFuture()

    import scala.concurrent._
    val f = Future.sequence(List(f1, f2))
    waitReadyResult(f)
  }

  init()


  def upsert(_id: Value, doc: Document): Future[UpdateResult] = {
    val f = collection.replaceOne(Filters.equal("_id", _id.toString), doc, ReplaceOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    f
  }

  def get(_id: Value): Future[BsonValue] = {
    val f = collection.find(Filters.eq("_id", _id.toString)).first().toFuture()
    f.onFailure(errorHandler)
    for (ret <- f) yield {
      val doc =
        if (ret == null)
          defaultConfig(_id)
        else
          ret
      doc("value")
    }
  }

  def get(_id: Value, defaultDoc:Document): Future[BsonValue] = {
    val f = collection.find(Filters.eq("_id", _id.toString)).first().toFuture()
    f.onFailure(errorHandler)
    for (ret <- f) yield {
      val doc =
        if (ret == null)
          defaultDoc
        else
          ret
      doc(valueKey)
    }
  }

  def set(_id: Value, v: BsonValue): Future[UpdateResult] = upsert(_id, Document(valueKey -> v))

  def getAlarmLastRead: Future[Date] = {
    import java.util.Date
    import java.time.Instant
    val f = get(ALARM_LAST_READ)
    f.failed.foreach(errorHandler)
    for (ret <- f) yield Date.from(Instant.ofEpochMilli(ret.asDateTime().getValue))
  }

  def setAlarmLastRead(): Future[UpdateResult] = {
    import java.util.Date
    import java.time.Instant
    val f = upsert(ALARM_LAST_READ, Document(valueKey -> Date.from(Instant.now())))
    f.failed.foreach(errorHandler)
    f
  }

  def getDataPeriod: Future[Int] = {
    val f = get(DATA_PERIOD)
    f.failed.foreach(errorHandler)
    for (ret <- f)
      yield ret.asInt32().getValue
  }

  def setDataPeriod(min: Int): Future[UpdateResult] = {
    val f = upsert(DATA_PERIOD, Document(valueKey -> min))
    f.failed.foreach(errorHandler)
    f
  }


  def getOperationMode: Future[Int] = {
    val f = get(OPERATION_MODE)
    f.failed.foreach(errorHandler)
    for (ret <- f)
      yield ret.asInt32().getValue
  }

  def setOperationMode(mode: Int): Future[UpdateResult] = {
    val f = upsert(OPERATION_MODE, Document(valueKey -> mode))
    f.failed.foreach(errorHandler)
    f
  }

  def getGcNameList: Future[Seq[String]] = {
    val defaultGcNameList = monitorOp.indParkList
    val f = get(GCNAME_LIST, Document(valueKey -> defaultGcNameList))
    f.failed.foreach(errorHandler)
    for (ret <- f)
      yield ret.asArray().getValues.asScala.map(v => v.asString().getValue).toSeq
  }

  def setGcNameList(nameList: Seq[String]): Future[UpdateResult] = {
    val f = upsert(GCNAME_LIST, Document(valueKey -> nameList))
    f.failed.foreach(errorHandler)
    f
  }

  def getGcNameMap: Future[Map[String, String]] = {
    val gcIdList = monitorOp.indParkList
    for (gcNameList: Seq[String] <- getGcNameList) yield {
      val pairs = gcIdList.zip(gcNameList)
      pairs.toMap
    }
  }

  def getStopWarn: Future[Boolean] = {
    val f = get(STOP_WARN)
    f.failed.foreach(errorHandler)
    for (ret <- f)
      yield ret.asBoolean().getValue
  }

  def setStopWarn(v: Boolean): Future[UpdateResult] = {
    val f = upsert(STOP_WARN, Document(valueKey -> v))
    f.failed.foreach(errorHandler)
    f
  }

  def getCleanCount: Future[Int] = {
    val f = get(CLEAN_COUNT)
    f.failed.foreach(errorHandler)
    for (ret <- f)
      yield ret.asInt32().getValue
  }

  def setCleanCount(count: Int): Future[UpdateResult] = {
    val f = upsert(CLEAN_COUNT, Document(valueKey -> count))
    f.failed.foreach(errorHandler)
    f
  }

  def setLineToken(token: String): Future[UpdateResult] = {
    val f = upsert(LINE_TOKEN, Document(valueKey -> token))
    f.failed.foreach(errorHandler)
    f
  }

  def getLineToken: Future[String] = {
    val f = get(LINE_TOKEN, Document(valueKey -> ""))
    f.failed.foreach(errorHandler)
    for (ret <- f)
      yield ret.asString().getValue
  }

  def getExecuteCount: Future[Int] = {
    val f = get(EXECUTE_COUNT)
    f.failed.foreach(errorHandler)
    for (ret <- f)
      yield ret.asInt32().getValue
  }

  def setExecuteCount(count: Int): Future[UpdateResult] = {
    val f = upsert(EXECUTE_COUNT, Document(valueKey -> count))
    f.failed.foreach(errorHandler)
    f
  }
}