package io.github.nomisrev

import arrow.fx.coroutines.parTraverse
import io.github.nomisrev.Backpressure.Strategy.Lossless
import io.github.nomisrev.Backpressure.Strategy.Lossy
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

class BackpressureSpec :
  StringSpec({
    "Lossy Strategy should return null when no permits are available" {
      val backpressure = Backpressure(Lossy, 1)
      val never =
        launch(start = CoroutineStart.UNDISPATCHED) {
          backpressure.metered<Unit> { awaitCancellation() }
        }
      backpressure.metered {}.shouldBeNull()
      never.cancelAndJoin()
      backpressure.metered {} shouldBe Unit
    }

    "Lossless Strategy should complete effects even when no permits are available" {
      checkAll(Arb.list(Arb.int())) { ints ->
        val backpressure = Backpressure(Lossless, 1)
        ints.parTraverse { backpressure.metered { it } } shouldBe ints
      }
    }
  })
