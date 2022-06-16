package io.github.nomisrev

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

public interface Backpressure {

  /**
   * Applies rate limiting to a given suspend function based on backpressure semantics
   *
   * @param block the block the backpressure is applied to
   * @return [A] if the `block` ran, or `null` when it was skipped for backpressure reasons
   */
  public suspend fun <A> metered(block: suspend () -> A): A?

  public enum class Strategy {
    Lossless,
    Lossy
  }
}

public fun Backpressure(strategy: Backpressure.Strategy, bound: Int): Backpressure =
  object : Backpressure {
    val semaphore = Semaphore(bound)
    override suspend fun <A> metered(block: suspend () -> A): A? =
      when (strategy) {
        Backpressure.Strategy.Lossless -> semaphore.withPermit { block() }
        Backpressure.Strategy.Lossy -> {
          val acquired = semaphore.tryAcquire()
          try {
            if (acquired) block() else null
          } finally {
            if (acquired) semaphore.release()
          }
        }
      }
  }
