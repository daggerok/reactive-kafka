# reactive-kafka
Simple Reactive Kafka app by using awesome: `reactor-kafka`, `spring-webflux` and `spring-boot`!

_start kafka and app_

```bash
./gradlew kStart
./gradlew bootRun
```

_test in a parallel in a terminal_

```bash
http :8080/help
http :8080 payload=hello
http :8080 payload=how\ are\ u\?
http :8080 payload='{"data":"nice!"}'
```

_shutdown and cleanup_

```bash
./gradlew kStop
./gradlew kCleanData
./gradlew --stop
```

resources:

- [reactor-kafka](https://projectreactor.io/docs/kafka/release/reference/)
- [YouTube: Reactive Kafka](https://www.youtube.com/watch?v=-ioxYn9Vlao)
