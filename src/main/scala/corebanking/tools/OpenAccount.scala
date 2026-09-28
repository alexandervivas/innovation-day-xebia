package corebanking.tools

import java.math.BigDecimal as JBigDecimal
import java.time.LocalDate
import java.util.UUID

import zio.json.*

import com.augustnagro.magnum.{DbCodec, DbCon, Transactor, sql, transact}

import corebanking.config.CoreEnv
import corebanking.db.{Account, Client, DryRun, Ids, ProductRef, SystemClockRow, Transaction}
import corebanking.db.given

/** The account as `open_account` reports it back, together with its opening transaction. */
final case class OpenedAccountData(
    id: String,
    clientId: String,
    productId: String,
    currency: String,
    openedOn: String,
    openingTransactionId: String,
    initialDeposit: String,
    dryRun: Boolean
)

object OpenedAccountData:
  given JsonEncoder[OpenedAccountData] = DeriveJsonEncoder.gen[OpenedAccountData]

/** A rejected account opening, reported as a code the caller can branch on. */
final case class OpenAccountError(error: String, message: String)

object OpenAccountError:
  given JsonEncoder[OpenAccountError] = DeriveJsonEncoder.gen[OpenAccountError]

final case class OpenAccountRequest(
    clientId: String,
    productId: String,
    currency: String,
    initialDeposit: Option[String],
    idempotencyKey: Option[String],
    dryRun: Boolean
)

object OpenAccountRequest:
  given JsonEncoder[OpenAccountRequest] = DeriveJsonEncoder.gen[OpenAccountRequest]

/** A rejected opening, always raised before anything is written to the ledger. */
final private case class OpenAccountFailure(code: String, message: String)
    extends RuntimeException(message)

/** How many ledger entries an idempotency key has already been spent on. */
final private case class KeyUsageCount(n: Long) derives DbCodec

/**
 * Opens an account for an existing client on an existing product and posts its `account_opening`
 * transaction. A retried call carrying the same `idempotency_key` returns the original account
 * instead of opening a second one.
 */
object OpenAccount:

  private val AllowedCurrencies = Set("COP", "USD", "EUR")

  /** The integer width the ledger's NUMERIC(18,2) amount columns allow. */
  private val MaxIntegerDigits = 16

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
    // The audited dry-run flag follows the response, so an idempotency hit is audited as no preview.
    val (response, auditedDryRun) =
      try
        val data =
          execute(xa, clientId, productId, currency, initialDeposit, idempotencyKey, dryRun)
        (ToolResponse.respond(env, data), data.dryRun)
      catch
        case OpenAccountFailure(code, message) =>
          (ToolResponse.respond(env, OpenAccountError(code, message)), dryRun)
    // Every call is auditable, including a rejected one and one that only previews.
    AuditLog.record(
      xa,
      toolName = "open_account",
      env = env,
      requestJson,
      responseJson = response,
      dryRun = auditedDryRun
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
  ): OpenedAccountData =
    if !AllowedCurrencies.contains(currency) then
      throw OpenAccountFailure("INVALID_CURRENCY", s"'$currency' is not one of $AllowedCurrencies")

    val clientUuid = parseUuid(clientId, "client_id")
    val productUuid = parseUuid(productId, "product_id")
    val deposit = parseAmount(initialDeposit)

    // The opening transaction carries the key, so a repeat is recognised from the ledger itself.
    idempotencyKey.flatMap(findOpeningByIdempotencyKey(xa, _, clientUuid, productUuid)) match
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
        // Amounts are NUMERIC(18,2): no more than 16 digits before the decimal point.
        if parsed.precision - parsed.scale > MaxIntegerDigits then
          throw OpenAccountFailure(
            "INVALID_AMOUNT",
            s"initial_deposit '$text' exceeds $MaxIntegerDigits digits before the decimal point"
          )
        BigDecimal(parsed.setScale(2))

  /**
   * A key only replays the opening it was first spent on: the lookup is scoped to this request's
   * client and product, and a key already spent elsewhere in the ledger is a conflict rather than
   * another client's account.
   */
  private def findOpeningByIdempotencyKey(
      xa: Transactor,
      key: String,
      clientUuid: UUID,
      productUuid: UUID
  ): Option[(Account, Transaction)] =
    transact(xa):
      val opening = sql"""
        SELECT t.id, t.account_id, t.type, t.amount, t.booking_date, t.value_date, t.reverses_id, t.idempotency_key
        FROM transactions t
        JOIN accounts a ON a.id = t.account_id
        WHERE t.idempotency_key = $key
          AND t.type = 'account_opening'
          AND a.client_id = $clientUuid
          AND a.product_id = $productUuid
      """.query[Transaction].run().headOption
      opening match
        case Some(tx) =>
          val account =
            sql"SELECT id, client_id, product_id, kind, opened_on, currency FROM accounts WHERE id = ${tx.accountId}"
              .query[Account]
              .run()
              .head
          Some((account, tx))
        case None =>
          val spentElsewhere =
            sql"SELECT COUNT(*) AS n FROM transactions WHERE idempotency_key = $key"
              .query[KeyUsageCount]
              .run()
              .head
              .n > 0
          if spentElsewhere then
            throw OpenAccountFailure(
              "IDEMPOTENCY_KEY_CONFLICT",
              s"idempotency_key '$key' was already used for a different request"
            )
          else None

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

  private def toData(account: Account, tx: Transaction, dryRun: Boolean): OpenedAccountData =
    OpenedAccountData(
      id = account.id.toString,
      clientId = account.clientId.toString,
      productId = account.productId.toString,
      currency = account.currency,
      openedOn = account.openedOn.toString,
      openingTransactionId = tx.id.toString,
      initialDeposit = tx.amount.bigDecimal.toPlainString,
      dryRun = dryRun
    )
