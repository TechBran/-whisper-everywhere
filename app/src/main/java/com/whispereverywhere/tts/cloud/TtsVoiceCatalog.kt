package com.whispereverywhere.tts.cloud

import com.whispereverywhere.provider.ProviderId

/**
 * One selectable cloud voice for read-aloud, shared shape across all three providers' catalogs
 * (two static, one dynamic — see [ElevenLabsTts]). [recommended] flags a provider-suggested voice
 * (e.g. OpenAI's marin/cedar) so the picker can chip it — never a speed or quality claim.
 */
data class CloudVoice(
    val providerId: ProviderId,
    val voiceId: String,
    val displayName: String,
    val recommended: Boolean = false,
)
