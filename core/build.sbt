import Dependencies._

name := "http4s-core"

description := "Core http4s framework"

libraryDependencies <+= scalaVersion(ScalaReflect)

libraryDependencies ++= Seq(
  AkkaActor,
  Junit % "test",
  Rl,
  Slf4j,
  ScalaStm,
  ScalazCore,
  ScalaloggingSlf4j,
  Shapeless,
  Specs2 % "test",
  ParboiledScala,
  TypesafeConfig
)

seq(buildInfoSettings:_*)

sourceGenerators in Compile <+= buildInfo

buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion)

buildInfoPackage <<= organization

