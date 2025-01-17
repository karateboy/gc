package models

import com.github.nscala_time.time.Imports._
import models.ModelHelper._
import org.mongodb.scala._
import org.mongodb.scala.bson._
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Projections.include
import org.mongodb.scala.result.{InsertOneResult, UpdateResult}
import play.api._

import javax.inject.Inject
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class Record(monitor: MonitorOp#Value, time: DateTime, value: Double, status: String)

case class Record2(value: Double, status: String)

case class MtRecord(mtName: String, value: Double, status: String, text: String)

case class RecordList(monitor: String, time: Long, mtDataList: Seq[MtRecord], pdfReport: ObjectId)

object RecordOp {
  val HourCollection = "hour_data"
  val MinCollection = "min_data"
}

@javax.inject.Singleton
class RecordOp @Inject()(mongoDB: MongoDB, monitorOp: MonitorOp, monitorTypeOp: MonitorTypeOp) {
  private type MTMap = Map[MonitorTypeOp#Value, Record2]

  import RecordOp._
  import org.mongodb.scala.model.Indexes._
  import play.api.libs.json._

  implicit val writer: Writes[Record] = Json.writes[Record]

  import com.mongodb.client.model._

  if (!mongoDB.colNames.contains(HourCollection)) {
    val f = mongoDB.database.createCollection(HourCollection).toFuture()
    f.onFailure(errorHandler)
    f.onSuccess({
      case _ =>
        val indexOpt = new IndexOptions
        indexOpt.unique(true)
        val cf1 = mongoDB.database.getCollection(HourCollection).createIndex(ascending("time", "monitor"), indexOpt).toFuture()
        val cf2 = mongoDB.database.getCollection(HourCollection).createIndex(ascending("monitor", "time"), indexOpt).toFuture()
        cf1.onFailure(errorHandler)
        cf2.onFailure(errorHandler)
    })
  }

  if (!mongoDB.colNames.contains(MinCollection)) {
    val f = mongoDB.database.createCollection(MinCollection).toFuture()
    f.onFailure(errorHandler)
    f.onSuccess({
      case _ =>
        val cf1 = mongoDB.database.getCollection(MinCollection).createIndex(ascending("time", "monitor")).toFuture()
        val cf2 = mongoDB.database.getCollection(MinCollection).createIndex(ascending("monitor", "time")).toFuture()
        cf1.onFailure(errorHandler)
        cf2.onFailure(errorHandler)
    })
  }


  private def getDocKey(monitor: MonitorOp#Value, dt: DateTime) = {
    import org.mongodb.scala.bson._

    val bdt: BsonDateTime = dt
    Document("time" -> bdt, "monitor" -> monitor.toString)
  }

  def toDocument(monitor: MonitorOp#Value, dt: DateTime,
                 dataList: List[(MonitorTypeOp#Value, (Double, String))], pdfReport: ObjectId, sampleName: Option[String]): Document = {
    import org.mongodb.scala.bson._
    val bdt: BsonDateTime = dt
    var doc = Document("_id" -> getDocKey(monitor, dt), "time" -> bdt, "monitor" -> monitor.toString,
      "pdfReport" -> pdfReport, "sampleName" -> sampleName)
    for {
      data <- dataList
      mt = data._1
      (v, s) = data._2
    } {
      doc = doc ++ Document(monitorTypeOp.BFName(mt) -> Document("v" -> v, "s" -> s))
    }

    doc
  }

  def toDocument(monitor: MonitorOp#Value, dt: DateTime,
                 dataList: List[(MonitorTypeOp#Value, (Double, String))]): Document = {
    import org.mongodb.scala.bson._
    val bdt: BsonDateTime = dt
    var doc = Document("_id" -> getDocKey(monitor, dt), "time" -> bdt, "monitor" -> monitor.toString)
    for {
      data <- dataList
      mt = data._1
      (v, s) = data._2
    } {
      doc = doc ++ Document(monitorTypeOp.BFName(mt) -> Document("v" -> v, "s" -> s))
    }

    doc
  }

  def insertRecord(doc: Document)(colName: String): Future[InsertOneResult] = {
    val col = mongoDB.database.getCollection(colName)
    val f = col.insertOne(doc).toFuture()
    f.onFailure({
      case ex: Exception => Logger.error(ex.getMessage, ex)
    })
    f
  }

  def upsertRecord(doc: Document)(colName: String): Future[UpdateResult] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.UpdateOptions
    import org.mongodb.scala.model.Updates._

    val col = mongoDB.database.getCollection(colName)

    val updateList = doc.toList.map(kv => set(kv._1, kv._2))

    val f = col.updateOne(equal("_id", doc("_id")), combine(updateList: _*), UpdateOptions().upsert(true)).toFuture()
    f.onFailure(errorHandler)
    f
  }

  private def updateRecordStatus(monitor: monitorOp.Value, dt: Long, mt: monitorTypeOp.Value, status: String)(colName: String) = {
    import org.mongodb.scala.bson._
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Updates._

    val col = mongoDB.database.getCollection(colName)
    val bdt = new BsonDateTime(dt)
    val fieldName = s"${monitorTypeOp.BFName(mt)}.s"

    val f = col.updateOne(equal("_id", getDocKey(monitor, new DateTime(dt))), set(fieldName, status)).toFuture()
    f.onFailure({
      case ex: Exception => Logger.error(ex.getMessage, ex)
    })
    f
  }

  def getRecordMap(colName: String)
                  (mtList: List[MonitorTypeOp#Value], monitor: MonitorOp#Value, startTime: DateTime, endTime: DateTime):
  Map[MonitorTypeOp#Value, Seq[Record]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._

    val col = mongoDB.database.getCollection(colName)
    val projFields = "monitor" :: "time" :: mtList.map {
      monitorTypeOp.BFName
    }
    val proj = include(projFields: _*)

    val f = col.find(and(equal("monitor", monitor.toString), gte("time", startTime.toDate()), lt("time", endTime.toDate()))).projection(proj).sort(ascending("time")).toFuture()
    val docs = waitReadyResult(f)

    val pairs =
      for {
        mt <- mtList
        mtBFName = monitorTypeOp.BFName(mt)
      } yield {

        val list =
          for {
            doc <- docs
            monitor = monitorOp.withName(doc("monitor").asString().getValue)
            time = doc("time").asDateTime()
            mtDocOpt = doc.get(mtBFName) if mtDocOpt.isDefined && mtDocOpt.get.isDocument()
            mtDoc = mtDocOpt.get.asDocument()
            v = mtDoc.get("v") if v.isDouble
            s = mtDoc.get("s") if s.isString
          } yield {
            Record(monitor, time, v.asDouble().doubleValue(), s.asString().getValue)
          }

        mt -> list
      }
    Map(pairs: _*)
  }

  import scala.concurrent._

  def getMonitorRecordMapF(colName: String)
                          (mtList: List[MonitorTypeOp#Value], monitors: Seq[MonitorOp#Value], startTime: DateTime, endTime: DateTime):
  Future[Map[DateTime, Map[MonitorOp#Value, MTMap]]] = {
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._

    val col = mongoDB.database.getCollection(colName)
    val projFields = "monitor" :: "time" :: mtList.map {
      monitorTypeOp.BFName
    }
    val proj = include(projFields: _*)
    val monitorNames = monitors.map(monitorOp.map(_)._id)
    val f = col.find(and(in("monitor", monitorNames: _*), gte("time", startTime.toDate), lt("time", endTime.toDate))).projection(proj).sort(ascending("time")).toFuture()

    var timeMap = Map.empty[DateTime, Map[MonitorOp#Value, MTMap]]

    for (docs <- f) yield {
      val ret =
        for {
          doc <- docs
          time = new DateTime(doc("time").asDateTime().getValue)
        } yield {
          var monitorRecordMap = timeMap.getOrElse(time, Map.empty[MonitorOp#Value, MTMap])
          val monitor = monitorOp.withName(doc("monitor").asString().getValue)
          val mtRecordPairs =
            for {
              mt <- mtList
              mtBFName = monitorTypeOp.BFName(mt)
              mtDocOpt = doc.get(mtBFName) if mtDocOpt.isDefined && mtDocOpt.get.isDocument
              mtDoc = mtDocOpt.get.asDocument()
              v = mtDoc.get("v") if v.isDouble
              s = mtDoc.get("s") if s.isString
            } yield {
              mt -> Record2(v.asDouble().getValue, s.asString().getValue)
            }
          val mtMap = mtRecordPairs.toMap
          monitorRecordMap = monitorRecordMap + (monitor.asInstanceOf[MonitorOp#Value] -> mtMap)
          timeMap = timeMap + (time -> monitorRecordMap)
        }
      timeMap
    }
  }

  import ObjectIdUtil._
  implicit val mtRecordWrite: Writes[MtRecord] = Json.writes[MtRecord]
  implicit val recordListWrite: Writes[RecordList] = Json.writes[RecordList]

  import org.mongodb.scala.bson.conversions.Bson

  private def getRecordListFuture2(colName: String)(filter: Bson)
                                  (mtList: List[MonitorTypeOp#Value]): Future[Seq[RecordList]] = {
    import org.mongodb.scala.model.Projections._
    import org.mongodb.scala.model.Sorts._
    val mtFields = mtList map monitorTypeOp.BFName
    val projFields = "monitor" :: "time" :: "pdfReport" :: mtFields
    val proj = include(projFields: _*)

    val col = mongoDB.database.getCollection(colName)
    val f = col.find(filter).projection(proj).sort(ascending("time")).toFuture()

    for {
      docs <- f
    } yield {
      for {
        doc <- docs
        time = doc("time").asDateTime()
        monitor = monitorOp.withName(doc("monitor").asString().getValue)
      } yield {
        val mtDataList =
          for {
            mt <- mtList
            mtBFName = monitorTypeOp.BFName(mt)
            mtDesp = monitorTypeOp.map(mt).desp
            mtDocOpt = doc.get(mtBFName) if mtDocOpt.isDefined && mtDocOpt.get.isDocument()
            mtDoc = mtDocOpt.get.asDocument()
            v = mtDoc.get("v") if v.isDouble
            s = mtDoc.get("s") if s.isString
          } yield {
            MtRecord(mt.toString, v.asDouble().doubleValue(), s.asString().getValue, monitorTypeOp.formatWithUnit(mt, Some(v.asDouble().doubleValue())))
          }
        val pdfReportOpt = doc.get("pdfReport").map {
          _.asObjectId().getValue
        }
        RecordList(monitorOp.map(monitor).dp_no, time.getMillis, mtDataList, pdfReportOpt.getOrElse(new ObjectId()))
      }
    }
  }

  def getRecordListFuture(monitor: MonitorOp#Value, startTime: DateTime, endTime: DateTime)
                         (colName: String): Future[Seq[RecordList]] = {
    import org.mongodb.scala.model.Filters._

    val mtList = monitorTypeOp.mtvList
    val filter = and(equal("monitor", monitor.toString),
      exists("pdfReport"),
      gte("time", startTime.toDate),
      lt("time", endTime.toDate))
    getRecordListFuture2(colName)(filter)(mtList)
  }

  def getRecordListFuture(startTime: DateTime, endTime: DateTime)(colName: String): Future[Seq[RecordList]] = {
    import org.mongodb.scala.model.Filters._

    val mtList = monitorTypeOp.mtvList
    val filter = and(gte("time", startTime.toDate), lt("time", endTime.toDate))
    getRecordListFuture2(colName)(filter)(mtList)
  }

  def getLatestRecordListFuture(colName: String, monitors: Seq[String],
                                rename: Boolean = false)(limit: Int): Future[Seq[RecordList]] = {
    import org.mongodb.scala.bson._
    import org.mongodb.scala.model.Sorts._
    import org.mongodb.scala.model._

    val mtList = monitorTypeOp.mtvList
    val col = mongoDB.database.getCollection(colName)
    val projFields = "monitor" :: "time" :: "pdfReport" :: monitorTypeOp.mtvList.map {
      monitorTypeOp.BFName
    }
    val proj = Projections.include(projFields: _*)
    val filters = Filters.and(Filters.exists("pdfReport"), Filters.in("_id.monitor", monitors: _*))
    val f = col.find(filters).projection(proj).sort(descending("time")).limit(limit).toFuture()
    for {
      docs <- f
    } yield {
      for {
        doc <- docs
        time = doc("time").asDateTime()
        monitor = monitorOp.withName(doc("monitor").asString().getValue)
      } yield {
        val mtDataList =
          for {
            mt <- mtList
            mtBFName = monitorTypeOp.BFName(mt)
            mtDesp = monitorTypeOp.map(mt).desp
            mtDocOpt = doc.get(mtBFName) if mtDocOpt.isDefined && mtDocOpt.get.isDocument
            mtDoc = mtDocOpt.get.asDocument()
            v = mtDoc.get("v") if v.isDouble
            s = mtDoc.get("s") if s.isString
          } yield {
            if (rename)
              MtRecord(mtDesp, v.asDouble().doubleValue(), s.asString().getValue, monitorTypeOp.formatWithUnit(mt, Some(v.asDouble().doubleValue())))
            else
              MtRecord(mt.toString, v.asDouble().doubleValue(), s.asString().getValue, monitorTypeOp.formatWithUnit(mt, Some(v.asDouble().doubleValue())))
          }
        val pdfReport = if (doc.get("pdfReport").isEmpty)
          None
        else
          doc.get("pdfReport").map {
            _.asObjectId().getValue
          }
        RecordList(monitorOp.map(monitor).dp_no, time.getMillis, mtDataList, pdfReport.getOrElse(new ObjectId()))
      }
    }
  }

  def getLatestRecordFuture(colName: String, monitors: Seq[String],
                            rename: Boolean = false): Future[Seq[RecordList]] = {
    import org.mongodb.scala.bson._
    import org.mongodb.scala.model.Sorts._
    import org.mongodb.scala.model._

    val mtList = monitorTypeOp.mtvList
    val col = mongoDB.database.getCollection(colName)
    val projFields = "monitor" :: "time" :: "pdfReport" :: monitorTypeOp.mtvList.map {
      monitorTypeOp.BFName
    }
    val proj = Projections.include(projFields: _*)
    val filters = Filters.and(Filters.exists("pdfReport"), Filters.in("_id.monitor", monitors: _*))
    val f = col.find(filters).projection(proj).sort(descending("time")).limit(1).toFuture()
    val f2 = col.find(Filters.in("_id.monitor", monitors: _*)).projection(proj).sort(descending("time")).limit(1).toFuture()

    def getMtDataList(doc: Document): Seq[MtRecord] = {
      for {
        mt <- mtList
        mtBFName = monitorTypeOp.BFName(mt)
        mtName = monitorTypeOp.map(mt).desp
        mtDocOpt = doc.get(mtBFName) if mtDocOpt.isDefined && mtDocOpt.get.isDocument
        mtDoc = mtDocOpt.get.asDocument()
        v = mtDoc.get("v") if v.isDouble
        s = mtDoc.get("s") if s.isString
      } yield {
        if (rename)
          MtRecord(mtName, v.asDouble().doubleValue(), s.asString().getValue, monitorTypeOp.formatWithUnit(mt, Some(v.asDouble().doubleValue())))
        else
          MtRecord(mt.toString, v.asDouble().doubleValue(), s.asString().getValue, monitorTypeOp.formatWithUnit(mt, Some(v.asDouble().doubleValue())))
      }
    }

    for {
      docs <- f
      docs2 <- f2
    } yield {
      for {
        doc <- docs
        time = doc("time").asDateTime()
        monitor = monitorOp.withName(doc("monitor").asString().getValue)
      } yield {
        val mtDataList = getMtDataList(doc)
        // Update mtDataList with the latest record
        val mtDataList2 = getMtDataList(docs2.head)
        val updateMtList = ListBuffer(mtDataList: _*)
        for (mtRecord <- mtDataList2) {
          if (updateMtList.exists(_.mtName == mtRecord.mtName)) {
            val idx = updateMtList.indexWhere(_.mtName == mtRecord.mtName)
            updateMtList.update(idx, mtRecord)
          } else {
            updateMtList += mtRecord
          }
        }

        val pdfReport = if (doc.get("pdfReport").isEmpty)
          None
        else
          doc.get("pdfReport").map {
            _.asObjectId().getValue
          }
        RecordList(monitorOp.map(monitor).dp_no, time.getMillis, updateMtList, pdfReport.getOrElse(new ObjectId()))
      }
    }
  }


  def getLatestFixedRecordListFuture(colName: String, monitors: Seq[String])(limit: Int): Future[Seq[RecordList]] = {
    import org.mongodb.scala.bson._
    import org.mongodb.scala.model.Sorts._
    import org.mongodb.scala.model._

    val mtList = monitorTypeOp.mtvList
    val col = mongoDB.database.getCollection(colName)
    val projFields = "monitor" :: "time" :: "pdfReport" :: monitorTypeOp.mtvList.map {
      monitorTypeOp.BFName
    }
    val proj = Projections.include(projFields: _*)
    val f = col.find(Filters.and(Filters.in("monitor", monitors: _*), Filters.exists("pdfReport"))).projection(proj).sort(descending("time")).limit(limit).toFuture()
    for {
      docs <- f
    } yield {
      for {
        doc <- docs
        time = doc("time").asDateTime()
        monitor = doc("monitor").asString().getValue
      } yield {
        val mtDataList =
          for {
            mt <- mtList
            mtBFName = monitorTypeOp.BFName(mt)
            mtDesp = monitorTypeOp.map(mt).desp
          } yield {
            val mtDocOpt = doc.get(mtBFName)
            if (mtDocOpt.isDefined) {
              val mtDoc = mtDocOpt.get.asDocument()
              val v = mtDoc.get("v")
              val s = mtDoc.get("s")
              MtRecord(mt.toString, v.asDouble().doubleValue(), s.asString().getValue, monitorTypeOp.formatWithUnit(mt, Some(v.asDouble().doubleValue())))
            } else {
              MtRecord(mt.toString, 0, MonitorStatus.NormalStat, monitorTypeOp.formatWithUnit(mt, Some(0)))
            }
          }
        val pdfReport = if (doc.get("pdfReport").isEmpty)
          None
        else
          doc.get("pdfReport").map {
            _.asObjectId().getValue
          }
        RecordList(monitor, time.getMillis, mtDataList, pdfReport.getOrElse(new ObjectId()))
      }
    }
  }

  def getRecordWithPdfID(pdfId: ObjectId): Future[Map[MonitorOp#Value, (DateTime, Option[String], Map[MonitorTypeOp#Value, Record])]] = {
    val col = mongoDB.database.getCollection(MinCollection)
    val projFields = "monitor" :: "time" :: "sampleName" :: monitorTypeOp.mtvList.map {
      monitorTypeOp.BFName
    }

    val proj = include(projFields: _*)
    val f = col.find(equal("pdfReport", pdfId)).projection(proj).toFuture()
    val pairsF =
      for {
        docs <- f
      } yield {
        for {
          doc <- docs
          m = monitorOp.withName(doc("monitor").asString().getValue)
          time = doc("time").asDateTime().toDateTime()
          sampleName = doc.get("sampleName").map(_.asString().getValue)
        } yield {
          val pair =
            for {
              mt <- monitorTypeOp.mtvList
              mtBFName = monitorTypeOp.BFName(mt)
              mtDocOpt = doc.get(mtBFName) if mtDocOpt.isDefined && mtDocOpt.get.isDocument
              mtDoc = mtDocOpt.get.asDocument()
              v = mtDoc.get("v") if v.isDouble
              s = mtDoc.get("s") if s.isString
            } yield {
              mt.asInstanceOf[MonitorTypeOp#Value] ->
                Record(m.asInstanceOf[MonitorOp#Value], time, v.asDouble().doubleValue(), s.asString().getValue)
            }
          m.asInstanceOf[MonitorOp#Value] -> (time, sampleName, pair.toMap)
        }
      }

    for (pairs <- pairsF) yield
      pairs.toMap
  }
}