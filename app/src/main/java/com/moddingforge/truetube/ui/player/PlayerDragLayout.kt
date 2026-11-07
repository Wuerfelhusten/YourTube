package com.moddingforge.truetube.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max

/**
 * Hosts the player WebView and steals vertical downward drags that start on the video,
 * like the official app's "swipe down to minimize". Horizontal moves, taps and drags below the
 * video stay with the WebView (seeking, scrolling the comments ...).
 */
class PlayerDragLayout(context: Context) : FrameLayout(context) {

    /** Only in the expanded portrait player. */
    var dragEnabled = true

    /** Height of the video area from the top, in px (16:9 of the width by default). */
    var videoHeight: () -> Int = { width * 9 / 16 }

    var onDrag: (dy: Float) -> Unit = {}
    var onRelease: (dy: Float, velocityY: Float) -> Unit = { _, _ -> }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startedOnVideo = false
    private var dragging = false
    private var tracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!dragEnabled) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                startedOnVideo = ev.y <= videoHeight()
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dy = ev.y - downY
                val dx = abs(ev.x - downX)
                if (startedOnVideo && dy > touchSlop && dy > dx * 1.5f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true // WebView gets ACTION_CANCEL: no seek / tap on the player
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return false
        tracker?.addMovement(ev)
        val dy = max(0f, ev.y - downY)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> onDrag(dy)
            MotionEvent.ACTION_UP -> {
                val vy = tracker?.run { computeCurrentVelocity(1000); yVelocity } ?: 0f
                onRelease(dy, vy)
                reset()
            }
            MotionEvent.ACTION_CANCEL -> {
                onRelease(0f, 0f)
                reset()
            }
        }
        return true
    }

    private fun reset() {
        dragging = false
        startedOnVideo = false
        tracker?.recycle()
        tracker = null
    }
}
