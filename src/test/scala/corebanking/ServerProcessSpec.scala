package corebanking

import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

import zio.*
import zio.json.*
import zio.test.*
import zio.test.TestAspect.*

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

  private def readAllLines(stream: java.io.InputStream): Task[Vector[String]] =
    ZIO.attemptBlocking {
      val reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
      val lines = scala.collection.mutable.ArrayBuffer.empty[String]
      var line: String = reader.readLine()
      while line != null do
        lines += line
        line = reader.readLine()
      lines.toVector
    }

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

  private val frames = Vector(initializeFrame, initializedNotification, pingCallFrame)

  /**
   * Runs the sandbox happy path: writes the handshake frames, holds stdin open ~3s, then closes it.
   */
  private def runHappyPath(): Task[HandshakeOutcome] =
    for
      proc <- spawn(Map("CORE_ENV" -> "sandbox"))
      stdoutFiber <- readAllLines(proc.getInputStream).fork
      stderrFiber <- readAllBytes(proc.getErrorStream).fork
      _ <- ZIO.attemptBlocking {
        val writer = new OutputStreamWriter(proc.getOutputStream, StandardCharsets.UTF_8)
        frames.foreach { frame =>
          writer.write(frame)
          writer.write("\n")
          writer.flush()
        }
      }
      // Real wall-clock sleep: ZIOSpecDefault's TestClock never advances on its own, and
      // Live.live(ZIO.sleep) would still add avoidable indirection here.
      _ <- ZIO.attemptBlocking(Thread.sleep(3000))
      _ <- ZIO.attemptBlocking(proc.getOutputStream.close())
      exitFiber <- awaitExit(proc).fork
      exitCode <- exitFiber.join
      stdoutLines <- stdoutFiber.join
      stderrBytes <- stderrFiber.join
    yield HandshakeOutcome(exitCode, stdoutLines, new String(stderrBytes, StandardCharsets.UTF_8))

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
      test("CORE_ENV=sandbox happy path: clean stdio wire and a correctly enveloped ping result") {
        for
          outcome <- runHappyPath()
          toolCallLine = outcome.stdoutLines.find(_.contains("\"id\":2"))
        yield assertTrue(
          outcome.stdoutLines.nonEmpty,
          outcome.stdoutLines.forall(_.startsWith("{")),
          toolCallLine.isDefined,
          toolCallLine.get.fromJson[ToolCallResponse].flatMap { response =>
            response.result.content.headOption
              .toRight("no content item in tools/call result")
              .flatMap(_.text.fromJson[PingEnvelope])
          } == Right(
            PingEnvelope(
              env = "sandbox",
              data = PingEnvelopeData(pong = true, server = "core-banking-mcp", version = "0.1.0")
            )
          )
        )
      }
    ) @@ sequential @@ timeout(2.minutes)
