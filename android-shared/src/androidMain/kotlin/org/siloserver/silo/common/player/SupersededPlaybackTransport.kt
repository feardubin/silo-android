package org.siloserver.silo.common.player

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val SUPERSEDED_CODE = "playback_transport_superseded"
private const val SUPERSEDED_PROBLEM = "https://siloserver.org/docs/api/v2/problems/$SUPERSEDED_CODE"

private class SupersededPlaybackTransportException(cause: IOException) : IOException(SUPERSEDED_CODE, cause)

/** A terminal HLS load from an old plan. Recovery and session teardown belong to the current plan. */
internal fun Throwable.isPlaybackTransportSuperseded(): Boolean {
    var error: Throwable? = this
    repeat(16) {
        val current = error ?: return false
        if (current is SupersededPlaybackTransportException) return true
        error = current.cause
    }
    return false
}

@UnstableApi
internal fun DataSource.Factory.withSupersededHlsTransportHandling() = DataSource.Factory {
    val upstream = createDataSource()
    object : DataSource by upstream {
        override fun open(dataSpec: DataSpec): Long =
            try {
                upstream.open(dataSpec)
            } catch (error: HttpDataSource.InvalidResponseCodeException) {
                if (!error.isSupersededResponse()) throw error
                // Media3 can retry or fall back on a raw 404 before consulting our retry delay.
                throw SupersededPlaybackTransportException(error)
            }
    }
}

@UnstableApi
private fun HttpDataSource.InvalidResponseCodeException.isSupersededResponse(): Boolean {
    if (responseCode != 404) return false
    val hasSupersededHeader = headerFields.any { (name, values) ->
        name.equals("X-Silo-Playback-Error", ignoreCase = true) && SUPERSEDED_CODE in values
    }
    if (hasSupersededHeader) return true
    val body = runCatching { Json.parseToJsonElement(responseBody.decodeToString()) }.getOrNull() as? JsonObject
        ?: return false
    return (body["type"] as? JsonPrimitive)?.content == SUPERSEDED_PROBLEM ||
        (body["error"] as? JsonPrimitive)?.content == SUPERSEDED_CODE
}

/** ExoPlayer has already ended the old source. Controllers only need the resulting idle state. */
@UnstableApi
internal class SupersededTransportFilteringPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {
    override fun getState(): State {
        val state = super.getState()
        return if (state.playerError?.isPlaybackTransportSuperseded() == true) {
            state.buildUpon().setPlayerError(null).build()
        } else {
            state
        }
    }
}
