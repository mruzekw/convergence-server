package com.convergencelabs.server.datastore.convergence

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import com.convergencelabs.server.db.DatabaseProvider
import com.convergencelabs.server.datastore.StoreActor
import com.convergencelabs.server.domain.DomainFqn
import com.convergencelabs.server.datastore.convergence.RoleStore.Role

import akka.actor.ActorLogging
import akka.actor.Props
import akka.util.Timeout
import com.convergencelabs.server.datastore.convergence.RoleStore.UserRole
import scala.util.Try

object RoleStoreActor {
  val RelativePath = "RoleStoreActor"

  def props(dbProvider: DatabaseProvider): Props = Props(new RoleStoreActor(dbProvider))

  case class CreateRoleRequest(role: Role)
  case class SetRolesRequest(username: String, target: RoleTarget, roles: Set[String])

  case class GetRoleProfileRequest(target: RoleTarget, username: String)
  case class GetAllUserRolesRequest(target: RoleTarget)

  case class GetUserRolesForTargetRequest(username: String, target: RoleTarget)
  case class UpdateRolesForTargetRequest(target: RoleTarget, userRoles: Map[String, Set[String]])

  case class RemoveUserFromTarget(target: RoleTarget, username: String)

  case class GetUserPermissionsRequest(username: String, target: RoleTarget)
}

class RoleStoreActor private[datastore] (private[this] val dbProvider: DatabaseProvider) extends StoreActor
  with ActorLogging {

  import RoleStoreActor._

  // FIXME: Read this from configuration
  private[this] implicit val requstTimeout = Timeout(2 seconds)
  private[this] implicit val exectionContext = context.dispatcher

  private[this] val permissionsStore: RoleStore = new RoleStore(dbProvider)

  def receive: Receive = {
    case message: CreateRoleRequest => createRole(message)
    case message: SetRolesRequest => setRolesRequest(message)
    case message: GetRoleProfileRequest => getPermissionsProfile(message)
    case message: GetAllUserRolesRequest => getAllUserRoles(message)
    case message: GetUserRolesForTargetRequest => getUserRoles(message)
    case message: GetUserPermissionsRequest => getUserPermissions(message)
    case message: UpdateRolesForTargetRequest => updateRolesForTarget(message)
    case message: RemoveUserFromTarget => removeUserRoleFromTarget(message)
    case message: Any => unhandled(message)
  }

  def createRole(message: CreateRoleRequest): Unit = {
    val CreateRoleRequest(role) = message
    reply(permissionsStore.createRole(role))
  }

  def setRolesRequest(message: SetRolesRequest): Unit = {
    val SetRolesRequest(username, target, roles) = message
    reply(permissionsStore.setUserRolesForTarget(username, target, roles))
  }
  
  def updateRolesForTarget(message: UpdateRolesForTargetRequest): Unit = {
    val UpdateRolesForTargetRequest(target, userRoles) = message
    reply(permissionsStore.setUserRolesForTarget(target, userRoles))
  }

  def getPermissionsProfile(message: GetRoleProfileRequest): Unit = {
    val GetRoleProfileRequest(target, username) = message
    reply(permissionsStore.getUserRolesForTarget(username, target).map { roles => new RoleProfile(roles) })
  }

  def getAllUserRoles(message: GetAllUserRolesRequest): Unit = {
    val GetAllUserRolesRequest(target) = message
    reply(permissionsStore.getAllUserRolesForTarget(target))
  }

  def getUserRoles(message: GetUserRolesForTargetRequest): Unit = {
    val GetUserRolesForTargetRequest(username, target) = message
    reply(permissionsStore.getUserRolesForTarget(username, target))
  }

  def getUserPermissions(message: GetUserPermissionsRequest): Unit = {
    val GetUserPermissionsRequest(username, target) = message
    reply(permissionsStore.getUserPermissionsForTarget(username, target))
  }
  
  def removeUserRoleFromTarget(message: RemoveUserFromTarget): Unit = {
    val RemoveUserFromTarget(target, username) = message
    reply(permissionsStore.removeUserRoleFromTarget(target, username))
  }
}
