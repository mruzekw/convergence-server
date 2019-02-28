package com.convergencelabs.server.api.rest.domain

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import com.convergencelabs.server.api.rest.CreatedResponse
import com.convergencelabs.server.api.rest.ErrorResponse
import com.convergencelabs.server.api.rest.OkResponse
import com.convergencelabs.server.api.rest.RestResponse
import com.convergencelabs.server.api.rest.notFoundResponse
import com.convergencelabs.server.api.rest.okResponse
import com.convergencelabs.server.datastore.domain.UserGroup
import com.convergencelabs.server.datastore.domain.UserGroupInfo
import com.convergencelabs.server.datastore.domain.UserGroupStoreActor.AddUserToGroup
import com.convergencelabs.server.datastore.domain.UserGroupStoreActor.CreateUserGroup
import com.convergencelabs.server.datastore.domain.UserGroupStoreActor.DeleteUserGroup
import com.convergencelabs.server.datastore.domain.UserGroupStoreActor.GetUserGroup
import com.convergencelabs.server.datastore.domain.UserGroupStoreActor.GetUserGroupInfo
import com.convergencelabs.server.datastore.domain.UserGroupStoreActor.GetUserGroupSummaries
import com.convergencelabs.server.datastore.domain.UserGroupStoreActor.GetUserGroups
import com.convergencelabs.server.datastore.domain.UserGroupStoreActor.RemoveUserFromGroup
import com.convergencelabs.server.datastore.domain.UserGroupStoreActor.UpdateUserGroup
import com.convergencelabs.server.datastore.domain.UserGroupStoreActor.UpdateUserGroupInfo
import com.convergencelabs.server.datastore.domain.UserGroupSummary
import com.convergencelabs.server.domain.DomainId
import com.convergencelabs.server.domain.DomainUserId
import com.convergencelabs.server.domain.DomainUserType
import com.convergencelabs.server.domain.rest.RestDomainActor.DomainRestMessage
import com.convergencelabs.server.security.AuthorizationProfile

import akka.actor.ActorRef
import akka.http.scaladsl.marshalling.ToResponseMarshallable.apply
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directive.addByNameNullaryApply
import akka.http.scaladsl.server.Directive.addDirectiveApply
import akka.http.scaladsl.server.Directives._enhanceRouteWithConcatenation
import akka.http.scaladsl.server.Directives._segmentStringToPathMatcher
import akka.http.scaladsl.server.Directives._string2NR
import akka.http.scaladsl.server.Directives.as
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.Directives.delete
import akka.http.scaladsl.server.Directives.entity
import akka.http.scaladsl.server.Directives.get
import akka.http.scaladsl.server.Directives.parameters
import akka.http.scaladsl.server.Directives.path
import akka.http.scaladsl.server.Directives.pathEnd
import akka.http.scaladsl.server.Directives.pathPrefix
import akka.http.scaladsl.server.Directives.post
import akka.http.scaladsl.server.Directives.put
import akka.http.scaladsl.server.PathMatchers.Segment
import akka.http.scaladsl.server.Route
import akka.util.Timeout

object DomainUserGroupService {
  case class UserGroupData(id: String, description: String, members: Set[String])
  case class UserGroupSummaryData(id: String, description: String, members: Int)
  case class UserGroupInfoData(id: String, description: String)
}

class DomainUserGroupService(
  private[this] val executionContext: ExecutionContext,
  private[this] val timeout: Timeout,
  private[this] val domainRestActor: ActorRef)
  extends DomainRestService(executionContext, timeout) {

  import DomainUserGroupService._
  import akka.pattern.ask

  def route(authProfile: AuthorizationProfile, domain: DomainId): Route = {
    pathPrefix("groups") {
      pathEnd {
        get {
          parameters("type".?, "filter".?, "offset".as[Int].?, "limit".as[Int].?) { (responseType, filter, offset, limit) =>
            complete(getUserGroups(domain, responseType, filter, offset, limit))
          }
        } ~ post {
          entity(as[UserGroupData]) { group =>
            complete(createUserGroup(domain, group))
          }
        }
      } ~ pathPrefix(Segment) { groupId =>
        pathEnd {
          get {
            complete(getUserGroup(domain, groupId))
          } ~ delete {
            complete(deleteUserGroup(domain, groupId))
          } ~ put {
            entity(as[UserGroupData]) { updateData =>
              complete(updateUserGroup(domain, groupId, updateData))
            }
          }
        } ~ path("info") {
          get {
            complete(getUserGroupInfo(domain, groupId))
          } ~ put {
            entity(as[UserGroupInfoData]) { updateData =>
              complete(updateUserGroupInfo(domain, groupId, updateData))
            }
          }
        } ~ pathPrefix("members") {
          pathEnd {
            get {
              complete(getUserGroupMembers(domain, groupId))
            }
          } ~ path(Segment) { groupUser =>
            put {
              complete(addUserToGroup(domain, groupId, groupUser))
            } ~ delete {
              complete(removeUserFromGroup(domain, groupId, groupUser))
            }
          }
        }
      }
    }
  }

  def getUserGroups(domain: DomainId, resultType: Option[String], filter: Option[String], offset: Option[Int], limit: Option[Int]): Future[RestResponse] = {
    resultType.getOrElse("all") match {
      case "all" =>
        val message = DomainRestMessage(domain, GetUserGroups(filter, offset, limit))
        (domainRestActor ? message).mapTo[List[UserGroup]] map (groups =>
          okResponse(groups.map(groupToUserGroupData(_))))
      case "summary" =>
        val message = DomainRestMessage(domain, GetUserGroupSummaries(None, limit, offset))
        (domainRestActor ? message).mapTo[List[UserGroupSummary]] map (groups =>
          okResponse(groups.map { c =>
            val UserGroupSummary(id, desc, count) = c
            UserGroupSummaryData(id, desc, count)
          }))
      case t =>
        Future.successful((StatusCodes.BadRequest, ErrorResponse("invalid_type", Some(s"Invalid type: $t"))))
    }
  }

  def getUserGroup(domain: DomainId, groupId: String): Future[RestResponse] = {
    val message = DomainRestMessage(domain, GetUserGroup(groupId))
    (domainRestActor ? message).mapTo[Option[UserGroup]] map {
      case Some(group) => okResponse(groupToUserGroupData(group))
      case None => notFoundResponse()
    }
  }

  def getUserGroupMembers(domain: DomainId, groupId: String): Future[RestResponse] = {
    val message = DomainRestMessage(domain, GetUserGroup(groupId))
    (domainRestActor ? message).mapTo[Option[UserGroup]] map {
      case Some(UserGroup(_, _, members)) => okResponse(members.map(_.username))
      case None => notFoundResponse()
    }
  }

  def getUserGroupInfo(domain: DomainId, groupId: String): Future[RestResponse] = {
    val message = DomainRestMessage(domain, GetUserGroupInfo(groupId))
    (domainRestActor ? message).mapTo[Option[UserGroupInfo]] map {
      case Some(UserGroupInfo(id, description)) =>
        okResponse(UserGroupInfoData(id, description))
      case None =>
        notFoundResponse()
    }
  }

  def updateUserGroupInfo(domain: DomainId, groupId: String, infoData: UserGroupInfoData): Future[RestResponse] = {
    val UserGroupInfoData(id, description) = infoData
    val info = UserGroupInfo(id, description)
    val message = DomainRestMessage(domain, UpdateUserGroupInfo(groupId, info))
    (domainRestActor ? message).mapTo[Unit] map { _ => OkResponse }
  }

  def addUserToGroup(domain: DomainId, groupId: String, username: String): Future[RestResponse] = {
    val message = DomainRestMessage(domain, AddUserToGroup(groupId, DomainUserId.normal(username)))
    (domainRestActor ? message).mapTo[Unit] map (_ => OkResponse)
  }

  def removeUserFromGroup(domain: DomainId, groupId: String, username: String): Future[RestResponse] = {
    val message = DomainRestMessage(domain, RemoveUserFromGroup(groupId, DomainUserId.normal(username)))
    (domainRestActor ? message).mapTo[Unit] map (_ => OkResponse)
  }

  def createUserGroup(domain: DomainId, groupData: UserGroupData): Future[RestResponse] = {
    val group = this.groupDataToUserGroup(groupData)
    val message = DomainRestMessage(domain, CreateUserGroup(group))
    (domainRestActor ? message) map { _ => CreatedResponse }
  }

  def updateUserGroup(domain: DomainId, groupId: String, groupData: UserGroupData): Future[RestResponse] = {
    val group = this.groupDataToUserGroup(groupData)
    val message = DomainRestMessage(domain, UpdateUserGroup(groupId, group))
    (domainRestActor ? message) map { _ => OkResponse }
  }

  def deleteUserGroup(domain: DomainId, groupId: String): Future[RestResponse] = {
    val message = DomainRestMessage(domain, DeleteUserGroup(groupId))
    (domainRestActor ? message) map { _ => OkResponse }
  }

  def groupDataToUserGroup(groupData: UserGroupData): UserGroup = {
    // FIXME is this what we want? Assume normal user?
    val UserGroupData(id, description, members) = groupData
    UserGroup(id, description, members.map(DomainUserId(DomainUserType.Normal, _)))
  }

  def groupToUserGroupData(group: UserGroup): UserGroupData = {
    val UserGroup(id, description, members) = group
    UserGroupData(id, description, members.map(_.username))
  }
}
