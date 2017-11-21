package com.convergencelabs.server.frontend.realtime

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success

import com.convergencelabs.server.datastore.domain.DomainPersistenceManagerActor
import com.convergencelabs.server.datastore.domain.ModelOperationStoreActor
import com.convergencelabs.server.datastore.domain.ModelOperationStoreActor.GetOperations
import com.convergencelabs.server.datastore.domain.ModelStoreActor
import com.convergencelabs.server.domain.DomainFqn
import com.convergencelabs.server.domain.model.GetRealtimeModel
import com.convergencelabs.server.domain.model.Model
import com.convergencelabs.server.domain.model.ModelOperation
import com.convergencelabs.server.domain.model.RealtimeModelSharding
import com.convergencelabs.server.domain.model.SessionKey
import com.convergencelabs.server.util.concurrent.AskFuture

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.util.Timeout

object HistoricModelClientActor {
  def props(
    sk: SessionKey,
    domainFqn: DomainFqn,
    modelStoreActor: ActorRef,
    operationStoreActor: ActorRef): Props =
    Props(new HistoricModelClientActor(sk, domainFqn, modelStoreActor, operationStoreActor))
}

class HistoricModelClientActor(
  private[this] val sessionKey: SessionKey,
  private[this] val domainFqn: DomainFqn,
  private[this] val modelStoreActor: ActorRef,
  private[this] val operationStoreActor: ActorRef)
    extends Actor with ActorLogging {

  import akka.pattern.ask

  private[this] implicit val timeout = Timeout(5 seconds)
  private[this] implicit val ec = context.dispatcher

  private[this] val modelClusterRegion: ActorRef = RealtimeModelSharding.shardRegion(this.context.system)

  def receive: Receive = {
    case RequestReceived(message, replyPromise) if message.isInstanceOf[IncomingHistoricalModelRequestMessage] =>
      onRequestReceived(message.asInstanceOf[IncomingHistoricalModelRequestMessage], replyPromise)
    case x: Any => unhandled(x)
  }

  private[this] def onRequestReceived(message: IncomingHistoricalModelRequestMessage, replyCallback: ReplyCallback): Unit = {
    message match {
      case dataRequest: HistoricalDataRequestMessage => onDataRequest(dataRequest, replyCallback)
      case operationRequest: HistoricalOperationRequestMessage => onOperationRequest(operationRequest, replyCallback)
    }
  }

  private[this] def onDataRequest(request: HistoricalDataRequestMessage, cb: ReplyCallback): Unit = {
    (modelClusterRegion ? GetRealtimeModel(domainFqn, request.m, None)).mapResponse[Option[Model]] onComplete {
      case (Success(Some(model))) => {
        cb.reply(
          HistoricalDataResponseMessage(
            model.metaData.collectionId,
            model.data,
            model.metaData.version,
            model.metaData.createdTime.toEpochMilli,
            model.metaData.modifiedTime.toEpochMilli))
      }
      case Success(None) => {
        cb.expectedError("model_not_found", "The model does not exist")
      }
      case Failure(cause) => {
        log.error(cause, "Unexpected error getting model history.")
        cb.unknownError()
      }
    }
  }

  private[this] def onOperationRequest(request: HistoricalOperationRequestMessage, cb: ReplyCallback): Unit = {
    val HistoricalOperationRequestMessage(modelId, first, last) = request
    (operationStoreActor ? GetOperations(modelId, first, last)).mapResponse[List[ModelOperation]] onComplete {
      case (Success(operations)) => {
        cb.reply(HistoricalOperationsResponseMessage(operations map ModelOperationMapper.mapOutgoing))
      }
      case Failure(cause) => {
        log.error(cause, "Unexpected error getting model history.")
        cb.unknownError()
      }
    }
  }
}
