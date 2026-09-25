package corebanking.engine

import zio.test.*

import java.time.LocalDate

object RecalculationStrategySpec extends ZIOSpecDefault:

  def spec = suite("engine types")(
    test("ChainStep cases carry their id") {
      assertTrue(
        ChainStep.Reverse("TX-1") == ChainStep.Reverse("TX-1"),
        ChainStep.Post("TX-2") != ChainStep.Repost("TX-2")
      )
    },
    test("RecalcError.PeriodClosed carries the period start date") {
      val err = RecalcError.PeriodClosed(LocalDate.parse("2026-09-01"))
      assertTrue(err == RecalcError.PeriodClosed(LocalDate.parse("2026-09-01")))
    }
  )
