package org.scalalab.twitter.streaming.sample.auth

import akka.actor.ActorSystem
import org.scalalab.twitter.streaming.sample.auth.AuthenticationStage.{RetrievingAccessToken, RetrievingRequestToken}
import spray.client.pipelining._
import spray.http.Uri

import scala.concurrent.Future

class TwitterAuthenticator(system: ActorSystem) {
    implicit private val implicitSystem = system
    import system.dispatcher

    def requestToken(consumer: ConsumerCredentials): Future[RequestToken] = {
        val signatureHelper = new OAuthSignatureHelper(authStage = RetrievingRequestToken(consumer))
        val pipeline =
            signatureHelper.signOAuthRequestTransformer ~> sendReceive

        pipeline(Post("https://api.twitter.com/oauth/request_token")).map { response =>
            val formData = Uri.Query(response.entity.data.asString).toMap

            RequestToken(
                token = formData("oauth_token"),
                secret = formData("oauth_token_secret")
            )
        }
    }

    def accessToken(consumer: ConsumerCredentials,
                    requestToken: RequestToken,
                    pinCode: String): Future[AccessToken] = {
        val signatureHelper = new OAuthSignatureHelper(authStage = RetrievingAccessToken(consumer, requestToken, pinCode))
        val pipeline =
            signatureHelper.signOAuthRequestTransformer ~> sendReceive

        pipeline(Post(s"https://api.twitter.com/oauth/access_token")).map { response =>
            val formData = Uri.Query(response.entity.data.asString).toMap

            AccessToken(
                token = formData("oauth_token"),
                secret = formData("oauth_token_secret")
            )
        }
    }
}
