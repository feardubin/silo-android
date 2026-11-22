package org.siloserver.silo.common.player

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.AudioProfileBuilder
import org.robolectric.shadows.ShadowBuild
import org.siloserver.silo.common.player.audio.TvSurroundOutputPolicy
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.LAYOUT_AWARE_PASSTHROUGH_FEATURE
import org.siloserver.silo.model.playback.PlaybackOutputContext
import org.siloserver.silo.model.playback.playbackClientFeaturesV3
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28])
class HdmiAudioSinkReportTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun pluggedIntent(
        channels: Int = 6,
        vararg encodings: Int = intArrayOf(AudioFormat.ENCODING_AC3),
    ) = Intent(AudioManager.ACTION_HDMI_AUDIO_PLUG)
        .putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 1)
        .putExtra(AudioManager.EXTRA_ENCODINGS, encodings)
        .putExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, channels)

    @Test
    fun reportRequiresPluggedHdmiWithExplicitEncodingsAndChannelCeiling() {
        assertEquals(HdmiAudioSinkReport(setOf(AudioFormat.ENCODING_AC3), 6), hdmiAudioSinkReport(pluggedIntent()))
        assertNull(hdmiAudioSinkReport(null))
        assertNull(hdmiAudioSinkReport(pluggedIntent().putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0)))
        assertNull(hdmiAudioSinkReport(pluggedIntent().apply { removeExtra(AudioManager.EXTRA_ENCODINGS) }))
        assertNull(hdmiAudioSinkReport(pluggedIntent().apply { removeExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT) }))
        assertNull(hdmiAudioSinkReport(pluggedIntent(0)))
    }

    @Test
    fun forcedSurroundWithoutSinkEarnsNoEntriesAndKeepsMedia3Ceiling() {
        forceSurround()
        val manager = AudioCapabilityManager(context)
        assertTrue("ac3" in manager.capabilities.value.passthroughCodecs)
        assertEntriesAndFeature(manager, expected = false)
        assertEquals(
            AudioCapabilities.getCapabilities(context).maxChannelCount.coerceAtLeast(2),
            manager.capabilities.value.maxChannels,
        )
    }

    @Test
    fun hdmiChangesRefreshEvenWhenMedia3ForcedCapabilitiesAreUnchanged() {
        forceSurround()
        context.sendStickyBroadcast(pluggedIntent())
        val manager = AudioCapabilityManager(context)
        shadowOf(Looper.getMainLooper()).idle()
        val media3Before = AudioCapabilities.getCapabilities(context)
        assertEquals(6, manager.capabilities.value.maxChannels)
        val generationBefore = manager.outputRouteGeneration.value

        context.sendStickyBroadcast(pluggedIntent(2))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(media3Before, AudioCapabilities.getCapabilities(context))
        assertEquals(2, manager.capabilities.value.maxChannels)
        assertTrue(manager.outputRouteGeneration.value > generationBefore)
    }

    @Test
    fun competingOutputsSuppressHdmiEntriesUntilRemoved() {
        val types = mutableListOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_DOCK,
        )
        // Android 7 has no USB_HEADSET mapping in AudioDeviceInfo.getType().
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) types += AudioDeviceInfo.TYPE_USB_HEADSET
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) types += AudioDeviceInfo.TYPE_HEARING_AID
        for (type in types) {
            context.sendStickyBroadcast(pluggedIntent())
            val shadowAudio = shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            val hdmi = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_HDMI).build()
            val competing = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
            shadowAudio.setOutputDevices(listOf(hdmi, competing))
            val manager = AudioCapabilityManager(context)
            shadowOf(Looper.getMainLooper()).idle()
            assertEntriesAndFeature(manager, expected = false)

            // No HDMI broadcast follows: the pre-Q device callback must refresh.
            shadowAudio.removeOutputDevice(competing, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEntriesAndFeature(manager, expected = true)
        }
    }

    @Test
    fun onlyAbsentOrAutoSurroundPolicyTrustsHdmiEvidence() {
        context.sendStickyBroadcast(pluggedIntent())
        val cases = mapOf(null to true, "0" to true, "1" to false, "2" to false, "3" to false, "invalid" to false)
        for ((value, expected) in cases) {
            // Production Settings stores strings; the check reads them to reject malformed data.
            Settings.Global.putString(context.contentResolver, "encoded_surround_output", value)
            assertEntriesAndFeature(AudioCapabilityManager(context), expected)
        }
    }

    @Test
    fun surroundPolicyChangesRefreshEntriesWithoutAnHdmiChange() {
        context.sendStickyBroadcast(pluggedIntent())
        val manager = AudioCapabilityManager(context)
        shadowOf(Looper.getMainLooper()).idle()
        assertEntriesAndFeature(manager, expected = true)
        Settings.Global.putString(context.contentResolver, "encoded_surround_output", "2")
        shadowOf(Looper.getMainLooper()).idle()
        assertEntriesAndFeature(manager, expected = false)
    }

    @Test
    fun tvNeverSurroundWithdrawsAllPassthroughDespiteTheHdmiReport() {
        setTvMode()
        context.sendStickyBroadcast(
            pluggedIntent(6, AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_E_AC3, AudioFormat.ENCODING_DTS),
        )
        val manager = AudioCapabilityManager(context)
        shadowOf(Looper.getMainLooper()).idle()
        val before = manager.capabilities.value
        val codecs = setOf("ac3", "eac3", "dts")
        assertEquals(codecs, before.passthroughCodecs.toSet())
        assertEquals(codecs, before.entries.map { it.codec }.toSet())
        assertEntriesAndFeature(manager, expected = true)
        val platformCaps = AudioCapabilities.getCapabilities(context)

        setSurroundMode("1")
        val after = manager.capabilities.value
        assertEquals(platformCaps, AudioCapabilities.getCapabilities(context))
        assertTrue(after.passthroughCodecs.isEmpty())
        assertTrue(after.entries.isEmpty())
        assertEntriesAndFeature(manager, expected = false)
        assertEquals(before.maxChannels, after.maxChannels)

        setSurroundMode("0")
        assertEquals(before, manager.capabilities.value)
    }

    @Test
    @Config(sdk = [34])
    fun tvNeverSurroundWithdrawsUnchangedEncodedDirectProfiles() {
        setTvMode()
        val encodings = listOf(AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_E_AC3, AudioFormat.ENCODING_DTS)
        val profiles = encodings.map { encoding ->
            AudioProfileBuilder.newBuilder()
                .setFormat(encoding)
                .setSamplingRates(intArrayOf(48_000))
                .setChannelMasks(intArrayOf(AudioFormat.CHANNEL_OUT_5POINT1))
                .build()
        }
        val hdmi = AudioDeviceInfoBuilder.newBuilder()
            .setType(AudioDeviceInfo.TYPE_HDMI)
            .setProfiles(profiles)
            .build()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadowOf(audioManager).addOutputDeviceWithDirectProfiles(hdmi)
        val manager = AudioCapabilityManager(context)
        shadowOf(Looper.getMainLooper()).idle()
        val platformCaps = AudioCapabilities.getCapabilities(context)
        assertTrue(encodings.all(platformCaps::supportsEncoding))
        assertEquals(setOf("ac3", "eac3", "dts"), manager.capabilities.value.passthroughCodecs.toSet())

        setSurroundMode("1")

        assertEquals(platformCaps, AudioCapabilities.getCapabilities(context))
        assertTrue(manager.capabilities.value.passthroughCodecs.isEmpty())
        assertTrue(manager.capabilities.value.entries.isEmpty())
    }

    @Test
    @Config(sdk = [34])
    fun togglingTvNeverSurroundPublishesBothRouteChangesWithIdenticalCapabilities() {
        setTvMode()
        val manager = AudioCapabilityManager(context)
        shadowOf(Looper.getMainLooper()).idle()
        val platformCaps = AudioCapabilities.getCapabilities(context)
        val auto = manager.playbackRouteSnapshot()
        assertTrue(auto.capabilities.passthroughCodecs.isEmpty())
        assertTrue(auto.capabilities.entries.isEmpty())

        setSurroundMode("1")
        val never = manager.playbackRouteSnapshot()
        assertEquals(platformCaps, AudioCapabilities.getCapabilities(context))
        assertEquals(auto.capabilities, never.capabilities)
        assertTrue(never.routeGeneration > auto.routeGeneration)
        assertEquals(never.routeGeneration, manager.outputRouteGeneration.value)

        setSurroundMode("2")
        val always = manager.playbackRouteSnapshot()
        assertEquals(platformCaps, AudioCapabilities.getCapabilities(context))
        assertEquals(auto.capabilities, always.capabilities)
        assertTrue(always.routeGeneration > never.routeGeneration)
        assertEquals(always.routeGeneration, manager.outputRouteGeneration.value)

        setSurroundMode("3")
        assertEquals(always, manager.playbackRouteSnapshot())
    }

    @Test
    fun amazonAndXiaomiExternalSurroundSettingDoesNotWithdrawHdmiEvidence() {
        context.sendStickyBroadcast(pluggedIntent())
        // A Fire TV Cube on Surround Sound "Best Available" reports 0.
        val cases = mapOf(null to true, "1" to true, "0" to true)
        for ((value, expected) in cases) {
            Settings.Global.putString(context.contentResolver, "external_surround_sound_enabled", value)
            for (manufacturer in listOf("Amazon", "Xiaomi")) {
                ShadowBuild.setManufacturer(manufacturer)
                assertEntriesAndFeature(AudioCapabilityManager(context), expected)
            }
        }
    }

    @Test
    fun amazonSurroundSettingCannotAddEntriesForFormatsMissingFromTheHdmiReport() {
        Settings.Global.putString(context.contentResolver, "external_surround_sound_enabled", "1")
        ShadowBuild.setManufacturer("Amazon")
        val caps = capabilitiesForSink(6, AudioFormat.ENCODING_DTS)
        assertEquals(setOf("dts"), caps.entries.map { it.codec }.toSet())
    }

    @Test
    fun onlyTrueHdAndDtsHdMayUseAnEightChannelReport() {
        val caps = capabilitiesForSink(
            8,
            AudioFormat.ENCODING_PCM_16BIT,
            AudioFormat.ENCODING_AC3,
            AudioFormat.ENCODING_E_AC3,
            AudioFormat.ENCODING_DTS,
            AudioFormat.ENCODING_DOLBY_TRUEHD,
        )
        // The aggregate ceiling still bounds decoded audio at the sink's PCM limit.
        assertEquals(8, caps.maxChannels)
        for (codec in listOf("ac3", "eac3", "dts")) {
            val entry = caps.entries.first { it.codec == codec }
            assertEquals(listOf(2, 6), entry.channelCounts)
            assertFalse("7.1" in entry.layouts)
        }
        if (Build.VERSION.SDK_INT >= 25) {
            val truehd = caps.entries.first { it.codec == "truehd" }
            assertEquals(listOf(2, 6, 8), truehd.channelCounts)
            assertTrue("7.1" in truehd.layouts)
        }
    }

    @Test
    fun dtsHdOnlyReportEarnsDtsFamilyCodecAndEntry() {
        val caps = capabilitiesForSink(8, AudioFormat.ENCODING_DTS_HD)
        assertEquals(setOf("dts", "dts_hd"), caps.passthroughCodecs.toSet())
        assertEquals(setOf("dts", "dts_hd"), caps.entries.map { it.codec }.toSet())
        caps.entries.forEach {
            assertEquals(listOf(2, 6, 8), it.channelCounts)
            assertTrue("7.1" in it.layouts)
        }
    }

    @Test
    fun untrustedDtsHdOnlyReportDoesNotEarnTheDtsFamilyCodec() {
        val shadowAudio = shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        shadowAudio.setOutputDevices(
            listOf(
                AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_HDMI).build(),
                AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_USB_DEVICE).build(),
            ),
        )
        val caps = capabilitiesForSink(8, AudioFormat.ENCODING_DTS_HD)
        assertFalse("dts" in caps.passthroughCodecs)
        assertTrue(caps.entries.isEmpty())
    }

    private fun setTvMode() {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        shadowOf(uiModeManager).setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
    }

    private fun setSurroundMode(value: String) {
        Settings.Global.putString(context.contentResolver, TvSurroundOutputPolicy.SURROUND_SETTING, value)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun capabilitiesForSink(channels: Int, vararg encodings: Int) =
        context.let {
            it.sendStickyBroadcast(pluggedIntent(channels, *encodings))
            AudioCapabilityManager(it).capabilities.value
        }

    private fun assertEntriesAndFeature(manager: AudioCapabilityManager, expected: Boolean) {
        val caps = manager.capabilities.value
        assertEquals(expected, caps.entries.isNotEmpty())
        val features = playbackClientFeaturesV3(
            ClientPlaybackContext(
                formFactor = "tv",
                appVersion = "test",
                output = PlaybackOutputContext(audioPassthrough = caps),
            ),
        )
        assertEquals(expected, LAYOUT_AWARE_PASSTHROUGH_FEATURE in features)
    }

    private fun forceSurround() {
        Settings.Global.putInt(context.contentResolver, "use_external_surround_sound_flag", 1)
        Settings.Global.putInt(context.contentResolver, "external_surround_sound_enabled", 1)
    }
}
