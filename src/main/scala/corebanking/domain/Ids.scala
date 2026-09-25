package corebanking.domain

import java.util.UUID

/**
 * Entity ids carry no timing meaning — value_date, booking_date, and system_clock are the only
 * source of when something happened.
 */
opaque type ClientId = UUID
object ClientId:
  def apply(value: UUID): ClientId = value
  extension (id: ClientId) def value: UUID = id

opaque type ProductId = UUID
object ProductId:
  def apply(value: UUID): ProductId = value
  extension (id: ProductId) def value: UUID = id

opaque type AccountId = UUID
object AccountId:
  def apply(value: UUID): AccountId = value
  extension (id: AccountId) def value: UUID = id

opaque type TransactionId = UUID
object TransactionId:
  def apply(value: UUID): TransactionId = value
  extension (id: TransactionId) def value: UUID = id
