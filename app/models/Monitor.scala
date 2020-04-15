package models
import play.api._
import EnumUtils._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import models.ModelHelper._
import com.github.nscala_time.time.Imports._
import scala.concurrent.ExecutionContext.Implicits.global
import org.mongodb.scala.bson._
import org.mongodb.scala.model._

case class Monitor(_id: String, gcName: String, selector: String, dp_no:String)

object Monitor extends Enumeration {
  implicit val monitorRead: Reads[Monitor.Value] = EnumUtils.enumReads(Monitor)
  implicit val monitorWrite: Writes[Monitor.Value] = EnumUtils.enumWrites

  implicit val mWrite = Json.writes[Monitor]
  implicit val mRead = Json.reads[Monitor]

  import org.mongodb.scala.bson.codecs.Macros._
  import org.mongodb.scala.bson.codecs.DEFAULT_CODEC_REGISTRY
  import org.bson.codecs.configuration.CodecRegistries.{ fromRegistries, fromProviders }

  import scala.concurrent._
  import scala.concurrent.duration._

  implicit object TransformMonitor extends BsonTransformer[Monitor.Value] {
    def apply(m: Monitor.Value): BsonString = new BsonString(m.toString)
  }

  val colName = "monitors"
  val codecRegistry = fromRegistries(fromProviders(classOf[Monitor]), DEFAULT_CODEC_REGISTRY)
  val collection = MongoDB.database.getCollection[Monitor](colName).withCodecRegistry(codecRegistry)

  def monitorId(gcName:String, selector: Int) = s"$gcName:${selector}"

  def buildMonitor(gcName:String, selector: Int, dp_no: String) = {
    assert(!dp_no.isEmpty)

    Monitor(monitorId(gcName, selector), gcName, selector.toString, dp_no)
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
  }

  def newMonitor(m: Monitor) = {
    Logger.debug(s"Create monitor value ${m._id}!")
    val v = Value(m._id)
    map = map + (v -> m)
    mvList = (v :: mvList.reverse).reverse

    val f = collection.insertOne(m).toFuture()
    f.onFailure(errorHandler)
    f.onSuccess({
      case _: Seq[t] =>
    })
    Monitor.withName(m._id)
  }

  private def mList: List[Monitor] =
    {
      val f = collection.find().toFuture()
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
    mvList = list.map(m => Monitor.withName(m._id))

  }

  var map: Map[Value, Monitor] = Map(mList.map { e => Value(e._id) -> e }: _*)
  var mvList = mList.map(mt => Monitor.withName(mt._id))
  def indParkList = mvList.map { map(_).gcName }.foldRight(Set.empty[String])((name, set) => set + name).toList.sorted
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

  def getMonitorValueByName(gcName:String, selector: Int) = {
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
}