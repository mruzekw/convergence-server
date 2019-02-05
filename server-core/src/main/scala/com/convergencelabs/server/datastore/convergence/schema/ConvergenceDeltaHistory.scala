package com.convergencelabs.server.datastore.convergence.schema

import com.convergencelabs.server.datastore.domain.schema.OrientDbClass

object ConvergenceDeltaHistoryClass extends OrientDbClass {
  val ClassName = "ConvergenceDeltaHistory"
  
  object Fields {
    val Delta = "delta"
    val Status = "status"
    val Message = "message"
    val Date = "date"
  }
  
  object Indices {
    val Delta = "ConvergenceDeltaHistory.delta"
  }
}
