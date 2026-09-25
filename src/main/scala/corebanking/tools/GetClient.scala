package corebanking.tools

import java.time.LocalDate
import java.util.UUID

import com.augustnagro.magnum.*
import zio.json.*

import corebanking.config.CoreEnv
import corebanking.db.given

/** A client's identity and the date they were onboarded. */
final case class ClientData(id: String, displayName: String, openedOn: LocalDate)

object ClientData:
  given JsonEncoder[ClientData] = DeriveJsonEncoder.gen[ClientData]

final private case class ClientRow(displayName: String, openedOn: LocalDate) derives DbCodec

object GetClient:

  /** Looks up one client by id. Throws `NoSuchElementException` when no client has that id. */
  def find(clientId: UUID)(using DbCon): ClientData =
    val rows = sql"SELECT display_name, opened_on FROM clients WHERE id = $clientId"
      .query[ClientRow]
      .run()
    rows.headOption match
      case Some(row) => ClientData(clientId.toString, row.displayName, row.openedOn)
      case None => throw new NoSuchElementException(s"no client with id $clientId")

  def response(env: CoreEnv, clientId: UUID)(using DbCon): String =
    ToolResponse.respond(env, find(clientId))
