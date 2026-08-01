package com.whispereverywhere.data.local

import android.content.Context
import android.content.SharedPreferences
import com.whispereverywhere.provider.ProviderId
import com.whispereverywhere.transcription.batch.BatchCostEstimator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure per-month cloud-spend estimation math, Compose- and Android-free so every rule is a plain
 * JVM test. The ESTIMATE framing is the product decision (owner 2026-08-01): no provider lets a
 * plain API key read its own spend — OpenAI's usage API wants org/admin credentials, Gemini has no
 * key-scoped billing read, ElevenLabs reports characters rather than dollars, Soniox has no spend
 * endpoint — so the app computes an estimate from ITS OWN measured cloud seconds times the SAME
 * pinned prices its selectors already show, and the copy says so plainly ("our estimate, not your
 * provider's bill").
 */
object CloudCostMath {

    /**
     * Live (realtime WebSocket) ¢/min — the same rates liveModeLabel shows on the selector row,
     * pinned from live docs 2026-07-31: OpenAI $0.0045/min, ElevenLabs $0.007/min, Soniox
     * $0.002/min. Batch rates come from [BatchCostEstimator], the single existing price pin.
     * Gemini has no live path (not realtime-capable), so no live rate exists for it.
     */
    fun liveCentsPerMinute(providerId: ProviderId): Double = when (providerId) {
        ProviderId.OPENAI -> 0.45
        ProviderId.ELEVENLABS -> 0.70
        ProviderId.SONIOX -> 0.20
        ProviderId.GEMINI -> BatchCostEstimator.centsPerMinute(ProviderId.GEMINI)
    }

    /** ¢ for [seconds] of cloud transcription on [providerId] in the given transport mode. */
    fun cents(providerId: ProviderId, live: Boolean, seconds: Int): Double {
        val rate = if (live) liveCentsPerMinute(providerId)
        else BatchCostEstimator.centsPerMinute(providerId)
        return (seconds / 60.0) * rate
    }

    /**
     * The stats-panel footer, or null when the month has no cloud spend at all — a fully
     * on-device month keeps the clean panel. "about", "estimate", and "not your provider's bill"
     * are all load-bearing copy: this number is derived from our own measured seconds and pinned
     * prices, never fetched from a provider, and must never read as a statement of account.
     */
    fun monthCostFooter(totalCents: Double): String? {
        if (totalCents <= 0.0) return null
        val dollars = totalCents / 100.0
        val amount = if (dollars < 0.01) "less than $0.01" else "about $" + String.format(Locale.US, "%.2f", dollars)
        return "$amount in cloud transcription this month — our estimate, not your provider's bill"
    }
}

/**
 * Persists this month's estimated cloud transcription spend. One bucket per (provider, transport
 * mode) so each pinned rate multiplies its own measured seconds; the month rolls over by key
 * comparison exactly like [UsageTracker]'s daily reset. Fed at the same two places the usage
 * stats are fed: the bubble service's finalize (live/batch mic sessions) and the batch service's
 * deliver-on-Done.
 */
class CloudCostTracker(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "whisper_cost_prefs",
        Context.MODE_PRIVATE,
    )

    private fun monthKey(): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

    private fun checkAndResetIfNewMonth() {
        val stored = prefs.getString(KEY_MONTH, null)
        val now = monthKey()
        if (stored != now) {
            // New month: the estimate is explicitly "this month", so the buckets restart.
            prefs.edit().clear().putString(KEY_MONTH, now).apply()
        }
    }

    private fun bucketKey(providerId: ProviderId, live: Boolean) =
        "sec_${providerId.name}_" + if (live) "live" else "batch"

    /** Credit [seconds] of cloud transcription to this month's (provider, mode) bucket. */
    fun recordCloudSeconds(providerId: ProviderId, live: Boolean, seconds: Int) {
        if (seconds <= 0) return
        checkAndResetIfNewMonth()
        val key = bucketKey(providerId, live)
        prefs.edit().putInt(key, prefs.getInt(key, 0) + seconds).apply()
    }

    /** This month's total estimated ¢ across every bucket. */
    fun estimatedMonthCents(): Double {
        checkAndResetIfNewMonth()
        var total = 0.0
        for (id in ProviderId.entries) {
            for (live in booleanArrayOf(true, false)) {
                val s = prefs.getInt(bucketKey(id, live), 0)
                if (s > 0) total += CloudCostMath.cents(id, live, s)
            }
        }
        return total
    }

    private companion object {
        const val KEY_MONTH = "cost_month"
    }
}
