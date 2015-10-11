package com.convergencelabs.server.frontend.realtime

import scala.concurrent.duration._
import scala.concurrent.Promise
import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.actorRef2Scala
import akka.pattern.ask
import akka.util.Timeout
import com.convergencelabs.server.ProtocolConfiguration
import com.convergencelabs.server.domain.DomainFqn
import com.convergencelabs.server.domain.HandshakeFailure
import com.convergencelabs.server.domain.HandshakeRequest
import com.convergencelabs.server.domain.HandshakeSuccess
import com.convergencelabs.server.frontend.realtime.proto.IncomingModelRequestMessage
import com.convergencelabs.server.frontend.realtime.proto.IncomingModelNormalMessage
import com.convergencelabs.server.frontend.realtime.proto.IncomingProtocolNormalMessage
import com.convergencelabs.server.frontend.realtime.proto.IncomingProtocolResponseMessage
import com.convergencelabs.server.frontend.realtime.proto.IncomingProtocolRequestMessage
import com.convergencelabs.server.frontend.realtime.proto.OutgoingProtocolResponseMessage
import com.convergencelabs.server.frontend.realtime.proto.OutgoingProtocolNormalMessage
import com.convergencelabs.server.frontend.realtime.proto.OutgoingProtocolRequestMessage
import com.convergencelabs.server.frontend.realtime.proto.HandshakeRequestMessage
import com.convergencelabs.server.frontend.realtime.proto.HandshakeRequestMessage
import com.convergencelabs.server.frontend.realtime.proto.OutgoingProtocolResponseMessage
import com.convergencelabs.server.frontend.realtime.proto.HandshakeResponseMessage
import com.convergencelabs.server.frontend.realtime.proto.ErrorData
import com.convergencelabs.server.domain.model.RealtimeModelClientMessage
import com.convergencelabs.server.domain.HandshakeResponse
import com.convergencelabs.server.domain.AuthenticationResponse
import com.convergencelabs.server.domain.AuthenticationSuccess
import com.convergencelabs.server.domain.AuthenticationFailure
import com.convergencelabs.server.domain.TokenAuthRequest
import com.convergencelabs.server.domain.PasswordAuthRequest
import com.convergencelabs.server.util.concurrent._
import java.util.concurrent.TimeUnit
import com.convergencelabs.server.frontend.realtime.proto.AuthenticationRequestMessage
import com.convergencelabs.server.frontend.realtime.proto.PasswordAuthenticationRequestMessage
import com.convergencelabs.server.frontend.realtime.proto.TokenAuthenticationRequestMessage
import com.convergencelabs.server.frontend.realtime.proto.AuthenticationSuccessResponseMessage
import com.convergencelabs.server.frontend.realtime.proto.AuthenticationFailureResponseMessage

object ClientActor {
  def props(
    domainManager: ActorRef,
    connection: ProtocolConnection,
    domainFqn: DomainFqn): Props = Props(
    new ClientActor(domainManager, connection, domainFqn))
}

class ClientActor(
  private[this] val domainManager: ActorRef,
  private[this] val connection: ProtocolConnection,
  private[this] val domainFqn: DomainFqn)
    extends Actor with ActorLogging {

  // FIXME hard-coded
  implicit val timeout = Timeout(500 millis)
  implicit val ec = context.dispatcher
  
  val handshakeTimeoutTask = context.system.scheduler.scheduleOnce(1 seconds) {
    connection.abort("Handhsake timeout")
    context.stop(self)
  }

  private[this] val connectionManager = context.parent

  connection.eventHandler = { case event => self ! event }

  var modelClient: ActorRef = _
  var domainActor: ActorRef = _

  def receive = receiveWhileHandshaking

  def receiveWhileHandshaking: Receive = {
    case RequestReceived(message, replyPromise) if message.isInstanceOf[HandshakeRequestMessage] => {
      handshake(message.asInstanceOf[HandshakeRequestMessage], replyPromise)
    }
    case _ => invalidMessage()
  }
  
  def receiveWhileAuthenticating: Receive = {
    case RequestReceived(message, replyPromise) if message.isInstanceOf[AuthenticationRequestMessage] => {
      authenticate(message.asInstanceOf[AuthenticationRequestMessage], replyPromise)
    }
    case _ => invalidMessage()
  }

  def authenticate(requestMessage: AuthenticationRequestMessage, reply: Promise[OutgoingProtocolResponseMessage]): Unit = {
    val message = requestMessage match {
      case PasswordAuthenticationRequestMessage(username, password) => PasswordAuthRequest(username, password)
      case TokenAuthenticationRequestMessage(token) => TokenAuthRequest(token)
    }
    
    val future = domainManager ? message
    
    future.mapReponse[AuthenticationResponse] onComplete {
      case Success(AuthenticationSuccess(username)) => {
        reply.success(AuthenticationSuccessResponseMessage(username))
        context.become(receiveWhileHandshook)
      }
      case Success(AuthenticationFailure) => {
        reply.success(AuthenticationFailureResponseMessage())
      }
      case Failure(cause) => {
        reply.success(AuthenticationFailureResponseMessage())
      }
    }
  }
  
  def handshake(request: HandshakeRequestMessage, reply: Promise[OutgoingProtocolResponseMessage]): Unit = {
    var canceled = handshakeTimeoutTask.cancel()
    if (!canceled) {
      return
    }
    
    val future = domainManager ? HandshakeRequest(domainFqn, self, request.reconnect, request.reconnectToken)
    future.mapReponse[HandshakeResponse] onComplete {
      case Success(HandshakeSuccess(sessionId, reconnectToken, domainActor, modelManagerActor)) => {
        this.domainActor = domainActor
        this.modelClient = context.actorOf(ModelClientActor.props(modelManagerActor))
        reply.success(HandshakeResponseMessage(true, None, Some(sessionId), Some(reconnectToken)))
        context.become(receiveWhileHandshook)
      }
      case Success(HandshakeFailure(code, details)) => {
        reply.success(HandshakeResponseMessage(false, Some(ErrorData(code, details)), None, None))
        connection.abort("handshake timeout")
        context.stop(self)
      }
      case Failure(cause) => {
        reply.success(HandshakeResponseMessage(false, Some(ErrorData("unknown", "uknown error")), None, None))
        connection.abort("handshake timeout")
        context.stop(self)
      }
    }
  }

  def receiveWhileHandshook: Receive = {
    case RequestReceived(message, replyPromise) if message.isInstanceOf[HandshakeRequestMessage] => invalidMessage()

    case message: OutgoingProtocolNormalMessage => onOutgoingMessage(message)
    case message: OutgoingProtocolRequestMessage => onOutgoingRequest(message)

    case message: MessageReceived => onMessageReceived(message)
    case message: RequestReceived => onRequestReceived(message)

    case ConnectionClosed() => onConnectionClosed()
    case ConnectionDropped() => onConnectionDropped()
    case ConnectionError(message) => onConnectionError(message)

    case x => unhandled(x)
  }

  def onOutgoingMessage(message: OutgoingProtocolNormalMessage): Unit = {
    connection.send(message)
  }

  def onOutgoingRequest(message: OutgoingProtocolRequestMessage): Unit = {
    val askingActor = sender()
    val f = connection.request(message)
    // FIXME should we allow them to specify what should be coming back.
    f.mapTo[IncomingProtocolResponseMessage] onComplete {
      case Success(response) => askingActor ! response
      case Failure(cause) => ??? // FIXME what do do on failure?
    }
  }

  private def onMessageReceived(message: MessageReceived): Unit = {
    message match {
      case MessageReceived(x) if x.isInstanceOf[IncomingModelNormalMessage] => modelClient.forward(message)
      case _ => ???
    }
  }

  private def onRequestReceived(message: RequestReceived): Unit = {
    message match {
      case RequestReceived(x, _) if x.isInstanceOf[IncomingModelRequestMessage] => modelClient.forward(message)
      case _ => ???
    }
  }

  private def onConnectionClosed(): Unit = {
    context.stop(self)
  }

  private def onConnectionDropped(): Unit = {
    context.stop(self)
  }

  private def onConnectionError(message: String): Unit = {
    context.stop(self)
  }

  private[this] def invalidMessage(): Unit = {
    connection.abort("Invalid message")
    context.stop(self)
  }
  
  override def postStop(): Unit = {
    if (!handshakeTimeoutTask.isCancelled) {
      handshakeTimeoutTask.cancel()
    }
  }
}