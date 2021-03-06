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

package com.convergencelabs.convergence.server.backend.services.domain.chat.processors.event

import java.time.Instant

import com.convergencelabs.convergence.server.model.domain.user.DomainUserId
import com.convergencelabs.convergence.server.model.DomainId
import com.convergencelabs.convergence.server.model.domain.chat
import com.convergencelabs.convergence.server.model.domain.chat.{ChatMember, ChatMembership, ChatState, ChatType}

trait TestConstants {
  val domainId: DomainId = DomainId("ns", "d")
  val chatId = "chatId"

  val requester: DomainUserId = DomainUserId.normal("requester")

  val member1: ChatMember = chat.ChatMember(chatId, DomainUserId.normal("user1"), 0)
  val member2: ChatMember = chat.ChatMember(chatId, DomainUserId.normal("user2"), 0)
  val nonMember: DomainUserId = DomainUserId.normal("user3")

  val state: ChatState = chat.ChatState(chatId,
    ChatType.Channel,
    Instant.now(),
    ChatMembership.Public,
    "chat name",
    "chat topic",
    Instant.now(),
    1,
    Map(member1.userId -> member1, member2.userId -> member2))
}
