package com.convergencelabs.server.datastore.domain.mapper

import scala.language.implicitConversions

import com.convergencelabs.server.datastore.mapper.ODocumentMapper
import com.convergencelabs.server.domain.model.ot.NumberSetOperation
import com.orientechnologies.orient.core.record.impl.ODocument

object NumberSetOperationMapper extends ODocumentMapper {

  private[domain] implicit class NumberSetOperationToODocument(val s: NumberSetOperation) extends AnyVal {
    def asODocument: ODocument = numberSetOperationToODocument(s)
  }

  private[domain] implicit def numberSetOperationToODocument(obj: NumberSetOperation): ODocument = {
    val NumberSetOperation(id, noOp, value) = obj
    val doc = new ODocument(DocumentClassName)
    doc.field(Fields.Id, id)
    doc.field(Fields.NoOp, noOp)
    doc.field(Fields.Val, value)
    doc
  }

  private[domain] implicit class ODocumentToNumberSetOperation(val d: ODocument) extends AnyVal {
    def asNumberSetOperation: NumberSetOperation = oDocumentToNumberSetOperation(d)
  }

  private[domain] implicit def oDocumentToNumberSetOperation(doc: ODocument): NumberSetOperation = {
    validateDocumentClass(doc, DocumentClassName)

    val id = doc.field(Fields.Id).asInstanceOf[String]
    val noOp = doc.field(Fields.NoOp).asInstanceOf[Boolean]
    val value = doc.field(Fields.Val).asInstanceOf[Double]
    NumberSetOperation(id, noOp, value)
  }

  private[domain] val DocumentClassName = "NumberSetOperation"

  private[domain] object Fields {
    val Id = "vid"
    val NoOp = "noOp"
    val Val = "val"
  }
}