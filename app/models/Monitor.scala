package models

import models.ModelHelper._
import org.bson.codecs.configuration.CodecRegistry
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson._
import org.mongodb.scala.model._
import org.mongodb.scala.result.UpdateResult
import play.api._
import play.api.libs.json._

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global

case class Monitor(_id: String, gcName: String, selector: Int, dp_no: String)

@javax.inject.Singleton
class MonitorOp @Inject()(mongoDB: MongoDB) extends Enumeration {
  implicit val monitorRead: Reads[Value] = EnumUtils.enumReads(this)
  implicit val monitorWrite: Writes[Value] = EnumUtils.enumWrites

  implicit val mWrite: OWrites[Monitor] = Json.writes[Monitor]
  implicit val mRead: Reads[Monitor] = Json.reads[Monitor]

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  import scala.concurrent._

  implicit object TransformMonitor extends BsonTransformer[Value] {
    def apply(m: Value): BsonString = new BsonString(m.toString)
  }

  val colName = "monitors"
  val codecRegistry: CodecRegistry = fromRegistries(fromProviders(classOf[Monitor]), DEFAULT_CODEC_REGISTRY)
  val collection: MongoCollection[Monitor] = mongoDB.database.getCollection[Monitor](colName).withCodecRegistry(codecRegistry)
  var map: Map[MonitorOp#Value, Monitor] = Map.empty[MonitorOp#Value, Monitor]


  def monitorId(gcName: String, selector: Int) = s"$gcName:$selector"

  private def buildMonitor(gcName: String, selector: Int, dp_no: String) = {
    assert(dp_no.nonEmpty)

    Monitor(monitorId(gcName, selector), gcName, selector, dp_no)
  }

  private def upgradeDb(): Unit = {
    val n = waitReadyResult(mongoDB.database.getCollection(colName).countDocuments().toFuture())
    Logger.debug(s"$n monitors")
    if (n == 0)
      return

    //Test schema
    val docs: Seq[Document] = waitReadyResult(mongoDB.database.getCollection(colName).find().toFuture())

    val gcName: Option[BsonValue] = docs.head.get[BsonValue]("gcName")
    if (gcName.isEmpty) {
      for (doc <- docs) {
        val gcName = "gc1"
        val _id = doc("_id").asString().getValue
        val selector = _id.toInt
        val new_id = monitorId(gcName, selector)
        val newMonitor = Monitor(_id = new_id, gcName = gcName, selector = selector, dp_no = doc("dp_no").asString().getValue)
        waitReadyResult(mongoDB.database.getCollection(colName).deleteOne(Filters.equal("_id", _id)).toFuture())
        waitReadyResult(collection.insertOne(newMonitor).toFuture())
        //Update min_data
        val updateF = mongoDB.database.getCollection(RecordOp.MinCollection).updateMany(Filters.equal("monitor", _id), Updates.set("monitor", new_id)).toFuture()
        waitReadyResult(updateF)
      }
    }
  }


  if (!mongoDB.colNames.contains(colName)) {
    val f = mongoDB.database.createCollection(colName).toFuture()
    f.onFailure(errorHandler)
    waitReadyResult(f)
  }

  private def newMonitor(m: Monitor) = {
    Logger.debug(s"Create monitor value ${m._id}!")
    val v = Value(m._id)
    map = map + (v -> m)

    val f = collection.insertOne(m).toFuture()
    f.onFailure(errorHandler)
    f.onSuccess({
      case _: Seq[t] =>
    })
    withName(m._id)
  }

  private def getMonitorFromDb: List[Monitor] = {
    val f = collection.find().sort(Sorts.ascending("gcName", "selector")).toFuture()
    val ret = waitReadyResult(f)
    ret.toList
  }

  def getMonitorList: List[Monitor] = map.values.toList.sortBy(_.gcName)

  def refresh(): Unit = {
    Logger.info("Refresh MonitorOp")
    val list = getMonitorFromDb
    map = Map.empty[MonitorOp#Value, Monitor]
    for (m <- list) {
      try {
        val mv = withName(m._id)
        map = map + (mv -> m)
      } catch {
        case _: NoSuchElementException =>
          map = map + (Value(m._id) -> m)
      }
    }
    Logger.info(map.toString())
    Logger.info("Refresh MonitorOp done")
  }

  def indParkList: List[String] = {
    var nameSet = Set.empty[String]
    for (m <- map.values) {
      nameSet += m.gcName
    }
    nameSet.toList.sorted
  }

  def getMonitorValueByName(gcName: String, selector: Int): Value = {
    try {
      val id = monitorId(gcName, selector)
      withName(id)
    } catch {
      case _: NoSuchElementException =>
        newMonitor(buildMonitor(gcName, selector, s"$gcName:$selector"))
    }
  }

  def format(v: Option[Double]): String = {
    if (v.isEmpty)
      "-"
    else
      v.get.toString
  }

  def updateMonitor(m: Value, col: String, newValue: String): Unit = {
    import org.mongodb.scala._
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.FindOneAndUpdateOptions
    import org.mongodb.scala.model.Updates._
    Logger.debug(s"col=$col newValue=$newValue")
    val idFilter = equal("_id", map(m)._id)
    val opt = FindOneAndUpdateOptions().returnDocument(com.mongodb.client.model.ReturnDocument.AFTER)
    val f =
      if (newValue == "-")
        collection.findOneAndUpdate(idFilter, set(col, null), opt).toFuture()
      else {
        collection.findOneAndUpdate(idFilter, set(col, newValue.toDouble), opt).toFuture()
      }

    val mCase = waitReadyResult(f)

    map = map + (m -> mCase)
  }

  def upsert(m: Monitor): Future[UpdateResult] = {
    val f = collection.replaceOne(Filters.equal("_id", m._id), m, ReplaceOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    f
  }

  def getMonitorsByGcName(gcName: String): List[Monitor] = {
    getMonitorFromDb filter { m => m.gcName == gcName }
  }

  def getMonitorByGcFilter(gcFilter: String): Seq[String] = if (gcFilter.isEmpty)
    map.values.map(_._id).toSeq
  else
    map.values.filter(m => if (gcFilter.isEmpty) true else m.gcName == gcFilter).map(_._id).toSeq

  upgradeDb()
  refresh()
}