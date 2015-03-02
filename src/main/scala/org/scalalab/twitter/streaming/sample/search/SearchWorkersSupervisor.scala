package org.scalalab.twitter.streaming.sample.search

import akka.actor._
import org.scalalab.twitter.streaming.sample.auth.{AccessToken, ConsumerCredentials}
import org.scalalab.twitter.streaming.sample.search.SearchWorkerActor.{ConnectionError, CouldNotAuthenticate, SearchResult, UnexpectedResponseStatus}
import spray.can.Http
import spray.can.client.HostConnectorSettings

class SearchWorkersSupervisor(consumer: ConsumerCredentials,
                              token: AccessToken,
                              ioActor: ActorRef,
                              queries: Seq[String]) extends Actor with ActorLogging {

    override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy(loggingEnabled = false /* too verbose when connection lost */) {
        case _: ConnectionError => SupervisorStrategy.Restart
        case _: UnexpectedResponseStatus => SupervisorStrategy.Restart
        case e =>
            log.error("Unexpected error from search worker: ", e)
            SupervisorStrategy.Escalate
    }

    override def preStart(): Unit = {
        val hostConnectorSetupCommand =
            Http.HostConnectorSetup("stream.twitter.com", port = 443, sslEncryption = true, settings = Some(HostConnectorSettings(
                // `response-chunk-aggregation-limit` setting is essential for working with individuals chunks -
                // ensure it always has value we need; defaults are fine for other options
                Seq("spray.can.client.response-chunk-aggregation-limit = 0",
                    "spray.can.host-connector.max-retries = 10").mkString("\n")
            )))

        ioActor ! hostConnectorSetupCommand
    }

    override def receive = {
        case connectorInfo @ Http.HostConnectorInfo(hostConnectorActor, setup) =>
            queries.foreach { query =>
                context.actorOf(SearchWorkerActor.props(hostConnectorActor, consumer, token, query))
            }

            context.become(handlingSearchResults)

        case other => commonMessageHandlers(other)
    }

    def handlingSearchResults: Receive = {
        case SearchResult(query, Tweet(user, text)) =>
            log.info(s"\n\n'$query' query result from ${user.displayName} (@${user.screenName}): '$text'\n\n")

        case CouldNotAuthenticate =>
            log.error("FATAL: Could not authenticate, shutdown everything")
            context.system.shutdown()

        case other => commonMessageHandlers(other)
    }

    def commonMessageHandlers: Receive = {
        case msg => log.warning(s"Unexpected message at workers supervisor: $msg")
    }
}

object SearchWorkersSupervisor {
    def props(consumer: ConsumerCredentials,
              token: AccessToken,
              ioActor: ActorRef,
              queries: Seq[String]): Props = Props(new SearchWorkersSupervisor(consumer, token, ioActor, queries))
}
