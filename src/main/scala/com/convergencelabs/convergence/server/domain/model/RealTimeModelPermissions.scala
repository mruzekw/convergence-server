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

package com.convergencelabs.convergence.server.domain.model

import com.convergencelabs.convergence.server.datastore.domain.{CollectionPermissions, ModelPermissions}
import com.convergencelabs.convergence.server.domain.DomainUserId

case class RealTimeModelPermissions(
    overrideCollection: Boolean,
    collectionWorld: CollectionPermissions,
    collectionUsers: Map[DomainUserId, CollectionPermissions],
    modelWorld: ModelPermissions,
    modelUsers: Map[DomainUserId, ModelPermissions]) {

  def resolveSessionPermissions(userId: DomainUserId): ModelPermissions = {
    if (userId.isConvergence) {
      ModelPermissions(read = true, write = true, remove = true, manage = true)
    } else {
      if (overrideCollection) {
        modelUsers.getOrElse(userId, modelWorld)
      } else {
        val CollectionPermissions(create, read, write, remove, manage) = collectionUsers.getOrElse(userId, collectionWorld)
        ModelPermissions(read, write, remove, manage)
      }
    }
  }
}
