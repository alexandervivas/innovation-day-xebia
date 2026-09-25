package corebanking

import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.*
import zio.test.*
import zio.test.TestAspect.*

import corebanking.config.DbConfig
import corebanking.db.FlywayRunner

/**
 * CB-02 headline AC, verified at the process level rather than in-process, because the guard's
 * observable contract (process exit code, a raw stderr line, and a byte-clean stdout) and the MCP
 * stdio handshake can only be seen by actually spawning `corebanking.Server` as its own JVM
 * process.
 *
 * This is also the regression test for the `Runtime.getRuntime.halt(1)` vs. `sys.exit(1)` fix
 * documented on `Server`: a `sys.exit(1)` fired off the main thread while holding `Server$`'s
 * class-initialization lock deadlocks against ZIO's runtime shutdown hook, and the process never
 * exits. Every case below enforces a 30s wall-clock budget and destroys the process (failing the
 * test) if it hangs, so that regression cannot pass silently.
 */
object ServerProcessSpec extends ZIOSpecDefault:

  private val javaBin = java.lang.System.getProperty("java.home") + "/bin/java"
  private val classpath = java.lang.System.getProperty("java.class.path")
  private val mainClass = "corebanking.Server"

  private val processTimeout = 30.seconds

  final private case class ProcessOutcome(exitCode: Int, stdoutBytes: Array[Byte], stderr: String)
  final private case class HandshakeOutcome(
      exitCode: Int,
      stdoutLines: Vector[String],
      stderr: String
  )

  private def spawn(env: Map[String, String]): Task[Process] =
    ZIO.attemptBlocking {
      val pb = new ProcessBuilder(javaBin, "-cp", classpath, mainClass)
      val processEnv = pb.environment()
      processEnv.clear()
      processEnv.putAll(env.asJava)
      pb.start()
    }

  private def readAllBytes(stream: java.io.InputStream): Task[Array[Byte]] =
    ZIO.attemptBlocking(stream.readAllBytes())

  /** Waits up to `processTimeout`; on timeout, forcibly kills the process and fails with "hung". */
  private def awaitExit(proc: Process): Task[Int] =
    ZIO.attemptBlocking {
      val finished = proc.waitFor(processTimeout.toSeconds, TimeUnit.SECONDS)
      if !finished then
        proc.destroyForcibly()
        throw new RuntimeException(
          s"hung: corebanking.Server did not exit within $processTimeout; destroyForcibly invoked"
        )
      proc.exitValue()
    }

  /** Runs a failure case: stdin is closed immediately, stdout/stderr are drained fully. */
  private def runFailureCase(env: Map[String, String]): Task[ProcessOutcome] =
    for
      proc <- spawn(env)
      _ <- ZIO.attemptBlocking(proc.getOutputStream.close())
      stdoutFiber <- readAllBytes(proc.getInputStream).fork
      stderrFiber <- readAllBytes(proc.getErrorStream).fork
      exitFiber <- awaitExit(proc).fork
      exitCode <- exitFiber.join
      stdoutBytes <- stdoutFiber.join
      stderrBytes <- stderrFiber.join
    yield ProcessOutcome(exitCode, stdoutBytes, new String(stderrBytes, StandardCharsets.UTF_8))

  private val initializeFrame =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"spec","version":"0"}}}"""
  private val initializedNotification =
    """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
  private val pingCallFrame =
    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ping","arguments":{}}}"""
  private def getAuditLogCallFrame(startTime: String): String =
    s"""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_audit_log","arguments":{"startTime":"$startTime"}}}"""
  private val getSystemDateCallFrame =
    """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_system_date","arguments":{}}}"""

  private val dbEnvKeys = Set("DATABASE_URL", "POSTGRES_USER", "POSTGRES_PASSWORD")

  private val getClientMissingCallFrame =
    """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"get_client","arguments":{"clientId":"018f3f00-0000-7000-8000-0000000000ff"}}}"""

  // Reserved for the seeded happy-path client fixture below.
  private val SeededClientId = "018f3f00-0000-7000-8000-0000000000e1"
  private val SeededClientName = "Server Process Spec Client"
  private val SeededClientOpenedOn = "2026-01-01"

  private val getClientSeededCallFrame =
    s"""{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"get_client","arguments":{"clientId":"$SeededClientId"}}}"""

  // Reserved for the seeded happy-path loan schedule fixture below.
  private val SeededLoanAccountId = "018f3f00-0000-7000-8000-0000000000e2"
  private val SeededLoanProductId = "018f3f00-0000-7000-8000-0000000000e3"

  private val getLoanScheduleSeededCallFrame =
    s"""{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"get_loan_schedule","arguments":{"loanId":"$SeededLoanAccountId"}}}"""

  /** A client row exists for the happy-path calls. */
  private def seedClient(): Task[Unit] =
    ZIO.attemptBlocking {
      val config = DbConfig.fromEnv()
      FlywayRunner.migrate(config)
      val conn = DriverManager.getConnection(config.url, config.user, config.password)
      try
        val stmt = conn.createStatement()
        stmt.execute(
          "INSERT INTO clients (id, display_name, opened_on) " +
            s"VALUES ('$SeededClientId', '$SeededClientName', DATE '$SeededClientOpenedOn') " +
            "ON CONFLICT (id) DO NOTHING"
        )
      finally conn.close()
    }

  /** A loan account with no installments exists for the seeded schedule call. */
  private def seedLoanAccount(): Task[Unit] =
    ZIO.attemptBlocking {
      val config = DbConfig.fromEnv()
      val conn = DriverManager.getConnection(config.url, config.user, config.password)
      try
        val stmt = conn.createStatement()
        stmt.execute(
          "INSERT INTO products (id, name, kind, annual_rate, term_months) " +
            s"VALUES ('$SeededLoanProductId', 'Server Process Spec Loan', 'loan', 0.08, 12) " +
            "ON CONFLICT (id) DO NOTHING"
        )
        stmt.execute(
          "INSERT INTO accounts (id, client_id, product_id, kind, opened_on) " +
            s"VALUES ('$SeededLoanAccountId', '$SeededClientId', '$SeededLoanProductId', 'loan', DATE '$SeededClientOpenedOn') " +
            "ON CONFLICT (id) DO NOTHING"
        )
        stmt.execute(
          "INSERT INTO loans (account_id, principal, annual_rate, term_months, disbursement_date, installment_amount, grace_days, late_fee) " +
            s"VALUES ('$SeededLoanAccountId', 5000.00, 0.08, 12, DATE '$SeededClientOpenedOn', 434.94, 7, 15.00) " +
            "ON CONFLICT (account_id) DO NOTHING"
        )
      finally conn.close()
    }

  private val getTransactionsNoDatesCallFrame =
    """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"get_transactions","arguments":{"accountId":"018f3f00-0000-7000-8000-0000000000ff"}}}"""
  private val getTransactionsWithDatesCallFrame =
    """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"get_transactions","arguments":{"accountId":"018f3f00-0000-7000-8000-0000000000ff","startDate":"2026-01-01","endDate":"2026-12-31"}}}"""

  /**
   * Runs the sandbox happy path: calls `ping`, `get_audit_log`, and `get_system_date`, and checks
   * the audit trail recorded the ping.
   */
  private def runHappyPath(): Task[HandshakeOutcome] =
    for
      _ <- seedClient()
      _ <- seedLoanAccount()
      proc <- spawn(sys.env.filter((k, _) => dbEnvKeys(k)) ++ Map("CORE_ENV" -> "sandbox"))
      stderrFiber <- readAllBytes(proc.getErrorStream).fork
      result <- handshake(proc)
        .timeoutFail(
          new RuntimeException(s"hung: handshake did not complete within $processTimeout")
        )(processTimeout)
        .onError(_ => ZIO.attemptBlocking(proc.destroyForcibly()).ignore)
      exitFiber <- awaitExit(proc).fork
      exitCode <- exitFiber.join
      stderrBytes <- stderrFiber.join
    yield HandshakeOutcome(exitCode, result, new String(stderrBytes, StandardCharsets.UTF_8))

  /** The blocking read/write for one happy-path run. */
  private def handshake(proc: Process): Task[Vector[String]] =
    ZIO.attemptBlocking {
      val reader =
        new BufferedReader(new InputStreamReader(proc.getInputStream, StandardCharsets.UTF_8))
      val writer = new OutputStreamWriter(proc.getOutputStream, StandardCharsets.UTF_8)
      val lines = scala.collection.mutable.ArrayBuffer.empty[String]

      def writeFrame(frame: String): Unit =
        writer.write(frame)
        writer.write("\n")
        writer.flush()

      def readUntil(marker: String): Unit =
        var found = false
        while !found do
          reader.readLine() match
            case null =>
              found = true
            case line =>
              lines += line
              if line.contains(marker) then found = true

      val windowStart = java.time.OffsetDateTime.now().toString
      writeFrame(initializeFrame)
      writeFrame(initializedNotification)
      writeFrame(pingCallFrame)
      readUntil("\"id\":2")

      writeFrame(getAuditLogCallFrame(windowStart))
      readUntil("\"id\":3")

      writeFrame(getSystemDateCallFrame)
      readUntil("\"id\":4")

      writeFrame(getClientMissingCallFrame)
      readUntil("\"id\":5")

      writeFrame(getTransactionsNoDatesCallFrame)
      readUntil("\"id\":6")

      writeFrame(getTransactionsWithDatesCallFrame)
      readUntil("\"id\":7")

      writeFrame(getClientSeededCallFrame)
      readUntil("\"id\":8")

      writeFrame(getLoanScheduleSeededCallFrame)
      readUntil("\"id\":9")

      proc.getOutputStream.close()

      // Drains any remaining output to EOF so "every stdout line is JSON" covers the whole
      // stream, not just up to the last frame this test sends.
      var trailing = reader.readLine()
      while trailing != null do
        lines += trailing
        trailing = reader.readLine()

      lines.toVector
    }

  // Minimal JSON-RPC response shapes, matching only the fields this spec needs to decode.
  final private case class ContentItem(`type`: String, text: String)
  final private case class ToolCallResult(content: List[ContentItem], isError: Boolean)
  final private case class ToolCallResponse(jsonrpc: String, id: Int, result: ToolCallResult)

  private object ToolCallResponse:
    given JsonDecoder[ContentItem] = DeriveJsonDecoder.gen[ContentItem]
    given JsonDecoder[ToolCallResult] = DeriveJsonDecoder.gen[ToolCallResult]
    given JsonDecoder[ToolCallResponse] = DeriveJsonDecoder.gen[ToolCallResponse]

  final private case class PingEnvelope(env: String, data: PingEnvelopeData)
  final private case class PingEnvelopeData(pong: Boolean, server: String, version: String)

  private object PingEnvelope:
    given JsonDecoder[PingEnvelopeData] = DeriveJsonDecoder.gen[PingEnvelopeData]
    given JsonDecoder[PingEnvelope] = DeriveJsonDecoder.gen[PingEnvelope]

  final private case class AuditEntryDecoded(
      id: Long,
      toolName: String,
      calledAt: String,
      env: String,
      request: Option[String],
      response: Option[String],
      dryRunFlag: Boolean
  )
  final private case class AuditEnvelope(env: String, data: List[AuditEntryDecoded])

  private object AuditEnvelope:
    given JsonDecoder[AuditEntryDecoded] = DeriveJsonDecoder.gen[AuditEntryDecoded]
    given JsonDecoder[AuditEnvelope] = DeriveJsonDecoder.gen[AuditEnvelope]

  final private case class GetSystemDateEnvelope(env: String, data: GetSystemDateData)
  final private case class GetSystemDateData(currentDate: String)

  private object GetSystemDateEnvelope:
    given JsonDecoder[GetSystemDateData] = DeriveJsonDecoder.gen[GetSystemDateData]
    given JsonDecoder[GetSystemDateEnvelope] = DeriveJsonDecoder.gen[GetSystemDateEnvelope]

  final private case class ClientEnvelope(env: String, data: ClientEnvelopeData)
  final private case class ClientEnvelopeData(id: String, displayName: String, openedOn: String)

  private object ClientEnvelope:
    given JsonDecoder[ClientEnvelopeData] = DeriveJsonDecoder.gen[ClientEnvelopeData]
    given JsonDecoder[ClientEnvelope] = DeriveJsonDecoder.gen[ClientEnvelope]

  final private case class LoanScheduleEnvelope(env: String, data: List[LoanScheduleEnvelopeData])
  final private case class LoanScheduleEnvelopeData(
      loanId: String,
      seq: Int,
      dueDate: String,
      amountDue: BigDecimal,
      interest: BigDecimal,
      principal: BigDecimal
  )

  private object LoanScheduleEnvelope:
    given JsonDecoder[LoanScheduleEnvelopeData] = DeriveJsonDecoder.gen[LoanScheduleEnvelopeData]
    given JsonDecoder[LoanScheduleEnvelope] = DeriveJsonDecoder.gen[LoanScheduleEnvelope]

  private def contentText(line: String): Either[String, String] =
    line
      .fromJson[ToolCallResponse]
      .flatMap(_.result.content.headOption.toRight("no content item in tools/call result"))
      .map(_.text)

  def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Server process (CB-02 env guard, process-level)")(
      test("CORE_ENV=production exits 1, logs the FATAL message to stderr, and writes no stdout") {
        for outcome <- runFailureCase(Map("CORE_ENV" -> "production"))
        yield assertTrue(
          outcome.exitCode == 1,
          outcome.stderr.contains("""FATAL: CORE_ENV="production" is not allowed"""),
          outcome.stdoutBytes.length == 0
        )
      },
      test("CORE_ENV absent exits 1, logs the FATAL message to stderr, and writes no stdout") {
        for outcome <- runFailureCase(Map.empty)
        yield assertTrue(
          outcome.exitCode == 1,
          outcome.stderr.contains("FATAL: CORE_ENV is not set"),
          outcome.stdoutBytes.length == 0
        )
      },
      test(
        "CORE_ENV=sandbox happy path: clean stdio wire, ping/get_audit_log/get_system_date results, and proof the ping call was audited"
      ) {
        for
          outcome <- runHappyPath()
          pingLine = outcome.stdoutLines.find(_.contains("\"id\":2"))
          auditLine = outcome.stdoutLines.find(_.contains("\"id\":3"))
          getSystemDateLine = outcome.stdoutLines.find(_.contains("\"id\":4"))
        yield assertTrue(
          outcome.stdoutLines.nonEmpty,
          outcome.stdoutLines.forall(_.startsWith("{")),
          pingLine.isDefined,
          pingLine.get.fromJson[ToolCallResponse].flatMap { response =>
            response.result.content.headOption
              .toRight("no content item in tools/call result")
              .flatMap(_.text.fromJson[PingEnvelope])
          } == Right(
            PingEnvelope(
              env = "sandbox",
              data = PingEnvelopeData(pong = true, server = "core-banking-mcp", version = "0.1.0")
            )
          ),
          auditLine.isDefined,
          auditLine.get
            .fromJson[ToolCallResponse]
            .flatMap { response =>
              response.result.content.headOption
                .toRight("no content item in tools/call result")
                .flatMap(_.text.fromJson[AuditEnvelope])
            }
            .map(_.data.exists(_.toolName == "ping")) == Right(true),
          getSystemDateLine.isDefined,
          getSystemDateLine.get
            .fromJson[ToolCallResponse]
            .flatMap { response =>
              response.result.content.headOption
                .toRight("no content item in tools/call result")
                .flatMap(_.text.fromJson[GetSystemDateEnvelope])
            }
            .exists(env =>
              env.env == "sandbox" && env.data.currentDate.matches("""\d{4}-\d{2}-\d{2}""")
            )
        )
      },
      test("CORE_ENV=sandbox: get_client on an unknown id comes back as a tool-call error") {
        for
          outcome <- runHappyPath()
          toolCallLine = outcome.stdoutLines.find(_.contains("\"id\":5"))
        yield assertTrue(
          toolCallLine.isDefined,
          toolCallLine.get.fromJson[ToolCallResponse].map(_.result.isError) == Right(true),
          contentText(toolCallLine.get).map(_.contains("no client with id")) == Right(true)
        )
      },
      test("CORE_ENV=sandbox: get_transactions decodes both with and without optional date args") {
        for
          outcome <- runHappyPath()
          noDatesLine = outcome.stdoutLines.find(_.contains("\"id\":6"))
          withDatesLine = outcome.stdoutLines.find(_.contains("\"id\":7"))
        yield assertTrue(
          noDatesLine.isDefined,
          withDatesLine.isDefined,
          // Both calls reach the same "unknown account" error — the request is understood either way.
          noDatesLine.get.fromJson[ToolCallResponse].map(_.result.isError) == Right(true),
          withDatesLine.get.fromJson[ToolCallResponse].map(_.result.isError) == Right(true),
          contentText(noDatesLine.get).map(_.contains("no account with id")) == Right(true),
          contentText(withDatesLine.get).map(_.contains("no account with id")) == Right(true)
        )
      },
      test("CORE_ENV=sandbox: get_client on a seeded id returns a real, non-error envelope") {
        for
          outcome <- runHappyPath()
          toolCallLine = outcome.stdoutLines.find(_.contains("\"id\":8"))
        yield assertTrue(
          toolCallLine.isDefined,
          toolCallLine.get.fromJson[ToolCallResponse].map(_.result.isError) == Right(false),
          contentText(toolCallLine.get).flatMap(_.fromJson[ClientEnvelope]) == Right(
            ClientEnvelope(
              env = "sandbox",
              data = ClientEnvelopeData(
                id = SeededClientId,
                displayName = SeededClientName,
                openedOn = SeededClientOpenedOn
              )
            )
          )
        )
      },
      test(
        "CORE_ENV=sandbox: get_loan_schedule on a seeded loan account returns a real, non-error envelope"
      ) {
        for
          outcome <- runHappyPath()
          toolCallLine = outcome.stdoutLines.find(_.contains("\"id\":9"))
        yield assertTrue(
          toolCallLine.isDefined,
          toolCallLine.get.fromJson[ToolCallResponse].map(_.result.isError) == Right(false),
          contentText(toolCallLine.get).flatMap(_.fromJson[LoanScheduleEnvelope]) == Right(
            LoanScheduleEnvelope(env = "sandbox", data = Nil)
          )
        )
      },
      test("CORE_ENV=sandbox: get_transactions decodes both with and without optional date args") {
        for
          outcome <- runHappyPath()
          noDatesLine = outcome.stdoutLines.find(_.contains("\"id\":5"))
          withDatesLine = outcome.stdoutLines.find(_.contains("\"id\":6"))
        yield assertTrue(
          noDatesLine.isDefined,
          withDatesLine.isDefined,
          // Both calls reach GetTransactions.find and fail there with "no account", not with a
          // framework-level argument-decoding error — proving Option[LocalDate] round-trips.
          noDatesLine.get.fromJson[ToolCallResponse].map(_.result.isError) == Right(true),
          withDatesLine.get.fromJson[ToolCallResponse].map(_.result.isError) == Right(true)
        )
      }
    ) @@ sequential @@ timeout(2.minutes)
