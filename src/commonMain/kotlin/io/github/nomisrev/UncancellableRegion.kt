package io.github.nomisrev

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** DSL receiver that allows launching [cancellable] effects inside [uncancellable]. */
public interface UncancellableRegion : CoroutineScope {
  public suspend fun <A> cancellable(action: suspend () -> A): A
}

/**
 * Uncancellable builder that allows for creating regions of cancellable blocks. This is very useful
 * when working with predef.uncancellable code, since often you'll want to execute a cancellable
 * piece in the middle of an uncancellable piece of code.
 *
 * i.e.
 *
 * ```kotlin
 * import kotlinx.coroutines.CoroutineStart
 * import kotlinx.coroutines.cancelAndJoin
 * import kotlinx.coroutines.coroutineScope
 * import kotlinx.coroutines.launch
 *
 * suspend fun operation(msg: String, range: IntRange = 0..10): Unit =
 *   range.forEach {
 *     coroutineContext.ensureActive()
 *     println("$msg - $it")
 *   }
 *
 * suspend fun main() {
 *   coroutineScope {
 *     launch(start = CoroutineStart.ATOMIC) {
 *       uncancellable {
 *         operation("predef.uncancellable - start")
 *         cancellable { operation("cancellable - mid") }
 *         operation("predef.uncancellable - end")
 *       }
 *     }.cancelAndJoin()
 *   }
 * }
 * ```
 *
 * See [CyclicBarrier] for an example, where we work with atomic state in an uncancellable way, and
 * await the in a cancellable way.
 *
 * Port of Async#predef.uncancellable from Cats-effect
 */
public suspend fun <A> uncancellable(body: suspend UncancellableRegion.() -> A): A {
  val ctx = currentCoroutineContext()
  return withContext(NonCancellable) { body(PollImpl(ctx, this)) }
}

private class PollImpl(val original: CoroutineContext, val scope: CoroutineScope) :
  UncancellableRegion, CoroutineScope by scope {
  // We can run a cancellable block by running the suspend function on the original context.
  override suspend fun <A> cancellable(action: suspend () -> A): A =
    suspendCoroutineUninterceptedOrReturn { cont ->
      action.startCoroutineUninterceptedOrReturn(Continuation(original, cont::resumeWith))
    }
}
