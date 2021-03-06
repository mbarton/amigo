package data

import com.amazonaws.services.dynamodbv2.model._
import models._
import org.joda.time.DateTime
import com.gu.scanamo.syntax._
import cats.syntax.either._
import com.gu.scanamo.error.DynamoReadError

import scala.collection.JavaConverters._

object Recipes {
  import Dynamo._
  import DynamoFormats._

  def list()(implicit dynamo: Dynamo): Iterable[Recipe] = {
    val dbModels = table.scan().exec().flatMap(_.toOption)
    for {
      dbModel <- dbModels
      baseImage <- BaseImages.findById(dbModel.baseImageId)
    } yield {
      Recipe.db2domain(dbModel, baseImage)
    }
  }

  def create(id: RecipeId,
    description: Option[String],
    baseImage: BaseImage,
    roles: List[CustomisedRole],
    createdBy: String,
    bakeSchedule: Option[BakeSchedule])(implicit dynamo: Dynamo): Recipe = {
    val now = DateTime.now()
    val recipe = Recipe(id, description, baseImage, roles, createdBy, createdAt = now, modifiedBy = createdBy, modifiedAt = now, bakeSchedule)
    table.put(Recipe.domain2db(recipe, nextBuildNumber = 0)).exec()

    recipe
  }

  def update(recipe: Recipe,
    description: Option[String],
    baseImage: BaseImage,
    roles: List[CustomisedRole],
    modifiedBy: String,
    bakeSchedule: Option[BakeSchedule])(implicit dynamo: Dynamo): Either[DynamoReadError, Recipe] = {

    val update = table.update('id -> recipe.id,
      set('description -> description) and
        set('baseImageId -> baseImage.id) and
        set('roles -> roles) and
        set('modifiedBy -> modifiedBy) and
        set('modifiedAt -> DateTime.now()) and
        (if (bakeSchedule.isDefined) set('bakeSchedule -> bakeSchedule) else remove('bakeSchedule))
    )

    update.exec().map(Recipe.db2domain(_, baseImage))
  }

  def findById(id: RecipeId)(implicit dynamo: Dynamo): Option[Recipe] = {
    for {
      dbModel <- table.get('id -> id).exec().flatMap(_.toOption)
      baseImage <- BaseImages.findById(dbModel.baseImageId)
    } yield {
      Recipe.db2domain(dbModel, baseImage)
    }
  }

  def incrementAndGetBuildNumber(id: RecipeId)(implicit dynamo: Dynamo): Option[Int] = {
    val updateRequest = new UpdateItemRequest()
      .withTableName(table.name)
      .withKey(Map("id" -> new AttributeValue(id.value)).asJava)
      .withUpdateExpression("ADD nextBuildNumber :val1")
      .withConditionExpression("attribute_exists(id)") // to ensure the recipe exists in Dynamo
      .withExpressionAttributeValues(Map(":val1" -> new AttributeValue().withN("1")).asJava)
      .withReturnValues(ReturnValue.UPDATED_NEW) // TODO Scanamo doesn't support this
    val updateResult = dynamo.client.updateItem(updateRequest)
    if (updateResult.getAttributes.containsKey("nextBuildNumber"))
      Some(updateResult.getAttributes.get("nextBuildNumber").getN.toInt)
    else
      None
  }

  private def table(implicit dynamo: Dynamo) = dynamo.Tables.recipes.table

}
