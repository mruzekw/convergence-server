/*
 * Copyright (c) 2019 - Convergence Labs, Inc.
 *
 * This file is part of the Convergence Server, which is released under
 * the terms of the GNU General Public License version 3 (GPLv3). A copy
 * of the GPLv3 should have been provided along with this file, typically
 * located in the "LICENSE" file, which is part of this source code package.
 * Alternatively, see <https://www.gnu.org/licenses/gpl-3.0.html> for the
 * full text of the GPLv3 license, if it was not provided.
 */

package com.convergencelabs.convergence.server.datastore.domain

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import com.convergencelabs.convergence.common.PagedData
import com.convergencelabs.convergence.server.actor.CborSerializable
import com.convergencelabs.convergence.server.datastore.domain.DomainUserStore.{CreateNormalDomainUser, UpdateDomainUser}
import com.convergencelabs.convergence.server.datastore.{DuplicateValueException, EntityNotFoundException, SortOrder}
import com.convergencelabs.convergence.server.domain.rest.DomainRestActor.DomainRestMessageBody
import com.convergencelabs.convergence.server.domain.{DomainUser, DomainUserId}

import scala.util.Success


class UserStoreActor private(context: ActorContext[UserStoreActor.Message],
                             userStore: DomainUserStore)
  extends AbstractBehavior[UserStoreActor.Message](context) {

  import UserStoreActor._

  override def onMessage(msg: Message): Behavior[Message] = {
    msg match {
      case message: GetUserRequest =>
        onGetUser(message)
      case message: CreateUserRequest =>
        onCreateUser(message)
      case message: DeleteUserRequest =>
        onDeleteUser(message)
      case message: UpdateUserRequest =>
        onUpdateUser(message)
      case message: SetPasswordRequest =>
        onSetPassword(message)
      case message: GetUsersRequest =>
        onGetUsers(message)
      case message: FindUsersRequest =>
        onFindUser(message)
    }

    Behaviors.same
  }

  private[this] def onGetUsers(message: GetUsersRequest): Unit = {
    val GetUsersRequest(filter, offset, limit, replyTo) = message
    (filter match {
      case Some(filterString) =>
        userStore.searchUsersByFields(
          List(DomainUserField.Username, DomainUserField.Email),
          filterString,
          Some(DomainUserField.Username),
          Some(SortOrder.Ascending),
          offset,
          limit)
      case None =>
        userStore.getAllDomainUsers(Some(DomainUserField.Username), Some(SortOrder.Ascending), limit, offset)
    })
      .map(Right(_))
      .recover { cause =>
        context.log.error("Unexpected error getting users", cause)
        Left(UnknownError())
      }
      .foreach(replyTo ! GetUsersResponse(_))
  }

  private[this] def onGetUser(message: GetUserRequest): Unit = {
    val GetUserRequest(userId, replyTo) = message
    userStore
      .getDomainUser(userId)
      .map(_.map(Right(_)).getOrElse(Left(UserNotFoundError())))
      .recover { cause =>
        context.log.error("Unexpected error getting user", cause)
        Left(UnknownError())
      }
      .foreach(replyTo ! GetUserResponse(_))
  }

  private[this] def onFindUser(message: FindUsersRequest): Unit = {
    val FindUsersRequest(search, exclude, limit, offset, replyTo) = message
    userStore
      .findUser(search, exclude.getOrElse(List()), offset.getOrElse(0), limit.getOrElse(10))
      .map(Right(_))
      .recover { cause =>
        context.log.error("Unexpected error finding users", cause)
        Left(UnknownError())
      }
      .foreach(replyTo ! FindUsersResponse(_))
  }

  private[this] def onCreateUser(message: CreateUserRequest): Unit = {
    val CreateUserRequest(username, firstName, lastName, displayName, email, password, replyTo) = message
    val domainUser = CreateNormalDomainUser(username, firstName, lastName, displayName, email)
    userStore
      .createNormalDomainUser(domainUser)
      .flatMap { createResult =>
        // FIXME this only works as a hack because of the way our create result works.
        password match {
          case None =>
            Success(createResult)
          case Some(pw) =>
            userStore.setDomainUserPassword(username, pw) map { _ =>
              createResult
            }
        }
      }
      .map(Right(_))
      .recover {
        case DuplicateValueException(field, _, _) =>
          Left(UserAlreadyExistsError(field))
        case cause =>
          context.log.error("Unexpected error creating user", cause)
          Left(UnknownError())
      }
      .foreach(replyTo ! CreateUserResponse(_))
  }

  private[this] def onUpdateUser(message: UpdateUserRequest): Unit = {
    val UpdateUserRequest(username, firstName, lastName, displayName, email, disabled, replyTo) = message
    val domainUser = UpdateDomainUser(DomainUserId.normal(username), firstName, lastName, displayName, email, disabled)
    userStore
      .updateDomainUser(domainUser)
      .map(_ => Right(()))
      .recover {
        case _: EntityNotFoundException =>
          Left(UserNotFoundError())
        case cause =>
          context.log.error("Unexpected error updating user", cause)
          Left(UnknownError())
      }
      .foreach(replyTo ! UpdateUserResponse(_))
  }

  private[this] def onSetPassword(message: SetPasswordRequest): Unit = {
    val SetPasswordRequest(username, password, replyTo) = message
    userStore
      .setDomainUserPassword(username, password)
      .map(_ => Right(()))
      .recover {
        case _: EntityNotFoundException =>
          Left(UserNotFoundError())
        case cause =>
          context.log.error("Unexpected error setting user password", cause)
          Left(UnknownError())
      }
      .foreach(replyTo ! SetPasswordResponse(_))
  }

  private[this] def onDeleteUser(message: DeleteUserRequest): Unit = {
    val DeleteUserRequest(username, replyTo) = message
    userStore
      .deleteNormalDomainUser(username)
      .map(_ => Right(()))
      .recover {
        case _: EntityNotFoundException =>
          Left(UserNotFoundError())
        case cause =>
          context.log.error("Unexpected error deleting user", cause)
          Left(UnknownError())
      }
      .foreach(replyTo ! DeleteUserResponse(_))
  }
}


object UserStoreActor {
  def apply(userStore: DomainUserStore): Behavior[Message] =
    Behaviors.setup(context => new UserStoreActor(context, userStore))

  /////////////////////////////////////////////////////////////////////////////
  // Message Protocol
  /////////////////////////////////////////////////////////////////////////////

  sealed trait Message extends CborSerializable with DomainRestMessageBody

  //
  // GetUsers
  //
  final case class GetUsersRequest(filter: Option[String],
                             offset: Option[Int],
                             limit: Option[Int],
                             replyTo: ActorRef[GetUsersResponse]) extends Message

  sealed trait GetUsersError

  final case class GetUsersResponse(users: Either[GetUsersError, PagedData[DomainUser]]) extends CborSerializable

  //
  // GetUsers
  //
  case class GetUserRequest(userId: DomainUserId, replyTo: ActorRef[GetUserResponse]) extends Message

  sealed trait GetUserError

  final case class GetUserResponse(user: Either[GetUserError, DomainUser]) extends CborSerializable

  //
  // FindUsers
  //
  case class FindUsersRequest(filter: String,
                              exclude: Option[List[DomainUserId]],
                              offset: Option[Int],
                              limit: Option[Int],
                              replyTo: ActorRef[FindUsersResponse]) extends Message

  sealed trait FindUsersError

  final case class FindUsersResponse(users: Either[FindUsersError, PagedData[DomainUser]]) extends CborSerializable

  //
  // CreateUser
  //

  case class CreateUserRequest(username: String,
                               firstName: Option[String],
                               lastName: Option[String],
                               displayName: Option[String],
                               email: Option[String],
                               password: Option[String],
                               replyTo: ActorRef[CreateUserResponse]) extends Message

  sealed trait CreateUserError

  final case class UserAlreadyExistsError(field: String) extends CreateUserError

  final case class CreateUserResponse(username: Either[CreateUserError, String]) extends CborSerializable

  //
  // DeleteUser
  //
  final case class DeleteUserRequest(username: String, replyTo: ActorRef[DeleteUserResponse]) extends Message

  sealed trait DeleteUserError

  final case class DeleteUserResponse(response: Either[DeleteUserError, Unit]) extends CborSerializable

  //
  // UpdateUser
  //
  final case class UpdateUserRequest(username: String,
                               firstName: Option[String],
                               lastName: Option[String],
                               displayName: Option[String],
                               email: Option[String],
                               disabled: Option[Boolean],
                               replyTo: ActorRef[UpdateUserResponse]) extends Message

  sealed trait UpdateUserError

  final case class UpdateUserResponse(response: Either[UpdateUserError, Unit]) extends CborSerializable

  //
  // SetPassword
  //
  final case class SetPasswordRequest(uid: String,
                                password: String,
                                replyTo: ActorRef[SetPasswordResponse]) extends Message

  sealed trait SetPasswordError

  final case class SetPasswordResponse(response: Either[SetPasswordError, Unit]) extends CborSerializable

  //
  // Common Errors
  //

  final case class UserNotFoundError() extends AnyRef
    with GetUserError
    with DeleteUserError
    with UpdateUserError
    with SetPasswordError

  final case class UnknownError() extends AnyRef
    with GetUsersError
    with GetUserError
    with FindUsersError
    with CreateUserError
    with UpdateUserError
    with DeleteUserError
    with SetPasswordError

}
