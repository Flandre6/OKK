package com.OKK.yes.core.hooks.ui.wekit

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.withTranslation
import top.yukonga.miuix.kmp.blur.Backdrop

/**
 * [ViewBackdrop] 用于采样原生 View (ViewPager) 界面并提供给 Compose 模糊图层。
 */
@Composable
fun rememberViewBackdrop(sourceView: View): ViewBackdrop {
    val graphicsLayer = rememberGraphicsLayer()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    val backdrop = remember(graphicsLayer) { ViewBackdrop(graphicsLayer) }
    backdrop.sourceView = sourceView
    backdrop.density = density
    backdrop.layoutDirection = layoutDirection

    DisposableEffect(sourceView) {
        val observer = sourceView.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (sourceView.isDirty) {
                backdrop.bumpVersion()
            }
            true
        }
        observer.addOnPreDrawListener(listener)
        onDispose {
            (if (observer.isAlive) observer else sourceView.viewTreeObserver)
                .removeOnPreDrawListener(listener)
        }
    }

    return backdrop
}

@Stable
class ViewBackdrop internal constructor(
    private val graphicsLayer: GraphicsLayer
) : Backdrop {

    internal var sourceView: View? = null
    internal var density: Density = Density(1f)
    internal var layoutDirection: LayoutDirection = LayoutDirection.Ltr

    private var version by mutableIntStateOf(0)

    override val isCoordinatesDependent: Boolean = true

    override var offsetResidualX: Float = 0f
        private set
    override var offsetResidualY: Float = 0f
        private set

    private var lastBumpTime = 0L

    internal fun bumpVersion() {
        val now = System.currentTimeMillis()
        // 采样节流：两次硬件采样录制至少间隔 33ms (~30fps 高斯背景刷新率)，避免高刷下每帧强行 view.draw 重新递归遍历整屏 View 树导致主线程卡顿
        if (now - lastBumpTime >= 33L) {
            lastBumpTime = now
            version++
        }
    }

    private var lastRecordedVersion = -1
    private var lastRecordedScrollX = -1
    private var lastRecordedScrollY = -1

    internal fun recordSource(): Boolean {
        val view = sourceView ?: return false
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return false

        val currentScrollX = view.scrollX
        val currentScrollY = view.scrollY

        // 如果 content 未发生改变且 scroll 未变，无需重复重新硬件录制，直接复用 GraphicsLayer
        if (version == lastRecordedVersion && currentScrollX == lastRecordedScrollX && currentScrollY == lastRecordedScrollY) {
            return true
        }

        lastRecordedVersion = version
        lastRecordedScrollX = currentScrollX
        lastRecordedScrollY = currentScrollY

        runCatching {
            graphicsLayer.record(density, layoutDirection, IntSize(width, height)) {
                drawIntoCanvas { canvas ->
                    val nativeCanvas = canvas.nativeCanvas
                    nativeCanvas.withTranslation(-currentScrollX.toFloat(), -currentScrollY.toFloat()) {
                        view.draw(nativeCanvas)
                    }
                }
            }
        }
        return true
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int,
    ) {
        @Suppress("UNUSED_EXPRESSION") version
        val view = sourceView ?: return
        val barCoordinates = coordinates ?: return

        recordSource()

        val barInWindow = barCoordinates.positionInWindow()
        val viewLocation = IntArray(2).also { view.getLocationInWindow(it) }
        val offsetX = barInWindow.x - viewLocation[0]
        val offsetY = barInWindow.y - viewLocation[1]

        if (downscaleFactor > 1) {
            val inv = 1f / downscaleFactor
            val scaledX = offsetX * inv
            val scaledY = offsetY * inv
            val roundedX = kotlin.math.round(scaledX * 0.5f).toInt().toFloat() * 2f
            val roundedY = kotlin.math.round(scaledY * 0.5f).toInt().toFloat() * 2f
            offsetResidualX = (scaledX - roundedX) * downscaleFactor
            offsetResidualY = (scaledY - roundedY) * downscaleFactor
            translate(-roundedX, -roundedY) {
                scale(inv, inv, Offset.Zero) {
                    drawLayer(graphicsLayer)
                }
            }
        } else {
            offsetResidualX = 0f
            offsetResidualY = 0f
            translate(-offsetX, -offsetY) {
                drawLayer(graphicsLayer)
            }
        }
    }
}
