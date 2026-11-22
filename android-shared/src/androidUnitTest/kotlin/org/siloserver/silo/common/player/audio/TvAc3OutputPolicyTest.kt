package org.siloserver.silo.common.player.audio

import android.content.Context
import android.provider.Settings
import androidx.media3.common.MimeTypes
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TvAc3OutputPolicyTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val tvPolicy get() = TvAc3OutputPolicy(context, isTv = true)

    @Test
    fun onlyExplicitNeverSurroundRequestsAc3PcmOnTv() {
        val policy = tvPolicy
        for (setting in listOf(null, "0", "1", "2", "3", "invalid")) {
            Settings.Global.putString(context.contentResolver, TvAc3OutputPolicy.SURROUND_SETTING, setting)
            assertEquals(setting == "1", policy.requiresPcm(MimeTypes.AUDIO_AC3), "setting=$setting")
        }

        Settings.Global.putString(context.contentResolver, TvAc3OutputPolicy.SURROUND_SETTING, "1")
        assertFalse(TvAc3OutputPolicy(context, isTv = false).requiresPcm(MimeTypes.AUDIO_AC3))
        assertFalse(policy.requiresPcm(MimeTypes.AUDIO_E_AC3))
        assertFalse(policy.requiresPcm(MimeTypes.AUDIO_RAW))
    }

    @Test
    fun softwarePreferenceRequiresRuntimeAc3Support() {
        Settings.Global.putString(context.contentResolver, TvAc3OutputPolicy.SURROUND_SETTING, "1")
        val policy = tvPolicy
        assertTrue(policy.requiresPcm(MimeTypes.AUDIO_AC3))
        assertFalse(policy.shouldDecodeWithFfmpeg(MimeTypes.AUDIO_AC3) { false })
        assertTrue(policy.shouldDecodeWithFfmpeg(MimeTypes.AUDIO_AC3) { it == MimeTypes.AUDIO_AC3 })
        assertFalse(policy.shouldDecodeWithFfmpeg(MimeTypes.AUDIO_E_AC3) { true })
    }
}
