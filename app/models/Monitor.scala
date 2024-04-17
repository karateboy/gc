package models

import play.api._
import EnumUtils._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import models.ModelHelper._
import com.github.nscala_time.time.Imports._
import org.bson.conversions.Bson

import scala.concurrent.ExecutionContext.Implicits.global
import org.mongodb.scala.bson._
import org.mongodb.scala.model._
import org.mongodb.scala.result.UpdateResult

case class Monitor(_id: String, gcName: String, selector: Int, dp_no: String)

object Monitor extends Enumeration {
  implicit val monitorRead: Reads[Monitor.Value] = EnumUtils.enumReads(Monitor)
  implicit val monitorWrite: Writes[Monitor.Value] = EnumUtils.enumWrites

  implicit val mWrite = Json.writes[Monitor]
  implicit val mRead = Json.reads[Monitor]

  import org.mongodb.scala.bson.codecs.Macros._
  import org.mongodb.scala.bson.codecs.DEFAULT_CODEC_REGISTRY
  import org.bson.codecs.configuration.CodecRegistries.{fromRegistries, fromProviders}

  import scala.concurrent._
  import scala.concurrent.duration._

  implicit object TransformMonitor extends BsonTransformer[Monitor.Value] {
    def apply(m: Monitor.Value): BsonString = new BsonString(m.toString)
  }

  val colName = "monitors"
  val codecRegistry = fromRegistries(fromProviders(classOf[Monitor]), DEFAULT_CODEC_REGISTRY)
  val collection = MongoDB.database.getCollection[Monitor](colName).withCodecRegistry(codecRegistry)

  def monitorId(gcName: String, selector: Int) = s"$gcName:${selector}"

  def buildMonitor(gcName: String, selector: Int, dp_no: String) = {
    assert(!dp_no.isEmpty)

    Monitor(monitorId(gcName, selector), gcName, selector, dp_no)
  }

  def upgradeDb() {
    val n = waitReadyResult(MongoDB.database.getCollection(colName).countDocuments().toFuture())
    Logger.debug(s"${n} monitors")
    if (n == 0)
      return;

    //Test schema
    val docs: Seq[Document] = waitReadyResult(MongoDB.database.getCollection(colName).find().toFuture())

    val gcName: Option[BsonValue] = docs(0).get[BsonValue]("gcName")
    if (gcName.isEmpty) {

      for (doc <- docs) {
        val gcName = "gc1"
        val _id = doc("_id").asString().getValue
        val selector = _id.toInt
        val new_id = monitorId(gcName, selector)
        val newMonitor = Monitor(_id = new_id, gcName = gcName, selector = selector, dp_no = doc("dp_no").asString().getValue)
        waitReadyResult(MongoDB.database.getCollection(colName).deleteOne(Filters.equal("_id", _id)).toFuture())
        waitReadyResult(collection.insertOne(newMonitor).toFuture())
        //Update min_data
        val updateF = MongoDB.database.getCollection(Record.MinCollection).updateMany(Filters.equal("monitor", _id), Updates.set("monitor", new_id)).toFuture()
        waitReadyResult(updateF)
      }
    }
  }

  def init(colNames: Seq[String]) = {
    if (!colNames.contains(colName)) {
      val f = MongoDB.database.createCollection(colName).toFuture()
      f.onFailure(errorHandler)
      f.onSuccess({
        case _: Seq[t] =>
      })

      waitReadyResult(f)
    }
    upgradeDb()
    refresh
  }

  def newMonitor(m: Monitor) = {
    Logger.debug(s"Create monitor value ${m._id}!")
    val v = Value(m._id)
    map = map + (v -> m)

    val f = collection.insertOne(m).toFuture()
    f.onFailure(errorHandler)
    f.onSuccess({
      case _: Seq[t] =>
    })
    Monitor.withName(m._id)
  }

  private def mList: List[Monitor] = {
    val f = collection.find().sort(Sorts.ascending("gcName", "selector")).toFuture()
    val ret = waitReadyResult(f)
    ret.toList
  }

  def refresh = {
    val list = mList
    map = Map.empty[Monitor.Value, Monitor]
    for (m <- list) {
      try {
        val mv = Monitor.withName(m._id)
        map = map + (mv -> m)
      } catch {
        case _: NoSuchElementException =>
          map = map + (Value(m._id) -> m)
      }
    }

  }

  var map: Map[Value, Monitor] = Map.empty[Value, Monitor]

  def mvList = mList.map(mt => Monitor.withName(mt._id))

  def indParkList = {
    var nameSet = Set.empty[String]
    for (mv <- mvList) {
      nameSet += map(mv).gcName
    }
    nameSet.toList.sorted
  }

  def indParkMonitor(indParkFilter: Seq[String]) =
    mvList.filter(p => {
      val monitor = Monitor.map(p)
      indParkFilter.contains(monitor.gcName)
    })

  def indParkMonitor(indPark: String) =
    mvList.filter(p => {
      val monitor = Monitor.map(p)
      monitor.gcName == indPark
    })

  def getMonitorValueByName(gcName: String, selector: Int) = {
    try {
      val id = monitorId(gcName, selector)
      Monitor.withName(id)
    } catch {
      case _: NoSuchElementException =>
        newMonitor(buildMonitor(gcName, selector, s"$gcName:$selector"))
    }
  }

  def format(v: Option[Double]) = {
    if (v.isEmpty)
      "-"
    else
      v.get.toString
  }

  def updateMonitor(m: Monitor.Value, colname: String, newValue: String) = {
    import org.mongodb.scala._
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Updates._
    import org.mongodb.scala.model.FindOneAndUpdateOptions

    import scala.concurrent.ExecutionContext.Implicits.global
    Logger.debug(s"col=$colname newValue=$newValue")
    val idFilter = equal("_id", map(m)._id)
    val opt = FindOneAndUpdateOptions().returnDocument(com.mongodb.client.model.ReturnDocument.AFTER)
    val f =
      if (newValue == "-")
        collection.findOneAndUpdate(idFilter, set(colname, null), opt).toFuture()
      else {
        import java.lang.Double
        collection.findOneAndUpdate(idFilter, set(colname, Double.parseDouble(newValue)), opt).toFuture()
      }

    val mCase = waitReadyResult(f)

    map = map + (m -> mCase)
  }

  def upsert(m: Monitor) = {
    val f = collection.replaceOne(Filters.equal("_id", m._id), m, ReplaceOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    f
  }

  def getGcNameMap(): Future[Map[String, String]] = {
    val gcIdList = Monitor.indParkList
    for (gcNameList: Seq[String] <- SysConfig.getGcNameList) yield {
      val pairs = gcIdList.zip(gcNameList)
      pairs.toMap
    }
  }

  def getMonitorsByGcName(gcName:String) = {
    mList filter { m => m.gcName == gcName}
  }

  def getMonitorByGcFilter(gcFilter:String): Seq[String] = if (gcFilter.isEmpty)
    Monitor.map.values.map(_._id).toSeq
  else
    Monitor.map.values.filter(m => if (gcFilter.isEmpty) true else m.gcName == gcFilter).map(_._id).toSeq

}