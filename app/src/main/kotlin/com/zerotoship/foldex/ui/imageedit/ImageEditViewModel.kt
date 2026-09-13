// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.storage.StorageProviderRouter
import com.zerotoship.foldex.ui.imageedit.engine.DocumentRenderer
import com.zerotoship.foldex.ui.imageedit.engine.ExifTransfer
import com.zerotoship.foldex.ui.imageedit.engine.ImageEncoder
import com.zerotoship.foldex.ui.imageedit.engine.ImageSource
import com.zerotoship.foldex.ui.imageedit.engine.ExportFailure
import com.zerotoship.foldex.ui.imageedit.engine.ExportReceipt
import com.zerotoship.foldex.ui.imageedit.engine.ExternalImageExport
import com.zerotoship.foldex.ui.imageedit.engine.FileFingerprint
import com.zerotoship.foldex.ui.imageedit.engine.ImageExportCoordinator
import com.zerotoship.foldex.ui.imageedit.engine.ImageExportResult
import com.zerotoship.foldex.ui.imageedit.engine.LocalImageExport
import com.zerotoship.foldex.ui.imageedit.engine.PreparedImage
import com.zerotoship.foldex.ui.imageedit.engine.ObjectSelectionEngine
import com.zerotoship.foldex.ui.imageedit.engine.ObjectSelectionResult
import com.zerotoship.foldex.ui.imageedit.model.CanvasTransforms
import com.zerotoship.foldex.ui.imageedit.model.EditDocument
import com.zerotoship.foldex.ui.imageedit.model.EditHistory
import com.zerotoship.foldex.ui.imageedit.model.EditPoint
import com.zerotoship.foldex.ui.imageedit.model.EditRect
import com.zerotoship.foldex.ui.imageedit.model.EditSize
import com.zerotoship.foldex.ui.imageedit.model.FillMode
import com.zerotoship.foldex.ui.imageedit.model.ImageFormat
import com.zerotoship.foldex.ui.imageedit.model.Layer
import com.zerotoship.foldex.ui.imageedit.model.LayerTransform
import com.zerotoship.foldex.ui.imageedit.model.SaveNaming
import com.zerotoship.foldex.ui.imageedit.model.SelectionMode
import com.zerotoship.foldex.ui.imageedit.model.SelectionSpec
import com.zerotoship.foldex.ui.imageedit.model.Stroke
import com.zerotoship.foldex.ui.imageedit.model.applying
import com.zerotoship.foldex.ui.imageedit.model.invertedIn
import com.zerotoship.foldex.ui.imageedit.model.mapPoints
import com.zerotoship.foldex.ui.imageedit.model.TextStyleSpec
import com.zerotoship.foldex.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private var sourceFingerprint: FileFingerprint? = null
    private var preparedForPicker: PreparedImage? = null
    private var unconfirmedReceipt: ExportReceipt? = null
    private var exportMetadataWarning = false
    private val exportCoordinator = ImageExportCoordinator(File(appContext.cacheDir, "image-exports"))
    private val externalExport = ExternalImageExport(storage)
    private var previewJob: Job? = null
    private var estimateJob: Job? = null
    private var estimateRequest = 0L
    private val previewMutex = Mutex()
    private val selectionMutex = Mutex()
    private var selectionJob: Job? = null
    private var selectionRequest = 0L

    // ---- 読み込み ----

    fun load(file: File, displayName: String, sourceUriString: String?) {
        if (sourceFile != null) return // 再入防止 (Composable の再構成で二重に呼ばれても無視)
        sourceFile = file
        sourceUri = sourceUriString?.let { FileUri.fromStorageStringOrNull(it) }
        if (!sourceUriString.isNullOrBlank() && sourceUri == null) {
            _state.update { it.copy(error = appContext.getString(R.string.image_open_failed)) }
            return
        }
        _state.update { it.copy(loading = true, fileName = displayName) }

        viewModelScope.launch {
            val prepared = withContext(Dispatchers.Default) {
                val fingerprint = try {
                    FileFingerprint.read(file)
                } catch (_: Exception) {
                    return@withContext null
                }
                sourceFingerprint = fingerprint
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
                _state.update { it.copy(loading = false, error = appContext.getString(R.string.image_open_failed)) }
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
                    canOverwrite = sourceUri == null || sourceUri is FileUri.Local,
                    originalName = (sourceUri as? FileUri.Local)?.absolutePath?.substringAfterLast('/') ?: file.name,
                )
            }
            if (doc.output.scale < 1f) {
                _events.trySend(
                    ImageEditEvent.Message(
                        appContext.getString(R.string.image_large_limited, MAX_OUTPUT_LONG_EDGE),
                    ),
                )
            }
            refreshPreview()
        }
    }

    // ---- 編集操作 ----

    fun rotateRight() = reshape(
        transformOthers = { layer, canvas -> CanvasTransforms.rotateRight(layer, canvas, ::textSizeOf) },
        pointMap = CanvasTransforms::rotateRightMap,
        transformImage = { it.rotatedRight() },
    )

    fun rotateLeft() = reshape(
        transformOthers = { layer, canvas -> CanvasTransforms.rotateLeft(layer, canvas, ::textSizeOf) },
        pointMap = CanvasTransforms::rotateLeftMap,
        transformImage = { it.rotatedLeft() },
    )

    fun flipHorizontal() = reshape(
        transformOthers = { layer, canvas -> CanvasTransforms.flipHorizontal(layer, canvas, ::textSizeOf) },
        pointMap = CanvasTransforms::flipHorizontalMap,
        transformImage = { it.copy(flipH = !it.flipH) },
    )

    fun flipVertical() = reshape(
        transformOthers = { layer, canvas -> CanvasTransforms.flipVertical(layer, canvas, ::textSizeOf) },
        pointMap = CanvasTransforms::flipVerticalMap,
        transformImage = { it.copy(flipV = !it.flipV) },
    )

    /** 切り抜きを確定する。[rect] は現在の論理キャンバス座標 (見たままの向き)。 */
    fun applyCrop(rect: EditRect) {
        if (_state.value.saving) return
        val doc = _state.value.document ?: return
        val layer = doc.activeLayer as? Layer.Image ?: return
        val sourceRect = canvasRectToSourceRect(layer, rect) ?: return
        val cropped = layer.withCrop(sourceRect)
        history.record(doc)
        // 切り抜くと原点が動く。後から描いた線や文字を同じ分だけずらして追従させる。
        val next = doc.copy(
            layers = doc.layers.map { other ->
                if (other.id == layer.id) cropped
                else CanvasTransforms.translate(other, -rect.left, -rect.top)
            },
            selection = doc.selection?.mapPoints(
                CanvasTransforms.translateMap(-rect.left, -rect.top),
            ),
        ).withCanvasFitting(cropped.logicalSize)
        applyDocument(next, recordHistory = false)
    }

    /** 切り抜きを解除して元の範囲に戻す。 */
    fun resetCrop() {
        if (_state.value.saving) return
        val doc = _state.value.document ?: return
        val layer = doc.activeLayer as? Layer.Image ?: return
        val crop = layer.crop ?: return
        val restored = layer.withoutCrop()
        history.record(doc)
        // 解除で原点が元へ戻るので、線や文字も元の位置関係に戻す。
        val next = doc.copy(
            layers = doc.layers.map { other ->
                if (other.id == layer.id) restored
                else CanvasTransforms.translate(other, crop.left, crop.top)
            },
            selection = doc.selection?.mapPoints(
                CanvasTransforms.translateMap(crop.left, crop.top),
            ),
        ).withCanvasFitting(restored.logicalSize)
        applyDocument(next, recordHistory = false)
    }

    /**
     * キャンバスの形が変わる操作 (回転・反転) をまとめて扱う。
     * 画像レイヤーは自分のフィールドを書き換え、その他のレイヤーは座標系を追従させる。
     */
    private fun reshape(
        transformOthers: (Layer, EditSize) -> Layer,
        /** 選択範囲を追従させる座標変換。レイヤーと同じ式を使う (CanvasTransforms が正本)。 */
        pointMap: (EditSize) -> (EditPoint) -> EditPoint,
        transformImage: (Layer.Image) -> Layer.Image,
    ) {
        if (_state.value.saving) return
        val doc = _state.value.document ?: return
        val image = doc.activeLayer as? Layer.Image ?: return
        history.record(doc)
        val canvas = EditSize(doc.canvas.width, doc.canvas.height)
        val transformedImage = transformImage(image)
        val next = doc.copy(
            layers = doc.layers.map { layer ->
                if (layer.id == image.id) transformedImage else transformOthers(layer, canvas)
            },
            selection = doc.selection?.mapPoints(pointMap(canvas)),
        ).withCanvasFitting(transformedImage.logicalSize)
        applyDocument(next, recordHistory = false)
    }

    /** テキストが占める大きさ (論理キャンバス座標)。座標変換で中心を求めるのに使う。 */
    private fun textSizeOf(layer: Layer.Text): EditSize {
        val bounds = DocumentRenderer.measureText(layer)
        return EditSize(
            width = bounds.width.toInt().coerceAtLeast(1),
            height = bounds.height.toInt().coerceAtLeast(1),
        )
    }

    /** 出力の長辺を [longEdge] px にする (解像度の変更)。 */
    fun setOutputLongEdge(longEdge: Int) {
        if (_state.value.saving) return
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
        if (_state.value.saving) return
        val doc = _state.value.document ?: return
        if (doc.output.format == format) return
        applyDocument(doc.copy(output = doc.output.copy(format = format)), recordHistory = false)
    }

    fun setQuality(quality: Int) {
        if (_state.value.saving) return
        val doc = _state.value.document ?: return
        val q = quality.coerceIn(1, 100)
        if (doc.output.quality == q) return
        applyDocument(doc.copy(output = doc.output.copy(quality = q)), recordHistory = false)
    }

    fun undo() {
        if (_state.value.saving) return
        cancelAutomaticSelection()
        if (_state.value.mosaicDraft != null) { cancelMosaic(); return }
        val current = _state.value.document ?: return
        val previous = history.undo(current) ?: return
        applyDocument(previous, recordHistory = false)
    }

    fun redo() {
        if (_state.value.saving) return
        cancelAutomaticSelection()
        if (_state.value.mosaicDraft != null) return
        val current = _state.value.document ?: return
        val next = history.redo(current) ?: return
        applyDocument(next, recordHistory = false)
    }

    fun selectTool(tool: EditTool?) {
        if (_state.value.saving) return
        cancelAutomaticSelection()
        cancelMosaic()
        _state.update { it.copy(activeTool = if (it.activeTool == tool) null else tool) }
        if (_state.value.activeTool == EditTool.RESIZE) requestEstimate()
    }

    // ---- ブラシ / 消しゴム (v2) ----

    /** 1 本描き終わったときに呼ぶ。ストロークは点列のまま積む (画素には焼かない)。 */
    fun addStroke(stroke: Stroke) {
        if (_state.value.saving) return
        if (stroke.points.isEmpty()) return
        val doc = _state.value.document ?: return
        history.record(doc)
        val target = doc.layers.filterIsInstance<Layer.Drawing>().lastOrNull()
        val next = if (target == null) {
            // 最初の 1 本。描画用のレイヤーを画像の上に作る。
            doc.copy(
                layers = doc.layers + Layer.Drawing(
                    id = "draw-${doc.layers.size}",
                    name = appContext.getString(R.string.image_drawing_layer),
                    strokes = listOf(stroke),
                ),
            )
        } else {
            doc.updateLayer(target.id) { (it as Layer.Drawing).plus(stroke) }
        }
        applyDocument(next, recordHistory = false)
    }

    // ---- テキスト (v2) ----

    /** キャンバス中央に新しいテキストを置き、それを編集対象にする。 */
    fun addText(initialSizePx: Float, color: Int) {
        if (_state.value.saving) return
        val doc = _state.value.document ?: return
        history.record(doc)
        val id = "text-${doc.layers.size}"
        val layer = Layer.Text(
            id = id,
            name = appContext.getString(R.string.image_text),
            text = "",
            style = TextStyleSpec(sizePx = initialSizePx, color = color),
            transform = LayerTransform(
                offsetX = doc.canvas.width * 0.1f,
                offsetY = doc.canvas.height * 0.45f,
            ),
        )
        _state.update { it.copy(editingTextId = id) }
        applyDocument(doc.copy(layers = doc.layers + layer), recordHistory = false)
    }

    /**
     * 編集中テキストの内容・見た目を更新する。
     * ここは履歴に積まない — 打ち間違いは入力欄でそのまま直せるので、
     * 1 文字ごとに Undo 履歴が伸びる方が邪魔になる。
     */
    fun updateText(block: (Layer.Text) -> Layer.Text) {
        if (_state.value.saving) return
        val doc = _state.value.document ?: return
        val id = _state.value.editingTextId ?: return
        val current = doc.layers.firstOrNull { it.id == id } as? Layer.Text ?: return
        applyDocument(doc.updateLayer(id) { block(current) }, recordHistory = false)
    }

    /** テキストを指で動かす。指を離したとき ([commitTextMove]) に履歴へ積む。 */
    fun moveEditingText(dx: Float, dy: Float) {
        if (_state.value.saving) return
        val doc = _state.value.document ?: return
        val id = _state.value.editingTextId ?: return
        if (!textMoveInProgress) {
            history.record(doc)
            textMoveInProgress = true
        }
        applyDocument(
            doc.updateLayer(id) { (it as Layer.Text).movedBy(dx, dy) },
            recordHistory = false,
        )
    }

    fun commitTextMove() {
        textMoveInProgress = false
    }

    /** 編集中のテキストを消す。空のまま完了したときも呼ばれる。 */
    fun deleteEditingText() {
        if (_state.value.saving) return
        val doc = _state.value.document ?: return
        val id = _state.value.editingTextId ?: return
        history.record(doc)
        _state.update { it.copy(editingTextId = null) }
        applyDocument(doc.copy(layers = doc.layers.filterNot { it.id == id }), recordHistory = false)
    }

    /** 編集対象を切り替える (null で編集をやめる)。中身が空のテキストは残さず消す。 */
    fun selectText(id: String?) {
        if (_state.value.saving) return
        val doc = _state.value.document
        val current = _state.value.editingTextId
        if (doc != null && current != null && current != id) {
            val layer = doc.layers.firstOrNull { it.id == current } as? Layer.Text
            if (layer != null && layer.text.isBlank()) {
                applyDocument(
                    doc.copy(layers = doc.layers.filterNot { it.id == current }),
                    recordHistory = false,
                )
            }
        }
        _state.update { it.copy(editingTextId = id) }
    }

    private var textMoveInProgress = false

    // ---- 選択範囲 (v3) ----

    fun selectObject(point: EditPoint, mode: SelectionMode) {
        val state = _state.value
        val doc = state.document ?: return
        if (state.saving || state.mosaicDraft != null || state.previewing || state.previewFailed ||
            state.activeTool != EditTool.SELECT
        ) return
        cancelAutomaticSelection()
        val request = selectionRequest
        val resolve = sourceResolver()
        _state.update { it.copy(selectingObject = true) }
        selectionJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    selectionMutex.withLock {
                        val context = currentCoroutineContext()
                        context.ensureActive()
                        // UI の Bitmap を推論側で解放しないよう、専用の縮小画像を作る。
                        val bitmap = DocumentRenderer.render(doc, resolve, targetLongEdge = 1024) {
                            context.ensureActive()
                        }.getOrNull() ?: return@withLock ObjectSelectionResult.Failed
                        try {
                            ObjectSelectionEngine.select(appContext, bitmap, point, doc.canvasSize) {
                                context.ensureActive()
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
                // 選択の解除・ツール変更・編集が先に進んでいれば古い結果を捨てる。
                if (selectionRequest != request || _state.value.document !== doc) return@launch
                when (result) {
                    is ObjectSelectionResult.Success -> _state.update {
                        it.copy(document = doc.copy(selection = doc.selection.applying(mode, result.selection)))
                    }
                    ObjectSelectionResult.Empty -> objectSelectionMessage(R.string.image_object_empty)
                    ObjectSelectionResult.Unavailable -> objectSelectionMessage(R.string.image_object_unavailable)
                    ObjectSelectionResult.Failed -> objectSelectionMessage(R.string.image_object_failed)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (selectionRequest == request) objectSelectionMessage(R.string.image_object_failed)
            } finally {
                if (selectionRequest == request) _state.update { it.copy(selectingObject = false) }
            }
        }
    }

    fun cancelAutomaticSelection() {
        selectionRequest++
        selectionJob?.cancel()
        selectionJob = null
        _state.update { it.copy(selectingObject = false) }
    }

    private fun objectSelectionMessage(message: Int) {
        _events.trySend(ImageEditEvent.Message(appContext.getString(message)))
    }

    /**
     * 指で描いた形を選択に反映する。**履歴には積まない。**
     *
     * 選択そのものは画素を変えないので、Undo で選択の変遷を延々と戻らされるのは邪魔になる。
     * 履歴に残すのは「塗りつぶした」「透明にした」という結果の方だけ。
     */
    fun applySelection(mode: SelectionMode, drawn: SelectionSpec) {
        val doc = _state.value.document ?: return
        updateSelection(doc.selection.applying(mode, drawn))
    }

    /** 選択を反転する (選んでいなければ全面が選ばれる)。 */
    fun invertSelection() {
        val doc = _state.value.document ?: return
        updateSelection(doc.selection.invertedIn(doc.canvasSize))
    }

    /** 選択を解除する。 */
    fun clearSelection() = updateSelection(null)

    /** 選択範囲を [color] で塗る。 */
    fun fillSelection(color: Int) = addFill(FillMode.COLOR, color)

    /** 選択範囲を透明にする (下の写真ごとくり抜く)。 */
    fun eraseSelection() = addFill(FillMode.CLEAR, 0)

    fun beginMosaic() {
        val doc = _state.value.document ?: return
        val area = doc.selection?.takeUnless { it.isEmpty } ?: return
        if (_state.value.saving) return
        cancelAutomaticSelection()
        _state.update {
            it.copy(mosaicDraft = Layer.Mosaic(
                id = doc.freshLayerId("mosaic"),
                name = appContext.getString(R.string.image_mosaic),
                area = area,
                blockSizePx = (minOf(doc.canvas.width, doc.canvas.height) * 0.025f).coerceAtLeast(2f),
            ))
        }
        refreshPreview()
    }

    fun setMosaicPercent(percent: Float) {
        val doc = _state.value.document ?: return
        val draft = _state.value.mosaicDraft ?: return
        if (!percent.isFinite()) return
        val size = minOf(doc.canvas.width, doc.canvas.height) * percent.coerceIn(0.5f, 8f) / 100f
        _state.update { it.copy(mosaicDraft = draft.copy(blockSizePx = size.coerceAtLeast(2f))) }
        refreshPreview(debounce = true)
    }

    fun commitMosaic() {
        if (_state.value.previewing || _state.value.previewFailed || _state.value.saving) return
        val doc = _state.value.document ?: return
        val draft = _state.value.mosaicDraft ?: return
        history.record(doc)
        _state.update { it.copy(mosaicDraft = null) }
        applyDocument(doc.copy(layers = doc.layers + draft), recordHistory = false)
    }

    fun cancelMosaic() {
        if (_state.value.mosaicDraft == null) return
        _state.update { it.copy(mosaicDraft = null) }
        refreshPreview()
    }

    /**
     * 塗り / 透明化をレイヤーとして積む。画素は書き換えないので、Undo で完全に戻せるし
     * 保存時には原寸で描き直される。
     */
    private fun addFill(mode: FillMode, color: Int) {
        if (_state.value.saving) return
        val doc = _state.value.document ?: return
        val area = doc.selection ?: return
        if (area.isEmpty) return
        history.record(doc)
        val nameRes = if (mode == FillMode.CLEAR) {
            R.string.image_fill_layer_clear
        } else {
            R.string.image_fill_layer_color
        }
        val next = doc.copy(
            layers = doc.layers + Layer.Fill(
                id = doc.freshLayerId("fill"),
                name = appContext.getString(nameRes),
                area = area,
                color = color,
                mode = mode,
            ),
        )
        applyDocument(next, recordHistory = false)
    }

    /**
     * 選択が変わってもプレビューの絵は変わらない (枠は Compose 側が上に描く) ので、
     * 描き直さずに状態だけ差し替える。写真 1 枚の再レンダリングは数百 ms かかるため、
     * なぞっている間これを走らせると選択が明らかにもたつく。
     */
    private fun updateSelection(next: SelectionSpec?) {
        if (_state.value.mosaicDraft != null || _state.value.saving) return
        cancelAutomaticSelection()
        _state.update { st ->
            val doc = st.document ?: return@update st
            st.copy(document = doc.copy(selection = next))
        }
    }

    /** まだ使われていないレイヤー ID を作る。レイヤーを消した後でも衝突しない。 */
    private fun EditDocument.freshLayerId(prefix: String): String {
        var n = layers.size
        while (layers.any { it.id == "$prefix-$n" }) n++
        return "$prefix-$n"
    }

    private fun applyDocument(doc: EditDocument, recordHistory: Boolean = true) {
        cancelAutomaticSelection()
        _state.update {
            it.copy(
                document = doc,
                mosaicDraft = null,
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

    private fun refreshPreview(debounce: Boolean = false) {
        val base = _state.value.document ?: return
        val draft = _state.value.mosaicDraft
        val doc = if (draft == null) base else base.copy(layers = base.layers + draft)
        val resolve = sourceResolver()
        previewJob?.cancel()
        _state.update { it.copy(previewing = true, previewFailed = false) }
        previewJob = viewModelScope.launch {
            try {
                if (debounce) delay(100)
                val bitmap = withContext(Dispatchers.Default) {
                    previewMutex.withLock {
                        val context = currentCoroutineContext()
                        context.ensureActive()
                        DocumentRenderer.render(doc, resolve, targetLongEdge = PREVIEW_LONG_EDGE) {
                            context.ensureActive()
                        }.getOrNull()
                    }
                }
                if (bitmap == null) {
                    _events.trySend(ImageEditEvent.Message(appContext.getString(R.string.image_preview_failed)))
                } else {
                    // Compose の前フレームがまだ旧 Bitmap を参照しうるため、明示 recycle しない。
                    _state.update { it.copy(preview = bitmap) }
                }
                _state.update { it.copy(previewing = false, previewFailed = bitmap == null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(previewing = false, previewFailed = true) }
                _events.trySend(ImageEditEvent.Message(appContext.getString(R.string.image_preview_failed)))
            }
        }
    }

    /**
     * 保存したときのファイルサイズを**実測**する。
     * 予測式ではなく実際に原寸でエンコードして測るので正確だが数百 ms かかる。
     * 呼ぶのは入力が落ち着いてから (UI 側で debounce する)。
     */
    fun requestEstimate() {
        val doc = _state.value.document ?: return
        if (_state.value.saving) return
        val resolve = sourceResolver()
        estimateJob?.cancel()
        val request = ++estimateRequest
        _state.update { it.copy(estimating = true, estimatedBytes = null) }
        estimateJob = viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) {
                    previewMutex.withLock {
                        val context = currentCoroutineContext()
                        val bitmap = DocumentRenderer.render(doc, resolve) { context.ensureActive() }
                            .getOrNull() ?: return@withLock null
                        try {
                            ImageEncoder.encodedSize(bitmap, doc.output.format, doc.output.quality)
                                .takeIf { it >= 0 }
                        } finally {
                            bitmap.recycle()
                            imageSource?.release()
                        }
                    }
                }
                if (request == estimateRequest && _state.value.document === doc) {
                    _state.update { it.copy(estimatedBytes = bytes) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 見積もれなくても編集データは保持する。保存側で失敗理由を表示する。
            } finally {
                if (request == estimateRequest) _state.update { it.copy(estimating = false) }
            }
        }
    }

    // ---- 保存 ----

    fun save(request: SaveRequest) {
        val doc = _state.value.document ?: return
        val file = sourceFile ?: return
        val fingerprint = sourceFingerprint ?: return
        if (_state.value.saving || _state.value.mosaicDraft != null || unconfirmedReceipt != null) return
        if (request.overwrite && (!_state.value.canOverwrite ||
                !SaveNaming.canOverwrite(_state.value.originalName, doc.output.format))
        ) {
            showExportFailure(ExportFailure.DESTINATION_EXISTS)
            return
        }
        val previousSelection = selectionJob
        cancelAutomaticSelection()
        estimateRequest++
        _state.update { it.copy(saving = true, estimating = false) }
        viewModelScope.launch {
            // Dispatcher を戻る瞬間のキャンセルでも、一時ファイルの所有者を失わない。
            var ownedPrepared: PreparedImage? = null
            try {
                estimateJob?.cancelAndJoin()
                previewJob?.cancelAndJoin()
                previousSelection?.cancelAndJoin()
                val prepared = withContext(Dispatchers.Default) {
                    previewMutex.withLock {
                        val context = currentCoroutineContext()
                        try {
                            exportCoordinator.prepare(
                                doc, sourceResolver(), file, fingerprint, request.keepLocation, request.targetBytes,
                            ) { context.ensureActive() }.also { ownedPrepared = it.getOrNull() }
                        } finally {
                            imageSource?.release()
                        }
                    }
                }
                when (prepared) {
                    is Result.Failure -> {
                        _state.update { it.copy(saving = false, previewing = false) }
                        showExportFailure(prepared.error)
                        refreshPreview()
                    }
                    is Result.Success -> {
                        exportMetadataWarning = prepared.value.metadataWarning
                        if (request.chooseLocation || sourceUri is FileUri.Saf) {
                            preparedForPicker = prepared.value
                            ownedPrepared = null
                            _events.send(ImageEditEvent.ChooseDestination(
                                SaveNaming.editedName(_state.value.fileName, doc.output.format),
                                doc.output.format.mimeType,
                            ))
                        } else {
                            prepared.value.use { image ->
                                // 最終配置に入ったらキャンセルで完了結果を失わない。
                                withContext(NonCancellable) {
                                    val outcome = withContext(Dispatchers.IO) {
                                        when (val source = sourceUri) {
                                            is FileUri.Remote -> externalExport.deliver(
                                                image, ExternalImageExport.newSibling(source, _state.value.fileName, doc.output.format), source,
                                            )
                                            else -> LocalImageExport().deliver(
                                                image,
                                                (source as? FileUri.Local)?.let { File(it.absolutePath) } ?: file,
                                                fingerprint, doc.output.format, request.overwrite,
                                            )
                                        }
                                    }
                                    finishExport(outcome)
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(saving = false, previewing = false) }
                throw cancelled
            } catch (_: Exception) {
                preparedForPicker?.close()
                preparedForPicker = null
                _state.update { it.copy(saving = false, previewing = false) }
                showExportFailure(ExportFailure.WRITE_FAILED)
            } finally {
                ownedPrepared?.close()
            }
        }
    }

    /** ACTION_CREATE_DOCUMENT が返した新規出力だけを受け取る。null は取消。 */
    fun destinationSelected(uri: String?) {
        val prepared = preparedForPicker ?: return
        preparedForPicker = null
        if (uri == null) {
            prepared.close()
            _state.update { it.copy(saving = false, previewing = false) }
            refreshPreview()
            return
        }
        viewModelScope.launch {
            prepared.use { image ->
                withContext(NonCancellable) {
                    val outcome = withContext(Dispatchers.IO) {
                        externalExport.deliver(image, FileUri.Saf(uri), sourceUri)
                    }
                    finishExport(outcome)
                }
            }
        }.invokeOnCompletion { prepared.close() }
    }

    fun verifySavedImage() {
        val receipt = unconfirmedReceipt ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            withContext(NonCancellable) {
                val outcome = withContext(Dispatchers.IO) { externalExport.verify(receipt) }
                finishExport(outcome)
            }
        }
    }

    fun dismissSaveVerification() {
        if (_state.value.saving) return
        unconfirmedReceipt = null
        _state.update { it.copy(unconfirmedSave = null) }
        refreshPreview()
    }

    private fun finishExport(result: ImageExportResult) {
        unconfirmedReceipt = (result as? ImageExportResult.Unconfirmed)?.receipt
        _state.update {
            it.copy(
                saving = false, previewing = false,
                dirty = if (result is ImageExportResult.Saved) false else it.dirty,
                unconfirmedSave = unconfirmedReceipt?.destination?.displayName(),
            )
        }
        when (result) {
            is ImageExportResult.Saved -> _events.trySend(ImageEditEvent.Saved(result.displayPath, exportMetadataWarning))
            is ImageExportResult.Unconfirmed -> Unit // 画面の確認ダイアログから同じ URI を再検証する。
            is ImageExportResult.Failed -> {
                showExportFailure(result.reason, result.displayPath)
                refreshPreview()
            }
        }
    }

    private fun showExportFailure(reason: ExportFailure, displayPath: String? = null) {
        val text = appContext.getString(when (reason) {
            ExportFailure.SOURCE_CHANGED -> R.string.image_export_source_changed
            ExportFailure.SOURCE_UNREADABLE -> R.string.image_export_source_unreadable
            ExportFailure.NOT_ENOUGH_MEMORY -> R.string.image_not_enough_memory
            ExportFailure.RENDER_FAILED -> R.string.image_preview_failed
            ExportFailure.ENCODE_FAILED -> R.string.image_encode_failed
            ExportFailure.INVALID_OUTPUT -> R.string.image_export_invalid
            ExportFailure.TARGET_TOO_SMALL -> R.string.image_export_target_small
            ExportFailure.ATOMIC_REPLACE_UNAVAILABLE -> R.string.image_export_replace_unavailable
            ExportFailure.DESTINATION_EXISTS -> R.string.image_export_conflict
            ExportFailure.VERIFICATION_FAILED -> R.string.image_export_verify_failed
            ExportFailure.WRITE_FAILED -> R.string.write_failed
        })
        _events.trySend(ImageEditEvent.Message(
            appContext.getString(R.string.image_save_failed_reason, text) +
                if (displayPath == null) "" else "\n$displayPath",
        ))
    }

    override fun onCleared() {
        selectionJob?.cancel()
        previewJob?.cancel()
        estimateJob?.cancel()
        imageSource?.release()
        preparedForPicker?.close()
        preparedForPicker = null
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
    }
}

/** 画面の状態。 */
data class ImageEditUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val fileName: String = "",
    val document: EditDocument? = null,
    val preview: Bitmap? = null,
    val previewing: Boolean = false,
    val previewFailed: Boolean = false,
    val mosaicDraft: Layer.Mosaic? = null,
    val selectingObject: Boolean = false,
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
    /** 原子的なローカル置換を試せるか。形式変更は別名保存する。 */
    val canOverwrite: Boolean = false,
    val originalName: String = "",
    val unconfirmedSave: String? = null,
    /** 編集中のテキストレイヤー ID。null なら文字を触っていない。 */
    val editingTextId: String? = null,
) {
    val outputSize: EditSize? get() = document?.outputSize

    /** 編集中のテキストレイヤー。 */
    val editingText: Layer.Text?
        get() = editingTextId?.let { id ->
            document?.layers?.firstOrNull { it.id == id } as? Layer.Text
        }

    /** 既に置いてあるテキストレイヤー (切り替え用)。 */
    val textLayers: List<Layer.Text>
        get() = document?.layers?.filterIsInstance<Layer.Text>().orEmpty()

    /** いま選ばれている範囲。null なら選択なし。 */
    val selection: SelectionSpec? get() = document?.selection

    /** 面積のある選択があるか (塗りつぶし等のボタンを出す判断に使う)。 */
    val hasSelection: Boolean get() = selection?.isEmpty == false
}

/** ツールバーで選ぶツール。 */
enum class EditTool { CROP, RESIZE, SELECT, BRUSH, TEXT }

/** 保存の指定。 */
data class SaveRequest(
    val overwrite: Boolean,
    val keepLocation: Boolean,
    val chooseLocation: Boolean = false,
    /** 目標ファイルサイズ (バイト)。指定すると品質を自動調整する。 */
    val targetBytes: Int? = null,
)

sealed interface ImageEditEvent {
    data class Message(val text: String) : ImageEditEvent
    data class Saved(val displayPath: String, val metadataWarning: Boolean = false) : ImageEditEvent
    data class ChooseDestination(val name: String, val mimeType: String) : ImageEditEvent
}
