# reactive-kafka
Simple Reactive Kafka app by using awesome: `reactor-kafka`, `spring-webflux` and `spring-boot`!

_start kafka_

```bash
./gradlew kStart
```

_test_

```bash
http :8080/help
http :8080 payload=hello
http :8080 payload=how\ are\ u\?
http :8080 payload='{"data":"nice!"}'
```

_shutdown and cleanup_

```bash
./gradlew kStop
killall -9 java
rm -rf /tmp/kafka-logs
```

resources:

- [reactor-kafka](https://projectreactor.io/docs/kafka/release/reference/)
- [YouTube: Reactive Kafka](https://www.youtube.com/watch?v=-ioxYn9Vlao)
