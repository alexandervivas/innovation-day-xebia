package corebanking.config

import zio.test.*

object DbConfigSpec extends ZIOSpecDefault:
  def spec = suite("DbConfig.fromEnv")(
    test("defaults match docker-compose.yml when no env vars are set") {
      assertTrue(
        DbConfig.fromEnv(Map.empty) ==
          DbConfig(
            url = "jdbc:postgresql://localhost:5432/corebanking",
            user = "corebanking",
            password = "change-me"
          )
      )
    },
    test("DATABASE_URL, POSTGRES_USER and POSTGRES_PASSWORD override the defaults") {
      val env = Map(
        "DATABASE_URL" -> "jdbc:postgresql://db-host:5555/other",
        "POSTGRES_USER" -> "other-user",
        "POSTGRES_PASSWORD" -> "other-pass"
      )
      assertTrue(
        DbConfig.fromEnv(env) ==
          DbConfig(
            url = "jdbc:postgresql://db-host:5555/other",
            user = "other-user",
            password = "other-pass"
          )
      )
    },
    test("a partial env map falls back to defaults for the missing keys") {
      assertTrue(
        DbConfig.fromEnv(Map("POSTGRES_USER" -> "solo-user")) ==
          DbConfig(
            url = "jdbc:postgresql://localhost:5432/corebanking",
            user = "solo-user",
            password = "change-me"
          )
      )
    }
  )
