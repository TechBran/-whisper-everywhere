package com.whispereverywhere.tts

/**
 * Speaker catalog for kokoro-multi-lang-v1_0 (53 voices). The id order is sherpa-onnx's
 * voices.bin order (authoritative mapping from the sherpa docs, fetched 2026-07-18) — do NOT
 * reorder. Display names decode the Kokoro prefix convention: a=American e=Spanish f=French
 * h=Hindi i=Italian j=Japanese p=Portuguese b=British z=Chinese; f/m = female/male.
 */
object TtsVoices {

    data class Voice(val id: Int, val key: String, val displayName: String)

    /** Default = af_heart, Kokoro's flagship US-English voice. */
    const val DEFAULT_VOICE_ID = 3

    val ALL: List<Voice> = listOf(
        Voice(0, "af_alloy", "Alloy — US female"),
        Voice(1, "af_aoede", "Aoede — US female"),
        Voice(2, "af_bella", "Bella — US female"),
        Voice(3, "af_heart", "Heart — US female"),
        Voice(4, "af_jessica", "Jessica — US female"),
        Voice(5, "af_kore", "Kore — US female"),
        Voice(6, "af_nicole", "Nicole — US female"),
        Voice(7, "af_nova", "Nova — US female"),
        Voice(8, "af_river", "River — US female"),
        Voice(9, "af_sarah", "Sarah — US female"),
        Voice(10, "af_sky", "Sky — US female"),
        Voice(11, "am_adam", "Adam — US male"),
        Voice(12, "am_echo", "Echo — US male"),
        Voice(13, "am_eric", "Eric — US male"),
        Voice(14, "am_fenrir", "Fenrir — US male"),
        Voice(15, "am_liam", "Liam — US male"),
        Voice(16, "am_michael", "Michael — US male"),
        Voice(17, "am_onyx", "Onyx — US male"),
        Voice(18, "am_puck", "Puck — US male"),
        Voice(19, "am_santa", "Santa — US male"),
        Voice(20, "bf_alice", "Alice — UK female"),
        Voice(21, "bf_emma", "Emma — UK female"),
        Voice(22, "bf_isabella", "Isabella — UK female"),
        Voice(23, "bf_lily", "Lily — UK female"),
        Voice(24, "bm_daniel", "Daniel — UK male"),
        Voice(25, "bm_fable", "Fable — UK male"),
        Voice(26, "bm_george", "George — UK male"),
        Voice(27, "bm_lewis", "Lewis — UK male"),
        Voice(28, "ef_dora", "Dora — Spanish female"),
        Voice(29, "em_alex", "Alex — Spanish male"),
        Voice(30, "ff_siwis", "Siwis — French female"),
        Voice(31, "hf_alpha", "Alpha — Hindi female"),
        Voice(32, "hf_beta", "Beta — Hindi female"),
        Voice(33, "hm_omega", "Omega — Hindi male"),
        Voice(34, "hm_psi", "Psi — Hindi male"),
        Voice(35, "if_sara", "Sara — Italian female"),
        Voice(36, "im_nicola", "Nicola — Italian male"),
        Voice(37, "jf_alpha", "Alpha — Japanese female"),
        Voice(38, "jf_gongitsune", "Gongitsune — Japanese female"),
        Voice(39, "jf_nezumi", "Nezumi — Japanese female"),
        Voice(40, "jf_tebukuro", "Tebukuro — Japanese female"),
        Voice(41, "jm_kumo", "Kumo — Japanese male"),
        Voice(42, "pf_dora", "Dora — Portuguese female"),
        Voice(43, "pm_alex", "Alex — Portuguese male"),
        Voice(44, "pm_santa", "Santa — Portuguese male"),
        Voice(45, "zf_xiaobei", "Xiaobei — Chinese female"),
        Voice(46, "zf_xiaoni", "Xiaoni — Chinese female"),
        Voice(47, "zf_xiaoxiao", "Xiaoxiao — Chinese female"),
        Voice(48, "zf_xiaoyi", "Xiaoyi — Chinese female"),
        Voice(49, "zm_yunjian", "Yunjian — Chinese male"),
        Voice(50, "zm_yunxi", "Yunxi — Chinese male"),
        Voice(51, "zm_yunxia", "Yunxia — Chinese male"),
        Voice(52, "zm_yunyang", "Yunyang — Chinese male"),
    )

    fun byId(id: Int): Voice = ALL.getOrNull(id) ?: ALL[DEFAULT_VOICE_ID]
}
