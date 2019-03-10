package com.github.daggerok

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType.APPLICATION_JSON_UTF8
import org.springframework.kafka.support.serializer.JsonDeserializer
import org.springframework.kafka.support.serializer.JsonSerializer
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.server.body
import org.springframework.web.reactive.function.server.router
import reactor.core.publisher.Mono
import reactor.core.publisher.toMono
import reactor.core.scheduler.Schedulers
import reactor.kafka.receiver.KafkaReceiver
import reactor.kafka.receiver.ReceiverOptions
import reactor.kafka.sender.KafkaSender
import reactor.kafka.sender.SenderOptions
import reactor.kafka.sender.SenderRecord
import java.util.function.Consumer
import javax.annotation.PostConstruct

const val id = "reactive-kafka"
const val topic = "$id-topic"

data class Command @JsonCreator constructor(val payload: String? = null) // JsonCreator is required by JsonDeserializer
//data class Event(val data: String) // TODO: implement me please...

@Configuration
class ReactorKafka {

  @Value("\${spring.kafka.bootstrap-servers:127.0.0.1:9092}")
  private lateinit var bootstrapServers: String

  //@Value("\${spring.kafka.consumer.auto-offset-reset:earliest}")
  @Value("\${spring.kafka.consumer.auto-offset-reset:latest}")
  private lateinit var autoOffsetReset: String

  @Bean
  fun senderOptions() = SenderOptions
      .create<String, Command>()
      // mandatory
      .producerProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
      .producerProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
      .producerProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer::class.java.name)
      .producerProperty(ProducerConfig.ACKS_CONFIG, "all")
      // optional
      .producerProperty(ProducerConfig.CLIENT_ID_CONFIG, "$id-producer")

  @Bean
  fun kafkaSender() = KafkaSender.create(senderOptions())

  @Bean
  fun receiverOptions() = ReceiverOptions
      .create<String, Command>()
      // mandatory
      .consumerProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
      .consumerProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
      .consumerProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer::class.java.name)
      .consumerProperty(JsonDeserializer.TRUSTED_PACKAGES, this::class.java.`package`.name)
      .consumerProperty(ConsumerConfig.GROUP_ID_CONFIG, "$id-group")
      .subscription(listOf(topic))
      // optional
      .consumerProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset)
      .consumerProperty(ConsumerConfig.CLIENT_ID_CONFIG, "$id-consumer")

  @Bean
  fun kafkaReceiver() = KafkaReceiver.create(receiverOptions())
}

@Service
class CommandHandler(private val kafkaReceiver: KafkaReceiver<String, Command>) {

  @PostConstruct
  fun subscribe() {
    kafkaReceiver
        .receive()
        .subscribe(Consumer {
          val timestamp = it.timestamp()
          val command = it.value()
          println("received: ${command.payload}")
        })
  }
}

@SpringBootApplication
class ReactiveKafkaApp(private val kafkaSender: KafkaSender<String, Command>) {

  @Bean
  fun routes() = router {
    "/".nest {
      contentType(APPLICATION_JSON_UTF8)
      POST("/") {
        ok().body(
            it.bodyToMono(Command::class.java)
                .map { ProducerRecord<String, Command>(topic, it) }
                .map { SenderRecord.create<String, Command, Void>(it, null) }
                .map { Mono.just(it) }
                //.map { throw RuntimeException("oh...") } // uncomment this and comment next line to test failure...
                .map { kafkaSender.send(it).subscribe() }
                .then(Mono.just("Command sent successfully."))
                .subscribeOn(Schedulers.elastic()))
      }
      GET("/") {
        ok().body("TODO: get events from store...".toMono())
      }
      path("/**") {
        ok().body(mapOf(
            "send" to "http post ${it.uri().scheme}://${it.uri().authority} payload=fuckit",
            "get" to "http get ${it.uri().scheme}://${it.uri().authority}"
        ).toMono())
      }
    }
  }
}

fun main(args: Array<String>) {
  runApplication<ReactiveKafkaApp>(*args)
}
