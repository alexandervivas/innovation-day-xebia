package corebanking.domain

import java.util.UUID

import zio.test.*

object IdsSpec extends ZIOSpecDefault:

  def spec: Spec[Any, Any] =
    suite("entity id opaque types")(
      test("ProductId round-trips the wrapped UUID") {
        val raw = UUID.randomUUID()
        assertTrue(ProductId(raw).value == raw)
      },
      test("ClientId round-trips the wrapped UUID") {
        val raw = UUID.randomUUID()
        assertTrue(ClientId(raw).value == raw)
      },
      test("AccountId round-trips the wrapped UUID") {
        val raw = UUID.randomUUID()
        assertTrue(AccountId(raw).value == raw)
      },
      test("TransactionId round-trips the wrapped UUID") {
        val raw = UUID.randomUUID()
        assertTrue(TransactionId(raw).value == raw)
      }
    )
