package org.siloserver.silo.common.player.audio

import android.content.Context
import android.provider.Settings
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

/** Android TV's explicit Never Surround setting requires PCM output. */
@UnstableApi
internal class TvSurroundOutputPolicy(
    private val context: Context,
    private val isTv: Boolean,
) {
    fun requiresPcm(): Boolean = isTv && runCatching {
        Settings.Global.getString(context.contentResolver, SURROUND_SETTING) == NEVER_SURROUND
    }.getOrDefault(false)

    fun requiresPcm(mimeType: String?): Boolean =
        MimeTypes.isAudio(mimeType) && mimeType != MimeTypes.AUDIO_RAW && requiresPcm()

    companion object {
        const val SURROUND_SETTING = "encoded_surround_output"
        // Settings.Global.ENCODED_SURROUND_OUTPUT_NEVER is hidden in the Android SDK.
        private const val NEVER_SURROUND = "1"
    }
}
