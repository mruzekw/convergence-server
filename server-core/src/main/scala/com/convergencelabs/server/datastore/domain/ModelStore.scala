package com.convergencelabs.server.datastore.domain

import java.time.Instant
import java.util.Date
import java.util.{ List => JavaList }
import java.util.UUID

import scala.collection.JavaConverters.asScalaBufferConverter
import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.util.Failure
import scala.util.Try

import com.convergencelabs.server.datastore.AbstractDatabasePersistence
import com.convergencelabs.server.datastore.DatabaseProvider
import com.convergencelabs.server.datastore.DuplicateValueExcpetion
import com.convergencelabs.server.datastore.EntityNotFoundException
import com.convergencelabs.server.datastore.QueryUtil
import com.convergencelabs.server.datastore.domain.mapper.ObjectValueMapper.ODocumentToObjectValue
import com.convergencelabs.server.domain.model.Model
import com.convergencelabs.server.domain.model.ModelFqn
import com.convergencelabs.server.domain.model.ModelMetaData
import com.convergencelabs.server.domain.model.data.ObjectValue
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx
import com.orientechnologies.orient.core.id.ORID
import com.orientechnologies.orient.core.metadata.schema.OType
import com.orientechnologies.orient.core.record.impl.ODocument
import com.orientechnologies.orient.core.sql.OCommandSQL
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery
import com.orientechnologies.orient.core.storage.ORecordDuplicatedException

import ModelStore.Constants.CollectionId
import ModelStore.Fields.Collection
import ModelStore.Fields.CreatedTime
import ModelStore.Fields.Data
import ModelStore.Fields.Id
import ModelStore.Fields.ModifiedTime
import ModelStore.Fields.Version
import ModelStore.Fields.WorldPermissions
import ModelStore.Fields.OverridePermissions
import grizzled.slf4j.Logging
import com.convergencelabs.server.domain.model.query.QueryParser
import com.convergencelabs.server.domain.model.query.QueryParser

object ModelStore {
  val ModelClass = "Model"
  val ModelCollectionIdIndex = "Model.collection_id"

  object Constants {
    val CollectionId = "collectionId"
  }

  object Fields {
    val ModelClass = "Model"
    val Data = "data"
    val Collection = "collection"
    val Id = "id"
    val Version = "version"
    val CreatedTime = "createdTime"
    val ModifiedTime = "modifiedTime"
    val OverridePermissions = "overridePermissions"
    val WorldPermissions = "worldPermissions"
  }

  private val FindModel = "SELECT * FROM Model WHERE id = :id AND collection.id = :collectionId"

  def getModelDocument(collectionId: String, modelId: String, db: ODatabaseDocumentTx): Try[ODocument] = {
    val params = Map("id" -> modelId, "collectionId" -> collectionId)
    QueryUtil.lookupMandatoryDocument(FindModel, params, db)
  }

  private def getModelDoc(fqn: ModelFqn, db: ODatabaseDocumentTx): Option[ODocument] = {
    val params = Map("id" -> fqn.modelId, "collectionId" -> fqn.collectionId)
    QueryUtil.lookupOptionalDocument(FindModel, params, db)
  }

  def docToModelMetaData(doc: ODocument): ModelMetaData = {
    val createdTime: Date = doc.field(CreatedTime, OType.DATETIME)
    val modifiedTime: Date = doc.field(ModifiedTime, OType.DATETIME)
    val worldPermissions = ModelPermissionsStore.docToModelPermissions(doc.field(WorldPermissions))
    ModelMetaData(
      ModelFqn(
        doc.field("collection.id"),
        doc.field(Id)),
      doc.field(Version, OType.LONG),
      createdTime.toInstant(),
      modifiedTime.toInstant(),
      doc.field(OverridePermissions),
      worldPermissions)
  }

  def docToModel(doc: ODocument): Model = {
    val data: ODocument = doc.field("data");
    Model(docToModelMetaData(doc), data.asObjectValue)
  }

  def getModelRid(id: String, collectionId: String, db: ODatabaseDocumentTx): Try[ORID] = {
    val query = "SELECT @RID as rid FROM Model WHERE id = :id AND collection.id = :collectionId"
    val params = Map("id" -> id, "collectionId" -> collectionId)
    QueryUtil.lookupMandatoryDocument(query, params, db) map { _.eval("rid").asInstanceOf[ORID] }
  }
}

class ModelStore private[domain] (
  dbProvider: DatabaseProvider,
  operationStore: ModelOperationStore,
  snapshotStore: ModelSnapshotStore)
    extends AbstractDatabasePersistence(dbProvider)
    with Logging {

  def modelExists(fqn: ModelFqn): Try[Boolean] = tryWithDb { db =>
    val query = "SELECT id FROM Model where id = :id AND collection.id = :collectionId"
    val params = Map("id" -> fqn.modelId, "collectionId" -> fqn.collectionId)
    QueryUtil.hasResults(query, params, db)
  }

  def createModel(
    collectionId: String,
    modelId: Option[String],
    data: ObjectValue,
    overridePermissions: Boolean,
    worldPermissions: ModelPermissions): Try[Model] = {

    val createdTime = Instant.now()
    val modifiedTime = createdTime
    val version = 1
    val computedModelId = modelId.getOrElse(UUID.randomUUID().toString)

    val model = Model(
      ModelMetaData(
        ModelFqn(collectionId, computedModelId),
        version,
        createdTime,
        modifiedTime,
        overridePermissions,
        worldPermissions),
      data)

    this.createModel(model) map (_ => model)
  }

  def createModel(model: Model): Try[Unit] = tryWithDb { db =>
    val collectionId = model.metaData.fqn.collectionId
    val modelId = model.metaData.fqn.modelId
    val createdTime = model.metaData.createdTime
    val modifiedTime = model.metaData.modifiedTime
    val version = model.metaData.version
    val data = model.data
    val overrridePermissions = model.metaData.overridePermissions
    val worldPermissions = model.metaData.worldPermissions

    CollectionStore.getCollectionRid(collectionId, db)
      .recoverWith {
        case cause: Exception =>
          val message = s"Could not create model because collection ${collectionId} could not be found."
          logger.error(message, cause)
          Failure(new IllegalArgumentException(message))
      }.map { collectionRid =>
        db.begin()
        val modelDoc = new ODocument(ModelStore.ModelClass)
        modelDoc.field(Collection, collectionRid)
        modelDoc.field(Id, modelId)
        modelDoc.field(Version, version)
        modelDoc.field(CreatedTime, Date.from(createdTime))
        modelDoc.field(ModifiedTime, Date.from(modifiedTime))
        modelDoc.field(OverridePermissions, overrridePermissions)
        
        val worldPermsDoc = ModelPermissionsStore.modelPermissionToDoc(worldPermissions)
        modelDoc.field(WorldPermissions, worldPermsDoc, OType.EMBEDDED)
        
        val dataDoc = OrientDataValueBuilder.objectValueToODocument(data, modelDoc)
        modelDoc.field(Data, dataDoc, OType.LINK)
        
        // FIXME what about the user permissions LINKLIST?

        dataDoc.save()
        modelDoc.save()
        db.commit()
        ()
      }.get
  } recoverWith {
    case e: ORecordDuplicatedException => handleDuplicateValue(e)
  }

  //FIXME: Add in overridePermissions flag
  def updateModel(fqn: ModelFqn, data: ObjectValue, worldPermissions: Option[ModelPermissions]): Try[Unit] = tryWithDb { db =>
    ModelStore.getModelDoc(fqn, db) match {
      case Some(doc) =>
        deleteDataValuesForModel(fqn, db).map { _ =>
          val dataValueDoc = OrientDataValueBuilder.dataValueToODocument(data, doc)
          doc.field(Data, dataValueDoc)
          val worldPermissionsDoc = worldPermissions.map { ModelPermissionsStore.modelPermissionToDoc(_) }
          doc.field(WorldPermissions, worldPermissions)
          doc.save()
          ()
        }.get
      case None =>
        throw EntityNotFoundException()
    }
  }

  def updateModelOnOperation(fqn: ModelFqn, timestamp: Instant): Try[Unit] = tryWithDb { db =>
    val queryString =
      """UPDATE Model SET
        |  version = eval('version + 1'),
        |  modifiedTime = :timestamp
        |WHERE
        |  collection.id = :collectionId AND
        |  id = :modelId""".stripMargin

    val updateCommand = new OCommandSQL(queryString)

    val params = Map(
      "collectionId" -> fqn.collectionId,
      "modelId" -> fqn.modelId,
      "timestamp" -> Date.from(timestamp))

    db.command(updateCommand).execute(params.asJava).asInstanceOf[Int] match {
      case 0 =>
        throw EntityNotFoundException()
      case _ =>
        ()
    }
  }

  def deleteModel(fqn: ModelFqn): Try[Unit] = tryWithDb { db =>
    operationStore.deleteAllOperationsForModel(fqn).flatMap { _ =>
      snapshotStore.removeAllSnapshotsForModel(fqn)
    }.flatMap { _ =>
      deleteDataValuesForModel(fqn, db)
    }.map { _ =>
      val command = new OCommandSQL("DELETE FROM Model WHERE collection.id = :collectionId AND id = :id")
      val params = Map(CollectionId -> fqn.collectionId, Id -> fqn.modelId)
      db.command(command).execute(params.asJava).asInstanceOf[Int] match {
        case 1 =>
          ()
        case _ =>
          throw EntityNotFoundException()
      }
    }.get
  }

  def deleteDataValuesForModel(fqn: ModelFqn, db: ODatabaseDocumentTx): Try[Unit] = Try {
    val command = new OCommandSQL("DELETE FROM DataValue WHERE model.collection.id = :collectionId AND model.id = :id")
    val params = Map(CollectionId -> fqn.collectionId, Id -> fqn.modelId)
    db.command(command).execute(params.asJava).asInstanceOf[Int]
    ()
  }

  def deleteAllModelsInCollection(collectionId: String): Try[Unit] = tryWithDb { db =>
    operationStore.deleteAllOperationsForCollection(collectionId).flatMap { _ =>
      snapshotStore.removeAllSnapshotsForCollection(collectionId)
    }.flatMap { _ =>
      deleteDataValuesForCollection(collectionId, db)
    }.map { _ =>
      val queryString = "DELETE FROM Model WHERE collection.id = :collectionId"
      val command = new OCommandSQL(queryString)
      val params = Map(CollectionId -> collectionId)
      db.command(command).execute(params.asJava)
      ()
    }.get
  }

  def deleteDataValuesForCollection(collectionId: String, db: ODatabaseDocumentTx): Try[Unit] = Try {
    val command = new OCommandSQL("DELETE FROM DataValue WHERE model.collection.id = :collectionId")
    val params = Map(CollectionId -> collectionId)
    db.command(command).execute(params.asJava).asInstanceOf[Int]
    ()
  }

  def getModel(fqn: ModelFqn): Try[Option[Model]] = tryWithDb { db =>
    ModelStore.getModelDoc(fqn, db) map (ModelStore.docToModel(_))
  }

  def getModelMetaData(fqn: ModelFqn): Try[Option[ModelMetaData]] = tryWithDb { db =>
    ModelStore.getModelDoc(fqn, db) map (ModelStore.docToModelMetaData(_))
  }

  def getAllModelMetaDataInCollection(
    collectionId: String,
    offset: Option[Int],
    limit: Option[Int]): Try[List[ModelMetaData]] = tryWithDb { db =>

    val queryString =
      s"""SELECT *
         |FROM Model
         |WHERE
         |  collection.id = :collectionId
         |ORDER BY
         |  id ASC""".stripMargin

    val pagedQuery = QueryUtil.buildPagedQuery(
      queryString,
      limit,
      offset)

    val query = new OSQLSynchQuery[ODocument](pagedQuery)
    val params = Map(CollectionId -> collectionId)
    val result: JavaList[ODocument] = db.command(query).execute(params.asJava)
    result.asScala.toList map { ModelStore.docToModelMetaData(_) }
  }

  // TODO implement orderBy and ascending / descending
  def getAllModelMetaData(
    offset: Option[Int],
    limit: Option[Int]): Try[List[ModelMetaData]] = tryWithDb { db =>

    val queryString =
      s"""SELECT *
        |FROM Model
        |ORDER BY
        |  collection.id ASC,
        |  id ASC""".stripMargin

    val pageQuery = QueryUtil.buildPagedQuery(queryString, limit, offset)
    val query = new OSQLSynchQuery[ODocument](pageQuery)
    val result: JavaList[ODocument] = db.command(query).execute()
    result.asScala.toList map { ModelStore.docToModelMetaData(_) }
  }

  def queryModels(query: String, username: Option[String]): Try[List[Model]] = tryWithDb { db =>
    val select = new QueryParser(query).InputLine.run()
    val queryResult = select.map(ModelQueryBuilder.queryModels(_, username)) map {
      queryParams: ModelQueryParameters =>
        {
          val query = new OSQLSynchQuery[ODocument](queryParams.query)
          val result: JavaList[ODocument] = db.command(query).execute(queryParams.params.asJava)

          result.asScala.toList map { ModelStore.docToModel(_) }
        }
    }
    queryResult.get
  }

  def getModelData(fqn: ModelFqn): Try[Option[ObjectValue]] = tryWithDb { db =>
    ModelStore.getModelDoc(fqn, db) map (doc => doc.field(Data).asInstanceOf[ODocument].asObjectValue)
  }

  private[this] def handleDuplicateValue[T](e: ORecordDuplicatedException): Try[T] = {
    e.getIndexName match {
      case ModelStore.ModelCollectionIdIndex =>
        Failure(DuplicateValueExcpetion("id_collection"))
      case _ =>
        Failure(e)
    }
  }
}
