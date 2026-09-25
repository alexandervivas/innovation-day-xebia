package corebanking.config

/**
 * The core-banking environment the server is running against.
 *
 * Pure and DB/ZIO-free: only `mock` and `sandbox` are allowed. Anything else (including a missing
 * value, or a real `production`) must fail loudly at startup rather than silently defaulting, so a
 * test tool can never be pointed at a real banking core by accident.
 */
enum CoreEnv:
  case Mock, Sandbox

  def label: String = this match
    case CoreEnv.Mock => "mock"
    case CoreEnv.Sandbox => "sandbox"

object CoreEnv:

  val VarName = "CORE_ENV"

  private val allowedLabel = "mock, sandbox"

  private val maxEchoedValueLength = 32

  /**
   * Truncates an untrusted value to at most `maxEchoedValueLength` characters for safe echoing in
   * error messages, appending `…` when truncation occurred.
   */
  private def truncateForEcho(value: String): String =
    if value.length <= maxEchoedValueLength then value
    else value.take(maxEchoedValueLength) + "…"

  /**
   * Parses the raw `CORE_ENV` value. Never echoes anything from the environment other than the
   * offending value itself, and only in the error message, truncated to a bounded length.
   */
  def parse(raw: Option[String]): Either[String, CoreEnv] =
    raw.map(_.trim) match
      case None | Some("") =>
        Left(s"$VarName is not set; refusing to start. Allowed values: $allowedLabel")
      case Some(value) =>
        value.toLowerCase match
          case "mock" => Right(CoreEnv.Mock)
          case "sandbox" => Right(CoreEnv.Sandbox)
          case _ =>
            val echoed = truncateForEcho(value)
            Left(
              s"""$VarName="$echoed" is not allowed; refusing to start. Allowed values: $allowedLabel"""
            )
