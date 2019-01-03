package com.convergencelabs.server.datastore.domain.schema

object DomainSessionClass extends OrientDBClass {
  val ClassName = "DomainSession"

  object Indices {
    val Id = "DomainSession.id"
  }

  object Fields {
    val Id = "id"
    val User = "user"
    val Connected = "connected"
    val Disconnected = "disconnected"
    val AuthMethod = "authMethod"
    val Client = "client"
    val ClientVersion = "clientVersion"
    val ClientMetaData = "clientMetaData"
    val RemoteHost = "remoteHost"
  }
}