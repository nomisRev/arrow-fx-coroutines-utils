package io.github.nomisrev

import arrow.fx.coroutines.Atomic
import arrow.fx.coroutines.Resource
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class HotswapSpec :
  StringSpec({
    suspend fun logged(log: Atomic<List<String>>, name: String): Resource<Unit> =
      Resource({ log.update { it + "open $name" } }, { _, _ -> log.update { it + "close $name" } })

    "run finalizer of target run when hotswap is finalized" {
      val log = Atomic(emptyList<String>())
      Hotswap(logged(log, "a")).use {}
      log.get() shouldBe listOf("open a", "close a")
    }

    "acquire new resource and finalize old resource on swap" {
      val log = Atomic(emptyList<String>())
      Hotswap(logged(log, "a")).use { (hotswap, _) -> hotswap.swap(logged(log, "b")) }
      log.get() shouldBe listOf("open a", "open b", "close a", "close b")
    }

    "finalize old resource on clear" {
      val log = Atomic(emptyList<String>())
      Hotswap(logged(log, "a")).use { (hotswap, _) ->
        hotswap.clear()
        hotswap.swap(logged(log, "b"))
      }
      log.get() shouldBe listOf("open a", "close a", "open b", "close b")
    }
  })
