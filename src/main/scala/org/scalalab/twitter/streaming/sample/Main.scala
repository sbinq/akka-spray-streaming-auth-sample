package org.scalalab.twitter.streaming.sample

import akka.actor.ActorSystem
import akka.io.IO
import com.typesafe.config.{Config, ConfigFactory}
import org.scalalab.twitter.streaming.sample.auth.{AccessToken, ConsumerCredentials, RequestToken, TwitterAuthenticator}
import org.scalalab.twitter.streaming.sample.search.SearchWorkersSupervisor
import spray.can.Http

import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.io.StdIn

object Main {

    def readPinCode(requestToken: RequestToken): String = {
        StdIn.readLine(s"\n\nPlease authorize application going to ${requestToken.authenticateUrl} and enter PIN code: ").trim()
    }

    def readOrCreateAccessToken(config: Config, system: ActorSystem): (ConsumerCredentials, AccessToken) = {
        val consumer = ConsumerCredentials(
            config.getString("consumer.key"),
            config.getString("consumer.secret")
        )

        val accessTokenFuture =
            (config.getString("access.token.key"), config.getString("access.token.secret")) match {
                case (key, secret) if key.nonEmpty && secret.nonEmpty =>
                    Future.successful(AccessToken(token = key, secret = secret))

                case (_, _) =>
                    val authenticator = new TwitterAuthenticator(system)

                    import system.dispatcher
                    for {
                        requestToken <- authenticator.requestToken(consumer)
                        pinCode = readPinCode(requestToken)
                        accessToken <- authenticator.accessToken(consumer, requestToken, pinCode)
                    } yield accessToken
            }

        val accessToken = Await.result(accessTokenFuture, Duration.Inf) // TODO: error handling
        println(s"Using acess token $accessToken")

        (consumer, accessToken)
    }

    def main(args: Array[String]): Unit = {
        val system = ActorSystem()

        val config = ConfigFactory.load()
        val searchQueries = config.getStringList("twitter.search.queries").toList

        val (consumer, accessToken) = readOrCreateAccessToken(config, system)

        val ioActor = IO(Http)(system)
        val searchActor =
            system.actorOf(SearchWorkersSupervisor.props(consumer, accessToken, ioActor, searchQueries))

        sys.addShutdownHook {
            println("Will shutdown everything now..")
            // TODO: output statistics collected here
            system.shutdown()
        }
    }
}
