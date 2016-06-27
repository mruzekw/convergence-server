package com.convergencelabs.server

import scala.language.postfixOps

import com.convergencelabs.server.datastore.DomainStore
import com.convergencelabs.server.datastore.domain.DomainPersistenceManagerActor
import com.convergencelabs.server.domain.DomainManagerActor
import com.orientechnologies.orient.core.db.OPartitionedDatabasePool

import akka.actor.ActorSystem
import grizzled.slf4j.Logging

class BackendNode(system: ActorSystem, dbPool: OPartitionedDatabasePool) extends Logging {

  def start(): Unit = {
    logger.info("Backend Node starting up.")

    val dbConfig = system.settings.config.getConfig("convergence.convergence-database")

    val domainStore = new DomainStore(dbPool)

    val protocolConfig = ProtocolConfigUtil.loadConfig(system.settings.config)

    system.actorOf(DomainManagerActor.props(
      domainStore,
      protocolConfig),
      DomainManagerActor.RelativeActorPath)

    logger.info("Backend Node started up.")
  }

  def stop(): Unit = {

  }
}