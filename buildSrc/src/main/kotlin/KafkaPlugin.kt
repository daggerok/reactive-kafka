import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.create
import java.io.FileOutputStream
import java.lang.Thread.sleep
import java.net.URL
import java.nio.channels.Channels
import java.nio.file.Paths

const val SCALA_VERSION = "SCALA_VERSION"
const val KAFKA_VERSION = "KAFKA_VERSION"

fun String.getEnvOrSystemOrElse(defaultValue: String) =
    System.getenv().getOrDefault(this, System.getProperty(
        this.toLowerCase().replace("_", "."), defaultValue))!!

data class KafkaData(val scalaVersion: String,
                     val kafkaVersion: String,
                     val downloadUrl: String,
                     val workDir: String)

open class KafkaExtension(var scalaVersion: String = SCALA_VERSION.getEnvOrSystemOrElse(Globals.scalalVersion),
                          var kafkaVersion: String = KAFKA_VERSION.getEnvOrSystemOrElse(Globals.kafkaVersion),
                          var downloadUrl: String = toDownloadUrl(scalaVersion, kafkaVersion),
                          var workDir: String = defaultWorkDir) {
  companion object {
    private val defaultWorkDir: String by lazy {
      Paths.get(System.setProperty("java.io.tmpdir", "/tmp"), "kafka-gradle-plugin").toString()
    }
    private fun toBaseName(scalaVersion: String, kafkaVersion: String) = "kafka_$scalaVersion-$kafkaVersion"
    private fun toFilename(scalaVersion: String, kafkaVersion: String) = "${toBaseName(scalaVersion, kafkaVersion)}.tgz"
    private fun toTar(scalaVersion: String, kafkaVersion: String, workDir: String) =
        Paths.get(workDir, toFilename(scalaVersion, kafkaVersion)).toFile()
    private fun toDownloadUrl(scalaVersion: String, kafkaVersion: String) =
        "https://www-eu.apache.org/dist/kafka/$kafkaVersion/${toFilename(scalaVersion, kafkaVersion)}"
  }

  override fun toString() = KafkaData(scalaVersion, kafkaVersion, downloadUrl, workDir)
      .toString().replaceFirst(KafkaData::class.java.simpleName, KafkaExtension::class.java.simpleName)

  fun getFilename() = toFilename(scalaVersion, kafkaVersion)
  fun getTar() = toTar(scalaVersion, kafkaVersion, workDir)
  fun getHome() = Paths.get(workDir, toBaseName(scalaVersion, kafkaVersion))
}

class KafkaPlugin : Plugin<Project> {
  companion object {
    const val Kafka = "Kafka"
  }

  override fun apply(target: Project): Unit = target.run {
    val kafka = extensions.create<KafkaExtension>("KafkaExtension")
    extensions.add("kafka", kafka)

    tasks.register("kafkaInfo") {
      group = Kafka
      description = "Print kafka-gradle-plugin configuration"

      doLast {
        println(kafka)
      }
    }

    val kafkaCleanHome = "kafkaCleanHome"
    tasks.register(kafkaCleanHome, Delete::class.java) {
      group = Kafka
      description = "Remove kafka home directory"

      isFollowSymlinks = false
      delete(kafka.getHome())

      doLast {
        println("removed: ${kafka.getHome()}")
      }
    }

    val kafkaCleanArchive = "kafkaCleanArchive"
    tasks.register(kafkaCleanArchive, Delete::class.java) {
      group = Kafka
      description = "Remove kafka archive"
      shouldRunAfter(kafkaCleanHome)

      isFollowSymlinks = false
      delete(kafka.getTar())

      doLast {
        println("removed: ${kafka.getTar()}")
      }
    }

    tasks.register("kafkaClean", Delete::class.java) {
      group = Kafka
      description = "Cleanup all kafka-gradle-plugin files and directories"
      dependsOn(kafkaCleanHome, kafkaCleanArchive)
      shouldRunAfter(kafkaCleanHome, kafkaCleanArchive)

      isFollowSymlinks = false
      delete(target.file(kafka.workDir))

      doLast {
        println("removed: ${kafka.workDir}")
      }
    }

    val kafkaDownload = "kafkaDownload"
    tasks.register(kafkaDownload) {
      group = Kafka
      description = "Download kafka binaries"

      doLast {
        if (kafka.getTar().exists()) {
          println("using ${kafka.getFilename()} from cache: ${kafka.getTar()}")
          return@doLast
        }

        val kafkaTarArchiveUrl = URL(kafka.downloadUrl)
        Channels.newChannel(kafkaTarArchiveUrl.openStream()).use { rbc ->
          println("create workDir: ${kafka.workDir}")
          kafka.getTar().parentFile.absoluteFile.mkdirs()
          println("download ${kafka.getFilename()} archive from: ${kafka.downloadUrl}")
          FileOutputStream(kafka.getTar().absolutePath).use { fos ->
            fos.channel.transferFrom(rbc, 0, Long.MAX_VALUE)
          }
        }
      }
    }

    val kafkaExtract = "kafkaExtract"
    tasks.register(kafkaExtract, Copy::class.java) {
      group = Kafka
      description = "Extract kafka tar archive into kafka home"
      dependsOn(kafkaDownload)

      from(tarTree(kafka.getTar()))
      into(kafka.getHome().parent)

      doLast {
        println("check kafka home in: ${kafka.getHome()}")
      }
    }

    val notWindows = !Os.isFamily(Os.FAMILY_WINDOWS)
    val kafkaZookeeperStart = "kafkaZookeeperStart"
    tasks.register(kafkaZookeeperStart, Exec::class.java) {
      group = Kafka
      description = "Start zooKeeper server"
      dependsOn(kafkaExtract)

      workingDir = kafka.getHome().toFile()
      if (notWindows) commandLine("sh", "-c", "bin/zookeeper-server-start.sh -daemon config/zookeeper.properties")
      else commandLine("cmd", "/c", "bin\\windows\\zookeeper-server-start.bat -daemon config\\zookeeper.properties")

      doLast {
        println("starting up zookeeper...")
      }
    }

    val kafkaBrokerStart = "kafkaBrokerStart"
    tasks.register(kafkaBrokerStart, Exec::class.java) {
      group = Kafka
      description = "Start kafka broker"
      dependsOn(kafkaZookeeperStart)
      shouldRunAfter(kafkaZookeeperStart)

      workingDir = kafka.getHome().toFile()
      if (notWindows) commandLine("sh", "-c", "bin/kafka-server-start.sh -daemon config/server.properties")
      else commandLine("cmd", "/c", "bin\\windows\\kafka-server-start.bat -daemon config\\server.properties")

      doLast {
        println("starting up kafka broker...")
        sleep(5000)
      }
    }

    tasks.register("kafkaStart") {
      group = Kafka
      description = "Start kafka"
      finalizedBy(kafkaZookeeperStart, kafkaBrokerStart)

      doLast {
        println("starting kafka...")
      }
    }

    val kafkaBrokerStop = "kafkaBrokerStop"
    tasks.register(kafkaBrokerStop, Exec::class.java) {
      group = Kafka
      description = "Stop kafka broker"
      dependsOn(kafkaExtract)

      workingDir = kafka.getHome().toFile()
      if (notWindows) commandLine("sh", "-c", "bin/kafka-server-stop.sh")
      else commandLine("cmd", "/c", "bin\\windows\\kafka-server-stop.bat")
      isIgnoreExitValue = true // please, do not fail if anything to stop...

      doLast {
        println("shutting down broker...")
      }
    }

    val kafkaZookeeperStop = "kafkaZookeeperStop"
    tasks.register(kafkaZookeeperStop, Exec::class.java) {
      group = Kafka
      description = "Stop zooKeeper server"
      dependsOn(kafkaExtract)

      workingDir = kafka.getHome().toFile()
      if (notWindows) commandLine("sh", "-c", "bin/zookeeper-server-stop.sh")
      else commandLine("cmd", "/c", "bin\\windows\\zookeeper-server-stop.bat")
      isIgnoreExitValue = true // please, do not fail if anything to stop...

      doLast {
        println("shutting down zookeeper...")
      }
    }

    tasks.register("kafkaStop") {
      group = Kafka
      description = "Stop kafka"
      finalizedBy(kafkaBrokerStop, kafkaZookeeperStop)

      doLast {
        println("shutting down kafka...")
      }
    }

    tasks.register("kafkaZookeeperRestart") {
      group = Kafka
      description = "Restart zookeeper server"
      dependsOn(kafkaZookeeperStop)
      shouldRunAfter(kafkaZookeeperStop)
      finalizedBy(kafkaZookeeperStart)

      doLast {
        println("restarting zookeeper...")
      }
    }

    tasks.register("kafkaBrokerRestart") {
      group = Kafka
      description = "Restart kafka broker"
      dependsOn(kafkaBrokerStop)
      shouldRunAfter(kafkaBrokerStop)
      finalizedBy(kafkaBrokerStart)

      doLast {
        println("restarting kafka...")
      }
    }

    tasks.register("kafkaRestart") {
      group = Kafka
      description = "Restart kafka"
      dependsOn(kafkaZookeeperStop, kafkaZookeeperStop)
      shouldRunAfter(kafkaZookeeperStop, kafkaZookeeperStop)
      finalizedBy(kafkaZookeeperStart, kafkaBrokerStart)

      doLast {
        println("restarting kafka...")
      }
    }
  }
}
