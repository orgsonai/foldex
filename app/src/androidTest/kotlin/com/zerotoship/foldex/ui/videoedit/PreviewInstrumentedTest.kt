// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.ui.videoedit.engine.AudioWaveforms
import com.zerotoship.foldex.ui.videoedit.engine.CompositionAdapter
import com.zerotoship.foldex.ui.videoedit.engine.PreviewContext
import com.zerotoship.foldex.ui.videoedit.engine.PreviewController
import com.zerotoship.foldex.ui.videoedit.engine.PreviewStatus
import com.zerotoship.foldex.ui.videoedit.model.*
import com.zerotoship.foldex.ui.videoedit.plan.RenderPlanner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class)
class PreviewInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun previewContextDoesNotCreatePlatformDiagnosticsSessions() {
        val wrapped = PreviewContext(context.applicationContext)
        assertSame(wrapped, wrapped.applicationContext)
        assertNull(wrapped.getSystemService("media_metrics"))
        assertNotNull(wrapped.getSystemService("audio"))
    }

    @Test fun clippedAndSpedUpItemsHaveTheSameDurationInPlayerAndExport() {
        val asset = MediaAsset("v", AssetKind.VIDEO, "/video.mp4", "video.mp4", 12_000_000L)
        val project = VideoProject(assets = mapOf(asset.id to asset),
            mainTrack = listOf(MainClip("c", asset.id, 2_000_000L, 8_000_000L, speed = 2.0)))
        val plan = (RenderPlanner.plan(project, ExportSettings()) as Result.Success).value
        val item = CompositionAdapter.build(plan).sequences.single().editedMediaItems.single()
        assertEquals(project.mainDurationUs, item.presentationDurationUs)
    }

    @Test fun decodedWaveformDistinguishesSilenceFromSignalAndKeepsSourceTime() = runBlocking {
        val file = makeWave()
        try {
            val asset = MediaAsset("wave", AssetKind.AUDIO, file.absolutePath, file.name, 2_000_000L, hasAudio = true)
            val waveform = AudioWaveforms.read(context, asset, 0L)
            assertNotNull(waveform)
            assertEquals(0f, waveform!!.peakAt(100_000L)!!, 0.001f)
            assertTrue(waveform.peakAt(1_200_000L)!! > 0.45f)
            assertNull(waveform.peakAt(3_000_000L))
        } finally { file.delete() }
    }

    @Test fun previewClockFollowsPlaybackAndPausesForScrubbing() {
        val file = makeWave()
        var controller: PreviewController? = null
        try {
            val asset = MediaAsset("wave", AssetKind.AUDIO, file.absolutePath, file.name, 2_000_000L, hasAudio = true)
            val project = VideoProject(assets = mapOf(asset.id to asset), audioTrack = listOf(AudioClip("a", asset.id, 0L, 0L, asset.durationUs)))
            instrumentation.runOnMainSync { controller = PreviewController(context); controller!!.update(project, 0L) }
            await { controller!!.status == PreviewStatus.READY || controller!!.status == PreviewStatus.FAILED }
            instrumentation.runOnMainSync { assertEquals(PreviewStatus.READY, controller!!.status); controller!!.togglePlay() }
            await { controller!!.poll() > 200_000L }
            instrumentation.runOnMainSync { controller!!.scrub(true); controller!!.seek(1_000_000L); controller!!.scrub(false) }
            SystemClock.sleep(150L)
            instrumentation.runOnMainSync {
                assertFalse(controller!!.playing)
                assertEquals(1_000_000L, controller!!.poll())
                controller!!.setActive(false)
            }
        } finally {
            instrumentation.runOnMainSync { controller?.release() }
            file.delete()
        }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var success = false
            instrumentation.runOnMainSync { success = predicate() }
            if (success) return
            SystemClock.sleep(50L)
        }
        fail("Preview did not reach the expected state")
    }

    /** 先頭1秒は無音、後半1秒はピーク0.5の440Hz。外部ツールやネットワークなしで再現する。 */
    private fun makeWave(): File {
        val rate = 16_000
        val samples = rate * 2
        val buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
        buffer.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { sample ->
            buffer.putShort(if (sample < rate) 0 else (sin(sample * 2.0 * Math.PI * 440 / rate) * 16384).toInt().toShort())
        }
        return File.createTempFile("preview-test-", ".wav", context.cacheDir).also { it.writeBytes(buffer.array()) }
    }
}
