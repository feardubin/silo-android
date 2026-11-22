package org.siloserver.silo.common.player

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.PlaceholderDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class SupersededPlaybackTransportTest {
    private val request = DataSpec(Uri.parse("https://silo.test/segment/1.m4s?silo_transport=old&st=a%2Bb"))
    private val policy = SiloMediaLoadErrorHandlingPolicy(nowMs = { 0L })
    private val fallbackOptions = LoadErrorHandlingPolicy.FallbackOptions(2, 0, 2, 0)

    @Test
    fun v2ProblemStopsPlaylistLoadWithoutRetryOrFallback() {
        val refused = failure(404, """{
            "type": "https://siloserver.org/docs/api/v2/problems/playback_transport_superseded",
            "status": 404, "title": "Playback transport superseded"
        }""")
        assertStopsOldPlayer(refused, C.DATA_TYPE_MANIFEST)
    }

    @Test
    fun proxyErrorStopsSegmentLoadWithoutRetryOrFallback() {
        assertStopsOldPlayer(
            failure(404, """{"message":"Localized detail","error":"playback_transport_superseded"}"""),
            C.DATA_TYPE_MEDIA,
        )
    }

    @Test
    fun playbackErrorHeaderCanIdentifySupersededResponseWithoutBody() {
        assertStopsOldPlayer(
            failure(404, "", mapOf("x-silo-playback-error" to listOf("playback_transport_superseded"))),
            C.DATA_TYPE_MANIFEST,
        )
    }

    @Test
    fun sessionPlayerSuppressesOnlyTheSupersededError() {
        val upstream = object : SimpleBasePlayer(Looper.getMainLooper()) {
            private var failure: PlaybackException? = null

            override fun getState(): State = State.Builder()
                .setPlaybackState(Player.STATE_IDLE)
                .setPlayerError(failure)
                .build()

            fun fail(error: PlaybackException) {
                failure = error
                invalidateState()
            }
        }
        val sessionPlayer = SupersededTransportFilteringPlayer(upstream)
        val errors = mutableListOf<PlaybackException>()
        sessionPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errors += error
            }
        })
        assertNull(sessionPlayer.playerError)

        val superseded = playerError(openFailure(failure(404, """{"error":"playback_transport_superseded"}""")))
        upstream.fail(superseded)
        assertSame(superseded, upstream.playerError)
        assertEquals(Player.STATE_IDLE, sessionPlayer.playbackState)
        assertNull(sessionPlayer.playerError)
        assertTrue(errors.isEmpty())

        val unavailable = playerError(failure(503, """{"error":"dependency_unavailable"}"""))
        upstream.fail(unavailable)
        assertSame(unavailable, sessionPlayer.playerError)
        assertEquals(listOf(unavailable), errors)
    }

    @Test
    fun relativePlaylistAndSegmentKeepTheServerIssuedMarker() {
        val initial = resolveRoutedDataSourceUrl(
            "https://silo.test", "/api/v2/playback/transcode/session/master.m3u8?silo_transport=opaque%2Bmarker&st=ref",
        )
        val rendition = UriUtil.resolve(initial, "master.m3u8?silo_rendition=video&silo_transport=opaque%2Bmarker&st=ref")
        val segment = UriUtil.resolve(rendition, "segment/1.ts?silo_transport=opaque%2Bmarker&st=ref")
        assertEquals(
            "https://silo.test/api/v2/playback/transcode/session/segment/1.ts?silo_transport=opaque%2Bmarker&st=ref",
            resolveRoutedDataSourceUrl("https://silo.test", segment),
        )
    }

    @Test
    fun unrelatedErrorsKeepTheirRetryAndFallbackBehavior() {
        val cases = listOf(
            failure(404, "<html>404 playback_transport_superseded</html>") to 1_000L,
            failure(404, """{"error":{"code":"playback_transport_superseded"}}""") to 1_000L,
            failure(404, """{"detail":"playback_transport_superseded"}""") to 1_000L,
            failure(404, """{"type":"https://siloserver.org/docs/api/v2/problems/not_found"}""") to 1_000L,
            failure(403, """{"error":"playback_transport_superseded"}""") to C.TIME_UNSET,
            failure(410, """{"error":"playback_session_ended"}""") to C.TIME_UNSET,
            failure(503, """{"error":"playback_transport_superseded"}""") to 1_000L,
        )
        cases.forEach { (response, delay) ->
            val error = openFailure(response)
            assertSame(response, error)
            assertFalse(playerError(error).isPlaybackTransportSuperseded())
            val load = loadInfo(error, C.DATA_TYPE_MEDIA)
            assertEquals(delay, policy.getRetryDelayMsFor(load))
            val fallback = DefaultLoadErrorHandlingPolicy()
                .getFallbackSelectionFor(fallbackOptions, load)
            assertEquals(
                fallback?.type,
                policy.getFallbackSelectionFor(fallbackOptions, load)?.type,
            )
        }
    }

    private fun assertStopsOldPlayer(response: IOException, dataType: Int) {
        val error = openFailure(response)
        assertSame(response, error.cause)
        val load = loadInfo(error, dataType)
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(load))
        assertNull(policy.getFallbackSelectionFor(fallbackOptions, load))
        assertTrue(playerError(IOException("Source load failed", error)).isPlaybackTransportSuperseded())
    }

    private fun openFailure(response: IOException): IOException {
        val factory = DataSource.Factory {
            object : DataSource by PlaceholderDataSource.INSTANCE {
                override fun open(dataSpec: DataSpec): Long {
                    assertSame(request, dataSpec)
                    throw response
                }
            }
        }
        return assertFailsWith<IOException> {
            factory.withSupersededHlsTransportHandling().createDataSource().open(request)
        }
    }

    private fun loadInfo(error: IOException, dataType: Int) = LoadErrorHandlingPolicy.LoadErrorInfo(
        LoadEventInfo.Builder(1L, request, 0L).build(),
        MediaLoadData(dataType),
        error,
        1,
    )

    private fun playerError(cause: IOException) =
        PlaybackException("Source error", cause, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

    private fun failure(status: Int, body: String, headers: Map<String, List<String>> = emptyMap()) =
        HttpDataSource.InvalidResponseCodeException(status, "", null, headers, request, body.encodeToByteArray())
}
