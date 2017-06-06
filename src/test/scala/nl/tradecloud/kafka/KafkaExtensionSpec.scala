package nl.tradecloud.kafka

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.event.LoggingAdapter
import akka.stream.ActorMaterializer
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import nl.tradecloud.kafka.command.{Publish, SubscribeWithAcknowledgement}
import nl.tradecloud.kafka.response.{PubSubAck, SubscribeAck}
import org.scalactic.ConversionCheckedTripleEquals
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers, WordSpecLike}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

class KafkaExtensionSpec(_system: ActorSystem) extends TestKit(_system)
  with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach
  with ConversionCheckedTripleEquals {

  implicit val mat: ActorMaterializer = ActorMaterializer()(_system)
  implicit val ec: ExecutionContextExecutor = _system.dispatcher
  implicit val embeddedKafkaConfig = EmbeddedKafkaConfig(9092, 2181)
  val bootstrapServers = s"localhost:${embeddedKafkaConfig.kafkaPort}"

  def this() = {
    this(
      ActorSystem(
        "KafkaExtensionSpec",
        ConfigFactory.parseString(s"""
          akka.loglevel = DEBUG
          akka.extensions = ["nl.tradecloud.kafka.KafkaExtension"]
          akka.actor.debug.receive = on
        """.stripMargin
        ).withFallback(ConfigFactory.load())
      )
    )
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    EmbeddedKafka.start()
  }

  override def afterAll(): Unit = {
    shutdown(_system, 30.seconds)
    EmbeddedKafka.stop()
    super.afterAll()
  }

  val defaultTimeout = FiniteDuration(60, TimeUnit.SECONDS)
  val mediator: ActorRef = KafkaExtension(_system).mediator
  val log: LoggingAdapter = _system.log

  "The KafkaExtension" must {
    "be able to subscribe to a topic" in {
      val subscriberProbe = TestProbe("subscriber")
      val receiverProbe = TestProbe("receiver")

      val subscribeCmd = SubscribeWithAcknowledgement(
        group = "test_group_0",
        topics = Set("test_topic_0"),
        ref = receiverProbe.ref,
        acknowledgeTimeout = 10.seconds,
        minBackoff = 3.seconds,
        maxBackoff = 10.seconds
      )

      subscriberProbe.send(mediator, subscribeCmd)
      subscriberProbe.expectMsg(defaultTimeout, SubscribeAck(subscribeCmd))

      mediator ! Publish(
        topic = "test_topic_0",
        msg = "Hello0"
      )

      receiverProbe.expectMsgPF(defaultTimeout) {
        case "Hello0" =>
          receiverProbe.reply(PubSubAck)
          true
      }

      mediator ! Publish(
        topic = "test_topic_0",
        msg = "Hello1"
      )

      receiverProbe.expectMsgPF(defaultTimeout) {
        case "Hello1" =>
          receiverProbe.reply(PubSubAck)
          true
      }
    }
  }

}
