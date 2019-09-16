package models
import org.mongodb.scala.model._
import org.mongodb.scala.model.Indexes._
import org.mongodb.scala.bson._
import MongoDB._
import models.ModelHelper._
import scala.concurrent.ExecutionContext.Implicits.global

case class PdfReport(_id: ObjectId, fileName: String, content: Array[Byte])
object PdfReport {
  import org.mongodb.scala.bson.codecs.Macros._
  import org.mongodb.scala.bson.codecs.DEFAULT_CODEC_REGISTRY
  import org.bson.codecs.configuration.CodecRegistries.{ fromRegistries, fromProviders }

  val codecRegistry = fromRegistries(fromProviders(classOf[PdfReport]), DEFAULT_CODEC_REGISTRY)

  val COLNAME = "PdfReport"
  val collection = MongoDB.database.getCollection[PdfReport](COLNAME).withCodecRegistry(codecRegistry)

  def getPdf(objId: ObjectId) = {
    val f = collection.find(Filters.eq("_id", objId)).first().toFuture()
    f.onFailure(errorHandler)
    f
  }
}