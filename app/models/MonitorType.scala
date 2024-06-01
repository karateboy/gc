package models

import models.ModelHelper._
import org.bson.codecs.configuration.CodecRegistry
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.model._
import play.api.libs.json._

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global

case class MonitorType(_id: String, desp: String, unit: String, order: Int, prec: Int = 2,
                       std_law: Option[Double] = None,
                       std_internal: Option[Double] = None,
                       itemID: Option[Int] = None) {
  def getItemIdUpdates = {

    Updates.combine(
      Updates.setOnInsert("desp", desp),
      Updates.setOnInsert("unit", unit),
      Updates.setOnInsert("order", order),
      Updates.set("itemID", itemID))
  }
}

@javax.inject.Singleton
class MonitorTypeOp @Inject()(mongoDB: MongoDB) extends Enumeration {

  import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.mongodb.scala.bson.codecs.Macros._

  implicit val mtvRead: Reads[Value] = EnumUtils.enumReads(this)
  implicit val mtvWrite: Writes[Value] = EnumUtils.enumWrites
  implicit val mtWrite: OWrites[MonitorType] = Json.writes[MonitorType]
  implicit val mtRead: Reads[MonitorType] = Json.reads[MonitorType]

  val ColName = "monitorTypes"
  val codecRegistry: CodecRegistry = fromRegistries(fromProviders(classOf[MonitorType]), DEFAULT_CODEC_REGISTRY)
  val collection: MongoCollection[MonitorType] = mongoDB.database.getCollection[MonitorType](ColName).withCodecRegistry(codecRegistry)

  if (!mongoDB.colNames.contains(ColName)) {
    val f = mongoDB.database.createCollection(ColName).toFuture()
    f.onFailure(errorHandler)
  }

  def BFName(mt: MonitorTypeOp#Value): String = {
    val mtCase = map(mt)
    mtCase._id.replace(".", "_")
  }

  private def mtList: List[MonitorType] = {
    val f = collection.find().toFuture()
    waitReadyResult(f).toList
  }

  def refreshMtv(): Unit = {
    val list = mtList
    map = Map.empty[MonitorTypeOp#Value, MonitorType]

    for (mt <- list) {
      try {
        val mtv = withName(mt._id)
        map = map + (mtv -> mt)
      } catch {
        case _: NoSuchElementException =>
          map = map + (Value(mt._id) -> mt)
      }
    }
  }

  var map: Map[MonitorTypeOp#Value, MonitorType] = Map(mtList.map { e => Value(e._id) -> e }: _*)

  def mtvList: List[MonitorTypeOp#Value] = map.toList.sortBy(_._2.order).map(_._1)

  def getMonitorTypeValueByName(_id: String, unit: String = "", start: Int = 0): Value = {
    try {
      withName(_id)
    } catch {
      case _: NoSuchElementException =>
        val mt = MonitorType(_id = _id, desp = _id, unit = unit, order = mtvList.size + start)
        newMonitorType(mt)
        val value = Value(mt._id)
        refreshMtv()
        value
    }
  }

  private def newMonitorType(mt: MonitorType) =
    collection.insertOne(mt).toFuture()

  import org.mongodb.scala.model.Filters._
  import org.mongodb.scala.model._

  def upsertMonitorType(mt: MonitorType): Boolean = {
    val f = collection.replaceOne(equal("_id", mt._id), mt, ReplaceOptions().upsert(true)).toFuture()
    waitReadyResult(f)
    true
  }

  def updateMonitorType(mt: MonitorTypeOp#Value, colname: String, newValue: String): Unit = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Updates._
    val idFilter = equal("_id", map(mt)._id)
    val opt = FindOneAndUpdateOptions().returnDocument(com.mongodb.client.model.ReturnDocument.AFTER)
    val f =
      if (colname == "desp" || colname == "unit" || colname == "measuringBy" || colname == "measuredBy") {
        if (newValue == "-")
          collection.findOneAndUpdate(idFilter, set(colname, null), opt).toFuture()
        else
          collection.findOneAndUpdate(idFilter, set(colname, newValue), opt).toFuture()
      } else if (colname == "prec" || colname == "order") {
        val v = Integer.parseInt(newValue)
        collection.findOneAndUpdate(idFilter, set(colname, v), opt).toFuture()
      } else {
        if (newValue == "-")
          collection.findOneAndUpdate(idFilter, set(colname, null), opt).toFuture()
        else {
          collection.findOneAndUpdate(idFilter, set(colname, newValue.toDouble), opt).toFuture()
        }
      }

    val mtCase = waitReadyResult(f)

    map = map + (mt -> mtCase)
  }

  def format(mt: MonitorTypeOp#Value, v: Option[Double]): String = {
    if (v.isEmpty)
      "-"
    else {
      val prec = map(mt).prec
      s"%.${prec}f".format(v.get)
    }
  }

  def formatWithUnit(mt: MonitorTypeOp#Value, v: Option[Double]): String = {
    if (v.isEmpty)
      "-"
    else {
      val prec = map(mt).prec
      val unit = map(mt).unit
      s"%.${prec}f $unit".format(v.get)
    }
  }

  private def overStd(mt: Value, v: Double) = {
    val mtCase = map(mt)
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

  /*
  def getOverStd(mt: Value, r: Option[Record]) = {
    if (r.isEmpty)
      false
    else {
      val (overInternal, overLaw) = overStd(mt, r.get.value)
      overInternal || overLaw
    }
  }

  def formatRecord(mt: Value, r: Option[Record]) = {
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

  def getCssClassStr(mt: Value, r: Option[Record]) = {
    if (r.isEmpty)
      ""
    else {
      val v = r.get.value
      val (overInternal, overLaw) = overStd(mt, v)
      MonitorStatus.getCssClassStr(r.get.status, overInternal, overLaw)
    }
  }

  def getCssClassStr(mt: Value, r: Record2) = {
    val v = r.value
    val (overInternal, overLaw) = overStd(mt, v)
    MonitorStatus.getCssClassStr(r.status, overInternal, overLaw)
  }
  */
}