package com.convergencelabs.server.db.schema

import java.time.Instant

import scala.util.Failure
import scala.util.Try

import org.apache.commons.lang3.exception.ExceptionUtils

import com.convergencelabs.server.datastore.DeltaHistoryStore
import com.convergencelabs.server.domain.DomainFqn
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx

import grizzled.slf4j.Logging
import com.convergencelabs.server.datastore.DomainDeltaHistory
import com.convergencelabs.server.datastore.DomainDelta

class DomainSchemaManager(
  domainFqn: DomainFqn,
  db: ODatabaseDocumentTx,
  historyStore: DeltaHistoryStore,
  preRelease: Boolean)
    extends AbstractSchemaManager(db, preRelease)
    with Logging {

  def getCurrentVersion(): Try[Int] = {
    this.historyStore.getDomainDBVersion(domainFqn)
  }

  def recordDeltaSuccess(delta: DeltaScript): Try[Unit] = Try {
    val dd = DomainDelta(delta.delta.version, delta.rawScript)
    val history = DomainDeltaHistory(domainFqn, dd, DeltaHistoryStore.Status.Success, None, Instant.now())
    this.historyStore.saveDomainDeltaHistory(history) recoverWith {
      case cause: Exception =>
        logger.error(s"Error creating detla history record for successful domain delta: \n${history}", cause)
        Failure(cause)
    }
  }

  def recordDeltaFailure(delta: DeltaScript, cause: Exception): Unit = {
    val message = ExceptionUtils.getStackTrace(cause)
    val dd = DomainDelta(delta.delta.version, delta.rawScript)
    val history = DomainDeltaHistory(domainFqn, dd, DeltaHistoryStore.Status.Error, Some(message), Instant.now())
    this.historyStore.saveDomainDeltaHistory(history) recover {
      case cause: Exception =>
        logger.error(s"Error creating detla history record for failed domain delta: ${history}")
    }
  }

  def loadManifest(): Try[DeltaManifest] = {
    DeltaManager.domainManifest()
  }
}