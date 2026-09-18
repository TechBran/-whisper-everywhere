package com.whispereverywhere.transcription.speakers

import android.content.Context
import java.io.File

/**
 * THE TWO DIRECTORIES the spike dump uses, resolved from a [Context] — the only Android in the
 * dump (4.10 speaker spike, session 2).
 *
 * It is a file of its own so that [SpeakerSpike], [SpikeJson], [SpikeWav] and [SpeakerSpikeDump]
 * stay pure `java.io` and reachable from the JVM suite: every rule the tuning loop depends on —
 * the wire format, the WAV header arithmetic, the sweep — is tested there, and nothing in this
 * file has a rule in it at all.
 *
 * ### Why one of the two is EXTERNAL, stated once
 *
 * `getExternalFilesDir(null)` is `/sdcard/Android/data/com.whispereverywhere/files` — app-private
 * storage that `adb` can nonetheless read and write WITHOUT `run-as`. That matters because the
 * builds the owner installs come off the internal Play track and are not debuggable, so `run-as`
 * is refused and `filesDir` is unreachable from a shell. The flag file therefore lives there (it
 * is the controller's only way in), the WAVs go there (they must be pullable to the PC), and the
 * jsonl is mirrored there (same reason).
 */
object SpeakerSpikeStore {

    /** `filesDir/speaker-spike` — the dump's home. Created on first write, not here. */
    fun internalDir(context: Context): File = File(context.filesDir, SpeakerSpike.DIR_NAME)

    /**
     * `getExternalFilesDir(null)/speaker-spike`, or null when external storage is not available.
     *
     * Android returns null here when the volume is not mounted, which on a device in the middle of
     * a media scan or a user in a secondary profile is an ordinary state and not an error: the
     * dump then writes its jsonl to [internalDir] alone and no audio at all, because the flag file
     * cannot be found either.
     */
    fun externalDir(context: Context): File? =
        runCatching { context.getExternalFilesDir(null) }.getOrNull()
            ?.let { File(it, SpeakerSpike.DIR_NAME) }

    /** Both directories plus the session's clock reading — one call per session. */
    fun dirs(context: Context, sessionStartMs: Long): SpeakerSpikeDirs = SpeakerSpikeDirs(
        sessionStartMs = sessionStartMs,
        internalDir = internalDir(context),
        externalDir = externalDir(context),
    )

    /**
     * Deletes every dump file in both directories — item 3 of the spike plan's storage rules, the
     * "switch turned off" half ([SpeakerSpikeDump] sweeps the 24 h half at session start).
     *
     * On a background thread of its own: the caller is `PreferencesManager`'s `detectSpeakers`
     * setter, which runs on Main under the user's finger, and a session's WAVs can be a few hundred
     * files. A daemon thread is the right size for this — there is nothing to wait for, nothing to
     * report, and the work is idempotent, so a process death mid-purge leaves files the next call
     * (or the next session's sweep) removes.
     */
    fun purgeAsync(context: Context) {
        val internal = internalDir(context)
        val external = externalDir(context)
        val thread = Thread({ SpeakerSpike.purge(internal, external) }, "speaker-spike-purge")
        thread.isDaemon = true
        runCatching { thread.start() }
    }
}
