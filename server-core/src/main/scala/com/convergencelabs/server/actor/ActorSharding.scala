package com.convergencelabs.server.actor

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardRegion

abstract class ActorSharding(
  val shardName:      String,
  val systemRole:     String,
  val actorClass:     Class[_]) {

  def start(system: ActorSystem, numberOfShards: Int): ActorRef = {
    this.start(system, numberOfShards, List())
  }
  
  def start(system: ActorSystem, numberOfShards: Int, args: List[Any] ): ActorRef = {
    val settings = ClusterShardingSettings
      .create(system)
      .withRole(this.systemRole);

    val sharedRegion = ClusterSharding.get(system).start(
      this.shardName,
      Props(this.actorClass, args),
      settings,
      this.extractEntityId,
      this.extractShardId(numberOfShards))

    sharedRegion
  }

  def startProxy(system: ActorSystem, numberOfShards: Int): ActorRef = {
    return ClusterSharding.get(system).startProxy(
      this.shardName,
      Some(this.systemRole),
      this.extractEntityId,
      this.extractShardId(numberOfShards));
  }

  def shardRegion(system: ActorSystem): ActorRef = {
    return ClusterSharding.get(system).shardRegion(this.shardName)
  }

  protected def extractEntityId: ShardRegion.ExtractEntityId

  protected def extractShardId(numberOfShards: Int): ShardRegion.ExtractShardId
}

