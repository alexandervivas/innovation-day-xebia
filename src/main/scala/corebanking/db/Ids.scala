package corebanking.db

import java.util.UUID

import com.github.f4b6a3.uuid.UuidCreator

/**
 * Mints RFC 9562 UUIDv7 ids in application code. Postgres 16 has no native `uuidv7()`, and
 * `V1__schema.sql`'s header comment says entity ids are "intended to hold UUIDv7 values minted in
 * application code by a future write-tools story" -- this is that story.
 */
object Ids:
  def next(): UUID = UuidCreator.getTimeOrderedEpoch()
