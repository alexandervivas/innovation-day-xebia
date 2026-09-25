package corebanking.tools

import zio.Scope
import zio.json.*
import zio.test.*

import corebanking.config.CoreEnv

object ToolResponseSpec extends ZIOSpecDefault:

  final case class SamplePayload(id: Int, name: String)

  object SamplePayload:
    given JsonCodec[SamplePayload] = DeriveJsonCodec.gen[SamplePayload]

  final case class DecodedEnvelope(env: String, data: SamplePayload)

  object DecodedEnvelope:
    given JsonCodec[DecodedEnvelope] = DeriveJsonCodec.gen[DecodedEnvelope]

  def spec: Spec[TestEnvironment & Scope, Any] = suite("ToolResponse.respond")(
    test("output parses back with an env matching the given label and data matching the input") {
      val payload = SamplePayload(id = 42, name = "widget")
      val json = ToolResponse.respond(CoreEnv.Mock, payload)

      val decoded = json.fromJson[DecodedEnvelope]

      assertTrue(decoded == Right(DecodedEnvelope(env = "mock", data = payload)))
    },
    test("sandbox env label round-trips too") {
      val payload = SamplePayload(id = 7, name = "gadget")
      val json = ToolResponse.respond(CoreEnv.Sandbox, payload)

      val decoded = json.fromJson[DecodedEnvelope]

      assertTrue(decoded == Right(DecodedEnvelope(env = "sandbox", data = payload)))
    }
  )
