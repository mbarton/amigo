package packer

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

import models.packer.PackerBuildConfig
import models.{ BakeId, RecipeId }
import play.api.libs.json.Json

import scala.util.Try

object TempFiles {

  // TODO should try to delete files if writing failed

  def writePlaybookToTempFile(playbookYaml: String, recipeId: RecipeId): Try[Path] = {
    val file = Files.createTempFile(s"amigo-ansible-$recipeId", ".yml")
    writeStringToFile(file, playbookYaml)
  }

  def writePackerBuildConfigToTempFile(packerBuildConfig: PackerBuildConfig, recipeId: RecipeId): Try[Path] = {
    val json = Json.prettyPrint(Json.toJson(packerBuildConfig))
    val file = Files.createTempFile(s"amigo-packer-$recipeId", ".json")
    writeStringToFile(file, json)
  }

  private def writeStringToFile(file: Path, string: String) =
    Try(Files.write(file, string.getBytes(StandardCharsets.UTF_8))).map(_ => file)

}
