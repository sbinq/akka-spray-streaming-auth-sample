package org.scalalab.twitter.streaming.sample.auth

import org.scalalab.twitter.streaming.sample.auth.AuthenticationStage.{Authenticated, RetrievingAccessToken, RetrievingRequestToken}
import spray.client.pipelining._
import spray.http.HttpHeaders.RawHeader
import spray.http.Uri.Query
import spray.http.{HttpMethod, Uri}

import scala.collection.immutable.TreeMap


case class ConsumerCredentials(key: String, secret: String)
case class RequestToken(token: String, secret: String) {
    def authenticateUrl: String = s"https://api.twitter.com/oauth/authenticate?oauth_token=$token"
}
case class AccessToken(token: String, secret: String)

/**
 * Represents current 'stage' within twitter authentication flow.
 */
sealed trait AuthenticationStage
object AuthenticationStage {
    case class RetrievingRequestToken(consumer: ConsumerCredentials) extends AuthenticationStage
    case class RetrievingAccessToken(consumer: ConsumerCredentials, requestToken: RequestToken, pinCode: String) extends AuthenticationStage
    case class Authenticated(consumer: ConsumerCredentials, accessToken: AccessToken) extends AuthenticationStage
}

/**
 * 'Random' stuff used while generating oauth header.
 */
case class OAuthParamsSeed(timestampSeconds: Long, nonce: String) {
    def this(timestampSeconds: Long) = {
        this(timestampSeconds, System.nanoTime.toString)
    }

    def this() = {
        this(System.currentTimeMillis() / 1000)
    }
}


class OAuthSignatureHelper(authStage: AuthenticationStage) {
    
    val signOAuthRequestTransformer: RequestTransformer = {
        request => {
            val header = generateAuthorizationHeader(request.method, request.uri, new OAuthParamsSeed())
            addHeader(header)(request)
        }
    }

    def generateAuthorizationHeader(method: HttpMethod, uri: Uri, seed: OAuthParamsSeed): RawHeader = {
        val oauthParams = OAuthParamsBuilder.oauthParamsFor(authStage, seed)

        val signatureBase = generateSignatureBaseString(method, uri, oauthParams)
        val signature = CryptUtils.percentEncode(CryptUtils.encrypt(key = encryptionKey, signatureBase))

        val paramsWithSignature = oauthParams + ("oauth_signature" -> signature)
        val sortedParamsWithSignature = TreeMap(paramsWithSignature.toSeq: _*)

        val encodedAuthorizationContent =
            sortedParamsWithSignature.map { case (key, value) => s"""$key="$value"""" }.mkString(", ")

        RawHeader("Authorization", "OAuth " + encodedAuthorizationContent)
    }

    def generateSignatureBaseString(method: HttpMethod, uri: Uri,
                                    oauthParams: Map[String, String]): String = {
        val url = uri.withoutFragment.withQuery(Query.Empty).toString()
        generateSignatureBaseString(method, url, oauthParams ++ uri.query)
    }

    def generateSignatureBaseString(httpMethod: HttpMethod, urlWithoutParams: String,
                                    params: Map[String, String]): String = {
        val encodedOrderedParams = TreeMap(params.toSeq: _*).map { case (k, v) => k + "=" + v }.mkString("&")
        CryptUtils.percentEncode(Seq(httpMethod.name, urlWithoutParams, encodedOrderedParams))
    }

    private val encryptionKey: String = {
        val (consumerSecret, tokenSecret) = authStage match {
            case RetrievingRequestToken(consumer: ConsumerCredentials) => (consumer.secret, "")
            case RetrievingAccessToken(consumer, requestToken, pinCode) => (consumer.secret, requestToken.secret)
            case Authenticated(consumer, accessToken) => (consumer.secret, accessToken.secret)
        }
        consumerSecret + "&" + tokenSecret
    }
}


object OAuthParamsBuilder {

    /** Generates oauth header params required at different authentication stages */
    def oauthParamsFor(authStage: AuthenticationStage, seed: OAuthParamsSeed): Map[String, String] = {
        authStage match {
            case RetrievingRequestToken(consumer: ConsumerCredentials) => oauthParamsFor(consumer, seed)
            case RetrievingAccessToken(consumer, requestToken, pinCode) => oauthParamsFor(consumer, requestToken, pinCode, seed)
            case Authenticated(consumer, accessToken) => oauthParamsFor(consumer, accessToken, seed)
        }
    }

    private def oauthParamsFor(consumer: ConsumerCredentials,
                               accessToken: AccessToken,
                               seed: OAuthParamsSeed): Map[String, String] = {
        oauthParamsFor(consumer, seed) ++ Map(
            "oauth_token" -> accessToken.token
        )
    }

    private def oauthParamsFor(consumer: ConsumerCredentials,
                       requestToken: RequestToken,
                       pinCode: String,
                       seed: OAuthParamsSeed): Map[String, String] = {
        oauthParamsFor(consumer, seed) ++ Map(
            "oauth_token" -> requestToken.token,
            "oauth_verifier" -> pinCode
        )
    }


    private def oauthParamsFor(consumer: ConsumerCredentials,
                       seed: OAuthParamsSeed): Map[String, String] = {
        Map(
            "oauth_consumer_key" -> consumer.key,
            "oauth_nonce" -> seed.nonce,
            "oauth_signature_method" -> "HMAC-SHA1",
            "oauth_timestamp" -> seed.timestampSeconds.toString,
            "oauth_version" -> "1.0"
        )
    }

}



