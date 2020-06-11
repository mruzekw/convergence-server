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

package com.convergencelabs.convergence.server.domain.chat

object ChatPermissions {

  final case class ChatPermission(p: String) extends AnyVal

  object Permissions {
    val CreateChat: ChatPermission = ChatPermission("create_chat_channel")
    val RemoveChat: ChatPermission = ChatPermission("remove_chat_channel")
    val JoinChat: ChatPermission = ChatPermission("join_chat_channel")
    val LeaveChat: ChatPermission = ChatPermission("leave_chat_channel")
    val AddUser: ChatPermission = ChatPermission("add_chat_user")
    val RemoveUser: ChatPermission = ChatPermission("remove_chat_user")
    val SetName: ChatPermission = ChatPermission("set_chat_name")
    val SetTopic: ChatPermission = ChatPermission("set_topic")
    val Manage: ChatPermission = ChatPermission("manage_chat_permissions")
  }

  val AllChatChatPermissions: Set[String] = Set(Permissions.RemoveChat, Permissions.JoinChat,
    Permissions.LeaveChat, Permissions.AddUser, Permissions.RemoveUser,
    Permissions.SetName, Permissions.SetTopic, Permissions.Manage).map(_.p)

  val AllChatPermissions: Set[String] = AllChatChatPermissions + Permissions.CreateChat.p

  val DefaultChatPermissions: Set[String] = Set(Permissions.JoinChat, Permissions.LeaveChat).map(_.p)
}
