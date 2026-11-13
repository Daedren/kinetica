package com.kinetica.keyboard.engine

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Portuguese (Portugal) real-asset goldens (ADDING_A_LANGUAGE.md §6): load
 * within the shared memory budget, common-word swipe decodes, accent
 * restoration through both the forms path and tap-autocorrect, and a latency
 * bound against the new dictionary's fan-out. qwerty_pt copies the qwerty_it
 * letter grid, so the shared TestData QWERTY geometry is the correct decode
 * surface. Golden words were measured first; every one leads its swipe at
 * every overshoot value.
 */
class PortugueseDictionaryTest {

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private fun loadDict(): LoadedDictionary {
        val p = assetPath("pt_wordlist.txt")
        assumeTrue("pt wordlist asset not found", Files.exists(p))
        return Files.newBufferedReader(p).use { DictionaryLoader.load(it) }
    }

    @Test
    fun portugueseDictionaryLoadsWithinMemoryBudget() {
        val dict = loadDict()
        assertTrue("word count ${dict.trie.wordCount}", dict.trie.wordCount >= 30_000)
        assertTrue("trie bytes ${dict.trie.sizeBytes()}", dict.trie.sizeBytes() < 4 * 1024 * 1024)
        for (w in listOf("não", "que", "uma", "também", "obrigado")) {
            // Words with accents are stored under their folded key; check the
            // folded form is present (AccentFolder folds ã→a, é→e, etc.).
            val folded = AccentFolder.fold(w)
            assertTrue("missing '$w' (folded '$folded')", dict.trie.contains(folded))
        }
        // Forms table must be populated: at least the accented Portuguese words
        // map to their display forms.
        assertTrue("forms table empty", dict.forms.isNotEmpty())
    }

    @Test
    fun portugueseBigramsLoadAndBoost() {
        val dict = loadDict()
        val p = assetPath("pt_bigrams.txt")
        assumeTrue("pt bigram asset not found", Files.exists(p))
        val table = Files.newBufferedReader(p).use { DictionaryLoader.loadBigrams(it, dict.trie) }
        assertTrue("bigram count ${table.size}", table.size > 50_000)
        assertTrue("table bytes ${table.sizeBytes()}", table.sizeBytes() < 4 * 1024 * 1024)
        val boost = table.multiplier(dict.trie.nodeFor("de"), dict.trie.nodeFor("que"))
        // Assert the asset's share of the available boost, independent of engine tuning.
        val share = (boost - 1f) / KineticaConstants.BIGRAM_BOOST_MAX
        assertTrue("de->que boost $boost, share $share of the cap", share > 0.5f)
    }

    @Test
    fun commonPortugueseSwipesDecodeTop1() {
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val words = mapOf(
            "para" to "para",
            "como" to "como",
            "mais" to "mais",
            "muito" to "muito",
            "obrigado" to "obrigado",
            "voce" to "você",
        )
        for ((folded, expected) in words) {
            for (overshoot in listOf(0f, 0.25f, 0.4f, 0.5f)) {
                val token = if (overshoot == 0f) {
                    TestData.swipe(folded, g, 0, 100L * folded.length)
                } else {
                    TestData.sloppySwipe(
                        folded, g, 0, 100L * folded.length, overshootKw = overshoot,
                    )
                }
                val result = predictor.decode(listOf(token), emptyList())
                assertTrue("'$expected' ($overshoot) produced no candidates", result.isNotEmpty())
                assertEquals(
                    "'$expected' ($overshoot) lost top-1 to ${result[0].word}",
                    expected,
                    result[0].word,
                )
            }
        }
    }

    @Test
    fun accentRestoredThroughDecodeForms() {
        // "tambem" is a left-off spelling the wordlist dropped, so the swipe
        // reaches the "também" node and the forms table offers the accent.
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val result = predictor.decode(
            listOf(TestData.swipe("tambem", g, 0, 600)), emptyList(),
        )
        assertTrue(
            "'também' missing from ${result.map { it.word }}",
            result.map { it.word }.contains("também"),
        )
    }

    @Test
    fun accentRestoredThroughTapAutocorrect() {
        // "acao" reaches the "ação" node (ã folds to a, ç folds to c) but is
        // not itself a dictionary word (only the accented form is present), so
        // isWord is false and tap-autocorrect restores the accent.
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        assertFalse(predictor.isWord("acao"))
        val tokens = "acao".mapIndexed { i, c -> TestData.tap(c, g, i * 100L) }
        val result = predictor.decode(tokens, emptyList())
        val target = predictor.autocorrectTarget(
            "acao", result, KineticaConstants.AUTOCORRECT_CONF_NORMAL,
        )
        assertNotNull("autocorrect did not fire on ${result.map { it.word }}", target)
        assertEquals("ação", target?.word)
    }

    @Test
    fun portugueseDecodeLatencyIsBounded() {
        // Same 100 ms budget as the other language latency goldens: the new
        // dictionary shape (~44k words) must not weaken the pruning.
        val dict = loadDict()
        val g = TestData.qwertyGeometry()
        val predictor = WordPredictor(dict.trie, BigramTable.EMPTY, g, dict.forms)
        val tokens = listOf(TestData.swipe("muito", g, 0, 500))
        predictor.decode(tokens, emptyList()) // warmup
        val t0 = System.nanoTime()
        repeat(20) { predictor.decode(tokens, emptyList()) }
        val perDecodeMs = (System.nanoTime() - t0) / 20 / 1_000_000.0
        assertTrue("pt decode took $perDecodeMs ms", perDecodeMs < 100.0)
    }
}
