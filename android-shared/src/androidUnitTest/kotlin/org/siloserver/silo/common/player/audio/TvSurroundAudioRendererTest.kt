package org.siloserver.silo.common.player.audio

import android.content.Context
import android.provider.Settings
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TvSurroundAudioRendererTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val tvPolicy get() = TvSurroundOutputPolicy(context, isTv = true)

    @Test
    fun neverSurroundBlocksPassthroughAndOffloadForEncodedAudio() {
        val policy = tvPolicy
        val sink = audioSink(policy, directEncodedOutput = true)
        val encodedMimes = listOf(
            MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC,
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_TRUEHD,
            MimeTypes.AUDIO_AC4, MimeTypes.AUDIO_AAC,
        )
        for (setting in listOf(null, "0", "1", "2", "3", "invalid")) {
            setSurroundMode(setting)
            for (mime in encodedMimes) {
                val format = audioFormat(mime)
                assertEquals(setting != "1", sink.supportsFormat(format), "setting=$setting mime=$mime")
                assertEquals(setting != "1", sink.getFormatOffloadSupport(format).isFormatSupported)
            }
        }

        setSurroundMode("1")
        assertTrue(sink.supportsFormat(audioFormat(MimeTypes.AUDIO_RAW)))
        assertFalse(TvSurroundOutputPolicy(context, isTv = false).requiresPcm(MimeTypes.AUDIO_AC3))
    }

    @Test
    fun neverSurroundHidesPlatformDolbyDecodersOnlyWithRuntimeFfmpegSupport() {
        val mimes = setOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3)
        val renderer = renderer(mimes, ffmpegSupportsMimeType = { it in mimes })
        val noFfmpegRenderer = renderer(mimes, ffmpegSupportsMimeType = { false })
        val ac3OnlyRenderer = renderer(mimes, ffmpegSupportsMimeType = { it == MimeTypes.AUDIO_AC3 })

        setSurroundMode("0")
        for (mime in mimes) {
            assertSupport(C.FORMAT_HANDLED, renderer, mime)
        }

        setSurroundMode("1")
        for (mime in mimes) {
            assertSupport(C.FORMAT_UNSUPPORTED_SUBTYPE, renderer, mime)
            assertSupport(C.FORMAT_HANDLED, noFfmpegRenderer, mime)
        }
        assertSupport(C.FORMAT_UNSUPPORTED_SUBTYPE, ac3OnlyRenderer, MimeTypes.AUDIO_AC3)
        assertSupport(C.FORMAT_HANDLED, ac3OnlyRenderer, MimeTypes.AUDIO_E_AC3)

        setSurroundMode("3")
        for (mime in mimes) {
            assertSupport(C.FORMAT_HANDLED, renderer, mime)
        }
    }

    @Test
    fun neverSurroundKeepsAlternatePlatformDecoderWhenFfmpegCannotDecodeTheSource() {
        setSurroundMode("1")
        val renderer = renderer(
            platformMimes = setOf(MimeTypes.AUDIO_DTS),
            ffmpegSupportsMimeType = { it == MimeTypes.AUDIO_DTS || it == MimeTypes.AUDIO_DTS_HD },
        )

        assertSupport(C.FORMAT_UNSUPPORTED_SUBTYPE, renderer, MimeTypes.AUDIO_DTS_HD)
        assertSupport(C.FORMAT_HANDLED, renderer, MimeTypes.AUDIO_DTS_UHD_P2)
    }

    private fun renderer(
        platformMimes: Set<String>,
        ffmpegSupportsMimeType: (String) -> Boolean,
    ): TvSurroundAudioRenderer {
        val policy = tvPolicy
        val platformSelector = MediaCodecSelector { mime, _, _ ->
            if (mime in platformMimes) {
                listOf(MediaCodecInfo.newInstance("test.$mime", mime, mime, null, true, false, true, false, false))
            } else {
                emptyList()
            }
        }
        return TvSurroundAudioRenderer(
            context = context,
            codecAdapterFactory = MediaCodecAdapter.Factory.getDefault(context),
            mediaCodecSelector = platformSelector,
            enableDecoderFallback = true,
            eventHandler = null,
            eventListener = null,
            audioSink = audioSink(policy, directEncodedOutput = false),
            surroundOutputPolicy = policy,
            ffmpegSupportsMimeType = ffmpegSupportsMimeType,
        )
    }

    private fun audioSink(policy: TvSurroundOutputPolicy, directEncodedOutput: Boolean): AudioSink {
        val offloadSupport = AudioOffloadSupport.Builder().setIsFormatSupported(true).build()
        val delegate = Proxy.newProxyInstance(
            AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getFormatSupport" -> {
                    val format = args!![0] as Format
                    if (format.sampleMimeType == MimeTypes.AUDIO_RAW || directEncodedOutput) {
                        AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
                    } else {
                        AudioSink.SINK_FORMAT_UNSUPPORTED
                    }
                }
                "getFormatOffloadSupport" -> offloadSupport
                else -> error("Unexpected delegate call: ${method.name}")
            }
        } as AudioSink
        return PassthroughSuppressingAudioSink(delegate) { policy.requiresPcm(it.sampleMimeType) }
    }

    private fun assertSupport(expected: Int, renderer: TvSurroundAudioRenderer, mime: String) {
        assertEquals(expected, RendererCapabilities.getFormatSupport(renderer.supportsFormat(audioFormat(mime))), mime)
    }

    private fun audioFormat(mime: String): Format = Format.Builder().setSampleMimeType(mime).build()

    private fun setSurroundMode(value: String?) {
        Settings.Global.putString(context.contentResolver, TvSurroundOutputPolicy.SURROUND_SETTING, value)
    }
}
