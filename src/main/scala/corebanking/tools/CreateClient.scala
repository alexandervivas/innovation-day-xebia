package corebanking.tools

import java.time.LocalDate

import zio.json.*

import com.augustnagro.magnum.{DbCon, Transactor, sql, transact}

import corebanking.config.CoreEnv
import corebanking.db.{Client, DryRun, Ids, SystemClockRow}
import corebanking.db.given

/** The client record as `create_client` reports it back to the caller. */
final case class ClientData(
    id: String,
    name: String,
    email: Option[String],
    openedOn: String,
    dryRun: Boolean
)

object ClientData:
  given JsonEncoder[ClientData] = DeriveJsonEncoder.gen[ClientData]

final private case class CreateClientRequest(
    name: String,
    email: Option[String],
    idempotencyKey: Option[String],
    dryRun: Boolean
)

private object CreateClientRequest:
  given JsonEncoder[CreateClientRequest] = DeriveJsonEncoder.gen[CreateClientRequest]

/**
 * Opens a client record, the entity every account and transaction hangs off. A retried call
 * carrying the same `idempotency_key` returns the original client instead of onboarding the same
 * person twice.
 */
object CreateClient:

  def run(
      xa: Transactor,
      env: CoreEnv,
      name: String,
      email: Option[String],
      idempotencyKey: Option[String],
      dryRun: Boolean
  ): String =
    val data = execute(xa, name, email, idempotencyKey, dryRun)
    val response = ToolResponse.respond(env, data)
    // Every call is auditable, including one that only previews.
    AuditLog.record(
      xa,
      toolName = "create_client",
      env = env.label,
      requestJson = CreateClientRequest(name, email, idempotencyKey, dryRun).toJson,
      responseJson = response
    )
    response

  private def execute(
      xa: Transactor,
      name: String,
      email: Option[String],
      idempotencyKey: Option[String],
      dryRun: Boolean
  ): ClientData =
    idempotencyKey.flatMap(findByIdempotencyKey(xa, _)) match
      // The client already exists, so nothing is written and nothing is being previewed.
      case Some(existing) => toData(existing, dryRun = false)
      case None =>
        DryRun(xa, dryRun):
          val today = readSystemDate()
          val client = Client(Ids.next(), name, today, email, idempotencyKey)
          insert(client)
          toData(client, dryRun)

  private def findByIdempotencyKey(xa: Transactor, key: String): Option[Client] =
    transact(xa):
      sql"SELECT id, display_name, opened_on, email, idempotency_key FROM clients WHERE idempotency_key = $key"
        .query[Client]
        .run()
        .headOption

  /** Onboarding is dated by the ledger's own clock, never by the JVM clock. */
  private def readSystemDate()(using DbCon): LocalDate =
    sql"SELECT current_date_value FROM system_clock WHERE id = true"
      .query[SystemClockRow]
      .run()
      .head
      .currentDateValue

  private def insert(client: Client)(using DbCon): Unit =
    sql"""
      INSERT INTO clients (id, display_name, opened_on, email, idempotency_key)
      VALUES (${client.id}, ${client.displayName}, ${client.openedOn}, ${client.email}, ${client.idempotencyKey})
    """.update.run()

  private def toData(client: Client, dryRun: Boolean): ClientData =
    ClientData(
      id = client.id.toString,
      name = client.displayName,
      email = client.email,
      openedOn = client.openedOn.toString,
      dryRun = dryRun
    )
