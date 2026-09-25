package corebanking.tools

import zio.json.*

import corebanking.config.CoreEnv

/**
 * Wraps a tool's payload with the current core environment, so every tool response satisfies
 * invariant 1 ("every tool response carries the current env") without each tool re-deriving it.
 */
final case class Envelope[A](env: String, data: A)

object Envelope:
  given [A: JsonEncoder]: JsonEncoder[Envelope[A]] = DeriveJsonEncoder.gen[Envelope[A]]

object ToolResponse:

  /** Serializes `a` alongside the environment's label as a single JSON object. */
  def respond[A: JsonEncoder](env: CoreEnv, a: A): String =
    Envelope(env.label, a).toJson
