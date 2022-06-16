package io.github.nomisrev

import arrow.continuations.generic.AtomicRef
import arrow.continuations.generic.loop
import arrow.fx.coroutines.onCancel
import kotlinx.coroutines.CompletableDeferred

public interface CyclicBarrier {
  /** Possibly semantically block until the cyclic barrier is full */
  public suspend fun await(): Unit
}

public fun CyclicBarrier(capacity: Int): CyclicBarrier = DefaultCyclicBarrier(capacity)

internal class DefaultCyclicBarrier(val capacity: Int) : CyclicBarrier {
  init {
    require(capacity > 0) {
      "Cyclic barrier must be constructed with positive non-zero capacity $capacity but was $capacity > 0"
    }
  }

  data class State(val awaiting: Int, val epoch: Long, val unblock: CompletableDeferred<Unit>)

  val state = AtomicRef(State(capacity, 0, CompletableDeferred()))

  // Everything is uncancellable in this function, exception for unblock::await on line 43
  override suspend fun await() = uncancellable {
    val gate = CompletableDeferred<Unit>()
    state
      .modify { original ->
        val (awaiting, epoch, unblock) = original
        val awaitingNow = awaiting - 1
        // No more waiters, complete the previous `gate`
        if (awaitingNow == 0) Pair(State(capacity, epoch + 1, gate), unblock.complete())
        else {
          // Sets newState, and makes this function suspend until `unblock` is completed.
          val newState = State(awaitingNow, epoch, unblock)
          Pair(
            newState,
            suspend {
              // Increment awaiting count if await gets canceled,
              // but only if the barrier hasn't reset in the meantime (s.epoch == epoch).
              onCancel({ cancellable(unblock::await) }) {
                state.update { s -> if (s.epoch == epoch) s.copy(awaiting = s.awaiting + 1) else s }
              }
            }
          )
        }
      }
      .invoke()
  }

  fun CompletableDeferred<Unit>.complete(): suspend () -> Unit = { complete(Unit) }

  inline fun <A> AtomicRef<A>.update(function: (A) -> A) {
    loop { current ->
      val update = function(current)
      if (compareAndSet(current, update)) return else Unit
    }
  }
}

internal inline fun <A, B> AtomicRef<A>.modify(f: (A) -> Pair<A, B>): B {
  loop { current ->
    val (update, res) = f(current)
    if (compareAndSet(current, update)) return res else Unit
  }
}
