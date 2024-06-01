package models
import org.mongodb.scala.model._
import org.mongodb.scala.model.Indexes._
import org.mongodb.scala.bson._
import models.ModelHelper._
import org.mongodb.scala.MongoCollection

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class PdfReport(_id: ObjectId, fileName: String, content: Array[Byte])
@javax.inject.Singleton
class PdfReportOp @Inject() (mongoDB: MongoDB) {
  import org.mongodb.scala.bson.codecs.Macros._
  import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
  import org.bson.codecs.configuration.CodecRegistries.{ fromRegistries, fromProviders }

  val codecRegistry = fromRegistries(fromProviders(classOf[PdfReport]), DEFAULT_CODEC_REGISTRY)

  val COLNAME = "PdfReport"
  val collection: MongoCollection[PdfReport] = mongoDB.database.getCollection[PdfReport](COLNAME).withCodecRegistry(codecRegistry)

  def getPdf(objId: ObjectId): Future[PdfReport] = {
    val f = collection.find(Filters.eq("_id", objId)).first().toFuture()
    f.onFailure(errorHandler)
    f
  }
}