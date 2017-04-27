package com.convergencelabs.server.domain

import java.time.Instant

import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Try

import com.convergencelabs.server.datastore.EntityNotFoundException
import com.convergencelabs.server.datastore.domain.DomainPersistenceManagerActor
import com.convergencelabs.server.datastore.domain.DomainPersistenceProvider

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ReceiveTimeout
import akka.actor.Status
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Publish

object ChatChannelActor {

  object ActorStatus extends Enumeration {
    val Initialized, NotInitialized = Value
  }

  def getChatUsernameTopicName(username: String): String = {
    return s"chat-user-${username}"
  }

  case class ChatChannelActorState(status: ActorStatus.Value, state: Option[ChatChannelState])

  case object Stop
}

case class ChatChannelState(
  id: String,
  channelType: String, // make enum?
  created: Instant,
  isPrivate: Boolean,
  name: String,
  topic: String,
  lastEventTime: Instant,
  lastEventNumber: Long,
  members: Set[String])

class ChatChannelActor private[domain] (domainFqn: DomainFqn) extends Actor with ActorLogging {
  import ChatChannelActor._
  import ChatChannelMessages._
  import akka.cluster.sharding.ShardRegion.Passivate

  log.debug(s"Chat Channel Actor starting in domain: '${domainFqn}'")

  // TODO: Load from configuration 
  context.setReceiveTimeout(120.seconds)

  //  override def persistenceId: String = "ChatChannel-" + self.path.name

  val mediator = DistributedPubSub(context.system).mediator

  // Here None signifies that the channel does not exist.
  var channelActorState: ChatChannelActorState = ChatChannelActorState(ActorStatus.NotInitialized, None)
  var channelManager: Option[ChatChannelManager] = None

  //  override def receiveRecover: Receive = {
  //    case state: ChatChannelActorState =>
  //      channelActorState = state
  //      state.status match {
  //        case ActorStatus.Initialized => context.become(receiveWhenInitialized)
  //      }
  //  }

  // Default recieve will be called the first time
  //  override def receiveCommand: Receive = {
  override def receive: Receive = {
    case message: ChatChannelMessage =>
      initialize(message.channelId)
        .flatMap { _ =>
          log.debug(s"Chat Channel Actor initialized processing message: '${domainFqn}/${message.channelId}'")
          processChatMessage(message)
        }
        .recover { case cause: Exception => this.unexpectedError(message, cause) }
    case other: Any =>
      this.receiveCommon(other)
  }

  private[this] def initialize(channelId: String): Try[Unit] = {
    log.debug(s"Chat Channel Actor starting: '${domainFqn}/${channelId}'")
    DomainPersistenceManagerActor.acquirePersistenceProvider(self, context, domainFqn) flatMap { provider =>
      // FIXME we probably want a get channel optional...
      // FIXME should we get a method that returns everyting below?

      this.channelManager = Some(new ChatChannelManager(channelId, provider))

      provider.chatChannelStore.getChatChannel(channelId) map { channel =>
        // FIXME don't have members?
        val members = Set("michael", "cameron")
        // FIXME don't have the sequence number?
        val maxEvent = 7L
        // FIXME don't have the last event time
        val lastTime = Instant.now()

        this.channelActorState = ChatChannelActorState(ActorStatus.Initialized, Some(
          ChatChannelState(
            channelId,
            channel.channelType,
            channel.created,
            channel.isPrivate,
            channel.name,
            channel.topic,
            lastTime,
            maxEvent,
            members)))
        //    persist(channelActorState)(updateState)
        ()
      } recover {
        case cause: EntityNotFoundException =>
          this.channelActorState = ChatChannelActorState(ActorStatus.Initialized, None)
      } map { _ =>
        context.become(receiveWhenInitialized)
      }
    }
  }

  def receiveWhenInitialized: Receive = {
    case message: ChatChannelMessage =>
      processChatMessage(message)
        .recover { case cause: Exception => this.unexpectedError(message, cause) }
    case other: Any =>
      this.receiveCommon(other)
  }

  def processChatMessage(message: ChatChannelMessage): Try[Unit] = {
    this.channelManager match {
      case Some(manager) =>
        manager.handleChatMessage(message, this.channelActorState.state) map { result =>
          result.state foreach (updateState(_))
          result.response foreach (response => sender ! response)
          result.broadcastMessages foreach (broadcastToChannel(_))
        } recover {
          case cause: ChannelNotFoundException =>
            // It seems like there is no reason to stay up, at this point.
            context.parent ! Passivate(stopMessage = Stop)
            sender ! Status.Failure(cause)

          case ChatChannelException(cause) =>
            sender ! Status.Failure(cause)
        }
      case None =>
        Failure(new IllegalStateException("Can't process chat message with no chat channel manager set."))
    }
  }

  private[this] def receiveCommon: PartialFunction[Any, Unit] = {
    case ReceiveTimeout =>
      this.onReceiveTimeout()
    case Stop =>
      onStop()
    case unhandled: Any =>
      this.unhandled(unhandled)
  }

  private[this] def onReceiveTimeout(): Unit = {
    log.debug("Receive timeout reached, asking shard region to passivate")
    context.parent ! Passivate(stopMessage = Stop)
  }

  private[this] def onStop(): Unit = {
    log.debug("Receive stop signal shutting down")
    DomainPersistenceManagerActor.releasePersistenceProvider(self, context, domainFqn)
    context.stop(self)
  }

  private[this] def unexpectedError(message: ChatChannelMessage, cause: Exception): Unit = {
    cause match {
      case cause: Exception =>
        sender ! Status.Failure(cause)
        ()
    }
  }

  private[this] def broadcastToChannel(message: Any): Unit = {
    // FIXME pattern match
    val members = this.channelActorState.state.get.members
    members.foreach { member =>
      val topic = ChatChannelActor.getChatUsernameTopicName(member)
      mediator ! Publish(topic, message)
    }
  }

  private[this] def updateState(state: ChatChannelState): Unit = {
    updateState(this.channelActorState.copy(state = Some(state)))
  }

  def updateState(state: ChatChannelActorState): Unit = channelActorState = state
}