package com.recengine.ml

import com.recengine.config.FmConfig
import com.recengine.model.FeatureVector
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Online Factorization Machine trained with AdaGrad SGD.
 *
 * Model:  ŷ(x) = w₀ + Σᵢ wᵢxᵢ + Σᵢ Σⱼ(j>i) ⟨vᵢ, vⱼ⟩ xᵢxⱼ
 *
 * Second-order interactions are computed with the sum-of-squares trick,
 * giving O(k·n) complexity instead of O(k·n²).
 *
 * AdaGrad update per parameter p with gradient g:
 *   accumᵢ += g²
 *   p -= (lr / √(accumᵢ + ε)) × (g + reg × p)
 */
class OnlineFM(private val cfg: FmConfig) {

    private var w0: Double = 0.0
    private var w0Accum: Double = 1.0

    private val w: DoubleArray = DoubleArray(cfg.numFeatures)
    private val wAccum: DoubleArray = DoubleArray(cfg.numFeatures) { 1.0 }

    private val v: Array<DoubleArray> = Array(cfg.numFeatures) {
        DoubleArray(cfg.numFactors) { Math.random() * 0.01 }
    }
    private val vAccum: Array<DoubleArray> = Array(cfg.numFeatures) {
        DoubleArray(cfg.numFactors) { 1.0 }
    }

    val totalUpdates: AtomicLong = AtomicLong(0)
    val lastUpdateMs: AtomicLong = AtomicLong(0)

    var runningLoss: Double = 0.0
        private set

    private val lossHistory = ArrayDeque<Pair<Long, Double>>()

    fun averageLoss(): Double = runningLoss

    /** Returns up to 300 (timestampMs, loss) snapshots taken during learn(). */
    fun getLossHistory(): List<Pair<Long, Double>> = synchronized(lossHistory) { lossHistory.toList() }

    private fun sigmoid(x: Double) = 1.0 / (1.0 + exp(-x))

    /** Raw FM output (unbounded). */
    fun predict(fv: FeatureVector): Double {
        var result = w0
        for (i in fv.indices.indices) {
            val fi = fv.indices[i]
            if (fi >= cfg.numFeatures) continue
            result += w[fi] * fv.values[i]
        }
        // Sum-of-squares trick for pairwise interactions
        for (f in 0 until cfg.numFactors) {
            var sumV = 0.0
            var sumV2 = 0.0
            for (i in fv.indices.indices) {
                val fi = fv.indices[i]
                if (fi >= cfg.numFeatures) continue
                val vif = v[fi][f] * fv.values[i]
                sumV  += vif
                sumV2 += vif * vif
            }
            result += 0.5 * (sumV * sumV - sumV2)
        }
        return result
    }

    /** Sigmoid-squashed score in [0, 1]. */
    fun score(fv: FeatureVector): Double = sigmoid(predict(fv))

    /** One AdaGrad SGD step with a binary label in {0.0, 1.0}. */
    fun learn(fv: FeatureVector, label: Double) {
        val pred = sigmoid(predict(fv))
        val loss = pred - label
        runningLoss = 0.99 * runningLoss + 0.01 * kotlin.math.abs(pred - label)
        synchronized(lossHistory) {
            if (lossHistory.size >= 300) lossHistory.removeFirst()
            lossHistory.addLast(System.currentTimeMillis() to runningLoss)
        }

        val lr  = cfg.learningRate
        val reg = cfg.regularization
        val eps = 1e-8

        // Bias
        w0Accum += loss * loss
        w0 -= (lr / sqrt(w0Accum + eps)) * (loss + reg * w0)

        // Pre-compute Σ vᵢf·xᵢ for each factor (needed for second-order gradient)
        val sumVx = DoubleArray(cfg.numFactors) { f ->
            var s = 0.0
            for (i in fv.indices.indices) {
                val fi = fv.indices[i]
                if (fi < cfg.numFeatures) s += v[fi][f] * fv.values[i]
            }
            s
        }

        for (i in fv.indices.indices) {
            val fi = fv.indices[i]
            if (fi >= cfg.numFeatures) continue
            val xi = fv.values[i]

            // Linear weight
            val gw = loss * xi + reg * w[fi]
            wAccum[fi] += gw * gw
            w[fi] -= (lr / sqrt(wAccum[fi] + eps)) * gw

            // Factor weights
            for (f in 0 until cfg.numFactors) {
                val gv = loss * xi * (sumVx[f] - v[fi][f] * xi) + reg * v[fi][f]
                vAccum[fi][f] += gv * gv
                v[fi][f] -= (lr / sqrt(vAccum[fi][f] + eps)) * gv
            }
        }
        totalUpdates.incrementAndGet()
        lastUpdateMs.set(System.currentTimeMillis())
    }

    /** Export all non-zero weights as a flat string map (for Redis persistence). */
    fun exportWeights(): Map<String, String> {
        val out = mutableMapOf("w0" to w0.toString())
        w.forEachIndexed { i, value ->
            if (value != 0.0) out["w:$i"] = value.toString()
        }
        for (i in v.indices) {
            for (f in v[i].indices) {
                val value = v[i][f]
                if (value != 0.0) out["v:$i:$f"] = value.toString()
            }
        }
        return out
    }

    /** Restore weights from a previously exported map. */
    fun importWeights(weights: Map<String, String>) {
        weights.forEach { (key, raw) ->
            val d = raw.toDoubleOrNull() ?: return@forEach
            when {
                key == "w0" -> w0 = d
                key.startsWith("w:") -> {
                    key.removePrefix("w:").toIntOrNull()
                        ?.takeIf { it < cfg.numFeatures }
                        ?.let { w[it] = d }
                }
                key.startsWith("v:") -> {
                    val parts = key.split(":")
                    if (parts.size == 3) {
                        val i = parts[1].toIntOrNull() ?: return@forEach
                        val f = parts[2].toIntOrNull() ?: return@forEach
                        if (i < cfg.numFeatures && f < cfg.numFactors) v[i][f] = d
                    }
                }
            }
        }
    }
}
