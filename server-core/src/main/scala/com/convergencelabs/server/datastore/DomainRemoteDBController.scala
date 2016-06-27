package com.convergencelabs.server.datastore

import java.time.{ Duration => JavaDuration }
import java.time.temporal.ChronoUnit
import java.util.UUID

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.io.Source
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.convergencelabs.server.datastore.domain.DomainPersistenceProvider
import com.convergencelabs.server.domain.JwtUtil
import com.convergencelabs.server.domain.ModelSnapshotConfig
import com.convergencelabs.server.domain.TokenKeyPair
import com.orientechnologies.orient.client.remote.OServerAdmin
import com.orientechnologies.orient.core.db.OPartitionedDatabasePool
import com.typesafe.config.Config

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.RequestEntity
import akka.http.scaladsl.model.Uri.apply
import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.stream.ActorMaterializer
import grizzled.slf4j.Logging

object DomainRemoteDBController {
  val DefaultSnapshotConfig = ModelSnapshotConfig(
    false,
    false,
    false,
    250,
    1000,
    false,
    false,
    JavaDuration.of(0, ChronoUnit.MINUTES),
    JavaDuration.of(0, ChronoUnit.MINUTES))
}

case class DBConfig(dbName: String, username: String, password: String)

class DomainDBController(
  val orientDbConfig: Config,
  val domainDbConfig: Config,
  implicit val system: ActorSystem)
    extends Logging {

  val AdminUser = orientDbConfig.getString("admin-username")
  val AdminPassword = orientDbConfig.getString("admin-password")
  val BaseDbUri = orientDbConfig.getString("db-uri")
  val BaseRestUri = orientDbConfig.getString("rest-uri")

  val Username = domainDbConfig.getString("username")
  val DefaultPassword = domainDbConfig.getString("default-password")
  val Schema = domainDbConfig.getString("schema")

  val DBType = "document"
  val StorageMode = "plocal"

  implicit val materializer = ActorMaterializer()
  implicit val ec = system.dispatcher

  def createDomain(importFile: Option[String]): Future[DBConfig] = {
    val id = UUID.randomUUID().getLeastSignificantBits().toString()

    val uri = s"${BaseDbUri}/${id}"
    logger.debug(s"Creating domain database: $uri")
    val serverAdmin = new OServerAdmin(uri)
    serverAdmin.connect(AdminUser, AdminPassword)
      .createDatabase(DBType, StorageMode)
      .close()
    logger.debug(s"Domain database created at: $uri")

    val importContents = Source.fromFile(importFile.getOrElse(Schema)).mkString
    val importApi = s"${BaseRestUri}/import/${id}"

    // FIXME A bit of a hack, but don't feel like messing with futures at the moment.
    val importEntity = Await.result(Marshal(importContents).to[RequestEntity], Duration.Inf)
    val authHeader = Authorization(BasicHttpCredentials("admin", "admin"))
    val importPost = HttpRequest(method = HttpMethods.POST,
      uri = importApi, headers = List(authHeader), entity = importEntity)

    logger.debug(s"Starting database import: $importPost")
    val f = Http().singleRequest(importPost)
    f.flatMap { response =>
      logger.debug(s"Import completed successfully: $response")
      initDomain(uri, Username, DefaultPassword) match {
        case Success(()) =>
          Future.successful(DBConfig(id, Username, DefaultPassword))
        case Failure(f) =>
          Future.failed(f)
      }
    }
  }

  private[this] def initDomain(uri: String, username: String, password: String): Try[Unit] = Try {
    logger.debug(s"Initializing domain: $uri")
    val pool = new OPartitionedDatabasePool(uri, username, password, 64, 1)
    val persistenceProvider = new DomainPersistenceProvider(pool)
    persistenceProvider.validateConnection()
    logger.debug(s"Connected to domain database: $uri")
    persistenceProvider
  } flatMap {
    case persistenceProvider =>
      logger.debug(s"Generating admin key: $uri")
      JwtUtil.createKey().flatMap { rsaJsonWebKey =>
        for {
          publicKey <- JwtUtil.getPublicCertificatePEM(rsaJsonWebKey)
          privateKey <- JwtUtil.getPrivateKeyPEM(rsaJsonWebKey)
        } yield {
          new TokenKeyPair(publicKey, privateKey)
        }
      } flatMap { keyPair =>
        logger.debug(s"Created public key for domain: $uri")
        if (persistenceProvider.configStore.isInitialized().get) {
          logger.debug(s"Domain alreay initialized, updating keys: $uri")
          persistenceProvider.configStore.setAdminKeyPair(keyPair)
        } else {
          logger.debug(s"Domain not initialized, iniitalizing: $uri")
          persistenceProvider.configStore.initializeDomainConfig(
            keyPair,
            DomainRemoteDBController.DefaultSnapshotConfig)
        }
      } match {
        case s: Success[_] =>
          logger.debug(s"Domain initialized: $uri")
          persistenceProvider.shutdown()
          s
        case f @ Failure(cause)  =>
          logger.error(s"Failure initializing domain: $uri", cause)
          persistenceProvider.shutdown()
          f
      }
  }

  def deleteDomain(id: String): Unit = {
    val serverAdmin = new OServerAdmin(s"${BaseDbUri}/${id}")
    serverAdmin.connect(AdminUser, AdminPassword).dropDatabase(id).close()
  }
}