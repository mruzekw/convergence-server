package com.convergencelabs.server.datastore.domain.mapper

import java.time.Instant
import java.util.Date

import scala.language.implicitConversions

import com.convergencelabs.server.datastore.mapper.ODocumentMapper
import com.convergencelabs.server.domain.model.ModelFqn
import com.convergencelabs.server.domain.model.ModelOperation
import com.orientechnologies.orient.core.metadata.schema.OType
import com.orientechnologies.orient.core.record.impl.ODocument

object ModelOperationMapper extends ODocumentMapper {

  private[domain] implicit class ModelOperationToODocument(val s: ModelOperation) extends AnyVal {
    def asODocument: ODocument = modelOperationToODocument(s)
  }

  private[domain] implicit def modelOperationToODocument(opEvent: ModelOperation): ODocument = {
    val doc = new ODocument(DocumentClassName)
    doc.field(Fields.CollectionId, opEvent.modelFqn.collectionId)
    doc.field(Fields.ModelId, opEvent.modelFqn.modelId)
    doc.field(Fields.Version, opEvent.version, OType.LONG)
    doc.field(Fields.Timestamp, Date.from(opEvent.timestamp), OType.DATETIME)
    doc.field(Fields.Username, opEvent.username)
    doc.field(Fields.Sid, opEvent.sid)
    doc.field(Fields.Operation, OrientDBOperationMapper.operationToODocument(opEvent.op))
    doc
  }

  private[domain] implicit class ODocumentToModelOperation(val d: ODocument) extends AnyVal {
    def asModelOperation: ModelOperation = oDocumentToModelOperation(d)
  }

  private[domain] implicit def oDocumentToModelOperation(doc: ODocument): ModelOperation = {
    validateDocumentClass(doc, DocumentClassName)

    val docDate: java.util.Date = doc.field(Fields.Timestamp, OType.DATETIME)
    val timestamp = Instant.ofEpochMilli(docDate.getTime)
    val opDoc: ODocument = doc.field(Fields.Operation, OType.EMBEDDED)
    val op = OrientDBOperationMapper.oDocumentToOperation(opDoc)
    ModelOperation(
      ModelFqn(doc.field(Fields.CollectionId), doc.field(Fields.ModelId)),
      doc.field(Fields.Version),
      timestamp,
      doc.field(Fields.Username),
      doc.field(Fields.Sid),
      op)
  }

  private[domain] val DocumentClassName = "ModelOperation"

  private[domain] object Fields {
    val CollectionId = "collectionId"
    val ModelId = "modelId"
    val Version = "version"
    val Timestamp = "timestamp"
    val Username = "username"
    val Sid = "sid"
    val Operation = "op"
  }
}