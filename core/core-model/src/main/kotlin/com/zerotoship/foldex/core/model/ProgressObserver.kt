package com.zerotoship.foldex.core.model

fun interface ProgressObserver {
    fun onProgress(bytesTransferred: Long, totalBytes: Long)
}
