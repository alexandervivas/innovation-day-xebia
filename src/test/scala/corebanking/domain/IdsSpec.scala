package corebanking.domain

import java.util.UUID

import zio.test.*
import zio.test.Assertion.*

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
      },
      test(
        "a ClientId cannot be used where a ProductId is expected (opaque, not a transparent alias)"
      ) {
        val program =
          "def wantsProductId(p: ProductId): Unit = (); wantsProductId(ClientId(java.util.UUID.randomUUID()))"
        assertZIO(typeCheck(program))(isLeft(anything))
      }
    )
