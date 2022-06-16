package io.github.nomisrev

import arrow.continuations.generic.AtomicRef
import arrow.continuations.generic.loop
import arrow.fx.coroutines.Resource

public interface Hotswap<R> {

  /**
   * Allocates a new resource, closes the previous one if it exists, and returns the newly allocated
   * [R].
   *
   * When the lifetime of the [Hotswap] is completed, the resource allocated by the most recent
   * [swap] will be finalized.
   *
   * [swap] finalizes the previous resource immediately, so users must ensure that the old `R` is
   * not used thereafter. Failure to do so may result in an error on the _consumer_ side. In any
   * case, no resources will be leaked.
   *
   * If [swap] is called after the lifetime of the [Hotswap] is over, it will raise an error, but
   * will ensure that all resources are finalized before returning.
   */
  public suspend fun swap(next: Resource<R>): R

  /**
   * Pops and runs the finalizer of the current resource, if it exists.
   *
   * Like [swap], users must ensure that the old [R] is not used after calling [clear]. Calling
   * [clear] after the lifetime of this [Hotswap] results in an error.
   */
  public suspend fun clear(): Unit

  public class FinalizedException : RuntimeException("Hotswap already finalized")
}

/** Creates a [Hotswap] initialised with `Resource<R>`. */
public fun <R> Hotswap(initial: Resource<R>): Resource<Pair<Hotswap<R>, R>> =
  Hotswap<R>().map { hotswap ->
    val r = hotswap.swap(initial)
    Pair(hotswap, r)
  }

private typealias Finalizer = suspend () -> Unit

public fun <R> Hotswap(): Resource<Hotswap<R>> =
  Resource(
      { AtomicRef<Finalizer?> {} },
      { state, _ -> state.getAndSet(null)?.invoke() ?: throw Hotswap.FinalizedException() }
    )
    .map { state ->
      object : Hotswap<R> {
        private suspend fun swapFinalizer(next: Finalizer): Unit =
          state.loop { finalizer ->
            when {
              finalizer == null -> {
                next.invoke()
                throw Hotswap.FinalizedException()
              }
              state.compareAndSet(finalizer, next) -> finalizer.invoke()
            }
          }

        override suspend fun swap(next: Resource<R>): R = uncancellable {
          val (r, finalizers) = cancellable(next::allocated)
          swapFinalizer(finalizers)
          r
        }

        override suspend fun clear() = uncancellable { swapFinalizer {} }
      }
    }
