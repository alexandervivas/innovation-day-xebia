package corebanking.tools

import java.math.BigDecimal as JBigDecimal
import java.time.LocalDate
import java.util.UUID

import zio.json.*

import com.augustnagro.magnum.{DbCon, Transactor, sql, transact}

import corebanking.config.CoreEnv
import corebanking.db.{Account, Client, DryRun, Ids, ProductRef, SystemClockRow, Transaction}
import corebanking.db.given

/** The account as `open_account` reports it back, together with its opening transaction. */
final case class AccountData(
    id: String,
    clientId: String,
    productId: String,
    currency: String,
    openedOn: String,
    openingTransactionId: String,
    initialDeposit: String,
    dryRun: Boolean
)

object AccountData:
  given JsonEncoder[AccountData] = DeriveJsonEncoder.gen[AccountData]

/** A rejected account opening, reported as a code the caller can branch on. */
final case class OpenAccountError(error: String, message: String)

object OpenAccountError:
  given JsonEncoder[OpenAccountError] = DeriveJsonEncoder.gen[OpenAccountError]

final private case class OpenAccountRequest(
    clientId: String,
    productId: String,
    currency: String,
    initialDeposit: Option[String],
    idempotencyKey: Option[String],
    dryRun: Boolean
)

private object OpenAccountRequest:
  given JsonEncoder[OpenAccountRequest] = DeriveJsonEncoder.gen[OpenAccountRequest]

/** A rejected opening, always raised before anything is written to the ledger. */
final private case class OpenAccountFailure(code: String, message: String)
    extends RuntimeException(message)

/**
 * Opens an account for an existing client on an existing product and posts its `account_opening`
 * transaction. A retried call carrying the same `idempotency_key` returns the original account
 * instead of opening a second one.
 */
object OpenAccount:

  private val AllowedCurrencies = Set("COP", "USD", "EUR")

  def run(
      xa: Transactor,
      env: CoreEnv,
      clientId: String,
      productId: String,
      currency: String,
      initialDeposit: Option[String],
      idempotencyKey: Option[String],
      dryRun: Boolean
  ): String =
    val requestJson =
      OpenAccountRequest(
        clientId,
        productId,
        currency,
        initialDeposit,
        idempotencyKey,
        dryRun
      ).toJson
    val response =
      try
        val data =
          execute(xa, clientId, productId, currency, initialDeposit, idempotencyKey, dryRun)
        ToolResponse.respond(env, data)
      catch
        case OpenAccountFailure(code, message) =>
          ToolResponse.respond(env, OpenAccountError(code, message))
    // Every call is auditable, including a rejected one and one that only previews.
    AuditLog.record(
      xa,
      toolName = "open_account",
      env = env.label,
      requestJson,
      responseJson = response
    )
    response

  private def execute(
      xa: Transactor,
      clientId: String,
      productId: String,
      currency: String,
      initialDeposit: Option[String],
      idempotencyKey: Option[String],
      dryRun: Boolean
  ): AccountData =
    if !AllowedCurrencies.contains(currency) then
      throw OpenAccountFailure("INVALID_CURRENCY", s"'$currency' is not one of $AllowedCurrencies")

    val clientUuid = parseUuid(clientId, "client_id")
    val productUuid = parseUuid(productId, "product_id")
    val deposit = parseAmount(initialDeposit)

    // The opening transaction carries the key, so a repeat is recognised from the ledger itself.
    idempotencyKey.flatMap(findOpeningByIdempotencyKey(xa, _)) match
      // The account already exists, so nothing is written and nothing is being previewed.
      case Some((account, tx)) => toData(account, tx, dryRun = false)
      case None =>
        DryRun(xa, dryRun):
          val client =
            sql"SELECT id, display_name, opened_on, email, idempotency_key FROM clients WHERE id = $clientUuid"
              .query[Client]
              .run()
              .headOption
              .getOrElse(
                throw OpenAccountFailure("CLIENT_NOT_FOUND", s"no client with id $clientId")
              )

          val product = sql"SELECT id, kind FROM products WHERE id = $productUuid"
            .query[ProductRef]
            .run()
            .headOption
            .getOrElse(
              throw OpenAccountFailure("PRODUCT_NOT_FOUND", s"no product with id $productId")
            )

          val today = readSystemDate()
          val accountId = Ids.next()
          val txId = Ids.next()
          // The account inherits the product's kind: savings and loan accounts behave differently.
          val account = Account(accountId, client.id, product.id, product.kind, today, currency)
          val tx = Transaction(
            id = txId,
            accountId = accountId,
            `type` = "account_opening",
            amount = deposit,
            // An opening applies the day it is booked.
            bookingDate = today,
            valueDate = today,
            reversesId = None,
            idempotencyKey = idempotencyKey.getOrElse(txId.toString)
          )
          insertAccount(account)
          insertTransaction(tx)
          toData(account, tx, dryRun)

  private def parseUuid(raw: String, field: String): UUID =
    try UUID.fromString(raw)
    catch
      case _: IllegalArgumentException =>
        throw OpenAccountFailure("INVALID_ID", s"$field '$raw' is not a UUID")

  /** Money is decimal to the cent; an absent deposit opens the account at zero. */
  private def parseAmount(raw: Option[String]): BigDecimal =
    raw match
      case None => BigDecimal("0.00")
      case Some(text) =>
        val parsed =
          try new JBigDecimal(text)
          catch
            case _: NumberFormatException =>
              throw OpenAccountFailure(
                "INVALID_AMOUNT",
                s"initial_deposit '$text' is not a decimal amount"
              )
        if parsed.scale > 2 || parsed.signum < 0 then
          throw OpenAccountFailure(
            "INVALID_AMOUNT",
            s"initial_deposit '$text' must be a non-negative amount with at most 2 decimal places"
          )
        BigDecimal(parsed.setScale(2))

  private def findOpeningByIdempotencyKey(
      xa: Transactor,
      key: String
  ): Option[(Account, Transaction)] =
    transact(xa):
      sql"""
        SELECT id, account_id, type, amount, booking_date, value_date, reverses_id, idempotency_key
        FROM transactions WHERE idempotency_key = $key AND type = 'account_opening'
      """.query[Transaction].run().headOption.map { tx =>
        val account =
          sql"SELECT id, client_id, product_id, kind, opened_on, currency FROM accounts WHERE id = ${tx.accountId}"
            .query[Account]
            .run()
            .head
        (account, tx)
      }

  /** An opening is dated by the ledger's own clock, never by the JVM clock. */
  private def readSystemDate()(using DbCon): LocalDate =
    sql"SELECT current_date_value FROM system_clock WHERE id = true"
      .query[SystemClockRow]
      .run()
      .head
      .currentDateValue

  private def insertAccount(account: Account)(using DbCon): Unit =
    sql"""
      INSERT INTO accounts (id, client_id, product_id, kind, opened_on, currency)
      VALUES (${account.id}, ${account.clientId}, ${account.productId}, ${account.kind}, ${account.openedOn}, ${account.currency})
    """.update.run()

  private def insertTransaction(tx: Transaction)(using DbCon): Unit =
    sql"""
      INSERT INTO transactions (id, account_id, type, amount, booking_date, value_date, reverses_id, idempotency_key)
      VALUES (${tx.id}, ${tx.accountId}, ${tx.`type`}, ${tx.amount}, ${tx.bookingDate}, ${tx.valueDate}, ${tx.reversesId}, ${tx.idempotencyKey})
    """.update.run()

  private def toData(account: Account, tx: Transaction, dryRun: Boolean): AccountData =
    AccountData(
      id = account.id.toString,
      clientId = account.clientId.toString,
      productId = account.productId.toString,
      currency = account.currency,
      openedOn = account.openedOn.toString,
      openingTransactionId = tx.id.toString,
      initialDeposit = tx.amount.bigDecimal.toPlainString,
      dryRun = dryRun
    )
