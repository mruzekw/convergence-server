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

package com.convergencelabs.convergence.server.db.data

import java.io.InputStream

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.json4s.jackson.JsonMethods
import org.json4s.{Extraction, Formats}

import scala.util.Try

class DomainScriptSerializer {
  private[this] val mapper = new ObjectMapper(new YAMLFactory())

  implicit val formats: Formats = JsonFormats.format

  def deserialize(in: InputStream): Try[DomainScript] = Try {
    val jsonNode = mapper.readTree(in)
    val jValue = JsonMethods.fromJsonNode(jsonNode)
    Extraction.extract[DomainScript](jValue)
  }
}