package com.convergencelabs.server.domain.model

import akka.actor.{ Props, ActorRef, ActorLogging, Actor }
import akka.pattern.{ AskTimeoutException, Patterns }
import com.convergencelabs.server.datastore.domain._
import com.convergencelabs.server.domain.model.ot.cc.{ UnprocessedOperationEvent, ServerConcurrencyControl }
import com.convergencelabs.server.domain.model.ot.ops.Operation
import com.convergencelabs.server.domain.model.ot.xform.OperationTransformer
import org.json4s.JsonAST.JValue
import scala.collection.immutable.HashMap
import scala.concurrent.{ ExecutionContext, Future }
import com.convergencelabs.server.domain.DomainFqn
import scala.util.Success
import scala.util.Failure
import scala.util.Try
import com.convergencelabs.server.domain.model.ot.cc.ProcessedOperationEvent
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import scala.compat.Platform
import com.convergencelabs.server.ErrorMessage

/**
 * An instance of the RealtimeModelActor manages the lifecycle of a single
 * realtime model.
 */
class RealtimeModelActor(
    private[this] val modelManagerActor: ActorRef,
    private[this] val domainFqn: DomainFqn,
    private[this] val modelFqn: ModelFqn,
    private[this] val modelResourceId: String,
    private[this] val modelStore: ModelStore,
    private[this] val modelSnapshotStore: ModelSnapshotStore,
    private[this] val clientDataResponseTimeout: Long,
    private[this] val snapshotConfig: SnapshotConfig) extends Actor with ActorLogging {

  // This sets the actor dispatcher as an implicit execution context.  This way we
  // don't have to pass this argument to futures.
  private[this] implicit val ec: ExecutionContext = context.dispatcher

  private[this] var connectedClients = HashMap[String, ActorRef]()
  private[this] var queuedOpeningClients = HashMap[String, OpenRequestRecord]()
  private[this] var concurrencyControl: ServerConcurrencyControl = null
  private[this] var latestSnapshot: SnapshotMetaData = null

  private[this] var nextClientId: Long = 0

  //
  // Receive methods
  //

  def receive = receiveUninitialized

  /**
   * Handles messages when the realtime model has not been initialized yet.
   */
  private[this] def receiveUninitialized: Receive = {
    case request: OpenRealtimeModelRequest => onOpenModelWhileUninitialized(request)
    case unknown => unhandled(unknown)
  }

  /**
   * Handles messages while the model is being initialized from one or more clients.
   */
  private[this] def receiveInitializingFromClients: Receive = {
    case request: OpenRealtimeModelRequest => onOpenModelWhileInitializing(request)
    case dataResponse: DatabaseModelResponse => onDatabaseModelResponse(dataResponse)
    case dataResponse: ClientModelDataResponse => onClientModelDataResponse(dataResponse)
    case modelDeleted: ModelDeleted => handleInitializationFailure("model_deleted", "The model was deleted while opening.")
    case unknown => unhandled(unknown)
  }

  /**
   * Handles messages while the model is being initialized from the database.
   */
  private[this] def receiveInitializingFromDatabase: Receive = {
    case request: OpenRealtimeModelRequest => onOpenModelWhileInitializing(request)
    case dataResponse: DatabaseModelResponse => onDatabaseModelResponse(dataResponse)
    case DatabaseModelFailure(cause) => handleInitializationFailure("unknown", cause.getMessage)
    case modelDeleted: ModelDeleted => handleInitializationFailure("model_deleted", "The model was deleted while opening.")
    case dataResponse: ClientModelDataResponse =>
    case unknown => unhandled(unknown)
  }

  /**
   * Handles messages once the model has been completely initialized.
   */
  private[this] def receiveInitialized: Receive = {
    case openRequest: OpenRealtimeModelRequest => onOpenModelWhileInitialized(openRequest)
    case closeRequest: CloseRealtimeModelRequest => onCloseModelRequest(closeRequest)
    case operationSubmission: OperationSubmission => onOperationSubmission(operationSubmission)
    case dataResponse: ClientModelDataResponse =>
    case snapshotMetaData: SnapshotMetaData => this.latestSnapshot = snapshotMetaData
    case modelDeleted: ModelDeleted => handleModelDeletedWhileOpen()
    case unknown => unhandled(unknown)
  }

  //
  // Opening and Closing
  //

  /**
   * Starts the open process from an uninitialized model.
   */
  private[this] def onOpenModelWhileUninitialized(request: OpenRealtimeModelRequest): Unit = {
    val modelSessionId = nextModelSessionId()
    queuedOpeningClients += (modelSessionId -> OpenRequestRecord(request.clientActor, sender()))

    if (modelStore.modelExists(modelFqn)) {
      // The model is persistent, load from the store.
      requestModelDataFromDatastore()
    } else {
      // The model is not persistent, ask the client for the data.
      requestModelDataFromClient(request.clientActor)
    }
  }

  /**
   * Handles an additional request for opening the model, while the model is
   * already initializgin.
   */
  private[this] def onOpenModelWhileInitializing(request: OpenRealtimeModelRequest): Unit = {
    // We know we are already INITIALIZING.  This means we are at least the second client
    // to open the model before it was fully initialized.
    val modelSessionId = nextModelSessionId()
    queuedOpeningClients += (modelSessionId -> OpenRequestRecord(request.clientActor, sender()))

    // If we are persistent, then the data is already loading, so there is nothing to do.
    // However, if we are not persistent, we have already asked the previous opening client
    // for the data, but we will ask this client too, in case the others fail.
    if (!modelStore.modelExists(modelFqn)) {
      requestModelDataFromClient(request.clientActor)
    }
  }

  /**
   * Asynchronously requests model data from the database.
   */
  private[this] def requestModelDataFromDatastore(): Unit = {
    val f = Future[DatabaseModelResponse] {
      val snapshotMetaData = modelSnapshotStore.getLatestSnapshotMetaData(modelFqn)
      val modelData = modelStore.getModelData(modelFqn)
      DatabaseModelResponse(modelData, snapshotMetaData)
    }

    f onComplete {
      case Success(modelDataResponse) => self ! modelDataResponse
      case Failure(cause) => self ! DatabaseModelFailure(cause)
    }

    context.become(receiveInitializingFromDatabase)
  }

  /**
   * Handles model initialization data coming back from the database and attempts to
   * complete the initialization process.
   */
  private[this] def onDatabaseModelResponse(response: DatabaseModelResponse): Unit = {
    try {
      latestSnapshot = response.snapshotMetaData
      val modelData = response.modelData

      concurrencyControl = new ServerConcurrencyControl(
        new OperationTransformer(),
        modelData.metaData.version)

      // TODO Initialize tree reference model

      queuedOpeningClients foreach {
        case (modelSessionId, queuedClientRecord) =>
          respondToClientOpenRequest(modelSessionId, modelData, queuedClientRecord)
      }

      this.queuedOpeningClients = HashMap[String, OpenRequestRecord]()

      context.become(receiveInitialized)
    } catch {
      case e: Exception =>
        log.error(e, "Unable to initialize shared model.")
        handleInitializationFailure("unknown", e.getMessage)
    }
  }

  /**
   * Asynchronously requests the model data from the connecting client.
   */
  private[this] def requestModelDataFromClient(clientActor: ActorRef): Unit = {
    val future = Patterns.ask(
      clientActor,
      ClientModelDataRequest(modelFqn),
      clientDataResponseTimeout)

    val askingActor = sender()

    future onSuccess {
      case response: ClientModelDataResponse => self ! response
      case x =>
        log.warning("The client responded with an unexpected value:" + x)
        askingActor ! ErrorMessage("invalid_response", "The client responded with an unexpected value.")
    }

    future onFailure {
      case cause: AskTimeoutException =>
        askingActor ! ErrorMessage(
          "data_request_timeout",
          "The client did not correctly respond with data, while initializing a new model.")
      case e: Exception =>
        askingActor ! ErrorMessage("unknown", e.getMessage)
    }

    context.become(receiveInitializingFromClients)
  }

  /**
   * Processes the model data coming back from a client.  This will persist the model and
   * then open the model from the database.
   */
  private[this] def onClientModelDataResponse(response: ClientModelDataResponse): Unit = {
    val data = response.modelData
    val createTime = System.currentTimeMillis()

    modelStore.createModel(modelFqn, data, createTime)
    modelSnapshotStore.addSnapshot(
      SnapshotData(SnapshotMetaData(modelFqn, 0, createTime), data))

    requestModelDataFromDatastore()
  }

  /**
   * Handles a request to open the model, when the model is already initialized.
   */
  private[this] def onOpenModelWhileInitialized(request: OpenRealtimeModelRequest): Unit = {
    val modelSessionId = nextModelSessionId()
    val modelData = modelStore.getModelData(modelFqn)
    respondToClientOpenRequest(modelSessionId, modelData, OpenRequestRecord(request.clientActor, sender()))
  }

  /**
   * Lets a client know that the open process has completed successfully.
   */
  private[this] def respondToClientOpenRequest(modelSessionId: String, modelData: ModelData, requestRecord: OpenRequestRecord): Unit = {
    // Inform the concurrency control that we have a new client.
    val contextVersion = modelData.metaData.version
    concurrencyControl.trackClient(modelSessionId, contextVersion)
    connectedClients += (modelSessionId -> requestRecord.clientActor)

    // Send a message to the client informing them of the successful model open.
    val metaData = OpenMetaData(
      modelData.metaData.version,
      modelData.metaData.createdTime,
      modelData.metaData.modifiedTime)

    val openModelResponse = OpenModelResponse(
      self,
      modelResourceId,
      modelSessionId,
      metaData,
      modelData.data)

    requestRecord.askingActor ! openModelResponse
  }

  /**
   * Handles a request to close the model.
   */
  private[this] def onCloseModelRequest(request: CloseRealtimeModelRequest): Unit = {
    if (!connectedClients.contains(request.modelSessionId)) {
      sender ! ErrorMessage("invalid_model_session_id", "The supplied modelSessionId does not have the model open")
    } else {
      connectedClients -= request.modelSessionId
      concurrencyControl.untrackClient(request.modelSessionId)

      // TODO handle reference leaving

      // Acknowledge the close back to the requester
      sender ! CloseModelAcknowledgement(modelFqn, modelResourceId, request.modelSessionId)

      val closedMessage = RemoteSessionClosed(modelFqn, request.modelSessionId)

      // If there are other clients, inform them.
      connectedClients.values foreach { client => client ! closedMessage }

      checkForConnectionsAndClose()
    }
  }

  /**
   * Determines if there are no more clients connected and if so request to shutdown.
   */
  private[this] def checkForConnectionsAndClose(): Unit = {
    if (connectedClients.isEmpty) {
      modelManagerActor ! new ModelShutdownRequest(this.modelFqn)
    }
  }

  /**
   * Handles the notification of a deleted model, while open.
   */
  private[this] def handleModelDeletedWhileOpen() {
    connectedClients.keys foreach {
      sessionId => forceClosedModel(sessionId, "Model deleted", false)
    }

    context.stop(self)
  }

  /**
   * A helper method to get the next model session id.
   */
  private[this] def nextModelSessionId(): String = {
    val id = "" + nextClientId
    nextClientId += 1
    id
  }

  //
  // Operation Handling
  //

  private[this] def onOperationSubmission(request: OperationSubmission): Unit = {
    val unprocessedOpEvent = UnprocessedOperationEvent(
      request.modelSessionId,
      request.contextVersion,
      request.operation)

    transformAndApplyOperation(unprocessedOpEvent) match {
      case Success(outgoinOperation) => {
        concurrencyControl.commit()
        broadcastOperation(outgoinOperation)
        if (snapshotRequired()) { executeSnapshot() }
      }
      case Failure(error) => {
        log.debug("Error applying operation to model, closing client: " + error)
        concurrencyControl.rollback()
        forceClosedModel(
          request.modelSessionId,
          "Error applying operation to model, closing as a precautionary step: " + error.getMessage,
          true)

      }
    }
  }

  /**
   * Attempts to transform the operation and apply it to the data model.
   */
  private[this] def transformAndApplyOperation(unprocessedOpEvent: UnprocessedOperationEvent): Try[OutgoingOperation] = Try {
    val processedOpEvent = concurrencyControl.processRemoteOperation(unprocessedOpEvent)

    val timestamp = Platform.currentTime

    // TODO get username.
    modelStore.applyOperationToModel(
      modelFqn,
      processedOpEvent.operation,
      processedOpEvent.resultingVersion,
      timestamp,
      "")

    OutgoingOperation(
      processedOpEvent.clientId,
      processedOpEvent.contextVersion,
      timestamp,
      processedOpEvent.operation)
  }

  /**
   * Sends an ACK back to the originator of the operation and an operation message
   * to all other connected clients.
   */
  private[this] def broadcastOperation(outgoingOperation: OutgoingOperation) {
    val originModelSessiond = outgoingOperation.modelSessionId

    // Ack the sender
    connectedClients(originModelSessiond) ! OperationAcknowledgement(
      modelFqn, originModelSessiond, outgoingOperation.contextVersion)

    // Send the message to all others
    connectedClients.filter(p => p._1 != originModelSessiond) foreach {
      case (sessionId, clientActor) => clientActor ! outgoingOperation
    }
  }

  /**
   * Determines if a snapshot is required.
   */
  private[this] def snapshotRequired(): Boolean = snapshotConfig.snapshotRequired(
    latestSnapshot.version,
    concurrencyControl.contextVersion,
    latestSnapshot.timestamp,
    Platform.currentTime)

  /**
   * Asynchronously performs a snapshot of the model.
   */
  private[this] def executeSnapshot(): Unit = {
    // This might not be the exact version that gets snapshotted
    // but that is OK, this is approximate.
    latestSnapshot = SnapshotMetaData(modelFqn, concurrencyControl.contextVersion, Platform.currentTime)

    val f = Future[SnapshotMetaData] {
      val modelData = modelStore.getModelData(this.modelFqn)
      val snapshot = new SnapshotData(
        SnapshotMetaData(
          modelData.metaData.fqn,
          modelData.metaData.version,
          modelData.metaData.modifiedTime),
        modelData.data)

      modelSnapshotStore.addSnapshot(snapshot)

      new SnapshotMetaData(
        modelFqn,
        modelData.metaData.version,
        modelData.metaData.modifiedTime)
    }

    f onSuccess {
      case snapshotMetaData =>
        // Send the snapshot back to the model so it knows when the snapshot was actually taken.
        self ! snapshotMetaData
        log.debug(s"Snapshot successfully taken for '${modelFqn.collectionId}/${modelFqn.modelId}' " +
          s"at version: ${snapshotMetaData.version}, timestamp: ${snapshotMetaData.timestamp}")
    }

    f onFailure {
      case cause => log.error(cause, s"Error taking snapshot of model (${modelFqn.collectionId}/${modelFqn.modelId})")
    }
  }

  //
  // Error handling
  //

  /**
   * Kicks all clients out of the model.
   */
  private[this] def forceCloseAllAfterError(reason: String) {
    connectedClients foreach {
      case (modelSessionId, actor) => forceClosedModel(modelSessionId, reason, false)
    }
  }

  /**
   * Kicks a specific clent out of the model.
   */
  private[this] def forceClosedModel(modelSessionId: String, reason: String, notifyOthers: Boolean) {
    val closedActor = connectedClients(modelSessionId)
    connectedClients -= modelSessionId
    concurrencyControl.untrackClient(modelSessionId)

    // TODO handle reference node leaving

    val closedMessage = RemoteSessionClosed(modelFqn, modelSessionId)

    if (notifyOthers) {
      // There are still other clients with this model open so notify them
      // that this person has left
      connectedClients.values foreach { client => client ! closedMessage }
    }

    val forceCloseMessage = ModelForceClose(modelFqn, modelSessionId, reason)
    closedActor ! forceCloseMessage
    checkForConnectionsAndClose()
  }

  /**
   * Informs all clients that the model could not be initialized.
   */
  private[this] def handleInitializationFailure(errorCode: String, errorMessage: String): Unit = {
    queuedOpeningClients.values foreach {
      openRequest => openRequest.askingActor ! ErrorMessage(errorCode, errorMessage)
    }

    queuedOpeningClients = HashMap[String, OpenRequestRecord]()
    checkForConnectionsAndClose()
  }

  override def postStop() {
    log.debug("Unloading SharedModel({}/{})", domainFqn, modelFqn)
    connectedClients = HashMap()
  }
}

/**
 * Provides a factory method for creating the RealtimeModelActor
 */
object RealtimeModelActor {
  def props(
    modelManagerActor: ActorRef,
    domainFqn: DomainFqn,
    modelFqn: ModelFqn,
    resourceId: String,
    modelStore: ModelStore,
    modelSnapshotStore: ModelSnapshotStore,
    clientDataResponseTimeout: Long,
    snapshotConfig: SnapshotConfig): Props =
    Props(new RealtimeModelActor(
      modelManagerActor,
      domainFqn,
      modelFqn,
      resourceId,
      modelStore,
      modelSnapshotStore,
      clientDataResponseTimeout,
      snapshotConfig))
}