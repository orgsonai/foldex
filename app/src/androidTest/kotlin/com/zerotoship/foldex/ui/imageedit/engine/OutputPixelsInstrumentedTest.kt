// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.ui.imageedit.model.EditDocument
import com.zerotoship.foldex.ui.imageedit.model.EditRect
import com.zerotoship.foldex.ui.imageedit.model.FillMode
import com.zerotoship.foldex.ui.imageedit.model.ImageFormat
import com.zerotoship.foldex.ui.imageedit.model.Layer
import com.zerotoship.foldex.ui.imageedit.model.MetadataPolicy
import com.zerotoship.foldex.ui.imageedit.model.SelectionSpec
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Android の Canvas・圧縮器・ExifInterface で出力を読み戻す。JVM テストとは別に実行する。 */
@RunWith(AndroidJUnit4::class)
class OutputPixelsInstrumentedTest {
    private val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "output-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
    private val exports = File(directory, "exports")

    @After fun cleanup() { directory.deleteRecursively() }

    private fun source(noise: Boolean = false): File {
        val file = File(directory, "input.png")
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        try {
            if (noise) {
                val random = java.util.Random(123)
                bitmap.setPixels(IntArray(80 * 40) { random.nextInt() or (0xff shl 24) }, 0, 80, 0, 0, 80, 40)
            } else bitmap.eraseColor(Color.BLUE)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        return file
    }

    private fun document(file: File, format: ImageFormat): Pair<EditDocument, ImageSource> {
        val source = ImageSource(file)
        return EditDocument.ofSingleImage("image", file.name, file.path, source.size, format) to source
    }

    @Test fun jpegBackgroundIsAppliedAfterClearAndPngKeepsItsHole() {
        val file = source()
        for (format in listOf(ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.WEBP)) {
            val (doc, source) = document(file, format)
            val hole = Layer.Fill("hole", "hole", area = SelectionSpec.Rect(EditRect(10f, 10f, 20f, 20f)),
                mode = FillMode.CLEAR, color = 0)
            val edited = doc.copy(layers = doc.layers + hole, output = doc.output.copy(jpegBackground = Color.WHITE))
            val bitmap = requireNotNull(DocumentRenderer.render(edited, { source }).getOrNull())
            try {
                assertEquals(0, Color.alpha(bitmap.getPixel(15, 15)))
                ExportPixels.apply(bitmap, edited.output)
                if (format == ImageFormat.JPEG) {
                    assertEquals(Color.WHITE, bitmap.getPixel(15, 15))
                    assertFalse(bitmap.hasAlpha())
                } else assertEquals(0, Color.alpha(bitmap.getPixel(15, 15)))
                assertEquals(Color.BLUE, bitmap.getPixel(40, 30))
            } finally { bitmap.recycle() }
        }
    }

    @Test fun halfTransparentRedBlendsWithWhiteWithoutBlackFringes() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixel(0, 0, 0x80ff0000.toInt())
            val (doc, _) = document(source(), ImageFormat.JPEG)
            ExportPixels.apply(bitmap, doc.output)
            val pixel = bitmap.getPixel(0, 0)
            assertEquals(255, Color.alpha(pixel))
            assertEquals(255, Color.red(pixel))
            assertTrue(Color.green(pixel) in 127..128)
            assertTrue(Color.blue(pixel) in 127..128)
        } finally { bitmap.recycle() }
    }

    @Test fun wideGamutBitmapIsConvertedIntoSrgbPixels() {
        val wide = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
        wide.eraseColor(Color.RED)
        val srgb = BitmapOps.toSrgb(wide)
        try {
            assertTrue(wide.isRecycled)
            assertTrue(srgb.colorSpace?.isSrgb == true)
            assertEquals(Bitmap.Config.ARGB_8888, srgb.config)
            assertEquals(255, Color.alpha(srgb.getPixel(0, 0)))
        } finally { srgb.recycle() }
    }

    @Test fun metadataPoliciesDimensionsAndOrientationSurviveEveryOutputFormat() {
        val file = source()
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ARTIST, "Foldex test")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:09:13 10:00:00")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "35/1,0/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, "180/1")
            setAttribute(ExifInterface.TAG_ORIENTATION, "6")
            saveAttributes()
        }
        val original = FileFingerprint.read(file)
        for (format in ImageFormat.entries) for (policy in MetadataPolicy.entries) {
            val (base, source) = document(file, format)
            val doc = base.withOutputLongEdge(40).let { it.copy(output = it.output.copy(metadata = policy)) }
            val result = ImageExportCoordinator(exports).prepare(doc, { source }, file, original) {}
            assertTrue(result.toString(), result is Result.Success)
            requireNotNull(result.getOrNull()).use { output ->
                assertFalse(output.metadataWarning)
                val metadata = ExifInterface(output.file.path)
                assertEquals(1, metadata.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
                assertEquals(20, metadata.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0))
                assertEquals(40, metadata.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0))
                if (policy == MetadataPolicy.KEEP_CAPTURE) assertNotNull(metadata.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
                else for (tag in ExifTransfer.LOCATION_TAGS) assertNull(metadata.getAttribute(tag))
                assertEquals(if (policy == MetadataPolicy.SHARE) null else "Foldex test", metadata.getAttribute(ExifInterface.TAG_ARTIST))
                assertEquals(output.file.length(), output.fingerprint.size)
            }
            assertEquals(original, FileFingerprint.read(file))
        }
    }

    @Test fun targetIncludesMetadataAndOversizedOutputNeverReachesTheDestination() {
        val file = source()
        ExifInterface(file.path).apply { setAttribute(ExifInterface.TAG_ARTIST, "x".repeat(5000)); saveAttributes() }
        val original = FileFingerprint.read(file)
        val (base, source) = document(file, ImageFormat.JPEG)
        val doc = base.copy(output = base.output.copy(targetBytes = 1024))
        val result = ImageExportCoordinator(exports).prepare(doc, { source }, file, original) {} as Result.Failure
        assertEquals(ExportFailure.TARGET_TOO_SMALL, result.error.reason)
        assertTrue(requireNotNull(result.error.actualBytes) > 1024)
        assertNotNull(result.error.suggestedLongEdge)
        assertEquals(original, FileFingerprint.read(file))
        assertTrue(exports.listFiles().orEmpty().isEmpty())
    }

    @Test fun pngOffersSmallerDimensionsWhenQualityCannotReachTheTarget() {
        val file = source(noise = true)
        val original = FileFingerprint.read(file)
        val (base, source) = document(file, ImageFormat.PNG)
        val doc = base.copy(output = base.output.copy(metadata = MetadataPolicy.SHARE, targetBytes = 1024))
        val result = ImageExportCoordinator(exports).prepare(doc, { source }, file, original) {} as Result.Failure
        assertEquals(ExportFailure.TARGET_TOO_SMALL, result.error.reason)
        assertTrue(requireNotNull(result.error.suggestedLongEdge) < doc.outputSize.longEdge)
        assertEquals(original, FileFingerprint.read(file))
        assertTrue(exports.listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellationAfterEncodingRemovesTheTemporaryOutput() {
        val file = source()
        val original = FileFingerprint.read(file)
        val (doc, source) = document(file, ImageFormat.JPEG)
        assertThrows(CancellationException::class.java) {
            ImageExportCoordinator(exports).prepare(doc, { source }, file, original) {
                if (exports.listFiles().orEmpty().any { it.length() > 0 }) throw CancellationException()
            }
        }
        assertTrue(exports.listFiles().orEmpty().isEmpty())
        assertEquals(original, FileFingerprint.read(file))
    }
}
