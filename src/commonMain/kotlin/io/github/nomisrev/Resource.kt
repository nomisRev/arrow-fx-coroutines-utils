@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.github.nomisrev

import arrow.core.NonEmptyList
import arrow.core.ValidatedNel
import arrow.core.continuations.AtomicRef
import arrow.core.continuations.update
import arrow.core.identity
import arrow.core.invalidNel
import arrow.core.prependTo
import arrow.core.traverseValidated
import arrow.core.valid
import arrow.fx.coroutines.ExitCase
import arrow.fx.coroutines.Platform
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.bracketCase
import arrow.fx.coroutines.continuations.ResourceScope
import arrow.fx.coroutines.continuations.resource

public suspend fun <A> Resource<A>.allocated(): Pair<A, suspend () -> Unit> = uncancellable {
  val (a, fin) = cancellable { allocatedCase() }
  Pair(a) { fin(ExitCase.Completed) }
}

public typealias AllocatedCase<A> = Pair<A, suspend (ExitCase) -> Unit>

// TODO Move to Arrow Fx Coroutines (this is a low-level Resource API. Also used in Kotest Arrow Fx
// Coroutines)
// Implementation can be significantly simplified when only using the DSL in 2.0
public suspend fun <A> Resource<A>.allocatedCase(): AllocatedCase<A> {
  val finalizers: AtomicRef<List<suspend (ExitCase) -> Unit>> = AtomicRef(emptyList())
  val effect = ResourceScopeImpl(finalizers)

  return when (this) {
    is Resource.Defer -> resource().allocatedCase()
    is Resource.Allocate -> resource { bind() }.allocatedCase()
    is Resource.Bind<*, *> -> resource { bind() }.allocatedCase()
    is Resource.Dsl -> Pair(dsl.invoke(effect)) { finalizers.get().cancelAll(it)?.let { throw it } }
  }
}

private class ResourceScopeImpl(val finalizers: AtomicRef<List<suspend (ExitCase) -> Unit>>) :
  ResourceScope {

  override suspend fun <A> Resource<A>.bind(): A =
    when (this) {
      is Resource.Dsl -> dsl.invoke(this@ResourceScopeImpl)
      is Resource.Allocate ->
        bracketCase(
          {
            val a = acquire()
            val finalizer: suspend (ExitCase) -> Unit = { ex: ExitCase -> release(a, ex) }
            finalizers.update { finalizer prependTo it }
            a
          },
          ::identity,
          { a, ex ->
            // Only if ExitCase.Failure, or ExitCase.Cancelled during acquire we cancel
            // Otherwise we've saved the finalizer, and it will be called from somewhere else.
            if (ex != ExitCase.Completed) {
              val e = finalizers.get().cancelAll(ex)
              val e2 = runCatching { release(a, ex) }.exceptionOrNull()
              Platform.composeErrors(e, e2)?.let { throw it }
            }
          }
        )
      is Resource.Bind<*, *> -> {
        val dsl: suspend ResourceScope.() -> A = {
          val any = source.bind()
          val ff = f as (Any?) -> Resource<A>
          ff(any).bind()
        }
        dsl(this@ResourceScopeImpl)
      }
      is Resource.Defer -> resource().bind()
    }
}

@Suppress("TooGenericExceptionCaught")
private inline fun <A> catchNel(f: () -> A): ValidatedNel<Throwable, A> =
  try {
    f().valid()
  } catch (e: Throwable) {
    e.invalidNel()
  }

private suspend fun List<suspend (ExitCase) -> Unit>.cancelAll(
  exitCase: ExitCase,
  first: Throwable? = null
): Throwable? =
  traverseValidated { f -> catchNel { f(exitCase) } }
    .fold(
      {
        if (first != null) Platform.composeErrors(NonEmptyList(first, it))
        else Platform.composeErrors(it)
      },
      { first }
    )
