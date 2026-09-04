package com.whispereverywhere.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE STOCK-PHRASE BLOCKLIST (4.3.2, Layer 2): exact match after normalisation, gated on the
 * no-speech vote. Three things are pinned here — the seed list AS SHIPPED (so a phrase cannot be
 * added or dropped without this file saying so), the normalisation's equivalence classes (every
 * seed matches itself under casing, trailing punctuation, CJK full-width punctuation and internal
 * whitespace; near-misses do not), and the gate in both directions.
 */
class HallucinationPolicyTest {

    private val allSeeds: List<String> = HallucinationPolicy.SEEDS.values.flatten()

    // ---------------------------------------------------------------- the list as shipped

    @Test
    fun theSeedListIsShortExactAndPinnedPerLanguage() {
        assertEquals(
            listOf("en", "zh", "ja", "ko", "es", "de", "fr", "pt", "it", "ru", "nl"),
            HallucinationPolicy.SEEDS.keys.toList(),
        )
        assertEquals(
            listOf(
                "Thank you.", "Thank you for watching.", "Thanks for watching.", "you", "Bye.",
                "Subtitles by the Amara.org community", "Please subscribe.", "Thank you very much.",
            ),
            HallucinationPolicy.SEEDS.getValue("en"),
        )
        assertEquals(
            listOf(
                "字幕由Amara.org社区提供", "谢谢观看",
                "请不吝点赞 订阅 转发 打赏支持明镜与点点栏目",
                "請不吝點贊 訂閱 轉發 打賞支持明鏡與點點欄目",
                "字幕志愿者 杨栋梁",
            ),
            HallucinationPolicy.SEEDS.getValue("zh"),
        )
        assertEquals(listOf("ご視聴ありがとうございました", "ご視聴ありがとうございます"), HallucinationPolicy.SEEDS.getValue("ja"))
        assertEquals(listOf("시청해주셔서 감사합니다", "감사합니다"), HallucinationPolicy.SEEDS.getValue("ko"))
        assertEquals(
            listOf("Gracias por ver.", "Subtítulos realizados por la comunidad de Amara.org"),
            HallucinationPolicy.SEEDS.getValue("es"),
        )
        assertEquals(listOf("Untertitel im Auftrag des ZDF für funk, 2017", "Vielen Dank."), HallucinationPolicy.SEEDS.getValue("de"))
        assertEquals(
            listOf(
                "Sous-titres réalisés par la communauté d'Amara.org",
                "Sous-titres réalisés para la communauté d'Amara.org",
                "Merci d'avoir regardé.",
            ),
            HallucinationPolicy.SEEDS.getValue("fr"),
        )
        assertEquals(listOf("Legendas pela comunidade Amara.org", "Obrigado por assistir."), HallucinationPolicy.SEEDS.getValue("pt"))
        assertEquals(listOf("Sottotitoli creati dalla comunità Amara.org", "Grazie per aver guardato."), HallucinationPolicy.SEEDS.getValue("it"))
        assertEquals(listOf("Субтитры сделал DimaTorzok", "Спасибо за просмотр"), HallucinationPolicy.SEEDS.getValue("ru"))
        assertEquals(listOf("Ondertitels ingediend door het Amara.org gemeenschap"), HallucinationPolicy.SEEDS.getValue("nl"))
        assertEquals("31 phrases: short enough to read in one sitting", 31, allSeeds.size)
        assertEquals("no two seeds collapse to one normalised form", 31, allSeeds.map(HallucinationPolicy::normalise).toSet().size)
    }

    // ---------------------------------------------------------------- normalisation

    @Test
    fun everySeedMatchesItselfAndItsCasingPunctuationAndWhitespaceVariants() {
        for (seed in allSeeds) {
            assertTrue("verbatim: $seed", HallucinationPolicy.isStockPhrase(seed))
            assertTrue("upper-cased: $seed", HallucinationPolicy.isStockPhrase(seed.uppercase()))
            assertTrue("trailing period: $seed", HallucinationPolicy.isStockPhrase("$seed."))
            assertTrue("no trailing period: $seed", HallucinationPolicy.isStockPhrase(seed.trimEnd('.')))
            assertTrue("exclamation: $seed", HallucinationPolicy.isStockPhrase("$seed!"))
            assertTrue("CJK full stop and bang: $seed", HallucinationPolicy.isStockPhrase("$seed。！"))
            assertTrue("leading and trailing whitespace: $seed", HallucinationPolicy.isStockPhrase("  $seed \n"))
            assertTrue("doubled internal whitespace: $seed", HallucinationPolicy.isStockPhrase(seed.replace(" ", "   ")))
            assertTrue("no internal whitespace: $seed", HallucinationPolicy.isStockPhrase(seed.replace(" ", "")))
            assertTrue("the leading space whisper's BPE emits: $seed", HallucinationPolicy.isStockPhrase(" $seed"))
        }
    }

    @Test
    fun normalisationFoldsFullWidthFormsAndDropsEverythingButLettersAndDigits() {
        assertEquals("thankyouforwatching", HallucinationPolicy.normalise("Thank you for watching!"))
        assertEquals("subtitlesbytheamaraorgcommunity", HallucinationPolicy.normalise("Subtitles by the Amara.org community"))
        assertEquals("字幕由amaraorg社区提供", HallucinationPolicy.normalise("字幕由Amara.org社区提供。"))
        assertEquals("ご視聴ありがとうございました", HallucinationPolicy.normalise("ご視聴ありがとうございました！"))
        // Full-width Latin (NFKC folds it) and CJK punctuation inside a phrase.
        assertEquals("thankyou", HallucinationPolicy.normalise("Ｔｈａｎｋ　ｙｏｕ、"))
        assertEquals("untertitelimauftragdeszdffürfunk2017", HallucinationPolicy.normalise("Untertitel im Auftrag des ZDF für funk, 2017"))
        assertEquals("", HallucinationPolicy.normalise(" ...!?。 "))
    }

    @Test
    fun nearMissesAndRealSpeechDoNotMatch() {
        for (text in listOf(
            "thank you for the ride",
            "Thank you for watching this whole thing.",
            "thanks for watching, see you next time",
            "you are welcome",
            "yo",
            "youu",
            "Bye bye",
            "please subscribe to the newsletter",
            "gracias por venir",
            "字幕由某人提供",
            "감사합니다 여러분",
            "The quick brown fox",
            "",
            " ",
            "...",
        )) {
            assertFalse("must not match: <$text>", HallucinationPolicy.isStockPhrase(text))
        }
    }

    @Test
    fun aSeedInsideALongerSentenceIsNotAMatchBecauseTheMatchIsExactNeverSubstring() {
        assertFalse(HallucinationPolicy.isStockPhrase("I said thank you and then left"))
        assertFalse(HallucinationPolicy.isStockPhrase("you know what I mean"))
        assertFalse(HallucinationPolicy.isStockPhrase("Bye. See you tomorrow."))
    }

    // ---------------------------------------------------------------- the gate, both directions

    @Test
    fun aStockPhraseIsBlankedOnlyWithAnElevatedNoSpeechVote() {
        // A hallucinated "Thank you." on a silent window: confident words (lp >= -1.0, so
        // isNoSpeech keeps it) with a silence vote well over 0.30 — blanked.
        assertTrue(HallucinationPolicy.shouldBlank("Thank you.", noSpeechProb = 0.45f))
        assertTrue(HallucinationPolicy.shouldBlank("字幕由Amara.org社区提供", noSpeechProb = 0.55f))
        assertTrue(HallucinationPolicy.shouldBlank(" you", noSpeechProb = 0.31f))
        // The vote alone does not blank a segment that is not a stock phrase.
        assertFalse(HallucinationPolicy.shouldBlank("hello there", noSpeechProb = 0.9f))
    }

    @Test
    fun aUserWhoGenuinelySaysThankYouIsNeverBlanked() {
        // Real speech decodes with nsp ~0: the phrase matches and the gate refuses.
        assertFalse(HallucinationPolicy.shouldBlank("Thank you.", noSpeechProb = 0.0f))
        assertFalse(HallucinationPolicy.shouldBlank("Thank you.", noSpeechProb = 0.05f))
        assertFalse(HallucinationPolicy.shouldBlank("Vielen Dank.", noSpeechProb = 0.29f))
    }

    @Test
    fun theGateIsStrictAtTheThreshold() {
        assertFalse("at 0.30 is not over it", HallucinationPolicy.shouldBlank("Thank you.", NpuDecodePolicy.STOCK_PHRASE_NSP_MIN))
        assertTrue(HallucinationPolicy.shouldBlank("Thank you.", NpuDecodePolicy.STOCK_PHRASE_NSP_MIN + 0.001f))
    }

    @Test
    fun anUnmeasurableVoteNeverBlanks() {
        // The same rule isNoSpeech follows: a NaN (nothing scored) or the -1 sentinel (the logits'
        // scale was unreadable) is a guard that cannot measure, and it must not blank.
        assertFalse(HallucinationPolicy.shouldBlank("Thank you.", Float.NaN))
        assertFalse(HallucinationPolicy.shouldBlank("Thank you.", -1f))
    }

    @Test
    fun theMatchIsLanguageIndependent() {
        // A window mis-detected as zh that decodes the English credit, or vice versa, is caught:
        // the keys are documentation, the match is against the union.
        assertTrue(HallucinationPolicy.shouldBlank("Subtitles by the Amara.org community", 0.5f))
        assertTrue(HallucinationPolicy.shouldBlank("ご視聴ありがとうございました", 0.5f))
        assertTrue(HallucinationPolicy.shouldBlank("Субтитры сделал DimaTorzok", 0.5f))
    }
}
