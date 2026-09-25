package corebanking.config

/**
 * Where to find the mock Postgres core. Defaults match `docker-compose.yml`'s own defaults, so a
 * fresh `docker compose up -d` with no `.env` file works out of the box.
 */
final case class DbConfig(url: String, user: String, password: String)

object DbConfig:

  private val defaultUrl = "jdbc:postgresql://localhost:5432/corebanking"
  private val defaultUser = "corebanking"
  private val defaultPassword = "change-me"

  def fromEnv(env: Map[String, String] = sys.env): DbConfig =
    DbConfig(
      url = env.getOrElse("DATABASE_URL", defaultUrl),
      user = env.getOrElse("POSTGRES_USER", defaultUser),
      password = env.getOrElse("POSTGRES_PASSWORD", defaultPassword)
    )
