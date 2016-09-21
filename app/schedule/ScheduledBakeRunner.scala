package schedule

import data.{ Bakes, Dynamo, Recipes }
import models.{ Bake, RecipeId }
import packer.BuildService.{ CreateImage, CreateImageContext }
import play.api.Logger

class ScheduledBakeRunner(enabled: Boolean, createImage: Bake => CreateImage, createImageContext: CreateImageContext)(implicit dynamo: Dynamo) {

  def bake(recipeId: RecipeId): Unit = {
    if (!enabled) {
      Logger.info("Skipping scheduled bake because I am disabled")
    } else {
      Recipes.findById(recipeId) match {
        case Some(recipe) =>
          // sanity check: is the recipe actually scheduled?
          if (recipe.bakeSchedule.isEmpty) {
            Logger.warn(s"Skipping scheduled bake of recipe $recipeId because it does not have a bake schedule defined")
          } else {
            Recipes.incrementAndGetBuildNumber(recipe.id) match {
              case Some(buildNumber) =>
                val theBake = Bakes.create(recipe, buildNumber, startedBy = "scheduler")

                Logger.info(s"Starting scheduled bake: ${theBake.bakeId}")
                createImage(theBake).run(createImageContext)
              case None =>
                Logger.warn(s"Failed to get the next build number for recipe $recipeId")
            }
          }
        case None =>
          Logger.warn(s"Skipping scheduled bake of recipe $recipeId because the recipe does not exist")
      }
    }
  }

}
