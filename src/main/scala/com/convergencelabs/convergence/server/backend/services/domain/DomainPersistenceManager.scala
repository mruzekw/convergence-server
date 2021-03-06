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

package com.convergencelabs.convergence.server.backend.services.domain

import akka.actor.typed.{ActorRef, ActorSystem}
import com.convergencelabs.convergence.server.backend.datastore.domain.DomainPersistenceProvider
import com.convergencelabs.convergence.server.model.DomainId

import scala.util.Try

/**
 * The [[DomainPersistenceManager]] trait represents a class that can acquire
 * and release a [[DomainPersistenceProvider]]. The DomainPersistenceProvider
 * is typically a heavy weight object backed by a database connection pool
 * and therefore needs to be shared between actors running on the same physical
 * node. This trait allows consumers to indicate that they require access to
 * a persistence provider for some period of time.
 */
trait DomainPersistenceManager {
  /**
   * Acquires a [[DomainPersistenceProvider]] for a specified domain. The
   * [[DomainPersistenceManager]] will ensure the DomainPersistenceProvider
   * remains valid until it is released.
   *
   * @param consumer The actor that will use the persistence provider.
   * @param system The actor system the actor that is requesting the persistence provider.
   * @param domainId The id of th domain to get th persistence provider for.
   *
   * @return The [[DomainPersistenceProvider]] for the specified domain.
   */
  def acquirePersistenceProvider(consumer: ActorRef[_], system: ActorSystem[_], domainId: DomainId): Try[DomainPersistenceProvider]

  /**
   * Indicates that the actor is no longer using the persistence provider and
   * that it can be potentially released.
   *
   * @param consumer The actor that was using the persistence provider.
   * @param system The system of the consuming actor.
   * @param domainId The id of the domain that is being released.
   */
  def releasePersistenceProvider(consumer: ActorRef[_], system: ActorSystem[_], domainId: DomainId): Unit
}
