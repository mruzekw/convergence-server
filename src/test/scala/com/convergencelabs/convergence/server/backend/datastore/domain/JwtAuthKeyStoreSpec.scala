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

package com.convergencelabs.convergence.server.backend.datastore.domain

import java.text.SimpleDateFormat
import java.time.Instant

import com.convergencelabs.convergence.server.backend.datastore.domain.jwt.JwtAuthKeyStore
import com.convergencelabs.convergence.server.backend.db.DatabaseProvider
import com.convergencelabs.convergence.server.backend.db.schema.NonRecordingSchemaManager
import com.convergencelabs.convergence.server.model.domain.jwt
import com.convergencelabs.convergence.server.util.{QueryLimit, QueryOffset}
import org.scalatest.TryValues.convertTryToSuccessOrFailure
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

// scalastyle:off line.size.limit
class JwtAuthKeyStoreSpec
    extends PersistenceStoreSpec[JwtAuthKeyStore](NonRecordingSchemaManager.SchemaType.Domain)
    with AnyWordSpecLike
    with Matchers {

  private val df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSz")

  private val jwtAuthKey = jwt.JwtAuthKey(
    "test",
    "A key for testing",
    Instant.ofEpochMilli(df.parse("2015-09-21T12:32:43.000+0000").getTime),
    "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAlqHvXYPseLVx2vCSCipw\nxOumeB2t3JFxXnQ1qC9QMcAydKT7oeTZEbJxgbpaeEy2QkH7J8drkfu78RBh9nFb\nJ3yh3/cZS09wMM4NQsmqqpee4qHromwR1N4MgYAzDGcnl0iHeaYc56tHt5n5ENjG\n+bjEULEGIFVbDs2MBxyswchyqRFGHse41uLxhybhDNkEQLUcqkbVesqVXGsz25Hi\nBdxfYRJ9+2x70GwxDf0nBfZb1fvUFZnCDJHKO/wqv5k9BcuCDLUOdRgJa7+E721V\nSvopddtRgEFnEWoOilcUWtF03CUy4tw3hv1VDcbXKsEma0KjP5IAvWFVI6dbDHeh\nLQIDAQAB\n-----END PUBLIC KEY-----\n",
    enabled = true)


  def createStore(dbProvider: DatabaseProvider): JwtAuthKeyStore = new JwtAuthKeyStore(dbProvider)

  "A ApiKeyStore" when {
    "retrieving domain keys" must {
      "return the correct list of all keys" in withPersistenceStore { store =>
        store.importKey(jwtAuthKey)
        store.getKeys(QueryOffset(), QueryLimit()).success.get shouldBe List(jwtAuthKey)
      }

      "return the correct key by id" in withPersistenceStore { store =>
        store.importKey(jwtAuthKey)
        store.getKey(jwtAuthKey.id).success.value.get shouldBe jwtAuthKey
      }
    }
  }
}
