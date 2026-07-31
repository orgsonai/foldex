// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.WriteMode
import com.zerotoship.foldex.storage.StorageProviderRouter
import com.zerotoship.foldex.ui.imageedit.engine.DocumentRenderer
import com.zerotoship.foldex.ui.imageedit.engine.ExifTransfer
import com.zerotoship.foldex.ui.imageedit.engine.ImageEncoder
import com.zerotoship.foldex.ui.imageedit.engine.ImageSource
import com.zerotoship.foldex.ui.imageedit.model.EditDocument
import com.zerotoship.foldex.ui.imageedit.model.EditHistory
import com.zerotoship.foldex.ui.imageedit.model.EditRect
import com.zerotoship.foldex.ui.imageedit.model.EditSize
import com.zerotoship.foldex.ui.imageedit.model.ImageFormat
import com.zerotoship.foldex.ui.imageedit.model.Layer
import com.zerotoship.foldex.ui.imageedit.model.SaveNaming
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 画像エディタの状態と操作。
 *
 * 編集の実体は [EditDocument] (画素を持たない値オブジェクト) の差し替えだけで、
 * 元画像には一切書き込まない。表示用のプレビューはドキュメントが変わるたびに
 * [DocumentRenderer] で作り直し、保存時だけ同じ描画を原寸で通す。
 */
@HiltViewModel
class ImageEditViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val storage: StorageProviderRouter,
) : ViewModel() {

    private val _state = MutableStateFlow(ImageEditUiState())
    val state: StateFlow<ImageEditUiState> = _state.asStateFlow()

    private val _events = Channel<ImageEditEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val history = EditHistory()

    /** 元ファイル (ローカル実体、またはリモート/SAF のキャッシュ実体)。 */
    private var sourceFile: File? = null

    /** リモート / SAF から開いた場合の元 URI。ローカル直編集なら null。 */
    private var sourceUri: FileUri? = null

    private var imageSource: ImageSource? = null
    private var previewJob: Job? = null
    private var estimateJob: Job? = null

    // ---- 読み込み ----

    fun load(file: File, displayName: String, sourceUriString: String?) {
        if (sourceFile != null) return // 再入防止 (Composable の再構成で二重に呼ばれても無視)
        sourceFile = file
        sourceUri = sourceUriString?.let { FileUri.fromStorageStringOrNull(it) }
        _state.update { it.copy(loading = true, fileName = displayName) }

        viewModelScope.launch {
            val prepared = withContext(Dispatchers.Default) {
                val src = ImageSource(file)
                val size = src.size
                if (size.width <= 1 && size.height <= 1) return@withContext null
                val base = EditDocument.ofSingleImage(
                    layerId = LAYER_ID,
                    name = displayName,
                    sourceKey = file.absolutePath,
                    sourceSize = size,
                    format = ImageFormat.fromFileName(displayName),
                )
                // 出力の上限を超える画像は、出力倍率を下げた状態で開く。
                // (論理キャンバスは元の画素数のまま = 情報を捨てない)
                val doc = if (size.longEdge > MAX_OUTPUT_LONG_EDGE) {
                    base.withOutputLongEdge(MAX_OUTPUT_LONG_EDGE)
                } else {
                    base
                }
                src to doc
            }
            if (prepared == null) {
                _state.update { it.copy(loading = false, error = "この画像を開けませんでした") }
                return@launch
            }
            val (src, doc) = prepared
            imageSource = src
            val hasLocation = withContext(Dispatchers.IO) { ExifTransfer.hasLocation(file) }
            _state.update {
                it.copy(
                    loading = false,
                    document = doc,
                    originalBytes = file.length(),
                    hasLocation = hasLocation,
                    // SAF は開いた document URI から親フォルダを辿れないため別名保存できない。
                    canSaveAs = sourceUri !is FileUri.Saf,
                )
            }
            if (doc.output.scale < 1f) {
                _events.trySend(
                    ImageEditEvent.Message(
                        "大きい画像なので、保存サイズを長辺 ${MAX_OUTPUT_LONG_EDGE}px に抑えて開きました",
                    ),
                )
            }
            refreshPreview()
        }
    }

    // ---- 編集操作 ----

    fun rotateRight() = mutateImage { it.rotatedRight() }

    fun rotateLeft() = mutateImage { it.rotatedLeft() }

    fun flipHorizontal() = mutateImage { it.copy(flipH = !it.flipH) }

    fun flipVertical() = mutateImage { it.copy(flipV = !it.flipV) }

    /** 切り抜きを確定する。[rect] は現在の論理キャンバス座標 (見たままの向き)。 */
    fun applyCrop(rect: EditRect) {
        val doc = _state.value.document ?: return
        val layer = doc.activeLayer as? Layer.Image ?: return
        val sourceRect = canvasRectToSourceRect(layer, rect) ?: return
        val cropped = layer.withCrop(sourceRect)
        history.record(doc)
        applyDocument(
            doc.updateLayer(layer.id) { cropped }.withCanvasFitting(cropped.logicalSize),
        )
    }

    /** 切り抜きを解除して元の範囲に戻す。 */
    fun resetCrop() {
        val doc = _state.value.document ?: return
        val layer = doc.activeLayer as? Layer.Image ?: return
        if (layer.crop == null) return
        val restored = layer.withoutCrop()
        history.record(doc)
        applyDocument(
            doc.updateLayer(layer.id) { restored }.withCanvasFitting(restored.logicalSize),
        )
    }

    /** 出力の長辺を [longEdge] px にする (解像度の変更)。 */
    fun setOutputLongEdge(longEdge: Int) {
        val doc = _state.value.document ?: return
        val max = maxOf(doc.canvas.width, doc.canvas.height)
        val clamped = longEdge.coerceIn(MIN_OUTPUT_LONG_EDGE, minOf(max, MAX_OUTPUT_LONG_EDGE))
        if (clamped == doc.outputSize.longEdge) return
        history.record(doc)
        applyDocument(doc.withOutputLongEdge(clamped))
    }

    /** 出力を元の [percent] % にする。 */
    fun setOutputPercent(percent: Int) {
        val doc = _state.value.document ?: return
        val max = maxOf(doc.canvas.width, doc.canvas.height)
        setOutputLongEdge((max * percent / 100f).toInt())
    }

    fun setFormat(format: ImageFormat) {
        val doc = _state.value.document ?: return
        if (doc.output.format == format) return
        applyDocument(doc.copy(output = doc.output.copy(format = format)), recordHistory = false)
    }

    fun setQuality(quality: Int) {
        val doc = _state.value.document ?: return
        val q = quality.coerceIn(1, 100)
        if (doc.output.quality == q) return
        applyDocument(doc.copy(output = doc.output.copy(quality = q)), recordHistory = false)
    }

    fun undo() {
        val current = _state.value.document ?: return
        val previous = history.undo(current) ?: return
        applyDocument(previous, recordHistory = false)
    }

    fun redo() {
        val current = _state.value.document ?: return
        val next = history.redo(current) ?: return
        applyDocument(next, recordHistory = false)
    }

    fun selectTool(tool: EditTool?) {
        _state.update { it.copy(activeTool = if (it.activeTool == tool) null else tool) }
        if (_state.value.activeTool == EditTool.RESIZE) requestEstimate()
    }

    private fun mutateImage(block: (Layer.Image) -> Layer.Image) {
        val doc = _state.value.document ?: return
        history.record(doc)
        val updated = doc.updateActiveImage(block)
        val layer = updated.activeLayer as? Layer.Image
        applyDocument(if (layer != null) updated.withCanvasFitting(layer.logicalSize) else updated)
    }

    private fun applyDocument(doc: EditDocument, recordHistory: Boolean = true) {
        _state.update {
            it.copy(
                document = doc,
                dirty = true,
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                // ドキュメントが変わったら前回の実測値は無効。
                estimatedBytes = null,
            )
        }
        refreshPreview()
        if (recordHistory || _state.value.activeTool == EditTool.RESIZE) requestEstimate()
    }

    // ---- プレビュー ----

    private fun refreshPreview() {
        val doc = _state.value.document ?: return
        val resolve = sourceResolver()
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                DocumentRenderer.render(doc, resolve, targetLongEdge = PREVIEW_LONG_EDGE)
            } ?: return@launch
            val old = _state.value.preview
            _state.update { it.copy(preview = bitmap) }
            old?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
        }
    }

    /**
     * 保存したときのファイルサイズを**実測**する。
     * 予測式ではなく実際に原寸でエンコードして測るので正確だが数百 ms かかる。
     * 呼ぶのは入力が落ち着いてから (UI 側で debounce する)。
     */
    fun requestEstimate() {
        val doc = _state.value.document ?: return
        val resolve = sourceResolver()
        estimateJob?.cancel()
        _state.update { it.copy(estimating = true) }
        estimateJob = viewModelScope.launch {
            val bytes = withContext(Dispatchers.Default) {
                val bitmap = DocumentRenderer.render(doc, resolve) ?: return@withContext -1
                val size = ImageEncoder.encodedSize(bitmap, doc.output.format, doc.output.quality)
                bitmap.recycle()
                // 原寸を抱えたままにせず、プレビュー解像度へ戻してメモリを返す。
                imageSource?.load(PREVIEW_LONG_EDGE)
                size
            }
            _state.update {
                it.copy(estimating = false, estimatedBytes = bytes.takeIf { b -> b >= 0 })
            }
        }
    }

    // ---- 保存 ----

    fun save(request: SaveRequest) {
        val doc = _state.value.document ?: return
        val file = sourceFile ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }

        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.Default) { performSave(doc, file, request) }
            }.getOrElse { t -> SaveOutcome.Failure(t.message ?: "不明なエラー") }

            _state.update {
                it.copy(saving = false, dirty = outcome !is SaveOutcome.Success)
            }
            when (outcome) {
                is SaveOutcome.Success -> _events.trySend(ImageEditEvent.Saved(outcome.displayPath))
                is SaveOutcome.Failure ->
                    _events.trySend(ImageEditEvent.Message("保存に失敗しました: ${outcome.reason}"))
            }
        }
    }

    private suspend fun performSave(
        doc: EditDocument,
        original: File,
        request: SaveRequest,
    ): SaveOutcome {
        // 1) 原寸で描く。プレビューと同じ render() に解像度だけ変えて通す。
        val bitmap = DocumentRenderer.render(doc, sourceResolver())
            ?: return SaveOutcome.Failure("メモリが足りません")
        try {
            val format = doc.output.format
            // 2) 目標容量の指定があれば、そこに収まる最大の品質を探す。
            val quality = if (request.targetBytes != null && format.hasQuality) {
                ImageEncoder.findQualityForTarget(request.targetBytes) { q ->
                    ImageEncoder.encodedSize(bitmap, format, q)
                }.quality
            } else {
                doc.output.quality
            }
            // 3) 一時ファイルへ書き出す (EXIF の書き戻しが File を要求するため)。
            val temp = File(appContext.cacheDir, "$TEMP_NAME.${format.extension}")
            temp.parentFile?.mkdirs()
            val encoded = temp.outputStream().use { out ->
                ImageEncoder.encode(bitmap, format, quality, out)
            }
            if (!encoded) return SaveOutcome.Failure("画像を書き出せませんでした")

            // 4) EXIF を引き継ぐ (Orientation は正規化、位置情報は要求どおり)。
            ExifTransfer.copy(from = original, to = temp, keepLocation = request.keepLocation)

            // 5) 目的地へ置く。
            return withContext(Dispatchers.IO) { deliver(temp, original, format, request) }
        } finally {
            bitmap.recycle()
            imageSource?.load(PREVIEW_LONG_EDGE)
        }
    }

    private suspend fun deliver(
        temp: File,
        original: File,
        format: ImageFormat,
        request: SaveRequest,
    ): SaveOutcome {
        val remote = sourceUri
        if (remote == null) {
            // ローカルの実ファイルを直接編集していた場合。
            val dir = original.parentFile ?: return SaveOutcome.Failure("保存先フォルダが見つかりません")
            val name = if (request.overwrite) {
                SaveNaming.sameNameWithFormat(original.name, format)
            } else {
                SaveNaming.uniqueName(SaveNaming.editedName(original.name, format)) {
                    File(dir, it).exists()
                }
            }
            val dest = File(dir, name)
            return runCatching {
                temp.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
                SaveOutcome.Success(dest.absolutePath)
            }.getOrElse { SaveOutcome.Failure(it.message ?: "書き込みエラー") }
        }

        // リモート / SAF から開いていた場合は元 URI (別名なら兄弟の URI) へ書き戻す。
        val targetUri = if (request.overwrite) {
            remote
        } else {
            siblingUri(remote, format) ?: return SaveOutcome.Failure("この場所には別名で保存できません")
        }
        val mode = if (request.overwrite) WriteMode.OVERWRITE else WriteMode.CREATE_NEW
        return runCatching {
            when (val r = storage.openOutput(targetUri, mode)) {
                is Result.Success -> r.value.use { out ->
                    temp.inputStream().use { it.copyTo(out) }
                }
                is Result.Failure ->
                    return SaveOutcome.Failure(r.error.message ?: "書き込めませんでした")
            }
            // 上書きしたときは、次に開いたときに編集後が出るようキャッシュ実体も更新する。
            if (request.overwrite) {
                temp.inputStream().use { input ->
                    original.outputStream().use { input.copyTo(it) }
                }
            }
            SaveOutcome.Success(targetUri.displayName())
        }.getOrElse { SaveOutcome.Failure(it.message ?: "書き込みエラー") }
    }

    /** 元 URI と同じフォルダに、編集後の名前で作る URI。SAF は親を辿れないので null。 */
    private fun siblingUri(source: FileUri, format: ImageFormat): FileUri? = when (source) {
        is FileUri.Remote -> {
            val name = SaveNaming.editedName(source.path.substringAfterLast('/'), format)
            val parent = source.path.substringBeforeLast('/', "")
            source.copy(path = if (parent.isEmpty()) name else "$parent/$name")
        }
        is FileUri.Local -> {
            val name = SaveNaming.editedName(source.absolutePath.substringAfterLast('/'), format)
            val parent = source.absolutePath.substringBeforeLast('/', "")
            FileUri.Local(if (parent.isEmpty()) name else "$parent/$name")
        }
        is FileUri.Saf -> null
    }

    override fun onCleared() {
        previewJob?.cancel()
        estimateJob?.cancel()
        imageSource?.release()
        _state.value.preview?.takeIf { !it.isRecycled }?.recycle()
        super.onCleared()
    }

    // ---- 内部 ----

    private fun sourceResolver(): (String) -> ImageSource? {
        val src = imageSource
        val key = sourceFile?.absolutePath
        return { requested -> if (key != null && requested == key) src else null }
    }

    /**
     * 論理キャンバス座標 (= 回転・反転を適用した「見たまま」) の矩形を、
     * 元画像の座標系へ写す。切り抜きは元画像座標で記録するので、この逆変換が要る。
     *
     * 描画は `反転 → 回転 → 素の画像` の順に効くので、解くときは逆順に戻す。
     */
    private fun canvasRectToSourceRect(layer: Layer.Image, rect: EditRect): EditRect? {
        val crop = layer.cropRect
        val logical = layer.logicalSize

        // 見た目の中での正規化座標 (0..1)
        var x0 = (rect.left / logical.width).coerceIn(0f, 1f)
        var y0 = (rect.top / logical.height).coerceIn(0f, 1f)
        var x1 = (rect.right / logical.width).coerceIn(0f, 1f)
        var y1 = (rect.bottom / logical.height).coerceIn(0f, 1f)

        // 1) 反転を戻す
        if (layer.flipH) {
            val l = 1f - x1
            x1 = 1f - x0
            x0 = l
        }
        if (layer.flipV) {
            val t = 1f - y1
            y1 = 1f - y0
            y0 = t
        }

        // 2) 回転を戻す (u,v = 回転前の正規化座標)
        val u0: Float
        val v0: Float
        val u1: Float
        val v1: Float
        when (layer.quarterTurns % 4) {
            1 -> { u0 = y0; v0 = 1f - x1; u1 = y1; v1 = 1f - x0 }
            2 -> { u0 = 1f - x1; v0 = 1f - y1; u1 = 1f - x0; v1 = 1f - y0 }
            3 -> { u0 = 1f - y1; v0 = x0; u1 = 1f - y0; v1 = x1 }
            else -> { u0 = x0; v0 = y0; u1 = x1; v1 = y1 }
        }

        // 3) 現在の切り抜き範囲の中の相対位置 → 元画像座標
        val out = EditRect(
            left = crop.left + u0 * crop.width,
            top = crop.top + v0 * crop.height,
            right = crop.left + u1 * crop.width,
            bottom = crop.top + v1 * crop.height,
        )
        return if (out.isEmpty) null else out
    }

    companion object {
        /** 画面表示用に描く解像度。編集中はこの大きさのビットマップだけを常時抱える。 */
        const val PREVIEW_LONG_EDGE: Int = 2048

        /** 出力の上限。これを超える画像は出力倍率を下げた状態で開く。 */
        const val MAX_OUTPUT_LONG_EDGE: Int = 8192

        const val MIN_OUTPUT_LONG_EDGE: Int = 16

        private const val LAYER_ID = "layer-0"
        private const val TEMP_NAME = "image-edit-out"
    }
}

/** 画面の状態。 */
data class ImageEditUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val fileName: String = "",
    val document: EditDocument? = null,
    val preview: Bitmap? = null,
    val activeTool: EditTool? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val estimating: Boolean = false,
    /** 保存したときの実測バイト数。null なら未計測。 */
    val estimatedBytes: Int? = null,
    val originalBytes: Long = 0,
    /** 元画像が位置情報を持っているか (保存ダイアログの表示判断に使う)。 */
    val hasLocation: Boolean = false,
    /** 別名保存ができるか (SAF 経由では不可)。 */
    val canSaveAs: Boolean = true,
) {
    val outputSize: EditSize? get() = document?.outputSize
}

/** ツールバーで選ぶツール。v1 は切り抜きとサイズだけ。 */
enum class EditTool { CROP, RESIZE }

/** 保存の指定。 */
data class SaveRequest(
    val overwrite: Boolean,
    val keepLocation: Boolean,
    /** 目標ファイルサイズ (バイト)。指定すると品質を自動調整する。 */
    val targetBytes: Int? = null,
)

private sealed interface SaveOutcome {
    data class Success(val displayPath: String) : SaveOutcome
    data class Failure(val reason: String) : SaveOutcome
}

sealed interface ImageEditEvent {
    data class Message(val text: String) : ImageEditEvent
    data class Saved(val displayPath: String) : ImageEditEvent
}
