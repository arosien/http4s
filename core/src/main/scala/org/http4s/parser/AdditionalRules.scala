package org.http4s
package parser

import org.parboiled.scala._
import BasicRules._
import java.net.InetAddress

// implementation of additional parsing rules required for extensions that are not in the core HTTP standard
private[parser] trait AdditionalRules {
  this: Parser =>

  def Ip: Rule1[InetAddress] = rule (
    group(IpNumber ~ ch('.') ~ IpNumber ~ ch('.') ~ IpNumber ~ ch('.') ~ IpNumber) ~> (InetAddress.getByName(_)) ~ OptWS
  )

  def IpNumber = rule {
    Digit ~ optional(Digit ~ optional(Digit))
  }

  def AuthScheme = rule {
    Token ~ OptWS
  }

  def AuthParam = rule {
    Token ~ "=" ~ (Token | QuotedString) ~~> ((_, _))
  }
}