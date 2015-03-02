package org.scalalab.twitter.streaming.sample.auth

import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import org.parboiled.common.Base64

object CryptUtils {
    def percentEncode(s: Seq[String]): String = {
        s.map(percentEncode).mkString("&")
    }

    def percentEncode(str: String): String = {
        URLEncoder.encode(str, "UTF-8").replace("+", "%20").replace("%7E", "~")
    }

    def toBytes(str: String) = {
        str.getBytes("UTF-8")
    }

    def encrypt(key: String, content: String) = {
        val secretKey = new SecretKeySpec(toBytes(key), "HmacSHA1")
        val mac = Mac.getInstance("HmacSHA1")

        mac.init(secretKey)
        val encryptedContent = Base64.rfc2045().encodeToString(mac.doFinal(toBytes(content)), false)
        mac.reset()

        encryptedContent
    }
}
