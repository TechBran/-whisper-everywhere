package com.whispereverywhere.probe

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import org.json.JSONObject
import java.io.File

/** Process memory, battery temperature and thermal status, read the same way at every checkpoint. */
object Metrics {
    fun rssKb(): Long {
        return try {
            File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("VmRSS:") }
                ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: -1
        } catch (t: Throwable) { -1 }
    }

    fun pssKb(): Long {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        return mi.totalPss.toLong()
    }

    /** BatteryManager.EXTRA_TEMPERATURE is in tenths of a degree Celsius. */
    fun batteryTempTenths(ctx: Context): Int {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
    }

    fun thermalStatus(ctx: Context): Int {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.currentThermalStatus
    }

    fun snapshot(ctx: Context, label: String): JSONObject {
        val o = JSONObject()
        o.put("label", label)
        o.put("rss_kb", rssKb())
        o.put("pss_kb", pssKb())
        o.put("batt_temp_tenths_c", batteryTempTenths(ctx))
        o.put("thermal_status", thermalStatus(ctx))
        ProbeLog.i("mem|$label|rss_kb=${o.getLong("rss_kb")}|pss_kb=${o.getLong("pss_kb")}|batt_temp_tenths_c=${o.getInt("batt_temp_tenths_c")}|thermal_status=${o.getInt("thermal_status")}")
        return o
    }

    fun stats(ms: List<Double>): JSONObject {
        val o = JSONObject()
        if (ms.isEmpty()) return o
        val sorted = ms.sorted()
        val mean = ms.average()
        val sd = if (ms.size > 1) Math.sqrt(ms.sumOf { (it - mean) * (it - mean) } / (ms.size - 1)) else 0.0
        o.put("n", ms.size)
        o.put("mean_ms", mean)
        o.put("median_ms", if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0)
        o.put("min_ms", sorted.first())
        o.put("max_ms", sorted.last())
        o.put("sd_ms", sd)
        return o
    }
}
