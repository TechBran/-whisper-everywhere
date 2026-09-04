package com.whispereverywhere.npu

import java.text.Normalizer
import java.util.Locale

/**
 * THE STOCK-PHRASE BLOCKLIST (4.3.2, Layer 2): Whisper's documented silence hallucinations, and
 * the rule that blanks one on the NPU tier.
 *
 * ## The mechanism it closes
 * The NPU tier encodes a fixed 30 s window with no pre-encode speech filter, so a near-silent
 * segment reaches the decoder as ~100 % padding. On that input the language step argmaxes over
 * noise (ties -> en; often zh/ja/ko) and the decoder emits the phrases its training data ends
 * with — subtitle credits and sign-offs: "Thank you for watching", the Chinese Amara.org credit,
 * a lone "you". whisper.cpp's no-speech rule ([NpuDecodePolicy.isNoSpeech]: nsp > 0.6 AND lp < -1.0)
 * lets them through because they decode CONFIDENT. A known hole in the reference too.
 *
 * ## The rule
 * [shouldBlank] is `noSpeechProb > STOCK_PHRASE_NSP_MIN && isStockPhrase(text)`: EXACT match,
 * after [normalise], against a short list — never a substring, never a fuzzy score. The nsp gate
 * is what keeps a user who genuinely says "thank you" (nsp ~0.0) from being blanked; a
 * hallucinated one carries an ELEVATED vote even when it fails the 0.6 gate, and 0.30 is the line
 * between those populations ([NpuDecodePolicy.STOCK_PHRASE_NSP_MIN]). A NaN or the -1 sentinel
 * never blanks: a guard that cannot measure must not.
 *
 * ## Normalisation
 * NFKC (full-width forms fold to ASCII), lower-case in [Locale.ROOT], then every character that is
 * not a letter or digit is dropped — punctuation, CJK punctuation (。！？、，), whitespace, the dot
 * in "Amara.org". Both the seeds and the decoded text go through the same function, so a match is
 * an equality of two normalised strings and nothing else: "Thank you for watching!" and "thank
 * you  for watching." are one phrase; "thank you for the ride" is not one.
 *
 * ## The seed list, as shipped
 * Each phrase is one reported verbatim in Whisper hallucination reports (the openai/whisper and
 * whisper.cpp issue trackers; the Amara credits, the ZDF/funk credit, the Mingjing sign-off,
 * "DimaTorzok"), or the short sign-off form ("Thank you.", "you", "Bye.") that a 30 s window of
 * silence produces on the small models. The Mingjing sign-off is carried in BOTH simplified and
 * traditional script because the decoder emits either; the French Amara credit in both its
 * misspelt ("para", as the model actually emits it) and spelt forms. The list is LANGUAGE-
 * INDEPENDENT at match time — the per-language keys are documentation and the tests' index — so a
 * segment mis-detected as zh that decodes the English credit is still caught.
 *
 * HONEST LIMITS. Exact match means a variant the list does not carry ("Thanks for watching, see
 * you next time") is not caught — the no-speech gate, the entropy/logprob ladder and Layer 1's
 * speech-evidence floor remain the defences. And the CPU tier is NOT in this build: whisper.cpp's
 * `whisper_full` exposes no per-segment no-speech probability to Kotlin, so there is no vote to
 * gate on there; its `we_vad_filter` pre-encode pass is what keeps silence out of its decoder.
 *
 * Pure Kotlin, no reference to `QnnAsrNative`: JVM-testable like the rest of the decode policy.
 * NO TRANSCRIPT CONTENT is logged by anything here; the diag line says a blank happened and why.
 */
object HallucinationPolicy {

    /**
     * The seeds by language code — exact phrases, as reported. Read the object KDoc before adding
     * one: a phrase a user could plausibly SAY on purpose is safe here only because the nsp gate
     * stands in front of this list.
     */
    val SEEDS: Map<String, List<String>> = linkedMapOf(
        "en" to listOf(
            "Thank you.",
            "Thank you for watching.",
            "Thanks for watching.",
            "you",
            "Bye.",
            "Subtitles by the Amara.org community",
            "Please subscribe.",
            "Thank you very much.",
        ),
        "zh" to listOf(
            "字幕由Amara.org社区提供",
            "谢谢观看",
            "请不吝点赞 订阅 转发 打赏支持明镜与点点栏目",
            "請不吝點贊 訂閱 轉發 打賞支持明鏡與點點欄目",
            "字幕志愿者 杨栋梁",
        ),
        "ja" to listOf(
            "ご視聴ありがとうございました",
            "ご視聴ありがとうございます",
        ),
        "ko" to listOf(
            "시청해주셔서 감사합니다",
            "감사합니다",
        ),
        "es" to listOf(
            "Gracias por ver.",
            "Subtítulos realizados por la comunidad de Amara.org",
        ),
        "de" to listOf(
            "Untertitel im Auftrag des ZDF für funk, 2017",
            "Vielen Dank.",
        ),
        "fr" to listOf(
            "Sous-titres réalisés par la communauté d'Amara.org",
            "Sous-titres réalisés para la communauté d'Amara.org",
            "Merci d'avoir regardé.",
        ),
        "pt" to listOf(
            "Legendas pela comunidade Amara.org",
            "Obrigado por assistir.",
        ),
        "it" to listOf(
            "Sottotitoli creati dalla comunità Amara.org",
            "Grazie per aver guardato.",
        ),
        "ru" to listOf(
            "Субтитры сделал DimaTorzok",
            "Спасибо за просмотр",
        ),
        "nl" to listOf(
            "Ondertitels ingediend door het Amara.org gemeenschap",
        ),
    )

    /** Every seed, normalised once. The match is set membership and nothing else. */
    private val normalisedSeeds: Set<String> = SEEDS.values.flatten().map(::normalise).toSet()

    /**
     * NFKC, lower-case ([Locale.ROOT]: no Turkish-i surprises), letters and digits only. Public
     * so the tests can state the equivalence classes in the same terms the policy uses.
     */
    fun normalise(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val sb = StringBuilder(folded.length)
        for (ch in folded) if (Character.isLetterOrDigit(ch)) sb.append(ch)
        return sb.toString()
    }

    /** EXACT match, after [normalise], against the seed list. An empty normalisation never matches. */
    fun isStockPhrase(text: String): Boolean {
        val n = normalise(text)
        return n.isNotEmpty() && n in normalisedSeeds
    }

    /**
     * THE RULE: blank iff the no-speech vote is strictly above
     * [NpuDecodePolicy.STOCK_PHRASE_NSP_MIN] AND the text is a stock phrase. NaN and the -1
     * sentinel fail the first comparison, so a segment whose vote could not be measured is never
     * blanked on the phrase alone.
     */
    fun shouldBlank(text: String, noSpeechProb: Float): Boolean =
        noSpeechProb > NpuDecodePolicy.STOCK_PHRASE_NSP_MIN && isStockPhrase(text)
}
