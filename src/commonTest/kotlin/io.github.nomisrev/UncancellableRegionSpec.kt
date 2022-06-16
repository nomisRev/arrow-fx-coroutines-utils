package io.github.nomisrev

import arrow.fx.coroutines.ExitCase
import arrow.fx.coroutines.guaranteeCase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf
import io.kotest.property.Arb
import io.kotest.property.PropertyContext
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalCoroutinesApi::class)
class UncancellableRegionSpec :
  StringSpec({
    "uncancellable always results in ExitCase.Completed" {
      check { timeout ->
        val exitCase = CompletableDeferred<ExitCase>()
        withTimeoutOrNull(timeout / 2) {
            uncancellable { guaranteeCase({ delay(timeout) }, exitCase::complete) }
          }
          .shouldBeNull()
        exitCase.isCompleted shouldBe true
        exitCase.await().shouldBeTypeOf<ExitCase.Completed>()
      }
    }

    "uncancellable allows for creating nested cancelable scopes" {
      check { timeout ->
        val exitCase = CompletableDeferred<ExitCase>()
        withTimeoutOrNull(timeout / 2) {
            uncancellable { guaranteeCase({ cancellable { delay(timeout) } }, exitCase::complete) }
          }
          .shouldBeNull()
        exitCase.isCompleted shouldBe true
        exitCase.await().shouldBeTypeOf<ExitCase.Cancelled>()
      }
    }

    "nested uncancelable is uncancelable" {
      check { timeout ->
        val exitCase = CompletableDeferred<ExitCase>()
        withTimeoutOrNull(timeout / 2) {
            uncancellable {
              uncancellable {
                guaranteeCase({ cancellable { delay(timeout) } }, exitCase::complete)
              }
            }
          }
          .shouldBeNull()
        exitCase.isCompleted shouldBe true
        exitCase.await().shouldBeTypeOf<ExitCase.Completed>()
      }
    }

    "nested uncancelable needs to be made cancellable" {
      check { timeout ->
        val exitCase = CompletableDeferred<ExitCase>()
        withTimeoutOrNull(timeout / 2) {
            uncancellable {
              cancellable {
                uncancellable {
                  guaranteeCase({ cancellable { delay(timeout) } }, exitCase::complete)
                }
              }
            }
          }
          .shouldBeNull()
        exitCase.isCompleted shouldBe true
        exitCase.await().shouldBeTypeOf<ExitCase.Cancelled>()
      }
    }
  })

suspend fun check(property: suspend TestScope.(timeout: Long) -> Unit): PropertyContext =
  checkAll(Arb.long(min = 2, max = (Long.MAX_VALUE / 2) - 1)) { timeout ->
    runTest { property(this, timeout) }
  }
