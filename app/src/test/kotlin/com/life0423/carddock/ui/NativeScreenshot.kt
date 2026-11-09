package com.life0423.carddock.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import java.io.File
import java.time.Duration
import org.robolectric.Shadows.shadowOf

/**
 * Draws the actual Compose Android views with Robolectric Native Graphics.
 * Dialog roots are composited after their host, so creator captures include the sheet.
 * This avoids captureToImage's device-only force-redraw wait on Robolectric's UI thread.
 */
internal fun ComposeContentTestRule.captureScreen(filename: String) {
    // Settle both Compose transitions and Android's pending layout/draw callbacks.
    mainClock.advanceTimeBy(400)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
    waitForIdle()
    val views = onAllNodes(isRoot(), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .map { (it.root as ViewRootForTest).view }
        .distinct()

    runOnIdle {
        val positionedViews = views.map { view ->
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            Triple(view, position[0], position[1])
        }
        val width = positionedViews.maxOf { (view, left, _) -> left + view.width }
        val height = positionedViews.maxOf { (view, _, top) -> top + view.height }
        check(width > 0 && height > 0) { "No laid-out Compose view to capture." }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(8, 19, 31))
        positionedViews.forEach { (view, left, top) ->
            val checkpoint = canvas.save()
            canvas.translate(left.toFloat(), top.toFloat())
            view.draw(canvas)
            canvas.restoreToCount(checkpoint)
        }

        val output = File("build/outputs/screenshots", "$filename.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        bitmap.recycle()
    }
}
