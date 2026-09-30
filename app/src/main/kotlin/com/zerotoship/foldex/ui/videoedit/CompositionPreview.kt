// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit

import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zerotoship.foldex.ui.videoedit.engine.PreviewController
import com.zerotoship.foldex.ui.videoedit.model.VideoProject
import com.zerotoship.foldex.ui.videoedit.timeline.TimelineState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive

@Composable
internal fun rememberPreviewController(project: VideoProject, timeline: TimelineState, allowed: Boolean = true): PreviewController {
    val context = LocalContext.current
    val controller = remember { PreviewController(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestAllowed = rememberUpdatedState(allowed)
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.setActive(latestAllowed.value)
                Lifecycle.Event.ON_STOP -> controller.setActive(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        controller.setActive(allowed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); controller.release() }
    }
    LaunchedEffect(allowed) {
        controller.setActive(allowed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    // スライダーやドラッグの途中でデコーダを毎フレーム作り直さない。止まった最新の編集だけ反映する。
    LaunchedEffect(project) {
        controller.pause()
        delay(180L)
        controller.update(project, timeline.playheadUs)
    }
    LaunchedEffect(controller, timeline) {
        snapshotFlow { timeline.scrubbing to timeline.playheadUs }.collectLatest { (scrubbing, timeUs) ->
            controller.scrub(scrubbing)
            if (!controller.playing && timeUs != controller.positionUs) controller.seek(timeUs)
        }
    }
    LaunchedEffect(controller) {
        while (isActive) {
            if (controller.playing && !timeline.scrubbing) timeline.playheadUs = controller.poll()
            delay(32L)
        }
    }
    LaunchedEffect(controller.ended) {
        if (controller.ended) timeline.playheadUs = controller.durationUs
    }
    return controller
}

/** Compose の Surface API を使い、選択枠・ドラッグ領域を映像の上に描けるようにする。 */
@Composable
internal fun CompositionPreview(controller: PreviewController, modifier: Modifier = Modifier) {
    AndroidEmbeddedExternalSurface(modifier.fillMaxSize()) {
        onSurface { surface, width, height ->
            controller.attach(surface, width, height)
            surface.onChanged { w, h -> controller.attach(this, w, h) }
            surface.onDestroyed { controller.detach(this) }
        }
    }
}
