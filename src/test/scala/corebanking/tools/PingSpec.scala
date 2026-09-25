package corebanking.tools

import zio.Scope
import zio.json.*
import zio.test.*

import corebanking.config.CoreEnv

/**
 * `Ping.response` is pure (free of ZIO/MCP transport concerns) and does not touch `Server`, so this
 * spec never triggers the `CORE_ENV` guard and must not reference `Server` at all.
 */
object PingSpec extends ZIOSpecDefault:

  final case class DecodedData(pong: Boolean, server: String, version: String)

  object DecodedData:
    given JsonCodec[DecodedData] = DeriveJsonCodec.gen[DecodedData]

  final case class DecodedEnvelope(env: String, data: DecodedData)

  object DecodedEnvelope:
    given JsonCodec[DecodedEnvelope] = DeriveJsonCodec.gen[DecodedEnvelope]

  /**
   * Splits a JSON object's body into its top-level `"key":value` members, tracking brace/bracket
   * depth so nested objects/arrays are not split on their own commas. Avoids depending on the
   * zio-json-ast module, which is not declared in build.sbt.
   */
  private def topLevelMembers(objectBody: String): Vector[String] =
    var depth = 0
    val parts = scala.collection.mutable.ArrayBuffer.empty[String]
    val current = new StringBuilder
    for c <- objectBody do
      c match
        case '{' | '[' =>
          depth += 1
          current.append(c)
        case '}' | ']' =>
          depth -= 1
          current.append(c)
        case ',' if depth == 0 =>
          parts += current.toString
          current.clear()
        case other =>
          current.append(other)
    if current.nonEmpty then parts += current.toString
    parts.toVector

  private def objectBody(json: String): String =
    json.trim.stripPrefix("{").stripSuffix("}")

  private def topLevelKeys(json: String): Set[String] =
    topLevelMembers(objectBody(json))
      .map(_.trim.takeWhile(_ != ':').trim.stripPrefix("\"").stripSuffix("\""))
      .toSet

  /** Returns the raw JSON value (still `{...}`) bound to top-level key `key`, if present. */
  private def topLevelValue(json: String, key: String): Option[String] =
    topLevelMembers(objectBody(json)).flatMap { member =>
      val idx = member.indexOf(':')
      val memberKey = member.substring(0, idx).trim.stripPrefix("\"").stripSuffix("\"")
      if memberKey == key then Some(member.substring(idx + 1).trim) else None
    }.headOption

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Ping.response")(
    test("mock env decodes to exactly the expected envelope with exact top-level and data keys") {
      val json = Ping.response(CoreEnv.Mock, "0.1.0")

      assertTrue(
        topLevelKeys(json) == Set("env", "data"),
        json.fromJson[DecodedEnvelope] ==
          Right(
            DecodedEnvelope(
              env = "mock",
              data = DecodedData(pong = true, server = "core-banking-mcp", version = "0.1.0")
            )
          )
      )
    },
    test(
      "sandbox env decodes to exactly the expected envelope with exact top-level and data keys"
    ) {
      val json = Ping.response(CoreEnv.Sandbox, "0.1.0")

      assertTrue(
        topLevelKeys(json) == Set("env", "data"),
        json.fromJson[DecodedEnvelope] ==
          Right(
            DecodedEnvelope(
              env = "sandbox",
              data = DecodedData(pong = true, server = "core-banking-mcp", version = "0.1.0")
            )
          )
      )
    },
    test("data object has exactly the keys pong, server, version") {
      val json = Ping.response(CoreEnv.Mock, "0.1.0")
      val data = topLevelValue(json, "data").getOrElse("")
      assertTrue(topLevelKeys(data) == Set("pong", "server", "version"))
    }
  )
