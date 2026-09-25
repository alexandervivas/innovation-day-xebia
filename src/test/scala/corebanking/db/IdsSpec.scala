package corebanking.db

import java.util.UUID

import zio.Scope
import zio.test.*

object IdsSpec extends ZIOSpecDefault:

  def spec: Spec[TestEnvironment & Scope, Any] = suite("Ids.next")(
    test("mints a UUID with version nibble 7 and variant nibble 8-b") {
      val id = Ids.next()
      // RFC 9562 UUIDv7: the 13th hex digit of the canonical string is the version ("7"), and the
      // 17th hex digit (first of the 4th group) is the variant, one of 8/9/a/b.
      val text = id.toString
      assertTrue(
        text.charAt(14) == '7',
        Set('8', '9', 'a', 'b').contains(text.charAt(19))
      )
    },
    test("two calls never collide") {
      val a = Ids.next()
      val b = Ids.next()
      assertTrue(a != b)
    }
  )
