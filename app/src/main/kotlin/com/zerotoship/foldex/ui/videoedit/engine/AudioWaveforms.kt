// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.engine

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import com.zerotoship.foldex.ui.videoedit.model.MediaAsset
import java.nio.ByteOrder
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** 原本の PCM のピーク。読み込めていない区間は null で、仮の波形は生成しない。 */
internal data class WaveformChunk(val startUs: Long, val peaks: FloatArray) {
    fun peakAt(timeUs: Long): Float? {
        val index = (timeUs - startUs) / AudioWaveforms.BUCKET_US
        return if (timeUs >= startUs && index >= 0L && index < peaks.size) peaks[index.toInt()] else null
    }
}

/** 画面に近い10秒単位を1デコーダで読む。長い音源を丸ごと展開せず、全画面でキャッシュを共有する。 */
internal object AudioWaveforms {
    const val BUCKET_US = 50_000L
    const val CHUNK_US = 10_000_000L
    private const val MAX_DECODE_MS = 8_000L
    private val decoder = Semaphore(1)
    private val cache = LruCache<String, WaveformChunk>(96)

    suspend fun read(context: Context, asset: MediaAsset, chunk: Long): WaveformChunk? = withContext(Dispatchers.IO) {
        val key = "${asset.source}:${asset.stamp}:${asset.durationUs}:$chunk"
        cache.get(key)?.let { return@withContext it }
        decoder.withPermit {
            cache.get(key)?.let { return@withPermit it }
            decode(context, asset, chunk * CHUNK_US)?.also { cache.put(key, it) }
        }
    }

    private suspend fun decode(context: Context, asset: MediaAsset, startUs: Long): WaveformChunk? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (asset.source.startsWith("content://")) extractor.setDataSource(context, Uri.parse(asset.source), null)
            else extractor.setDataSource(asset.source)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(track)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val endUs = minOf(startUs + CHUNK_US, asset.durationUs)
            if (endUs <= startUs) return null
            if (mime == "audio/raw") return readRaw(extractor, format, startUs, endUs)
            val created = MediaCodec.createDecoderByType(mime)
            codec = created
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            created.configure(format, null, null, 0)
            created.start()
            val peaks = FloatArray(((endUs - startUs + BUCKET_US - 1) / BUCKET_US).toInt())
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var sawOutput = false
            val deadline = SystemClock.elapsedRealtime() + MAX_DECODE_MS
            while (!outputEnded && SystemClock.elapsedRealtime() < deadline) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val index = created.dequeueInputBuffer(5_000L)
                    if (index >= 0) {
                        val buffer = created.getInputBuffer(index) ?: return null
                        val timeUs = extractor.sampleTime
                        val size = if (timeUs < 0L || timeUs >= endUs) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            created.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            created.queueInputBuffer(index, 0, size, timeUs, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val index = created.dequeueOutputBuffer(info, 5_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = created.outputFormat
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) out.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            else AudioFormat.ENCODING_PCM_16BIT
                        if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT) return null
                    }
                    else -> if (index >= 0) {
                        val buffer = created.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 && channels > 0 && sampleRate > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            sawOutput = accumulate(buffer, encoding, channels, sampleRate, info.presentationTimeUs, startUs, endUs, peaks) || sawOutput
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        created.releaseOutputBuffer(index, false)
                    }
                }
            }
            // 途中でタイムアウトした波形は「無音」としてキャッシュしない。
            return if (outputEnded && sawOutput) WaveformChunk(startUs, peaks) else null
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }

    private suspend fun readRaw(extractor: MediaExtractor, format: MediaFormat, startUs: Long, endUs: Long): WaveformChunk? {
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            else AudioFormat.ENCODING_PCM_16BIT
        if (rate <= 0 || channels <= 0 || encoding !in listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)) return null
        val peaks = FloatArray(((endUs - startUs + BUCKET_US - 1) / BUCKET_US).toInt())
        val buffer = ByteBuffer.allocateDirect(256 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        val deadline = SystemClock.elapsedRealtime() + MAX_DECODE_MS
        var sawOutput = false
        while (extractor.sampleTime >= 0 && extractor.sampleTime < endUs && SystemClock.elapsedRealtime() < deadline) {
            currentCoroutineContext().ensureActive()
            buffer.clear()
            val time = extractor.sampleTime
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            buffer.position(0); buffer.limit(size)
            sawOutput = accumulate(buffer, encoding, channels, rate, time, startUs, endUs, peaks) || sawOutput
            extractor.advance()
        }
        val complete = extractor.sampleTime < 0 || extractor.sampleTime >= endUs
        return if (complete && sawOutput) WaveformChunk(startUs, peaks) else null
    }

    private fun accumulate(buffer: ByteBuffer, encoding: Int, channels: Int, rate: Int, timeUs: Long,
        startUs: Long, endUs: Long, peaks: FloatArray): Boolean {
        val sampleBytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        var sample = 0L
        var sawOutput = false
        while (buffer.remaining() >= sampleBytes) {
            val amplitude = if (sampleBytes == 4) abs(buffer.float).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
                else abs(buffer.short.toInt()) / 32768f
            val time = timeUs + (sample / channels) * 1_000_000L / rate
            if (time >= startUs && time < endUs) {
                val bucket = ((time - startUs) / BUCKET_US).toInt()
                peaks[bucket] = max(peaks[bucket], amplitude)
                sawOutput = true
            }
            sample++
        }
        return sawOutput
    }
}
