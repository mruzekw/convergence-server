package com.convergencelabs.server.frontend.rest.domain

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import com.convergencelabs.server.datastore.domain.DomainStatsActor.DomainStats
import com.convergencelabs.server.datastore.domain.DomainStatsActor.GetStats
import com.convergencelabs.server.domain.DomainFqn
import com.convergencelabs.server.domain.rest.RestDomainActor.DomainRestMessage
import com.convergencelabs.server.frontend.rest.DomainRestService
import com.convergencelabs.server.frontend.rest.RestResponse
import com.convergencelabs.server.frontend.rest.domain.DomainStatsService.GetStatsResponse
import com.convergencelabs.server.frontend.rest.okResponse

import akka.actor.ActorRef
import akka.http.scaladsl.marshalling.ToResponseMarshallable.apply
import akka.http.scaladsl.server.Directive.addByNameNullaryApply
import akka.http.scaladsl.server.Directives._segmentStringToPathMatcher
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.Directives.get
import akka.http.scaladsl.server.Directives.path
import akka.http.scaladsl.server.Route
import akka.util.Timeout

object DomainStatsService {
  case class GetStatsResponse(stats: DomainStats)
}

class DomainStatsService(
  private[this] val executionContext: ExecutionContext,
  private[this] val timeout: Timeout,
  private[this] val authActor: ActorRef,
  private[this] val domainRestActor: ActorRef)
  extends DomainRestService(executionContext, timeout, authActor) {
  
  import akka.pattern.ask

  def route(username: String, domain: DomainFqn): Route =
    (path("stats") & get) {
      complete(getStats(domain))
    }

  def getStats(domain: DomainFqn): Future[RestResponse] = {
    (domainRestActor ? DomainRestMessage(domain, GetStats)).mapTo[DomainStats] map
      (stats => okResponse(GetStatsResponse(stats)))
  }
}
