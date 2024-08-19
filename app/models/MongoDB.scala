package models
import models.ModelHelper.waitReadyResult
import play.api._

import javax.inject.Inject
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@javax.inject.Singleton
class MongoDB @Inject()(configuration: Configuration) {
  import org.mongodb.scala._

  val url: Option[String] = configuration.getString("my.mongodb.url")
  private val dbName = configuration.getString("my.mongodb.db")
  
  val mongoClient: MongoClient = MongoClient(url.get)
  val database: MongoDatabase = mongoClient.getDatabase(dbName.get)
  val colNames: Seq[String] = waitReadyResult(database.listCollectionNames().toFuture())

}