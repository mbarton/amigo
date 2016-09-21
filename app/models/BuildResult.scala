package models

sealed abstract class BuildResult

object BuildResult {
  case object Success extends BuildResult
  case object Failure extends BuildResult

  def fromExitCode(code: Int): BuildResult = code match {
    case 0 => Success
    case _ => Failure
  }
}

