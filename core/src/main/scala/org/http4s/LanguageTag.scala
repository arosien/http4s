package org.http4s

sealed abstract class LanguageRange {
  def primaryTag: String
  def subTags: Seq[String]
  val value = (primaryTag +: subTags).mkString("-")
  override def toString = "LanguageRange(" + value + ')'
}

object LanguageTags {

  case object `*` extends LanguageRange {
    def primaryTag = "*"
    def subTags = Seq.empty[String]
  }

}

case class LanguageTag(primaryTag: String, subTags: String*) extends LanguageRange

