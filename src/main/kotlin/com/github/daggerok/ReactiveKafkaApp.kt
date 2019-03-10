package com.github.daggerok

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType.APPLICATION_JSON_UTF8
import org.springframework.kafka.support.serializer.JsonSerializer
import org.springframework.web.reactive.function.server.body
import org.springframework.web.reactive.function.server.router
import reactor.core.publisher.Mono
import reactor.core.publisher.toMono
import reactor.core.scheduler.Schedulers
import reactor.kafka.sender.KafkaSender
import reactor.kafka.sender.SenderOptions
import reactor.kafka.sender.SenderRecord

const val id = "reactive-kafka"

data class Command(val payload: Any)
//data class Event(val data: Any) // TODO: implement me please...

@Configuration
class ReactorKafka {

  @Bean
  fun senderOptions() = SenderOptions
      .create<String, Command>()
      .producerProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:9092")
      .producerProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
      .producerProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer::class.java.name)
      .producerProperty(ProducerConfig.CLIENT_ID_CONFIG, "$id-producer")
      .producerProperty(ProducerConfig.ACKS_CONFIG, "all")

  @Bean
  fun kafkaSender() = KafkaSender.create(senderOptions())
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
                .map { ProducerRecord<String, Command>("$id-topic", it) }
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
