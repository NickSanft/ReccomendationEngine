package com.recengine.ml

import com.recengine.config.FmConfig
import com.recengine.model.FeatureVector
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnlineFMTest {

    private val cfg = FmConfig(
        numFactors     = 4,
        learningRate   = 0.01,
        regularization = 0.001,
        numFeatures    = 1_000
    )

    // Higher lr + no regularization for convergence proofs; other tests use cfg
    private val convergenceCfg = FmConfig(
        numFactors     = 4,
        learningRate   = 0.05,
        regularization = 0.0,
        numFeatures    = 1_000
    )

    private fun fm() = OnlineFM(cfg)

    private fun fv(vararg indices: Int) =
        FeatureVector(indices, DoubleArray(indices.size) { 1.0 })

    // ── score ────────────────────────────────────────────────────────

    @Test
    fun `score returns value in 0-1 range on fresh model`() {
        val score = fm().score(FeatureVector(intArrayOf(0, 1, 2), doubleArrayOf(1.0, 0.5, 0.3)))
        assertTrue(score in 0.0..1.0, "Expected score in [0,1], got $score")
    }

    @Test
    fun `score returns 0-1 range for sparse vector`() {
        val score = fm().score(fv(42))
        assertTrue(score in 0.0..1.0)
    }

    @Test
    fun `score returns 0-1 range for empty vector`() {
        val score = fm().score(FeatureVector(intArrayOf(), doubleArrayOf()))
        assertTrue(score in 0.0..1.0)
    }

    // ── learn — positive label ───────────────────────────────────────

    @Test
    fun `learn increases score for positive label`() {
        val model = fm()
        val fv    = fv(10, 20)
        val before = model.score(fv)
        repeat(50) { model.learn(fv, 1.0) }
        assertTrue(model.score(fv) > before, "Score should increase after positive training")
    }

    @Test
    fun `learn converges score toward 1 for positive label after many steps`() {
        val model = OnlineFM(convergenceCfg)
        val fv    = fv(5, 15, 25)
        repeat(2_000) { model.learn(fv, 1.0) }
        assertTrue(model.score(fv) > 0.9, "Score should approach 1.0 after sustained positive training")
    }

    // ── learn — negative label ───────────────────────────────────────

    @Test
    fun `learn decreases score for negative label`() {
        val model = fm()
        val fv    = fv(30, 40)
        // Prime the model toward 1 first so the decrease is measurable
        repeat(30) { model.learn(fv, 1.0) }
        val before = model.score(fv)
        repeat(100) { model.learn(fv, 0.0) }
        assertTrue(model.score(fv) < before, "Score should decrease after negative training")
    }

    @Test
    fun `learn converges score toward 0 for negative label after many steps`() {
        val model = OnlineFM(convergenceCfg)
        val fv    = fv(7, 8, 9)
        repeat(2_000) { model.learn(fv, 0.0) }
        assertTrue(model.score(fv) < 0.1, "Score should approach 0.0 after sustained negative training")
    }

    // ── totalUpdates ─────────────────────────────────────────────────

    @Test
    fun `totalUpdates increments on each learn call`() {
        val model = fm()
        val fv    = fv(5)
        repeat(10) { model.learn(fv, 1.0) }
        assertEquals(10L, model.totalUpdates.get())
    }

    @Test
    fun `totalUpdates starts at zero`() {
        assertEquals(0L, fm().totalUpdates.get())
    }

    // ── weight export/import ─────────────────────────────────────────

    @Test
    fun `export then import preserves predict output`() {
        val model = fm()
        val fv    = fv(1, 2, 3)
        repeat(20) { model.learn(fv, 1.0) }

        val predictBefore = model.predict(fv)
        val weights       = model.exportWeights()

        val model2 = fm()
        model2.importWeights(weights)

        val predictAfter = model2.predict(fv)
        assertEquals(predictBefore, predictAfter, 1e-10,
            "predict() should be identical after export/import")
    }

    @Test
    fun `exportWeights includes w0 key`() {
        val model = fm()
        repeat(5) { model.learn(fv(0), 1.0) }
        assertTrue(model.exportWeights().containsKey("w0"))
    }

    @Test
    fun `importWeights ignores unknown keys gracefully`() {
        val model = fm()
        model.importWeights(mapOf("unknown:key" to "3.14", "w0" to "0.5"))
        // Should not throw; w0 is applied
    }

    // ── out-of-bounds feature indices ────────────────────────────────

    @Test
    fun `indices beyond numFeatures are silently ignored`() {
        val model = fm()
        val fvOob = FeatureVector(intArrayOf(cfg.numFeatures + 1), doubleArrayOf(1.0))
        val score = model.score(fvOob)  // should not throw
        assertTrue(score in 0.0..1.0)
    }
}
