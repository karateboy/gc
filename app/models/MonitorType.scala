package models
import play.api.Logger
import EnumUtils._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import models.ModelHelper._
import com.github.nscala_time.time.Imports._
import scala.concurrent.ExecutionContext.Implicits.global
import org.mongodb.scala.bson._
import org.mongodb.scala.model._

case class MonitorType(_id: String, desp: String, unit: String, order: Int, prec: Int = 2,
                       std_law:      Option[Double] = None,
                       std_internal: Option[Double] = None,
                       itemID:       Option[Int]    = None) {
  def getItemIdUpdates = {

    Updates.combine(
      Updates.setOnInsert("desp", desp),
      Updates.setOnInsert("unit", unit),
      Updates.setOnInsert("order", order),
      Updates.set("itemID", itemID))
  }
}

object MonitorType extends Enumeration {

  import org.mongodb.scala.bson.codecs.Macros._
  import org.mongodb.scala.bson.codecs.DEFAULT_CODEC_REGISTRY
  import org.bson.codecs.configuration.CodecRegistries.{ fromRegistries, fromProviders }

  implicit val mtvRead: Reads[MonitorType.Value] = EnumUtils.enumReads(MonitorType)
  implicit val mtvWrite: Writes[MonitorType.Value] = EnumUtils.enumWrites
  implicit val mtWrite = Json.writes[MonitorType]
  implicit val mtRead = Json.reads[MonitorType]

  val ColName = "monitorTypes"
  val codecRegistry = fromRegistries(fromProviders(classOf[MonitorType]), DEFAULT_CODEC_REGISTRY)
  val collection = MongoDB.database.getCollection[MonitorType](ColName).withCodecRegistry(codecRegistry)

  var epaOrder = 100
  def epaMonitorType(desp: String, unit: String, itemID: Int) = {
    epaOrder += 1
    MonitorType(_id = desp, desp = desp, unit = unit, order = epaOrder, itemID = Some(itemID))
  }

  def init(colNames: Seq[String]) = {
    if (!colNames.contains(ColName)) {
      val f = MongoDB.database.createCollection(ColName).toFuture()
      f.onFailure(errorHandler)
      f.onSuccess({
        case _: Seq[t] =>
        //insertMt
      })
      Some(f.mapTo[Unit])
    } else
      None
  }

  def BFName(mt: MonitorType.Value) = {
    val mtCase = map(mt)
    mtCase._id.replace(".", "_")
  }

  private def mtList: List[MonitorType] =
    {
      val f = collection.find().toFuture()
      waitReadyResult(f).toList
    }

  def refreshMtv = {
    val list = mtList
    map = Map.empty[MonitorType.Value, MonitorType]

    for (mt <- list) {
      try {
        val mtv = MonitorType.withName(mt._id)
        map = map + (mtv -> mt)
      } catch {
        case _: NoSuchElementException =>
          map = map + (Value(mt._id) -> mt)
      }
    }
    //mtvList = list.sortBy { _.order }.map(mt => MonitorType.withName(mt._id))
  }

  var map: Map[Value, MonitorType] = Map(mtList.map { e => Value(e._id) -> e }: _*)
  def mtvList = mtList.sortBy { _.order }.map(mt => MonitorType.withName(mt._id))
  def activeMtvList = mtvList
  def realtimeMtvList = mtvList

  def getMonitorTypeValueByName(_id: String, unit: String = "", start:Int = 0) = {
    try {
      MonitorType.withName(_id)
    } catch {
      case _: NoSuchElementException =>
        val mt = MonitorType(_id = _id, desp = _id, unit = unit, order = mtvList.size + start)
        newMonitorType(mt)
        val value = Value(mt._id)
        refreshMtv
        value
    }
  }

  def getMonitorTypeByItemID(itemID: Int) = {
    for (
      (v, mtCase) <- map.find(kv => {
        if (kv._2.itemID.isDefined && kv._2.itemID.get == itemID)
          true
        else
          false
      })
    ) yield {
      v
    }
  }
  def newMonitorType(mt: MonitorType) =
    collection.insertOne(mt).toFuture()

  import org.mongodb.scala.model.Filters._
  import org.mongodb.scala.model._

  def upsertMonitorType(mt: MonitorType) = {
    val f = collection.replaceOne(equal("_id", mt._id), mt, ReplaceOptions().upsert(true)).toFuture()
    waitReadyResult(f)
    true
  }

  def updateMonitorType(mt: MonitorType.Value, colname: String, newValue: String) = {
    import org.mongodb.scala._
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Updates._

    import scala.concurrent.ExecutionContext.Implicits.global
    val idFilter = equal("_id", map(mt)._id)
    val opt = FindOneAndUpdateOptions().returnDocument(com.mongodb.client.model.ReturnDocument.AFTER)
    val f =
      if (colname == "desp" || colname == "unit" || colname == "measuringBy" || colname == "measuredBy") {
        if (newValue == "-")
          collection.findOneAndUpdate(idFilter, set(colname, null), opt).toFuture()
        else
          collection.findOneAndUpdate(idFilter, set(colname, newValue), opt).toFuture()
      } else if (colname == "prec" || colname == "order") {
        import java.lang.Integer
        val v = Integer.parseInt(newValue)
        collection.findOneAndUpdate(idFilter, set(colname, v), opt).toFuture()
      } else {
        if (newValue == "-")
          collection.findOneAndUpdate(idFilter, set(colname, null), opt).toFuture()
        else {
          import java.lang.Double
          collection.findOneAndUpdate(idFilter, set(colname, Double.parseDouble(newValue)), opt).toFuture()
        }
      }

    val mtCase = waitReadyResult(f)

    map = map + (mt -> mtCase)
  }

  def format(mt: MonitorType.Value, v: Option[Double]) = {
    if (v.isEmpty)
      "-"
    else {
      val prec = map(mt).prec
      s"%.${prec}f".format(v.get)
    }
  }

  def formatWithUnit(mt: MonitorType.Value, v: Option[Double]) = {
    if (v.isEmpty)
      "-"
    else {
      val prec = map(mt).prec
      val unit = map(mt).unit
      s"%.${prec}f $unit".format(v.get)
    }
  }
  
  def overStd(mt: MonitorType.Value, v: Double) = {
    val mtCase = MonitorType.map(mt)
    val overInternal =
      if (mtCase.std_internal.isDefined) {
        if (v > mtCase.std_internal.get)
          true
        else
          false
      } else
        false
    val overLaw =
      if (mtCase.std_law.isDefined) {
        if (v > mtCase.std_law.get)
          true
        else
          false
      } else
        false
    (overInternal, overLaw)
  }

  def getOverStd(mt: MonitorType.Value, r: Option[Record]) = {
    if (r.isEmpty)
      false
    else {
      val (overInternal, overLaw) = overStd(mt, r.get.value)
      overInternal || overLaw
    }
  }

  def formatRecord(mt: MonitorType.Value, r: Option[Record]) = {
    if (r.isEmpty)
      "-"
    else {
      val (overInternal, overLaw) = overStd(mt, r.get.value)
      val prec = map(mt).prec
      val value = s"%.${prec}f".format(r.get.value)
      //if (overInternal || overLaw)
      //s"<i class='fa fa-exclamation-triangle'></i>$value"
      //else
      s"$value"
    }
  }

  def getCssClassStr(mt: MonitorType.Value, r: Option[Record]) = {
    if (r.isEmpty)
      ""
    else {
      val v = r.get.value
      val (overInternal, overLaw) = overStd(mt, v)
      MonitorStatus.getCssClassStr(r.get.status, overInternal, overLaw)
    }
  }

  def getCssClassStr(mt: MonitorType.Value, r: Record2) = {
    val v = r.value
    val (overInternal, overLaw) = overStd(mt, v)
    MonitorStatus.getCssClassStr(r.status, overInternal, overLaw)
  }

}