package scala.meta.internal.builds

import scala.meta.internal.builds.Digest.Status

/**
 * Represents the status of a `workspace/reload` or a `bloopInstall` request.
 */
sealed abstract class WorkspaceLoadedStatus extends Product with Serializable {
  import WorkspaceLoadedStatus._
  def name: String =
    this match {
      case Duplicate(status) => status.toString
      case _ => this.toString
    }
  def isInstalled: Boolean =
    this == Installed || this == Duplicate(Status.Installed)
  def isFailed: Boolean = this.isInstanceOf[Failed]
  def toChecksumStatus: Option[Status] =
    Option(this).collect {
      case Rejected => Status.Rejected
      case Installed => Status.Installed
      case Cancelled => Status.Cancelled
      case _: Failed => Status.Failed
    }
}
object WorkspaceLoadedStatus {
  case object Dismissed extends WorkspaceLoadedStatus
  case class Duplicate(status: Status) extends WorkspaceLoadedStatus
  case object Rejected extends WorkspaceLoadedStatus
  case object Unchanged extends WorkspaceLoadedStatus
  case object Installed extends WorkspaceLoadedStatus
  case object Cancelled extends WorkspaceLoadedStatus
  case class Failed(exit: Int, stdout: String, stderr: String) extends WorkspaceLoadedStatus
}
