package packer

import java.nio.file.Path

import cats.data.{ Reader, ReaderT }
import cats.implicits._
import event.EventBus
import models.packer.PackerBuildConfig
import models.{ Bake, BuildResult, Recipe, RecipeId }
import packer.PackerRunner.PackerInput
import play.api.Logger
import play.api.libs.ws.WSClient

import scala.concurrent.ExecutionContext.Implicits.global // TODO this should really be in the context
import scala.concurrent.Future
import scala.util.Try

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
    generatePlaybook: Recipe => String,
    writePlaybookToTempFile: (String, RecipeId) => Try[Path],
    findAllAWSAccountNumbers: ReaderT[Future, WSClient, Seq[String]],
    generatePackerBuildConfig: (Bake, Path, Seq[String]) => Reader[PackerConfig, PackerBuildConfig],
    writePackerConfigToTempFile: (PackerBuildConfig, RecipeId) => Try[Path],
    executePacker: PackerInput => ReaderT[Future, EventBus, Int])(bake: Bake): CreateImage = {

    // TODO This nasty wiring could be moved out to AppComponents.
    // In that case we would change all the arguments to have type (...) => UseContext[...].
    // Note that this would make the test for this method even more cumbersome than it already is.

    val genPlaybook: Recipe => UseContext[String] = recipe => ReaderT.pure[Future, CreateImageContext, String](generatePlaybook(recipe))

    val writePlaybook: String => UseContext[Path] = playbookYaml =>
      ReaderT.lift[Future, CreateImageContext, Path](Future.fromTry(writePlaybookToTempFile(playbookYaml, bake.recipe.id)))

    val findAccountNumbers: UseContext[Seq[String]] = findAllAWSAccountNumbers.local[CreateImageContext](_.wsClient)

    val genPackerBuildConfig: (Bake, Path, Seq[String]) => UseContext[PackerBuildConfig] = (bake, path, awsAccounts) =>
      generatePackerBuildConfig(bake, path, awsAccounts).local[CreateImageContext](_.packerConfig).lift[Future]

    val writePackerBuildConfig: PackerBuildConfig => UseContext[Path] = packerBuildConfig =>
      ReaderT.lift[Future, CreateImageContext, Path](Future.fromTry(writePackerConfigToTempFile(packerBuildConfig, bake.recipe.id)))

    val runPacker: PackerInput => UseContext[Int] = packerInput =>
      executePacker(packerInput).local[CreateImageContext](_.eventBus)

    for {
      playbookYaml <- genPlaybook(bake.recipe)
      playbook <- writePlaybook(playbookYaml)
      awsAccountNumbers <- findAccountNumbers
      _ = Logger.info(s"AMI will be shared with the following AWS accounts: $awsAccountNumbers")
      packerBuildConfig <- genPackerBuildConfig(bake, playbook, awsAccountNumbers)
      packerConfigFile <- writePackerBuildConfig(packerBuildConfig)
      exitCode <- runPacker(PackerInput(bake, playbook, packerConfigFile))
    } yield {
      BuildResult.fromExitCode(exitCode)
    }

  }

}
