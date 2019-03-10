import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    idea
    base
    kafka
    id("org.jetbrains.kotlin.jvm") version "1.3.21"
    id("org.jetbrains.kotlin.plugin.spring") version "1.3.21"
    id("io.spring.dependency-management") version "1.0.7.RELEASE"
    id("org.springframework.boot") version "2.2.0.M1"
    id("io.franzbecker.gradle-lombok") version "2.1"
}

kafka {
    workDir = "/tmp/kafka"
}

val gradleVersion = "5.2.1"
val kotlinVersion = "1.3.21"
val lombokVersion = "1.18.6"
val junitJupiterVersion = "5.4.0"
val springKafkaVersion = "2.2.4.RELEASE"
val reactorKafkaVersion = "1.1.0.RELEASE"
val javaVersion = JavaVersion.VERSION_1_8

extra["kotlin.version"] = kotlinVersion
extra["lombok.version"] = lombokVersion
extra["spring-kafka.version"] = springKafkaVersion
extra["reactor-kafka.version"] = reactorKafkaVersion
extra["junit-jupiter.version"] = junitJupiterVersion

lombok { 
    version = lombokVersion
}

group = "com.github.daggerok"
version = "1.0-SNAPSHOT"

defaultTasks("build")

tasks.withType<Wrapper> {
    gradleVersion = gradleVersion
    distributionType = Wrapper.DistributionType.BIN
}

java {
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}

the<SourceSetContainer>()["main"].java.srcDir("src/main/kotlin")
the<SourceSetContainer>()["test"].java.srcDir("src/test/kotlin")

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        freeCompilerArgs += "-Xjsr305=strict"
        jvmTarget = "$javaVersion"
    }
}

repositories {
    mavenCentral()
    maven(url = "https://repo.spring.io/snapshot")
    maven(url = "https://repo.spring.io/milestone")
}

dependencies {
    implementation("io.projectreactor.kafka:reactor-kafka:$reactorKafkaVersion")
    implementation("org.springframework.kafka:spring-kafka:$springKafkaVersion")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    annotationProcessor("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    testAnnotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
//    runtimeOnly("org.springframework.boot:spring-boot-devtools")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")

    testImplementation(platform("org.junit:junit-bom:$junitJupiterVersion"))
    testImplementation("junit:junit")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testRuntime("org.junit.platform:junit-platform-launcher")
}

tasks.withType<BootJar>().configureEach {
    launchScript()
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        showExceptions = true
        showStandardStreams = true
        events(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
    }
}
