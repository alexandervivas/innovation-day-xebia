package corebanking.config

import zio.Scope
import zio.test.*
import zio.test.Assertion.*

object CoreEnvSpec extends ZIOSpecDefault:

  private val notMockOrSandbox: Gen[Any, String] =
    Gen.string.filter { s =>
      val normalized = s.trim.toLowerCase
      normalized != "mock" && normalized != "sandbox"
    }

  def spec: Spec[TestEnvironment & Scope, Any] = suite("CoreEnv")(
    test("parses 'mock' as CoreEnv.Mock") {
      assertTrue(CoreEnv.parse(Some("mock")) == Right(CoreEnv.Mock))
    },
    test("parses 'SANDBOX' case-insensitively as CoreEnv.Sandbox") {
      assertTrue(CoreEnv.parse(Some("SANDBOX")) == Right(CoreEnv.Sandbox))
    },
    test("parses '  Sandbox ' trimmed as CoreEnv.Sandbox") {
      assertTrue(CoreEnv.parse(Some("  Sandbox ")) == Right(CoreEnv.Sandbox))
    },
    test("None yields the exact 'not set' message") {
      assertTrue(
        CoreEnv.parse(None) ==
          Left("CORE_ENV is not set; refusing to start. Allowed values: mock, sandbox")
      )
    },
    test("'production' yields the exact 'not allowed' message") {
      assertTrue(
        CoreEnv.parse(Some("production")) ==
          Left(
            """CORE_ENV="production" is not allowed; refusing to start. Allowed values: mock, sandbox"""
          )
      )
    },
    test("empty string yields the 'not set' message (implementation treats \"\" like None)") {
      // CoreEnv.parse matches raw.map(_.trim) against None | Some(""), so an empty (or
      // whitespace-only, after trim) string is routed to the same "not set" branch as None,
      // not the "is not allowed" branch.
      assertTrue(
        CoreEnv.parse(Some("")) ==
          Left("CORE_ENV is not set; refusing to start. Allowed values: mock, sandbox")
      )
    },
    test("whitespace-only string yields the 'not set' message (trims to empty)") {
      assertTrue(
        CoreEnv.parse(Some("   ")) ==
          Left("CORE_ENV is not set; refusing to start. Allowed values: mock, sandbox")
      )
    },
    test("label round-trips for every enum value") {
      assertTrue(
        CoreEnv.values.forall(e => CoreEnv.parse(Some(e.label)) == Right(e))
      )
    },
    test("any string not equal (case-insensitively, trimmed) to mock/sandbox is rejected") {
      check(notMockOrSandbox) { s =>
        assert(CoreEnv.parse(Some(s)))(isLeft)
      }
    },
    test("a 40-char invalid value is echoed truncated to 32 chars followed by an ellipsis") {
      val value = "a" * 40
      assertTrue(
        value.length == 40,
        CoreEnv.parse(Some(value)) ==
          Left(
            s"""CORE_ENV="${"a" * 32}…" is not allowed; refusing to start. Allowed values: mock, sandbox"""
          )
      )
    },
    test("a 32-char invalid value is echoed unchanged, with no ellipsis") {
      val value = "b" * 32
      assertTrue(
        value.length == 32,
        CoreEnv.parse(Some(value)) ==
          Left(
            s"""CORE_ENV="${"b" * 32}" is not allowed; refusing to start. Allowed values: mock, sandbox"""
          )
      )
    }
  )
