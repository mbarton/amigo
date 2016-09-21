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
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

object BuildService {

  case class CreateImageContext(
    eventBus: EventBus,
    packerConfig: PackerConfig,
    wsClient: WSClient,
    ec: ExecutionContext)

  /**
   * Starts a Packer process to create an image using the given recipe.
   *
   * @return a Future of the result of building the image
   */
  def createImage(bake: Bake,
    findAllAWSAccountNumbers: ReaderT[Future, WSClient, Seq[String]],
    generatePlaybook: Recipe => String,
    generatePackerBuildConfig: (Bake, Path, Seq[String]) => Reader[PackerConfig, PackerBuildConfig],
    writePlaybookToTempFile: (String, RecipeId) => Try[Path],
    writePackerConfigToTempFile: (PackerBuildConfig, RecipeId) => Try[Path],
    executePacker: PackerInput => ReaderT[Future, EventBus, Int]): ReaderT[Future, CreateImageContext, BuildResult] = {

    for {
      playbookYaml <- ReaderT.pure[Future, CreateImageContext, String](generatePlaybook(bake.recipe))
      playbook <- ReaderT.lift[Future, CreateImageContext, Path](Future.fromTry(writePlaybookToTempFile(playbookYaml, bake.recipe.id)))
      awsAccountNumbers <- findAllAWSAccountNumbers.local[CreateImageContext](_.wsClient)
      _ = Logger.info(s"AMI will be shared with the following AWS accounts: $awsAccountNumbers")
      packerBuildConfig <- generatePackerBuildConfig(bake, playbook, awsAccountNumbers).local[CreateImageContext](_.packerConfig).lift[Future]
      packerConfigFile <- ReaderT.lift[Future, CreateImageContext, Path](Future.fromTry(writePackerConfigToTempFile(packerBuildConfig, bake.recipe.id)))
      exitCode <- executePacker(PackerInput(bake, playbook, packerConfigFile)).local[CreateImageContext](_.eventBus)
    } yield BuildResult.fromExitCode(exitCode)

  }

}
