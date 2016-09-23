package packer

import java.nio.file.Path

import cats.data.{ Reader, ReaderT }
import cats.implicits._
import event.EventBus
import models.{ Bake, BuildResult, Recipe }
import packer.PackerRunner.PackerInput
import play.api.Logger
import play.api.libs.ws.WSClient

import scala.concurrent.ExecutionContext.Implicits.global // TODO this should really be in the context
import scala.concurrent.Future

object BuildService {

  case class CreateImageContext(
    eventBus: EventBus,
    packerConfig: PackerConfig,
    wsClient: WSClient)

  type UseContext[A] = ReaderT[Future, CreateImageContext, A]
  type CreateImage = UseContext[BuildResult]

  /**
   * Starts a Packer process to create an image using the given recipe.
   *
   * @return a Future of the result of building the image
   */
  def createImage(
    createPlaybook: Recipe => Future[Path],
    findAllAWSAccountNumbers: ReaderT[Future, WSClient, Seq[String]],
    createPackerBuildConfig: (Bake, Path, Seq[String]) => ReaderT[Future, PackerConfig, Path],
    executePacker: PackerInput => ReaderT[Future, EventBus, Int])(bake: Bake): CreateImage = {

    // TODO This nasty wiring could be moved out to AppComponents.
    // In that case we would change all the arguments to have type (...) => UseContext[...].
    // Note that this would make the test for this method even more cumbersome than it already is.

    val writePlaybook: Recipe => UseContext[Path] = recipe =>
      ReaderT.lift[Future, CreateImageContext, Path](createPlaybook(recipe))

    val findAccountNumbers: UseContext[Seq[String]] =
      findAllAWSAccountNumbers.local[CreateImageContext](_.wsClient)

    val writePackerBuildConfig: (Bake, Path, Seq[String]) => UseContext[Path] = (bake, path, awsAccounts) =>
      createPackerBuildConfig(bake, path, awsAccounts).local[CreateImageContext](_.packerConfig)

    val runPacker: PackerInput => UseContext[Int] = packerInput =>
      executePacker(packerInput).local[CreateImageContext](_.eventBus)

    for {
      playbook <- writePlaybook(bake.recipe)
      awsAccountNumbers <- findAccountNumbers
      _ = Logger.info(s"AMI will be shared with the following AWS accounts: $awsAccountNumbers")
      packerConfigFile <- writePackerBuildConfig(bake, playbook, awsAccountNumbers)
      exitCode <- runPacker(PackerInput(bake, playbook, packerConfigFile))
    } yield {
      BuildResult.fromExitCode(exitCode)
    }

  }

}
