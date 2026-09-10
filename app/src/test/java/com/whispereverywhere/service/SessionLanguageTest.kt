package com.whispereverywhere.service

import com.whispereverywhere.model.ModelScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The 3.8 cloud-"en" leak fix (spec §1), pinned as a truth table over engine x scope x selection.
 *
 * Before the fix the service computed ONE session language from the LOCAL installed model's scope
 * and fed it to every engine — so a cloud user with the English-only default `eco` installed had
 * "en" forced on the provider regardless of the language they chose, and a Gemini live user with
 * `pro` installed would have sent `languageCodes:["en"]` forever. The ENGLISH-scope override is a
 * fact about whisper's `.en` weights (auto-detect is unreliable on them, and they cannot transcribe
 * another language anyway), so it applies ONLY to the engine that runs those weights: the on-device
 * engine, or the English-only local MIRROR of a cloud session. The cloud half of a session always
 * gets the user's own selection.
 */
class SessionLanguageTest {

    // ---- the leak: a cloud engine must receive the user's selection, never the local override ----

    @Test fun aCloudEngineGetsTheUsersSelectionEvenWithAnEnglishOnlyLocalModel() {
        assertEquals("es", sessionLanguageFor(ModelScope.ENGLISH, "es", TranscribingEngine.CLOUD))
        assertEquals("de", sessionLanguageFor(ModelScope.ENGLISH, "de", TranscribingEngine.CLOUD))
    }

    @Test fun aCloudEngineOnAutoStaysOnAutoWhateverIsInstalledLocally() {
        // null = auto. The old code turned this into "en" whenever `eco` was installed — the leak.
        assertNull(sessionLanguageFor(ModelScope.ENGLISH, null, TranscribingEngine.CLOUD))
        assertNull(sessionLanguageFor(ModelScope.MULTILINGUAL, null, TranscribingEngine.CLOUD))
        assertNull(sessionLanguageFor(null, null, TranscribingEngine.CLOUD))
    }

    // ---- the override: the local engine (and the local mirror) keep the ENGLISH-scope pin -------

    @Test fun theLocalEngineIsForcedToEnglishOnAnEnglishOnlyModel() {
        // whisper's .en weights: auto-detect is unreliable and they cannot transcribe anything else.
        assertEquals("en", sessionLanguageFor(ModelScope.ENGLISH, null, TranscribingEngine.LOCAL))
        assertEquals("en", sessionLanguageFor(ModelScope.ENGLISH, "es", TranscribingEngine.LOCAL))
        assertEquals("en", sessionLanguageFor(ModelScope.ENGLISH, "en", TranscribingEngine.LOCAL))
    }

    @Test fun theLocalEngineHonoursTheSelectionOnAMultilingualModel() {
        assertEquals("es", sessionLanguageFor(ModelScope.MULTILINGUAL, "es", TranscribingEngine.LOCAL))
        assertNull("auto stays auto on a multilingual tier", sessionLanguageFor(ModelScope.MULTILINGUAL, null, TranscribingEngine.LOCAL))
    }

    @Test fun noInstalledModelMeansNoOverrideForEitherEngine() {
        // Defensive only — a recording session cannot start without a model — but the rule must
        // not invent "en" out of an absent scope.
        assertEquals("fr", sessionLanguageFor(null, "fr", TranscribingEngine.LOCAL))
        assertEquals("fr", sessionLanguageFor(null, "fr", TranscribingEngine.CLOUD))
    }

    @Test fun theMirrorAndTheCloudHalfOfOneSessionDivergeDeliberately() {
        // One session, one selection ("es"), eco installed: cloud transcribes Spanish, the
        // English-only mirror that rescues lost turns keeps "en". Both answers come from the same
        // function with only the engine kind changed — the shape the service wires.
        val selection = "es"
        assertEquals("es", sessionLanguageFor(ModelScope.ENGLISH, selection, TranscribingEngine.CLOUD))
        assertEquals("en", sessionLanguageFor(ModelScope.ENGLISH, selection, TranscribingEngine.LOCAL))
    }
}
