package corebanking

import com.tjclp.fastmcp.{*, given}

import corebanking.config.{CoreEnv, DbConfig}
import corebanking.db.{Db, FlywayRunner}
import corebanking.tools.{GetSystemDate, Ping}

/**
 * Entry point for the core-banking-mcp server.
 *
 * CB-02 env guard: `CORE_ENV` is resolved once, before any MCP transport starts. Both `run` and
 * `main` are declared `final` up the `McpServerApp` / `ZIOApp` / `ZIOAppPlatformSpecific` chain
 * (fast-mcp-scala 1.0.1's `McpServerApp.run`, and zio 2.1.26's `ZIOAppPlatformSpecific.main`), so
 * neither can be overridden to intercept before the stdio loop starts. Instead, the guard runs as a
 * plain, eager `val` at the top of this object body: the JVM's generated static `main` forwarder
 * must initialize the `Server` singleton (running every val initializer in the object body, in
 * source order) before it can invoke the inherited `main` method on it, so this val is guaranteed
 * to run first.
 *
 * `McpServerApp` does expose an overridable pre-run hook: `override val bootstrap: ZLayer[...]`
 * (documented on the trait as the way to install a custom logger). It is deliberately not used
 * here, because the guard must print exactly one plain `FATAL: <message>` line to stderr and
 * terminate before any ZIO runtime exists — going through `bootstrap` would run the failure inside
 * a ZIO effect, whose error path is rendered through ZIO's default (or a caller-installed) logger
 * rather than a guaranteed raw stderr line, and still requires a runtime to already be constructed
 * to observe the failure.
 *
 * On an invalid `CORE_ENV`, the guard prints `FATAL: <message>` to stderr, flushes it, and calls
 * `Runtime.getRuntime.halt(1)` — not `sys.exit`. `System.exit` runs registered JVM shutdown hooks
 * while still holding the class-initialization lock for `Server$` (the lock the current thread
 * acquired to run this very initializer); if any shutdown hook needs to join a fiber or otherwise
 * touch `Server$` from another thread, the two threads deadlock and the JVM never exits. This is
 * exactly what happens when the guard fires off the main thread (e.g. inside a test JVM that has
 * already installed ZIO's runtime shutdown hook). `Runtime.halt` skips shutdown hooks entirely and
 * terminates immediately, so it cannot deadlock regardless of what else has been initialized.
 *
 * Logging goes to stderr only (the transport's default stdio bootstrap), since stdout carries the
 * MCP JSON-RPC wire.
 */
object Server extends McpServerApp[Stdio, Server.type]:

  /** Resolved once, before anything else in this object. Every tool response reads this. */
  private val coreEnv: CoreEnv = CoreEnv.parse(sys.env.get(CoreEnv.VarName)) match
    case Right(env) => env
    case Left(message) =>
      System.err.println(s"FATAL: $message")
      System.err.flush()
      java.lang.Runtime.getRuntime.halt(1)
      // Unreachable: halt terminates the JVM immediately. Satisfies the type checker only.
      throw new IllegalStateException("unreachable: halt(1) did not terminate the JVM")

  /**
   * Mock Postgres connection settings, then every pending migration applied before serving tools.
   */
  private val dbConfig: DbConfig = DbConfig.fromEnv()
  FlywayRunner.migrate(dbConfig)
  private val transactor = Db.transactor(dbConfig)

  override def name: String = "core-banking-mcp"
  override def version: String = "0.1.0"

  @Tool(
    name = Some("ping"),
    description = Some("Liveness check; returns the current core environment"),
    readOnlyHint = Some(true)
  )
  def ping(): String =
    Ping.response(coreEnv, version)

  @Tool(
    name = Some("get_system_date"),
    description = Some("Returns the ledger's current date from system_clock, not the JVM clock"),
    readOnlyHint = Some(true)
  )
  def getSystemDate(): String =
    GetSystemDate.run(transactor, coreEnv)
