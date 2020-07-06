import java.time.format.DateTimeFormatter
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

import org.apache.spark.sql._
import org.apache.spark._
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming._
import org.apache.spark.streaming.kafka010._
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.spark
import reactivemongo.api.commands.WriteResult

import scala.concurrent.Future
import scala.util.{Failure, Success}
//import scalikejdbc._
import reactivemongo.api.MongoConnection.ParsedURI
import reactivemongo.api.collections.bson.BSONCollection
import reactivemongo.api._
import reactivemongo.bson.{BSONDocument, BSONDocumentReader}
import reactivemongo.core.nodeset.Authenticate
import reactivemongo.api.MongoConnectionOptions


import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Await
import org.mongodb.scala._



object ExactlyOnce {
  def main(args: Array[String]): Unit = {
    val brokers = "3.21.127.72:9092,18.217.220.79:9092,3.128.26.238:9092"
    //val brokers = "localhost:9092" 18.222.179.73
    val topic = "spark-topic"

    val kafkaParams = Map[String, Object](
      "bootstrap.servers" -> brokers,
      "key.deserializer" -> classOf[StringDeserializer],
      "value.deserializer" -> classOf[StringDeserializer],
      "group.id" -> "at-least-once",
      //"enable.auto.commit" -> (false: java.lang.Boolean),
      "auto.offset.reset" -> "latest")

    val conf = new SparkConf().setAppName("spark-streaming-semantics").setIfMissing("spark.master", "local[2]")
    val ssc = new StreamingContext(conf, Seconds(5))

    val messages = KafkaUtils.createDirectStream[String, String](ssc,
      LocationStrategies.PreferConsistent,
      ConsumerStrategies.Subscribe[String, String](Seq("spark-topic"), kafkaParams))
    messages.map(record=> {
      val data = record.value().toString
      val document1 = BSONDocument("value" -> data.toString)
      val writeRes: Future[WriteResult] = getMongoCollection("spark-test","ConsumerData").insert.one(document1)
      writeRes.onComplete {
        case Failure(e) => e.printStackTrace()
        case Success(writeResult) =>
          println(s"successfully inserted document with result: $writeResult")
      }
    }).print
    ssc.start()
    ssc.awaitTermination()
  }
  def getMongoCollection(dataBase : String, collection : String) : BSONCollection = {
//    def servers: List[String] = List("18.222.232.155:27017", "3.136.108.176:27017", "3.128.197.113:27017")
    // setting up mongo connection, database and collection
    val driver: MongoDriver = new MongoDriver()
    
    //val servers = List("server1:27017", "server2:27017", "server3:27017")
    //val connection: MongoConnection = driver.connection(servers)
    val servers: List[String] = List("18.222.232.155:27017", "3.136.108.176:27017", "3.128.197.113:27017")
    val dbName = "spark-test"
    val userName = "siteRootAdmin"
    val password = "passw0rd"
    val credentials = List(Authenticate(dbName, userName, password))

    val connection: MongoConnection = driver.connection(servers, authentications = credentials),
      options = MongoConnectionOptions(nbChannelsPerNode = 200, connectTimeoutMS = 5000),
      ignoredOptions = List.empty[String], db = None, authenticate = None))
    //Failover Strategy for Mongo Connections
    val strategy: FailoverStrategy =
      FailoverStrategy(
        initialDelay = 1000 milliseconds,
        retries = 32,
        delayFactor = attemptNumber => attemptNumber*1.5
      )
    val db = Await.result(connection.database(dataBase, strategy), 20 seconds)
    val bsonCollection = db.collection[BSONCollection](collection)
    bsonCollection
  }
}

