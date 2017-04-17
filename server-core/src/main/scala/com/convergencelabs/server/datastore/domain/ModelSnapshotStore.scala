package com.convergencelabs.server.datastore.domain

import java.time.Instant
import java.util.Date
import java.util.{ List => JavaList }

import scala.collection.JavaConversions.asScalaBuffer
import scala.collection.JavaConverters.asScalaBufferConverter
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.collection.immutable.HashMap
import scala.util.Try

import com.convergencelabs.server.datastore.AbstractDatabasePersistence
import com.convergencelabs.server.datastore.DatabaseProvider
import com.convergencelabs.server.datastore.QueryUtil
import com.convergencelabs.server.datastore.domain.mapper.ObjectValueMapper.ODocumentToObjectValue
import com.convergencelabs.server.datastore.domain.mapper.ObjectValueMapper.ObjectValueToODocument
import com.convergencelabs.server.domain.model.ModelSnapshot
import com.convergencelabs.server.domain.model.ModelSnapshotMetaData
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx
import com.orientechnologies.orient.core.record.impl.ODocument
import com.orientechnologies.orient.core.sql.OCommandSQL
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery

import ModelSnapshotStore.Constants.CollectionId
import ModelSnapshotStore.Constants.ModelId
import ModelSnapshotStore.Fields.Data
import ModelSnapshotStore.Fields.Model
import ModelSnapshotStore.Fields.Timestamp
import ModelSnapshotStore.Fields.Version
import grizzled.slf4j.Logging

object ModelSnapshotStore {
  val ClassName = "ModelSnapshot"

  object Fields {
    val Model = "model"
    val Version = "version"
    val Timestamp = "timestamp"
    val Data = "data"
  }

  object Constants {
    val CollectionId = "collectionId"
    val ModelId = "modelId"
  }

  def docToModelSnapshot(doc: ODocument): ModelSnapshot = {
    val dataDoc: ODocument = doc.field(Fields.Data)
    val data = dataDoc.asObjectValue
    ModelSnapshot(docToModelSnapshotMetaData(doc), data)
  }

  def modelSnapshotToDoc(modelSnapshot: ModelSnapshot, db: ODatabaseDocumentTx): Try[ODocument] = {
    val md = modelSnapshot.metaData
    ModelStore.getModelRid(md.modelId, db) map { modelRid =>
      val doc = new ODocument(ClassName)
      doc.field(Model, modelRid)
      doc.field(Version, modelSnapshot.metaData.version)
      doc.field(Timestamp, new Date(modelSnapshot.metaData.timestamp.toEpochMilli()))
      doc.field(Data, modelSnapshot.data.asODocument)
      doc
    }
  }

  def docToModelSnapshotMetaData(doc: ODocument): ModelSnapshotMetaData = {
    val timestamp: java.util.Date = doc.field(Fields.Timestamp)
    ModelSnapshotMetaData(
      doc.field(ModelId),
      doc.field(Fields.Version),
      Instant.ofEpochMilli(timestamp.getTime))
  }
}

/**
 * Manages the persistence of model snapshots.
 *
 * @constructor Creates a new ModelSnapshotStore using the provided database pool.
 * @param dbPool The database pool to use for connections.
 */
class ModelSnapshotStore private[domain] (
  private[this] val dbProvider: DatabaseProvider)
    extends AbstractDatabasePersistence(dbProvider)
    with Logging {

  val AllFields = s"version, timestamp, model.collection.id as collectionId, model.id as modelId, data"
  val MetaDataFields = s"version, timestamp, model.collection.id as collectionId, model.id as modelId"

  /**
   * Creates a new snapshot in the database.  The combination of modelId and
   * version must be unique in the database.
   *
   * @param snapshotData The snapshot to create.
   */
  def createSnapshot(modelSnapshot: ModelSnapshot): Try[Unit] = tryWithDb { db =>
    val doc = ModelSnapshotStore.modelSnapshotToDoc(modelSnapshot, db).get
    db.save(doc)
    ()
  }

  /**
   * Gets a snapshot for the specified model and version.
   *
   * @param id The model id of the snapshot to get.
   * @param version The version of the snapshot to get.
   *
   * @return Some(SnapshotData) if a snapshot corresponding to the model and
   * version if it exists, or None if it does not.
   */
  def getSnapshot(id: String, version: Long): Try[Option[ModelSnapshot]] = tryWithDb { db =>
    val queryString =
      s"""SELECT ${AllFields}
        |FROM ModelSnapshot
        |WHERE
        |  model.id = :modelId AND
        |  version = :version""".stripMargin

    val query = new OSQLSynchQuery[ODocument](queryString)
    val params = HashMap(
      ModelId -> id,
      Version -> version)

    val result: JavaList[ODocument] = db.command(query).execute(params.asJava)
    QueryUtil.mapSingletonList(result) { ModelSnapshotStore.docToModelSnapshot(_) }
  }

  /**
   * Gets snapshots for the specified model.
   *
   * @param ud The model id of the snapshot to get.
   * @param version The version of the snapshot to get.
   *
   * @return Some(SnapshotData) if a snapshot corresponding to the model and
   * version if it exists, or None if it does not.
   */
  def getSnapshots(id: String): Try[List[ModelSnapshot]] = tryWithDb { db =>
    val queryString =
      s"""SELECT ${AllFields}
        |FROM ModelSnapshot
        |WHERE
        |  model.id = :modelId""".stripMargin

    val query = new OSQLSynchQuery[ODocument](queryString)
    val params = HashMap(
      ModelId -> id)

    val result: JavaList[ODocument] = db.command(query).execute(params.asJava)
    result.toList map { ModelSnapshotStore.docToModelSnapshot(_) }
  }

  /////////////////////////////////////////////////////////////////////////////
  // Meta Data
  /////////////////////////////////////////////////////////////////////////////

  /**
   * Gets a listing of all snapshot meta data for a given model.  The results
   * are sorted in ascending order by version.
   *
   * @param id The id of the model to get the meta data for.
   * @param limit The maximum number of results to return.  If None, then all results are returned.
   * @param offset The offest into the result list.  If None, the default is 0.
   *
   * @return A list of (paged) meta data for the specified model.
   */
  def getSnapshotMetaDataForModel(
    id: String,
    limit: Option[Int],
    offset: Option[Int]): Try[List[ModelSnapshotMetaData]] = tryWithDb { db =>
    val baseQuery =
      s"""SELECT ${MetaDataFields}
        |FROM ModelSnapshot
        |WHERE
        |  model.id = :modelId
        |ORDER BY version ASC""".stripMargin

    val query = new OSQLSynchQuery[ODocument](QueryUtil.buildPagedQuery(baseQuery, limit, offset))
    val params = HashMap(
      ModelId -> id)

    val result: JavaList[ODocument] = db.command(query).execute(params.asJava)
    result.asScala.toList.map { ModelSnapshotStore.docToModelSnapshotMetaData(_) }
  }

  /**
   * Gets a listing of all snapshot meta data for a given model within time bounds.  The results
   * are sorted in ascending order by version.
   *
   * @param id The id of the model to get the meta data for.
   * @param startTime The lower time bound (defaults to the unix epoc)
   * @param endTime The upper time bound (defaults to now)
   * @param limit The maximum number of results to return.  If None, then all results are returned.
   * @param offset The offest into the result list.  If None, the default is 0.
   *
   * @return A list of (paged) meta data for the specified model.
   */
  def getSnapshotMetaDataForModelByTime(
    id: String,
    startTime: Option[Long],
    endTime: Option[Long],
    limit: Option[Int],
    offset: Option[Int]): Try[List[ModelSnapshotMetaData]] = tryWithDb { db =>
    val baseQuery =
      s"""SELECT ${MetaDataFields}
        |FROM ModelSnapshot
        |WHERE
        |  model.id = :modelId AND
        |  timestamp BETWEEN :startTime AND :endTime
        |ORDER BY version ASC""".stripMargin

    val query = new OSQLSynchQuery[ODocument](QueryUtil.buildPagedQuery(baseQuery, limit, offset))
    val params = HashMap(
      ModelId -> id,
      "startTime" -> new java.util.Date(startTime.getOrElse(0L)),
      "endTime" -> new java.util.Date(endTime.getOrElse(Long.MaxValue)))

    val result: JavaList[ODocument] = db.command(query).execute(params.asJava)
    result.asScala.toList.map { ModelSnapshotStore.docToModelSnapshotMetaData(_) }
  }

  /**
   * Returns the most recent snapshot meta data for the specified model.
   *
   * @param id The id of the model to get the latest snapshot meta data for.
   *
   * @return the latest snapshot (by version) of the specified model, or None
   * if no snapshots for that model exist.
   */
  def getLatestSnapshotMetaDataForModel(id: String): Try[Option[ModelSnapshotMetaData]] = tryWithDb { db =>
    val queryString =
      s"""SELECT ${MetaDataFields}
        |FROM ModelSnapshot
        |WHERE
        |  model.id = :modelId
        |ORDER BY version DESC LIMIT 1""".stripMargin

    val query = new OSQLSynchQuery[ODocument](queryString)
    val params = HashMap(
      ModelId -> id)
    val result: JavaList[ODocument] = db.command(query).execute(params.asJava)
    QueryUtil.mapSingletonList(result) { ModelSnapshotStore.docToModelSnapshotMetaData(_) }
  }

  def getClosestSnapshotByVersion(id: String, version: Long): Try[Option[ModelSnapshot]] = tryWithDb { db =>
    val queryString =
      s"""SELECT
        |  abs(eval('$$current.version - $version')) as abs_delta,
        |  eval('$$current.version - $version') as delta,
        |  ${AllFields}
        |FROM ModelSnapshot
        |WHERE
        |  model.id = :modelId
        |ORDER BY
        |  abs_delta ASC,
        |  delta DESC
        |LIMIT 1""".stripMargin

    val query = new OSQLSynchQuery[ODocument](queryString)
    val params = HashMap(
      ModelId -> id,
      Version -> version)
    val result: JavaList[ODocument] = db.command(query).execute(params.asJava)
    QueryUtil.mapSingletonList(result) { ModelSnapshotStore.docToModelSnapshot(_) }
  }

  /////////////////////////////////////////////////////////////////////////////
  // Removal
  /////////////////////////////////////////////////////////////////////////////

  /**
   * Removes a snapshot for a model at a specific version.
   *
   * @param id The id of the model to delete the snapshot for.
   * @param version The version of the snapshot to delete.
   */
  def removeSnapshot(id: String, version: Long): Try[Unit] = tryWithDb { db =>
    val query =
      """DELETE FROM ModelSnapshot
        |WHERE
        |  model.id = :modelId AND
        |  version = :version""".stripMargin

    val command = new OCommandSQL(query);
    val params = HashMap(
      ModelId -> id,
      Version -> version)

    db.command(command).execute(params.asJava)
    Unit
  }

  /**
   * Removes all snapshots for a model.
   *
   * @param id The id of the model to delete all snapshots for.
   */
  def removeAllSnapshotsForModel(id: String): Try[Unit] = tryWithDb { db =>
    val query =
      """DELETE FROM ModelSnapshot
        |WHERE
        |  model.id = :modelId""".stripMargin

    val command = new OCommandSQL(query);
    val params = HashMap(ModelId -> id)
    db.command(command).execute(params.asJava)
    Unit
  }

  /**
   * Removes all snapshot for all models in a collection.
   *
   * @param collectionId The collection id of the collection to remove snapshots for.
   */
  def removeAllSnapshotsForCollection(collectionId: String): Try[Unit] = tryWithDb { db =>
    val query = "DELETE FROM ModelSnapshot WHERE model.collection.id = :collectionId"
    val command = new OCommandSQL(query);
    val params = HashMap(CollectionId -> collectionId)
    db.command(command).execute(params.asJava)
    Unit
  }
}
