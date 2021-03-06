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

package com.convergencelabs.convergence.server.backend.services.domain.model.ot

object RangeRelationshipUtil {

  def getRangeIndexRelationship(rStart: Int, rEnd: Int, index: Int): RangeIndexRelationship.Value = {
    if (index < rStart) {
      RangeIndexRelationship.Before
    } else if (index > rEnd) {
      RangeIndexRelationship.After
    } else if (index == rStart) {
      RangeIndexRelationship.Start
    } else if (index == rEnd) {
      RangeIndexRelationship.End
    } else {
      RangeIndexRelationship.Within
    }
  }

  // scalastyle:off cyclomatic.complexity
  def getRangeRangeRelationship(sStart: Int, sEnd: Int, cStart: Int, cEnd: Int): RangeRangeRelationship.Value = {
    if (sStart == cStart) {
      if (sEnd == cEnd) {
        RangeRangeRelationship.EqualTo
      } else if (cEnd > sEnd) {
        RangeRangeRelationship.Starts
      } else {
        RangeRangeRelationship.StartedBy
      }
    } else if (sStart > cStart) {
      if (sStart > cEnd) {
        RangeRangeRelationship.PrecededBy
      } else if (cEnd == sEnd) {
        RangeRangeRelationship.Finishes
      } else if (sEnd < cEnd) {
        RangeRangeRelationship.ContainedBy
      } else if (sStart == cEnd) {
        RangeRangeRelationship.MetBy
      } else {
        RangeRangeRelationship.OverlappedBy
      }
    } else { // sStart < cStart
      if (sEnd < cStart) {
        RangeRangeRelationship.Precedes
      } else if (cEnd == sEnd) {
        RangeRangeRelationship.FinishedBy
      } else if (sEnd > cEnd) {
        RangeRangeRelationship.Contains
      } else if (sEnd == cStart) {
        RangeRangeRelationship.Meets
      } else {
        RangeRangeRelationship.Overlaps
      }
    }
  }
  // scalastyle:on cyclomatic.complexity
}
