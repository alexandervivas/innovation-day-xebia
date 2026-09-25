package corebanking.tools

import java.time.LocalDate
import java.util.UUID

import com.augustnagro.magnum.*
import zio.json.*

import corebanking.config.CoreEnv
import corebanking.db.given

/** One of a client's accounts, with its balance as of the mock system date. */
final case class AccountData(
    id: String,
    productId: String,
    kind: String,
    openedOn: LocalDate,
    balance: BigDecimal
)

object AccountData:
  given JsonEncoder[AccountData] = DeriveJsonEncoder.gen[AccountData]

final private case class AccountRow(
    id: UUID,
    productId: UUID,
    kind: String,
    openedOn: LocalDate,
    balance: BigDecimal
) derives DbCodec

object ListAccounts:

  /** Lists a client's accounts. Throws `NoSuchElementException` when no client has `clientId`. */
  def find(clientId: UUID)(using DbCon): List[AccountData] =
    val clientExists = sql"SELECT 1 FROM clients WHERE id = $clientId".query[Int].run()
    if clientExists.isEmpty then throw new NoSuchElementException(s"no client with id $clientId")
    sql"""
      SELECT a.id, a.product_id, a.kind, a.opened_on,
             COALESCE(SUM(t.amount) FILTER (WHERE t.value_date <= sc.current_date_value), 0.00) AS balance
      FROM accounts a
      CROSS JOIN system_clock sc
      LEFT JOIN transactions t ON t.account_id = a.id
      WHERE a.client_id = $clientId
      GROUP BY a.id, a.product_id, a.kind, a.opened_on
      ORDER BY a.opened_on
    """.query[AccountRow].run().toList.map { r =>
      AccountData(r.id.toString, r.productId.toString, r.kind, r.openedOn, r.balance)
    }

  def response(env: CoreEnv, clientId: UUID)(using DbCon): String =
    ToolResponse.respond(env, find(clientId))
