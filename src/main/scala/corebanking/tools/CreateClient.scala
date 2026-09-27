package corebanking.tools

import java.time.LocalDate

import zio.json.*

import com.augustnagro.magnum.{DbCon, Transactor, sql, transact}

import corebanking.config.CoreEnv
import corebanking.db.{Client, DryRun, Ids, SystemClockRow}
import corebanking.db.given

/** The client record as `create_client` reports it back to the caller. */
final case class CreatedClientData(
    id: String,
    name: String,
    email: Option[String],
    openedOn: String,
    dryRun: Boolean
)

object CreatedClientData:
  given JsonEncoder[CreatedClientData] = DeriveJsonEncoder.gen[CreatedClientData]

/** A rejected onboarding, reported as a code the caller can branch on. */
final case class CreateClientError(error: String, message: String)

object CreateClientError:
  given JsonEncoder[CreateClientError] = DeriveJsonEncoder.gen[CreateClientError]

final case class CreateClientRequest(
    name: String,
    email: Option[String],
    idempotencyKey: Option[String],
    dryRun: Boolean
)

object CreateClientRequest:
  given JsonEncoder[CreateClientRequest] = DeriveJsonEncoder.gen[CreateClientRequest]

/** A rejected onboarding, always raised before anything is written. */
final private case class CreateClientFailure(code: String, message: String)
    extends RuntimeException(message)

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
    val requestJson = CreateClientRequest(name, email, idempotencyKey, dryRun).toJson
    // The audited dry-run flag follows the response, so an idempotency hit is audited as no preview.
    val (response, auditedDryRun) =
      try
        val data = execute(xa, name, email, idempotencyKey, dryRun)
        (ToolResponse.respond(env, data), data.dryRun)
      catch
        case CreateClientFailure(code, message) =>
          (ToolResponse.respond(env, CreateClientError(code, message)), dryRun)
    // Every call is auditable, including a rejected one and one that only previews.
    AuditLog.record(
      xa,
      toolName = "create_client",
      env = env,
      requestJson = requestJson,
      responseJson = response,
      dryRun = auditedDryRun
    )
    response

  private def execute(
      xa: Transactor,
      name: String,
      email: Option[String],
      idempotencyKey: Option[String],
      dryRun: Boolean
  ): CreatedClientData =
    idempotencyKey.flatMap(findByIdempotencyKey(xa, _, name, email)) match
      // The client already exists, so nothing is written and nothing is being previewed.
      case Some(existing) => toData(existing, dryRun = false)
      case None =>
        DryRun(xa, dryRun):
          val today = readSystemDate()
          val client = Client(Ids.next(), name, today, email, idempotencyKey)
          insert(client)
          toData(client, dryRun)

  /** A key only replays the request it was first spent on; another request's key is a conflict. */
  private def findByIdempotencyKey(
      xa: Transactor,
      key: String,
      name: String,
      email: Option[String]
  ): Option[Client] =
    transact(xa):
      sql"SELECT id, display_name, opened_on, email, idempotency_key FROM clients WHERE idempotency_key = $key"
        .query[Client]
        .run()
        .headOption match
        case Some(client) if client.displayName == name && client.email == email => Some(client)
        case Some(_) =>
          throw CreateClientFailure(
            "IDEMPOTENCY_KEY_CONFLICT",
            s"idempotency_key '$key' was already used for a different client"
          )
        case None => None

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

  private def toData(client: Client, dryRun: Boolean): CreatedClientData =
    CreatedClientData(
      id = client.id.toString,
      name = client.displayName,
      email = client.email,
      openedOn = client.openedOn.toString,
      dryRun = dryRun
    )
