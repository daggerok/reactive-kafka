package com.github.daggerok

import com.fasterxml.jackson.annotation.JsonCreator
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.*
import org.apache.kafka.common.serialization.*
import org.apache.logging.log4j.LogManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.*
import org.springframework.http.MediaType.APPLICATION_JSON_UTF8
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.support.serializer.*
import org.springframework.stereotype.*
import org.springframework.web.reactive.function.server.*
import reactor.core.publisher.*
import reactor.core.scheduler.Schedulers
import reactor.kafka.receiver.*
import reactor.kafka.sender.*
import java.lang.RuntimeException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import javax.annotation.PostConstruct

const val id = "reactive-kafka"
const val commandTopic = "$id-command-topic"
const val eventTopic = "$id-event-topic"

data class Command @JsonCreator constructor(val payload: String? = null) // JsonCreator is required by JsonDeserializer
data class Event(val data: String? = null, val at: Long? = null)

@Configuration// TODO: FIXME: DRY code...
class Commands {

  companion object {
    const val named = "command"
  }

  @Value("\${spring.kafka.bootstrap-servers:127.0.0.1:9092}")
  private lateinit var bootstrapServers: String

  //@Value("\${spring.kafka.consumer.auto-offset-reset:earliest}")
  @Value("\${spring.kafka.consumer.auto-offset-reset:latest}")
  private lateinit var autoOffsetReset: String

  @Bean
  fun <K, V> commandSenderOptions() = SenderOptions
      .create<K, V>()
      // mandatory
      .producerProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
      .producerProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
      .producerProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer::class.java.name)
      .producerProperty(ProducerConfig.ACKS_CONFIG, "all")
      // optional
      .producerProperty(ProducerConfig.CLIENT_ID_CONFIG, "$id-$named-producer")

  @Bean
  fun <K, V> commandReceiverOptions() = ReceiverOptions
      .create<K, V>()
      // mandatory
      .consumerProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
      .consumerProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
      .consumerProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer::class.java.name)
      .consumerProperty(JsonDeserializer.TRUSTED_PACKAGES, this::class.java.`package`.name)
      .consumerProperty(ConsumerConfig.GROUP_ID_CONFIG, "$id-$named-group")
      // optional
      .consumerProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset)
      .consumerProperty(ConsumerConfig.CLIENT_ID_CONFIG, "$id-$named-consumer")

  @Bean
  fun commandGateway() = KafkaSender.create<String, Command>(commandSenderOptions())

  @Bean
  fun commandHandler() = KafkaReceiver.create(commandReceiverOptions<String, Command>().subscription(listOf(commandTopic)))
}

@EnableKafka
@Configuration // TODO: FIXME: DRY code...
class Events {

  companion object {
    const val named = "event"
  }

  @Value("\${spring.kafka.bootstrap-servers:127.0.0.1:9092}")
  private lateinit var bootstrapServers: String

  @Value("\${spring.kafka.consumer.auto-offset-reset:earliest}")
  //@Value("\${spring.kafka.consumer.auto-offset-reset:latest}")
  private lateinit var autoOffsetReset: String

  @Bean
  fun <K, V> eventSenderOptions() = SenderOptions
      .create<K, V>()
      // mandatory
      .producerProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
      .producerProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
      .producerProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer::class.java.name)
      .producerProperty(ProducerConfig.ACKS_CONFIG, "all")
      // optional
      .producerProperty(ProducerConfig.CLIENT_ID_CONFIG, "$id-$named-producer")

  @Bean
  fun <K, V> eventReceiverOptions() = ReceiverOptions
      .create<K, V>()
      // mandatory
      .consumerProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
      .consumerProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
      .consumerProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer::class.java.name)
      .consumerProperty(JsonDeserializer.TRUSTED_PACKAGES, this::class.java.`package`.name)
      .consumerProperty(ConsumerConfig.GROUP_ID_CONFIG, "$id-$named-group")
      // optional
      .consumerProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset)
      .consumerProperty(ConsumerConfig.CLIENT_ID_CONFIG, "$id-$named-consumer")

  @Bean
  fun eventGateway() = KafkaSender.create<String, Event>(eventSenderOptions())

  @Bean
  fun eventHandler() = KafkaReceiver.create(eventReceiverOptions<String, Event>().subscription(listOf(eventTopic)))
}

@Repository
class EventStore {
  private val db = CopyOnWriteArrayList<Event>()
  fun save(event: Event) = db.add(event)
  fun findAll() = db.toFlux()
  fun find(index: Int) = if (db.lastIndex < index) Mono.empty() else db[index].toMono()
}

@Configuration
class WebFlux(private val commandGateway: KafkaSender<String, Command>,
              private val eventStore: EventStore) {

  companion object {
    private val log = LogManager.getLogger()
  }

  @Bean
  fun routes() = router {
    "/".nest {
      contentType(APPLICATION_JSON_UTF8)
      POST("/") {
        ok().body(
            it.bodyToMono(Command::class.java)
                .map { ProducerRecord<String, Command>(commandTopic, it) }
                .map { SenderRecord.create<String, Command, Void>(it, null) }
                .map { it.toMono() }
                //.map { throw RuntimeException("oh...") } // uncomment this and comment next line to test failure...
                .map {
                  commandGateway
                      .send(it)
                      .subscribe { log.debug("command {} sent.") }
                }
                .then("Command sent.".toMono())
                .subscribeOn(Schedulers.elastic()))
      }
      GET("/") {
        ok().body(eventStore.findAll())
      }
      GET("/find/{index}") {
        val index = it.pathVariable("index")
        ok().body(eventStore.find(index.toInt()))
      }
      path("/**") {
        val uri = it.uri()
        val base = "${uri.scheme}://${uri.authority}"
        ok().body(mapOf(
            "send" to "http post $base payload={payload}",
            "find one" to "http get $base/find/{id}",
            "get all" to "http get $base"
        ).toMono())
      }
    }
  }
}

@Service
class CommandProcessor(private val commandHandler: KafkaReceiver<String, Command>,
                       private val eventGateway: KafkaSender<String, Event>) {
  companion object {
    private val log = LogManager.getLogger()
  }

  @PostConstruct
  fun subscribe() {
    commandHandler
        .receive()
        .doOnNext {
          println("received: ${it.value()} at ${Instant.ofEpochMilli(it.timestamp())}")
        }
        .map { it.value().payload to it.timestamp() }
        .map { Event(it.first) }
        .map { ProducerRecord<String, Event>(eventTopic, it) }
        .map { SenderRecord.create<String, Event, Void>(it, null) }
        .map { it.toMono() } //.map { Flux.just(Flux.from(it)) }
        .map {
          //val flux = eventGateway.sendTransactionally(Flux.from(it)) ; //flux.subscribe()
          val send: Flux<SenderResult<Void>> = eventGateway.send(it) ; send.subscribe()
        }
        .subscribeOn(Schedulers.elastic())
        .subscribe { log.debug("event fired.") }
  }
}

@Service
class EventProcessor(private val eventHandler: KafkaReceiver<String, Event>,
                     private val eventStore: EventStore) {
  @PostConstruct
  fun subscribe() {
    eventHandler

        // record should be manually acknowledged
        .receive()
        .subscribeOn(Schedulers.elastic())
        .map {
          val event = it.value().copy(at = it.timestamp())
          if (eventStore.save(event)) {
            it.receiverOffset().acknowledge() // <== manual ack
            return@map event
          }
          return@map Mono.error<RuntimeException>(RuntimeException("cannot commit"))
        }
        .subscribe {
          when (it) {
            is Event -> println("received: $it at ${Instant.ofEpochMilli(it.at ?: 0)}")
            else -> println("oops: $it")
          }
        }

//        // records committed periodically based on interval and batch size...
//        .receiveAutoAck().flatMap { it }
//        .subscribeOn(Schedulers.elastic())
//        .map {
//          it.value().copy(at = it.timestamp())
//        }
//        .filter { eventStore.save(it) }
//        .subscribe {
//          println("received: $it at ${Instant.ofEpochMilli(it.at ?: 0)}")
//        }

//        // expensive mode: committed individually and records not delivered until the commit succeed
//        .receiveAtmostOnce()
//        .subscribeOn(Schedulers.elastic())
//        .map {
//          it.value().copy(at = it.timestamp())
//        }
//        .filter { eventStore.save(it) }
//        .subscribe {
//          println("received: $it at ${Instant.ofEpochMilli(it.at ?: 0)}")
//        }
  }
}

@SpringBootApplication
class ReactiveKafkaApp

fun main(args: Array<String>) {
  runApplication<ReactiveKafkaApp>(*args)
}
