package controllers

import akka.stream.scaladsl.Source
import cats.data.ReaderT

import ansible.PlaybookGenerator
import packer.BuildService.CreateImageContext
import com.gu.googleauth.GoogleAuthConfig
import data._
import event._
import packer._
import models._
import play.api.Logger
import play.api.i18n.{ I18nSupport, MessagesApi }
import play.api.libs.EventSource
import play.api.libs.ws.WSClient
import play.api.mvc._
import prism.Prism

import scala.concurrent.Future

class BakeController(
    eventsSource: Source[BakeEvent, _],
    prism: Prism,
    wsClient: WSClient,
    val authConfig: GoogleAuthConfig,
    val messagesApi: MessagesApi)(implicit dynamo: Dynamo, packerConfig: PackerConfig, eventBus: EventBus) extends Controller with AuthActions with I18nSupport {

  def startBaking(recipeId: RecipeId) = AuthAction { request =>
    Recipes.findById(recipeId).fold[Result](NotFound) { recipe =>
      Recipes.incrementAndGetBuildNumber(recipe.id) match {
        case Some(buildNumber) =>
          val theBake = Bakes.create(recipe, buildNumber, startedBy = request.user.fullName)
          val context = CreateImageContext(eventBus, packerConfig, wsClient, scala.concurrent.ExecutionContext.global)
          BuildService.createImage(theBake,
            ReaderT[Future, WSClient, Seq[String]](_ => prism.findAllAWSAccountNumbers()), // TODO make prism an object
            PlaybookGenerator.generatePlaybook,
            PackerBuildConfigGenerator.generatePackerBuildConfig,
            TempFiles.writePlaybookToTempFile,
            TempFiles.writePackerBuildConfigToTempFile,
            PackerRunner.executePacker
          ).run(context)
          Redirect(routes.BakeController.showBake(recipeId, buildNumber))
        case None =>
          val message = s"Failed to get the next build number for recipe $recipeId"
          Logger.warn(message)
          InternalServerError(message)
      }
    }
  }

  def showBake(recipeId: RecipeId, buildNumber: Int) = AuthAction {
    Bakes.findById(recipeId, buildNumber).fold[Result](NotFound) { bake =>
      val bakeLogs = BakeLogs.list(BakeId(recipeId, buildNumber))
      Ok(views.html.showBake(bake, bakeLogs))
    }
  }

  def bakeEvents(recipeId: RecipeId, buildNumber: Int) = AuthAction { implicit req =>
    val bakeId = BakeId(recipeId, buildNumber)
    val source = eventsSource
      .filter(_.bakeId == bakeId) // only include events relevant to this bake
      .via(EventSource.flow)
    Ok.chunked(source).as("text/event-stream")
  }

}

