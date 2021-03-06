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

package com.convergencelabs.convergence.server.backend.services.domain.model.value

import com.convergencelabs.convergence.server.model.domain.model.ModelReferenceValues
import com.convergencelabs.convergence.server.model.domain.session.DomainSessionAndUserId

import scala.util.Try

private[model] abstract class RealtimeContainerValue(id: String,
                                      parent: Option[RealtimeContainerValue],
                                      parentField: Option[Any],
                                      validReferenceValueClasses: List[Class[_ <: ModelReferenceValues]])
  extends RealtimeValue(id, parent, parentField, validReferenceValueClasses) {

  def valueAt(path: List[Any]): Option[RealtimeValue]

  def child(childPath: Any): Try[Option[RealtimeValue]]

  override def detach(): Unit = {
    this.detachChildren()
    super.detach()
  }

  def detachChildren(): Unit

  def children: List[RealtimeValue]

  override def sessionDisconnected(session: DomainSessionAndUserId): Unit = {
    this.children.foreach { child => child.sessionDisconnected(session) }
    super.sessionDisconnected(session)
  }
}
