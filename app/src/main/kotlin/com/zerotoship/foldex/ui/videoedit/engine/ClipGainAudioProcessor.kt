// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.zerotoship.foldex.ui.videoedit.model.US_PER_SECOND
import com.zerotoship.foldex.ui.videoedit.plan.GainCurve
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * クリップの音量とフェードを 16bit PCM に掛ける。
 *
 * Media3 の GainProcessor は 0〜1 倍までなので、200% まで上げられる音量のために自前で持つ。
 * Transformer は速度変更を素材の読み込み段階で行うため、ここに届く音の時刻は速度変更の後 (タイムライン上の時刻)。
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class ClipGainAudioProcessor(private val curve: GainCurve) : BaseAudioProcessor() {

    private var framesRead = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val frames = inputBuffer.remaining() / format.bytesPerFrame
        if (frames == 0) return
        val output = replaceOutputBuffer(frames * format.bytesPerFrame)
        val order = inputBuffer.order()
        inputBuffer.order(ByteOrder.nativeOrder())
        repeat(frames) {
            val gain = curve.gainAt(framesRead * US_PER_SECOND / format.sampleRate)
            repeat(format.channelCount) {
                val scaled = (inputBuffer.getShort() * gain).roundToInt()
                output.putShort(scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
            framesRead++
        }
        inputBuffer.order(order)
        output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val sampleRate = inputAudioFormat.sampleRate
        framesRead = if (sampleRate > 0) streamMetadata.positionOffsetUs * sampleRate / US_PER_SECOND else 0L
    }

    override fun onReset() {
        framesRead = 0L
    }
}
