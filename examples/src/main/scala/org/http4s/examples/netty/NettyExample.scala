package org.http4s
package netty

import org.http4s.iteratee.{Enumeratee, Concurrent, Done}
import org.http4s._
import com.typesafe.scalalogging.slf4j.Logging
import concurrent.Future

object NettyExample extends App with Logging {

  import concurrent.ExecutionContext.Implicits.global

  SimpleNettyServer()(ExampleRoute())

}
