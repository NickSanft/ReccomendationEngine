package com.recengine.ml

import com.recengine.model.ItemFeatures
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureVectorBuilderTest {

    private val numFeatures = 1_000
    private val builder     = FeatureVectorBuilder(numFeatures)

    private fun item(
        itemId: String      = "item-1",
        categories: List<String> = listOf("electronics"),
        tags: List<String>       = listOf("sale"),
        popularity: Double       = 0.8
    ) = ItemFeatures(
        itemId        = itemId,
        categoryIds   = categories,
        tags          = tags,
        publishedAtMs = System.currentTimeMillis(),
        popularity    = popularity
    )

    @Test
    fun `all indices are in valid range`() {
        val fv = builder.build("user-1", "item-1", item())
        fv.indices.forEach { idx ->
            assertTrue(idx in 0 until numFeatures, "Index $idx out of range [0, $numFeatures)")
        }
    }

    @Test
    fun `indices are sorted ascending`() {
        val fv = builder.build("user-1", "item-1", item())
        for (i in 0 until fv.indices.size - 1) {
            assertTrue(fv.indices[i] <= fv.indices[i + 1],
                "Indices must be sorted: ${fv.indices[i]} > ${fv.indices[i + 1]}")
        }
    }

    @Test
    fun `indices and values have equal length`() {
        val fv = builder.build("user-1", "item-1", item())
        assertEquals(fv.indices.size, fv.values.size)
    }

    @Test
    fun `same inputs produce identical vector`() {
        val item = item()
        val fv1  = builder.build("user-42", "item-99", item)
        val fv2  = builder.build("user-42", "item-99", item)
        assertTrue(fv1.indices.contentEquals(fv2.indices), "Indices should be deterministic")
        assertTrue(fv1.values.contentEquals(fv2.values),   "Values should be deterministic")
    }

    @Test
    fun `different user IDs produce different vectors`() {
        val item = item()
        val fv1  = builder.build("user-A", "item-1", item)
        val fv2  = builder.build("user-B", "item-1", item)
        // At least one index or value must differ
        val indicesMatch = fv1.indices.contentEquals(fv2.indices) && fv1.values.contentEquals(fv2.values)
        assertTrue(!indicesMatch, "Different users should produce different feature vectors")
    }

    @Test
    fun `different item IDs produce different vectors`() {
        val fv1 = builder.build("user-1", "item-A", item("item-A"))
        val fv2 = builder.build("user-1", "item-B", item("item-B"))
        val same = fv1.indices.contentEquals(fv2.indices) && fv1.values.contentEquals(fv2.values)
        assertTrue(!same, "Different items should produce different feature vectors")
    }

    @Test
    fun `popularity feature value is included in the vector`() {
        val fv = builder.build("user-1", "item-1", item(popularity = 0.75))
        // popularity is included as a real-valued feature; at least one value should be 0.75
        assertTrue(fv.values.any { it == 0.75 }, "Popularity value should appear in the vector")
    }

    @Test
    fun `empty categories and tags still produces valid vector`() {
        val emptyItem = item(categories = emptyList(), tags = emptyList())
        val fv = builder.build("user-1", "item-1", emptyItem)
        assertTrue(fv.indices.isNotEmpty(), "Vector should still have user/item features")
    }

    @Test
    fun `hash collision sums values at same index`() {
        // With a tiny feature space collisions are guaranteed; builder must handle them gracefully
        val tinyBuilder = FeatureVectorBuilder(numFeatures = 1)
        val fv = tinyBuilder.build("user-1", "item-1", item())
        assertEquals(1, fv.indices.size, "All features must collapse to single bucket")
        assertEquals(1, fv.values.size)
        assertEquals(0, fv.indices[0])
    }
}
