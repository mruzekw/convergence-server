package com.convergencelabs.server.datastore.convergence

import scala.util.Success
import scala.util.Try

import com.convergencelabs.server.datastore.AbstractDatabasePersistence
import com.convergencelabs.server.datastore.OrientDBUtil
import com.convergencelabs.server.db.DatabaseProvider
import com.convergencelabs.server.domain.DomainFqn
import com.orientechnologies.orient.core.storage.ORecordDuplicatedException

import grizzled.slf4j.Logging

object UserFavoriteDomainStore {
  object Params {
    val Username = "username"
    val DomainId = "domainId"
    val NamespaceId = "namespaceId"
  }
}

class UserFavoriteDomainStore(private[this] val dbProvider: DatabaseProvider)
  extends AbstractDatabasePersistence(dbProvider)
  with Logging {

  import UserFavoriteDomainStore._

  private[this] val CreateFavoriteCommand =
    """INSERT INTO UserFavoriteDomain SET
      |  user = (SELECT FROM User WHERE username = :username),
      |  domain = (SELECT FROM Domain WHERE id = :domainId AND namespace.id = :namespaceId)""".stripMargin
  def addFavorite(username: String, domain: DomainFqn): Try[Unit] = withDb { db =>
    val params = Map(Params.Username -> username, Params.DomainId -> domain.domainId, Params.NamespaceId -> domain.namespace)
    OrientDBUtil.command(db, CreateFavoriteCommand, params)
      .map(_ => ())
      .recoverWith {
        case cause: ORecordDuplicatedException =>
          Success(())
      }
  }

  private[this] val GetFavoritesForUser =
    "SELECT domain.id as id, domain.namespace.id as namespace FROM UserFavoriteDomain WHERE user.username = :username"
  def getFavoritesForUser(username: String): Try[List[DomainFqn]] = withDb { db =>
    val params = Map(Params.Username -> username)
    OrientDBUtil.queryAndMap(db, GetFavoritesForUser, params) { doc =>
      val domain: String = doc.getProperty("id")
      val namespace: String = doc.getProperty("namespace")
      DomainFqn(namespace, domain)
    }
  }

  private[this] val DeleteFavoriteCommand =
    "DELETE FROM UserFavoriteDomain WHERE user.username = :username AND domain.id = :domainId AND domain.namespace.id = :namespaceId"
  def removeFavorite(username: String, domain: DomainFqn): Try[Unit] = withDb { db =>
    val params = Map(Params.Username -> username, Params.DomainId -> domain.domainId, Params.NamespaceId -> domain.namespace)
    OrientDBUtil.command(db, DeleteFavoriteCommand, params).map(_ => ())
  }

  private[this] val DeleteFavoritesForUserCommand =
    "DELETE FROM UserFavoriteDomain WHERE user.username = :username"
  def removeFavorite(username: String): Try[Unit] = withDb { db =>
    val params = Map(Params.Username -> username)
    OrientDBUtil.command(db, DeleteFavoriteCommand, params).map(_ => ())
  }

  private[this] val DeleteFavoritesForDomainCommand =
    "DELETE FROM UserFavoriteDomain WHERE domain.id = :domainId AND domain.namespace.id = :namespaceId"
  def removeFavorite(domain: DomainFqn): Try[Unit] = withDb { db =>
    val params = Map(Params.DomainId -> domain.domainId, Params.NamespaceId -> domain.namespace)
    OrientDBUtil.command(db, DeleteFavoriteCommand, params).map(_ => ())
  }
}
