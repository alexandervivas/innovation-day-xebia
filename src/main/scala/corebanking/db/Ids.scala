package corebanking.db

import java.util.UUID

import com.github.f4b6a3.uuid.UuidCreator

/** Mints the time-ordered ids every client, account, and transaction is keyed by. */
object Ids:
  def next(): UUID = UuidCreator.getTimeOrderedEpoch()
