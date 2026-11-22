package org.siloserver.silo.common.player.audio

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.siloserver.silo.common.player.FfmpegAudioSupport

@UnstableApi
internal class TvSurroundAudioRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
    private val surroundOutputPolicy: TvSurroundOutputPolicy,
    private val ffmpegSupportsMimeType: (String) -> Boolean = FfmpegAudioSupport::supportsMimeType,
) : MediaCodecAudioRenderer(
    context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, audioSink,
) {
    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
        super.supportsFormat(selectorForFormat(mediaCodecSelector, format), format)

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val selector = if (requiresSecureDecoder) {
            mediaCodecSelector
        } else {
            selectorForFormat(mediaCodecSelector, format)
        }
        return super.getDecoderInfos(selector, format, requiresSecureDecoder)
    }

    private fun selectorForFormat(selector: MediaCodecSelector, format: Format): MediaCodecSelector {
        val mimeType = format.sampleMimeType ?: return selector
        // Media3 also queries alternate MIME types (e.g. DTS core for DTS:X).
        // Hand the track to FFmpeg only if it supports the source format.
        return if (format.cryptoType == C.CRYPTO_TYPE_NONE &&
            surroundOutputPolicy.requiresPcm(mimeType) && ffmpegSupportsMimeType(mimeType)
        ) {
            MediaCodecSelector { _, _, _ -> emptyList() }
        } else {
            selector
        }
    }
}
