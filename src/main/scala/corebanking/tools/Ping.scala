package corebanking.tools

import zio.json.*

import corebanking.config.CoreEnv

/** Payload for the `ping` liveness-check tool. */
final case class PingData(pong: Boolean, server: String, version: String)

object PingData:
  given JsonEncoder[PingData] = DeriveJsonEncoder.gen[PingData]

/**
 * Pure liveness-check logic, free of ZIO and MCP transport concerns so it can be tested without
 * initializing `Server` (and, in particular, without triggering its `CORE_ENV` guard).
 */
object Ping:

  val ServerName = "core-banking-mcp"

  /** Builds the full `{"env":..., "data":{...}}` envelope for a `ping` call. */
  def response(env: CoreEnv, version: String): String =
    ToolResponse.respond(env, PingData(pong = true, server = ServerName, version = version))
