package packer

import java.nio.file.{ Files, Path }

import cats.data.ReaderT
import event.EventBus
import models.Bake

import scala.concurrent.{ Future, Promise }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

object PackerRunner {

  case class PackerInput(bake: Bake, playbookFile: Path, packerConfigFile: Path)

  private val packerCmd = sys.env.get("PACKER_HOME").map(ph => s"$ph/packer").getOrElse("packer")

  def executePacker(input: PackerInput): ReaderT[Future, EventBus, Int] = ReaderT { eventBus =>
    val packerProcess = new ProcessBuilder()
      .command(packerCmd, "build", "-machine-readable", input.packerConfigFile.toAbsolutePath.toString)
      .start()

    val exitValuePromise = Promise[Int]()

    val runnable = new Runnable {
      def run(): Unit = PackerProcessMonitor.monitorProcess(packerProcess, exitValuePromise, input.bake.bakeId, eventBus)
    }
    val listenerThread = new Thread(runnable, s"Packer process monitor for ${input.bake.recipe.id.value} #${input.bake.buildNumber}")
    listenerThread.setDaemon(true)
    listenerThread.start()

    val exitValueFuture = exitValuePromise.future

    // Make sure to delete the tmp files after Packer completes, regardless of success or failure
    exitValueFuture.onComplete {
      case _ =>
        Try(Files.deleteIfExists(input.playbookFile))
        Try(Files.deleteIfExists(input.packerConfigFile))
    }

    exitValueFuture
  }

}
