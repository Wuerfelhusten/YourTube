package com.moddingforge.yourtube.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max

/**
 * Hosts the player WebView and steals vertical drags for native gestures. Horizontal moves,
 * taps, double taps (YouTube's own +-10 s) and drags outside the gesture areas stay with the page.
 *
 * Portrait (expanded player), drags starting on the video:
 *  - down -> follow the finger, release -> mini player (official app)
 *  - up   -> fullscreen (official app)
 * Fullscreen:
 *  - left third, up/down   -> brightness
 *  - right third, up/down  -> volume
 *  - middle third, down    -> leave fullscreen (official app)
 */
class PlayerDragLayout(context: Context) : FrameLayout(context) {

    enum class GestureMode { NONE, PORTRAIT, FULLSCREEN }
    enum class Adjust { BRIGHTNESS, VOLUME }

    var mode = GestureMode.PORTRAIT

    /** Height of the video area from the top, in px (16:9 of the width by default). */
    var videoHeight: () -> Int = { width * 9 / 16 }

    // portrait
    var onDrag: (dy: Float) -> Unit = {}
    var onRelease: (dy: Float, velocityY: Float) -> Unit = { _, _ -> }
    var onSwipeUp: () -> Unit = {}
    // fullscreen
    /** [delta] is the change as a fraction of the view height since the last call (+ = up). */
    var onAdjust: (kind: Adjust, delta: Float) -> Unit = { _, _ -> }
    var onAdjustEnd: () -> Unit = {}
    var onSwipeDownFullscreen: () -> Unit = {}
    /** Any touch on the player (shows the native tools button). */
    var onAnyTouch: () -> Unit = {}

    /**
     * Native double-tap seek with a custom length. 0 or 10 = leave it to YouTube's own handler
     * (10 s); any other value takes over the second tap in the left/right third.
     */
    var doubleTapSeconds = 0
    var onDoubleTap: (forward: Boolean) -> Unit = {}
    private var lastTapUp = 0L
    private var lastTapX = 0f
    private var consumingDoubleTap = false

    private enum class Track { NONE, MINIMIZE, SWIPE_UP, ADJUST_BRIGHTNESS, ADJUST_VOLUME, EXIT_FULLSCREEN }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val swipeDistance = 80 * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var track = Track.NONE
    private var tracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) onAnyTouch()
        if (handleDoubleTap(ev)) return true
        if (mode == GestureMode.NONE) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastY = ev.y
                track = Track.NONE
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                val dy = ev.y - downY
                val dx = abs(ev.x - downX)
                val vertical = abs(dy) > touchSlop && abs(dy) > dx * 1.5f
                if (!vertical) return false
                track = when (mode) {
                    GestureMode.PORTRAIT -> when {
                        downY > videoHeight() -> Track.NONE
                        dy > 0 -> Track.MINIMIZE
                        else -> Track.SWIPE_UP
                    }
                    GestureMode.FULLSCREEN -> when {
                        downX < width / 3f -> Track.ADJUST_BRIGHTNESS
                        downX > width * 2f / 3f -> Track.ADJUST_VOLUME
                        dy > 0 -> Track.EXIT_FULLSCREEN
                        else -> Track.NONE
                    }
                    GestureMode.NONE -> Track.NONE
                }
                if (track != Track.NONE) {
                    lastY = ev.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true // the page gets ACTION_CANCEL: no seek / tap
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
        }
        return false
    }

    /** @return true when the second tap of a double tap is taken over (the page never sees it). */
    private fun handleDoubleTap(ev: MotionEvent): Boolean {
        if (doubleTapSeconds <= 0 || doubleTapSeconds == 10) return false
        val onVideo = mode == GestureMode.FULLSCREEN || ev.y <= videoHeight()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val now = ev.eventTime
                val side = ev.x < width / 3f || ev.x > width * 2f / 3f
                if (onVideo && side && now - lastTapUp < ViewConfiguration.getDoubleTapTimeout() && abs(ev.x - lastTapX) < width / 3f) {
                    consumingDoubleTap = true
                    onDoubleTap(ev.x > width / 2f)
                    lastTapUp = 0L
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                lastTapUp = ev.eventTime
                lastTapX = ev.x
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (consumingDoubleTap) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) consumingDoubleTap = false
            return true
        }
        if (track == Track.NONE) return false
        tracker?.addMovement(ev)
        val dy = ev.y - downY
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> when (track) {
                Track.MINIMIZE -> onDrag(max(0f, dy))
                Track.ADJUST_BRIGHTNESS, Track.ADJUST_VOLUME -> {
                    val delta = (lastY - ev.y) / height.coerceAtLeast(1)
                    lastY = ev.y
                    onAdjust(if (track == Track.ADJUST_BRIGHTNESS) Adjust.BRIGHTNESS else Adjust.VOLUME, delta)
                }
                else -> Unit
            }
            MotionEvent.ACTION_UP -> {
                val vy = tracker?.run { computeCurrentVelocity(1000); yVelocity } ?: 0f
                when (track) {
                    Track.MINIMIZE -> onRelease(max(0f, dy), vy)
                    Track.SWIPE_UP -> if (-dy > swipeDistance || vy < -1500f) onSwipeUp()
                    Track.EXIT_FULLSCREEN -> if (dy > swipeDistance || vy > 1500f) onSwipeDownFullscreen()
                    Track.ADJUST_BRIGHTNESS, Track.ADJUST_VOLUME -> onAdjustEnd()
                    Track.NONE -> Unit
                }
                reset()
            }
            MotionEvent.ACTION_CANCEL -> {
                when (track) {
                    Track.MINIMIZE -> onRelease(0f, 0f)
                    Track.ADJUST_BRIGHTNESS, Track.ADJUST_VOLUME -> onAdjustEnd()
                    else -> Unit
                }
                reset()
            }
        }
        return true
    }

    private fun reset() {
        track = Track.NONE
        tracker?.recycle()
        tracker = null
    }
}
