package org.scalalab.twitter.streaming.sample.search

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import org.scalalab.twitter.streaming.sample.auth.AuthenticationStage.Authenticated
import org.scalalab.twitter.streaming.sample.auth._
import org.scalalab.twitter.streaming.sample.search.SearchWorkerActor.{ConnectionError, CouldNotAuthenticate, SearchResult, UnexpectedResponseStatus}
import spray.http._
import spray.httpx.RequestBuilding._
import spray.json._


class SearchWorkerActor(hostConnectorActor: ActorRef,
                        consumer: ConsumerCredentials,
                        accessToken: AccessToken,
                        query: String) extends Actor with ActorLogging {

    private val signRequest =
        new OAuthSignatureHelper(authStage = Authenticated(consumer, accessToken)).signOAuthRequestTransformer

    override def preStart(): Unit = {
        val searchStreamRequest =
            signRequest(Get(Uri("https://stream.twitter.com/1.1/statuses/filter.json").withQuery("track" -> query)))
        hostConnectorActor ! searchStreamRequest
    }

    override def receive: Receive = {
        case ChunkedResponseStart(response) =>
            log.info(s"Start collecting tweets for query '$query'")
            context.become(collectingChunks(partialContent = ""))

        case other => commonMessageHandlers(other)
    }

    def collectingChunks(partialContent: String): Receive = {
        case MessageChunk(data, _) =>
            val updatedContent = partialContent + new String(data.toByteArray, "UTF-8")
            val (completeMessages, remainingPartialContent) = SearchWorkerActor.extractCompleteMessages(updatedContent)

            completeMessages.foreach { message =>
                val tweet = message.parseJson.convertTo[Tweet]
                context.parent ! SearchResult(query, tweet)
            }

            context.become(collectingChunks(remainingPartialContent))

        case other => commonMessageHandlers(other)
    }

    def commonMessageHandlers: Receive = {
        case Failure(cause) =>
            // connection broken, let supervisor handle this
            throw new ConnectionError(cause)

        case HttpResponse(status, entity, _, _) if status == StatusCodes.Unauthorized =>
            log.error(s"Could not authenticate - received response with status $status: $entity")
            context.parent ! CouldNotAuthenticate
            context.stop(self)

        case HttpResponse(status, entity, _, _) if status != StatusCodes.OK =>
            throw new UnexpectedResponseStatus(status)

        case msg =>
            log.warning(s"Unexpected message at worker actor: $msg")
    }
}


object SearchWorkerActor {
    case class SearchResult(query: String, tweet: Tweet)
    case object CouldNotAuthenticate

    class ConnectionError(cause: Throwable) extends RuntimeException(cause)
    class UnexpectedResponseStatus(status: StatusCode) extends RuntimeException(s"Unexpected status code: $status")

    def props(hostConnectorActor: ActorRef,
              consumer: ConsumerCredentials,
              token: AccessToken,
              query: String): Props = Props(new SearchWorkerActor(hostConnectorActor, consumer, token, query))
    
    private def extractCompleteMessages(partialContent: String): (Seq[String], String) = {
        val completeMessagesEnd = partialContent.lastIndexOf(Separator)

        if (completeMessagesEnd >= 0) {
            val (completeMessagesString, remainingPartialContent) = partialContent.splitAt(completeMessagesEnd)
            val completeMessages = completeMessagesString.split(Separator).toSeq.filter(_.nonEmpty)

            (completeMessages, remainingPartialContent.substring(Separator.length))
        } else {
            (Nil, partialContent)
        }
    }

    private val Separator = "\r\n"
}
