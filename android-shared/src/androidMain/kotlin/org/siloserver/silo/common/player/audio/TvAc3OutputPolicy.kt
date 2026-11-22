package org.siloserver.silo.common.player.audio

import android.content.Context
import android.provider.Settings
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.siloserver.silo.common.player.FfmpegAudioSupport

/**
 * Treat the TV's explicit Never Surround setting as a request for local AC-3
 * decode even when the platform still reports encoded output. Prefer FFmpeg
 * on that path to avoid relying on a vendor AC-3 decoder.
 */
@UnstableApi
internal class TvAc3OutputPolicy(
    private val context: Context,
    private val isTv: Boolean,
) {
    fun requiresPcm(mimeType: String?): Boolean =
        mimeType == MimeTypes.AUDIO_AC3 && isTv && runCatching {
            Settings.Global.getString(context.contentResolver, SURROUND_SETTING) == NEVER_SURROUND
        }.getOrDefault(false)

    fun shouldDecodeWithFfmpeg(
        mimeType: String,
        supportsMimeType: (String) -> Boolean = FfmpegAudioSupport::supportsMimeType,
    ): Boolean = requiresPcm(mimeType) && supportsMimeType(mimeType)

    companion object {
        const val SURROUND_SETTING = "encoded_surround_output"
        // Settings.Global.ENCODED_SURROUND_OUTPUT_NEVER is hidden in the Android SDK.
        private const val NEVER_SURROUND = "1"
    }
}
