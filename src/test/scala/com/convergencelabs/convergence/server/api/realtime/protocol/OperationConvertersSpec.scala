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

package com.convergencelabs.convergence.server.api.realtime.protocol

import com.convergencelabs.convergence.server.backend.services.domain.model.ot._
import com.convergencelabs.convergence.server.model.domain.model.DoubleValue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

// scalastyle:off magic.number
class OperationConvertersSpec extends AnyWordSpec with Matchers {

  private[this] val X = "X"

  private[this] val Id = "testId"
  private[this] val NoOp = true
  private[this] val Value = DoubleValue("vid", 2)
  private[this] val Prop = "prop"

  private[this] val operations = List(
    ObjectSetPropertyOperation(Id, NoOp, Prop, Value),
    ObjectAddPropertyOperation(Id, NoOp, Prop, Value),
    ObjectRemovePropertyOperation(Id, NoOp, Prop),
    ObjectSetOperation(Id, NoOp, Map("p" -> Value)),

    ArrayInsertOperation(Id, NoOp, 1, Value),
    ArrayRemoveOperation(Id, NoOp, 1),
    ArrayReplaceOperation(Id, NoOp, 1, Value),
    ArrayMoveOperation(Id, NoOp, 1, 2),
    ArraySetOperation(Id, NoOp, List(Value)),

    StringInsertOperation(Id, NoOp, 1, X),
    StringRemoveOperation(Id, NoOp, 1, X),
    StringSetOperation(Id, NoOp, X),

    NumberSetOperation(Id, NoOp, 3),
    NumberAddOperation(Id, NoOp, 4),

    BooleanSetOperation(Id, NoOp, value = true),

    CompoundOperation(List(NumberSetOperation(Id, NoOp, 3))))

  "An OperationConverter" when {
    "mapping an unmapping operations" must {
      "correctly map and unmap operations" in {
        operations.foreach { op =>
          val data = OperationConverters.mapOutgoing(op)
          val reverted = OperationConverters.mapIncoming(data)
          reverted shouldBe Right(op)
        }
      }
    }
  }
}
