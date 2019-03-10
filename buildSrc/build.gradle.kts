plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

configure<KotlinDslPluginOptions> {
    experimentalWarning.set(false)
}

gradlePlugin {
    plugins {
        register("kafka-plugin") {
            id = "kafka"
            implementationClass = "KafkaPlugin"
        }
    }
}
