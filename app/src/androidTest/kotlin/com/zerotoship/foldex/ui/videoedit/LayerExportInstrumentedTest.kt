// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.ui.videoedit.engine.CompositionAdapter
import com.zerotoship.foldex.ui.videoedit.engine.TransformerRunner
import com.zerotoship.foldex.ui.videoedit.model.*
import com.zerotoship.foldex.ui.videoedit.plan.RenderPlanner
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 実際に符号化→再読込し、合成順・不透明度・表示区間・分割の画素を調べる。素材は自作の色面と正弦波。 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class LayerExportInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun videoAndImageLayersRespectTimeOrderOpacityAndPlacement() = withFixture { folder, base, overlay ->
        val imageFile = File(folder, "yellow.png")
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.YELLOW)
        imageFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val image = MediaAsset("image", AssetKind.IMAGE, imageFile.path, imageFile.name, 0, 80, 80)
        val p = VideoProject(
            assets = listOf(base, overlay, image).associateBy { it.id },
            mainTrack = listOf(MainClip("main", base.id, 0, 4_000_000L)),
            layers = listOf(
                LayerClip(MainClip("video", overlay.id, 0, 2_000_000L, muted = true), 1_000_000L,
                    centerX = 0.25f, widthFraction = 0.4f, opacity = 0.5f),
                LayerClip(MainClip("image", image.id, 0, 1_000_000L), 1_500_000L,
                    centerX = 0.25f, widthFraction = 0.15f),
            ),
        )
        val output = export(p, folder, "layers.mp4")
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(output.path)
            assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() >= 3_900)
            assertColor(retriever, 500_000, 0.25f, Color.BLUE)
            assertColor(retriever, 1_250_000, 0.25f, Color.rgb(127, 0, 127))
            assertColor(retriever, 1_750_000, 0.25f, Color.YELLOW)
            assertColor(retriever, 2_250_000, 0.25f, Color.YELLOW)
            assertColor(retriever, 2_750_000, 0.25f, Color.rgb(0, 127, 127))
            assertColor(retriever, 3_500_000, 0.25f, Color.BLUE)
            assertColor(retriever, 1_750_000, 0.8f, Color.BLUE)
        }
    }

    @Test
    fun splitLayerExportsDifferentSourceRangesWithoutRepeatingTheFirstHalf() = withFixture { folder, base, overlay ->
        val p = VideoProject(
            assets = listOf(base, overlay).associateBy { it.id },
            mainTrack = listOf(MainClip("main", base.id, 0, 4_000_000L)),
            layers = listOf(LayerClip(MainClip("layer", overlay.id, 0, 2_000_000L), 1_000_000L)),
        )
        val split = LayerEditOps.split(p, "layer", 2_000_000L, "second")
        val output = export(split, folder, "split.mp4")
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(output.path)
            assertColor(retriever, 1_500_000, 0.5f, Color.RED)
            assertColor(retriever, 2_500_000, 0.5f, Color.GREEN)
            assertColor(retriever, 3_500_000, 0.5f, Color.BLUE)
        }
    }

    private fun export(project: VideoProject, folder: File, name: String): File {
        val planned = RenderPlanner.plan(project, ExportSettings()) as Result.Success
        val plan = planned.value.copy(output = planned.value.output.copy(width = 320, height = 180, videoKbps = 1_000))
        val file = File(folder, name)
        val outcome = runBlocking {
            withTimeout(120_000L) {
                TransformerRunner(context).run(CompositionAdapter.build(plan), file.path, plan.output, 1_000) {}
            }
        }
        assertEquals(TransformerRunner.Outcome.Completed, outcome)
        return file
    }

    private fun assertColor(retriever: MediaMetadataRetriever, timeUs: Long, x: Float, expected: Int) {
        val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
        try {
            val pixel = frame.getPixel((frame.width * x).toInt(), frame.height / 2)
            for (channel in listOf<(Int) -> Int>(Color::red, Color::green, Color::blue)) {
                assertEquals("time=$timeUs x=$x actual=${Integer.toHexString(pixel)}", channel(expected).toFloat(), channel(pixel).toFloat(), 38f)
            }
        } finally { frame.recycle() }
    }

    private fun withFixture(test: (File, MediaAsset, MediaAsset) -> Unit) {
        val folder = File(context.cacheDir, "layer-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            fun copy(name: String): File = File(folder, name).also { file ->
                instrumentation.context.assets.open("videoedit/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
            }
            val base = copy("main-blue.mp4")
            val overlay = copy("overlay-red-green.mp4")
            test(folder, MediaAsset("base", AssetKind.VIDEO, base.path, base.name, 4_000_000L, 320, 180, true),
                MediaAsset("overlay", AssetKind.VIDEO, overlay.path, overlay.name, 2_000_000L, 160, 160, true))
        } finally { folder.deleteRecursively() }
    }
}
