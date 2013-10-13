package org.http4s.examples

import org.http4s.scalatra.ScalatraService
import org.http4s.Status
import scalaz.{State, Monad, Unapply}
import org.http4s.effectful._
import scalaz._
import org.http4s.scalatra.ScalatraService.Action

object ScalatraExample extends ScalatraService {
  def logSetStatus(newStatus: Status) = {
    println("Previous status was: "+status)
    status = newStatus
  }

  GET("/gone") {
    logSetStatus(Status.Gone)
    s"status is ${status}"
  }

  GET("/pure") { "pure" }
}