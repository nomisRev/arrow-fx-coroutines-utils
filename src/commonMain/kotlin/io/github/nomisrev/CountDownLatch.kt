package io.github.nomisrev

import arrow.continuations.generic.AtomicRef
import arrow.continuations.generic.loop
import kotlinx.coroutines.CompletableDeferred

/** CountDownLatch allows for awaiting a given number of countdown signals. */
public interface CountDownLatch {
  public fun count(): Long
  public suspend fun await(): Unit
  public fun countDown(): Unit
}

public fun CountDownLatch(initial: Long): CountDownLatch = DefaultCountDownLatch(initial)

private class DefaultCountDownLatch(initial: Long) : CountDownLatch {
  private val signal = CompletableDeferred<Unit>()
  private val count = AtomicRef(initial)

  init {
    require(initial > 0) {
      "CountDownLatch must be constructed with positive non-zero initial count $initial but was $initial > 0"
    }
  }

  override fun count(): Long = count.get()

  override suspend fun await() = signal.await()

  @Suppress("ReturnCount")
  override fun countDown() {
    count.loop { current ->
      when {
        current == 0L -> return
        current == 1L && count.compareAndSet(1L, 0L) -> return signal.complete(Unit).let {}
        count.compareAndSet(current, current - 1) -> return
      }
    }
  }
}
