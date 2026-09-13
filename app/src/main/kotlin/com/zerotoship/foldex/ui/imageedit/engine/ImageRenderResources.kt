// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.engine

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Debug
import com.zerotoship.foldex.ui.imageedit.model.EditDocument
import java.lang.ref.WeakReference

/** 重い処理はこの入口を通す。枠待ちの後に空きメモリを読み直す。 */
internal object ImageRenderResources {
    private val gate = ImageWorkGate()
    private data class PreviewMemory(val bitmap: WeakReference<Bitmap>, val bytes: Long)
    private val previews = mutableListOf<PreviewMemory>()

    suspend fun <T> run(
        context: Context,
        doc: EditDocument,
        work: ImageWork,
        block: (targetLongEdge: Int?) -> T,
    ): ImageWorkResult<T> = gate.run {
        val available = snapshot(context).availableForWork(previewBytes())
        when (val plan = RenderBudget.plan(doc, work, available)) {
            is RenderAdmission.Rejected -> ImageWorkResult.Rejected(plan.suggestedLongEdge)
            is RenderAdmission.Allowed -> ImageWorkResult.Completed(block(plan.targetLongEdge))
        }
    }

    /** UI に渡す前、枠を保持したまま登録。表示済み Bitmap は手動 recycle しない。 */
    fun retainPreview(bitmap: Bitmap) {
        previews.add(PreviewMemory(WeakReference(bitmap), bitmap.allocationByteCount.toLong()))
    }

    private fun previewBytes(): Long {
        var total = 0L
        val iterator = previews.iterator()
        while (iterator.hasNext()) {
            val preview = iterator.next()
            val bitmap = preview.bitmap.get()
            if (bitmap == null || bitmap.isRecycled) iterator.remove()
            else total += preview.bytes
        }
        return total
    }

    private fun snapshot(context: Context): ImageMemorySnapshot {
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        return ImageMemorySnapshot(
            memoryClassBytes = manager.memoryClass.toLong() * RenderBudget.MIB,
            managedFreeBytes = runtime.maxMemory() - used,
            managedUsedBytes = used,
            nativeUsedBytes = Debug.getNativeHeapAllocatedSize(),
            systemAvailableBytes = memory.availMem,
            systemThresholdBytes = memory.threshold,
            lowRam = manager.isLowRamDevice,
            lowMemory = memory.lowMemory,
        )
    }
}

internal sealed interface ImageWorkResult<out T> {
    data class Completed<T>(val value: T) : ImageWorkResult<T>
    data class Rejected(val suggestedLongEdge: Int?) : ImageWorkResult<Nothing>
}
