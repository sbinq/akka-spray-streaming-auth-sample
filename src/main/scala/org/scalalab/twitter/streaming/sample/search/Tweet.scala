package org.scalalab.twitter.streaming.sample.search

import spray.json.DefaultJsonProtocol._
import spray.json.JsonFormat


case class TwitterUser(screenName: String, displayName: String)
case class Tweet(user: TwitterUser, text: String)

object TwitterUser {
    // This can be considered as example of 'Typeclass pattern' -
    // see http://danielwestheide.com/blog/2013/02/06/the-neophytes-guide-to-scala-part-12-type-classes.html
    implicit val format: JsonFormat[TwitterUser] = jsonFormat(TwitterUser.apply, "screen_name", "name")
}

object Tweet {
    // Same as above regarding typeclasses
    implicit val format: JsonFormat[Tweet] = jsonFormat(Tweet.apply, "user", "text")
}
