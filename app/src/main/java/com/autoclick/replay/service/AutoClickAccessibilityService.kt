package com.autoclick.replay.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autoclick.replay.model.Action
import com.autoclick.replay.model.ActionType
import com.autoclick.replay.util.TaskRepository
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

class AutoClickAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AutoClickAccessibilityService? = null
        var isRecording = false
        var currentTaskId: String? = null
        // for debouncing
        private var lastRecordTime = 0L
        val listeners = mutableListOf<() -> Unit>()
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastWindowBounds: Rect? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
        scope.cancel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!isRecording || currentTaskId == null) return
        // Throttle
        val now = System.currentTimeMillis()
        if (now - lastRecordTime < 250) {
            // allow scroll events more frequently? we throttle slightly
        }

        val pkg = event.packageName?.toString() ?: return
        // ignore our own package
        if (pkg == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClick(event)
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> handleLongClick(event)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> handleScroll(event)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> { /* ignore to avoid noise */ }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // update window bounds cache
                updateWindowBounds()
            }
            else -> {}
        }
    }

    override fun onInterrupt() {}

    private fun updateWindowBounds() {
        try {
            val windows = windows
            val targetPkg = TaskRepository.get(this, currentTaskId!!)?.targetPackage
            var rect: Rect? = null
            if (!targetPkg.isNullOrEmpty()) {
                windows?.forEach { w ->
                    val r = Rect()
                    w.getBoundsInScreen(r)
                    // heuristic: find window belonging to target package via title or via root
                    // we just pick largest window that overlaps
                    if (rect == null || r.width()*r.height() > rect!!.width()*rect!!.height()) rect = r
                }
            } else {
                // Use active window root bounds as fallback
                rootInActiveWindow?.let { root ->
                    val r = Rect()
                    root.getBoundsInScreen(r)
                    rect = r
                }
            }
            if (rect != null) lastWindowBounds = rect
        } catch (_: Exception) {}
    }

    private fun getCurrentWindowRect(eventPackage: String): Rect {
        // Try to find window rect for event package
        try {
            windows?.forEach { w ->
                val r = Rect()
                w.getBoundsInScreen(r)
                // if we have targetPackage, prefer matching window's package via root?
                // AccessibilityWindowInfo doesn't expose package directly except title
                // So we approximate: return first non-system window with reasonable size
                if (r.width() > 100 && r.height() > 100) {
                    // check if this window contains the event source bounds
                    eventPackage // unused, heuristic
                    return r
                }
            }
        } catch (_: Exception) {}
        // fallback to display size or last known
        lastWindowBounds?.let { return it }
        val dm = resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun handleClick(event: AccessibilityEvent) {
        val source = event.source ?: return
        val bounds = Rect()
        source.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val winRect = getWindowRectForSource(source) ?: getCurrentWindowRect(event.packageName.toString())
        val relX = ((cx - winRect.left).toFloat() / winRect.width().coerceAtLeast(1)).coerceIn(0f,1f)
        val relY = ((cy - winRect.top).toFloat() / winRect.height().coerceAtLeast(1)).coerceIn(0f,1f)

        val action = Action(
            type = ActionType.CLICK,
            relX = relX, relY = relY,
            windowLeft = winRect.left, windowTop = winRect.top,
            windowWidth = winRect.width(), windowHeight = winRect.height(),
            packageName = event.packageName.toString(),
            className = event.className?.toString() ?: "",
            viewId = source.viewIdResourceName,
            text = source.text?.toString() ?: event.text?.joinToString(),
            delayMs = 350
        )
        addAction(action)
        lastRecordTime = System.currentTimeMillis()
    }

    private fun handleLongClick(event: AccessibilityEvent) {
        val source = event.source ?: return
        val bounds = Rect(); source.getBoundsInScreen(bounds)
        val winRect = getWindowRectForSource(source) ?: getCurrentWindowRect(event.packageName.toString())
        val relX = ((bounds.centerX() - winRect.left).toFloat() / winRect.width()).coerceIn(0f,1f)
        val relY = ((bounds.centerY() - winRect.top).toFloat() / winRect.height()).coerceIn(0f,1f)
        addAction(Action(type=ActionType.LONG_CLICK, relX=relX, relY=relY, windowLeft=winRect.left, windowTop=winRect.top, windowWidth=winRect.width(), windowHeight=winRect.height(), packageName=event.packageName.toString(), className=event.className?.toString()?:"", viewId=source.viewIdResourceName, delayMs=400, durationMs=600))
        lastRecordTime = System.currentTimeMillis()
    }

    private fun handleScroll(event: AccessibilityEvent) {
        // event.scrollX/Y not reliable, we record swipe relative
        // Use source bounds to infer scroll delta
        val source = event.source ?: return
        val bounds = Rect(); source.getBoundsInScreen(bounds)
        val winRect = getWindowRectForSource(source) ?: getCurrentWindowRect(event.packageName.toString())
        val fromX = 0.5f; val fromY = 0.7f; val toY = 0.3f
        // Determine direction via scroll delta if available
        val scrollDeltaX = event.scrollDeltaX
        val scrollDeltaY = event.scrollDeltaY
        var relEndX = fromX; var relEndY = toY
        if (scrollDeltaY < 0) { // scroll up
            relEndY = 0.7f
        }
        // generic swipe
        addAction(Action(type=ActionType.SCROLL, relX=fromX, relY=fromY, relEndX=relEndX, relEndY=relEndY, windowLeft=winRect.left, windowTop=winRect.top, windowWidth=winRect.width(), windowHeight=winRect.height(), packageName=event.packageName.toString(), delayMs=500, durationMs=300))
        lastRecordTime = System.currentTimeMillis()
    }

    private fun getWindowRectForSource(source: AccessibilityNodeInfo): Rect? {
        return try {
            // Traverse to window via AccessibilityWindowInfo if available (API 21+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val win = source.window
                if (win != null) {
                    val r = Rect(); win.getBoundsInScreen(r); return r
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun addAction(action: Action) {
        val id = currentTaskId ?: return
        val task = TaskRepository.get(this, id) ?: return
        task.actions.add(action)
        TaskRepository.upsert(this, task)
        listeners.forEach { it.invoke() }
    }

    // --- Replay ---

    fun playTask(taskId: String, onDone: (Boolean)->Unit) {
        val task = TaskRepository.get(this, taskId) ?: run { onDone(false); return }
        scope.launch {
            var success = true
            for (a in task.actions) {
                val ok = executeAction(a)
                if (!ok) success = false
                delay(a.delayMs)
            }
            onDone(success)
        }
    }

    private suspend fun executeAction(action: Action): Boolean {
        return when(action.type) {
            ActionType.CLICK -> dispatchClick(action)
            ActionType.LONG_CLICK -> dispatchLongClick(action)
            ActionType.SWIPE, ActionType.SCROLL -> dispatchSwipe(action)
            ActionType.BACK -> { performGlobalAction(GLOBAL_ACTION_BACK); true }
            ActionType.INPUT_TEXT -> false
        }
    }

    private suspend fun dispatchClick(a: Action): Boolean {
        val winRect = resolveCurrentWindowRect(a)
        val x = winRect.left + a.relX * winRect.width()
        val y = winRect.top + a.relY * winRect.height()
        // try viewId click first
        if (!a.viewId.isNullOrEmpty()) {
            val node = findNodeByViewId(a.viewId)
            if (node != null) {
                val res = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (res) return true
            }
        }
        return dispatchGestureAt(x, y, 80)
    }

    private suspend fun dispatchLongClick(a: Action): Boolean {
        val winRect = resolveCurrentWindowRect(a)
        val x = winRect.left + a.relX * winRect.width()
        val y = winRect.top + a.relY * winRect.height()
        return dispatchGestureAt(x, y, a.durationMs.coerceAtLeast(600))
    }

    private suspend fun dispatchSwipe(a: Action): Boolean {
        val winRect = resolveCurrentWindowRect(a)
        val startX = winRect.left + a.relX * winRect.width()
        val startY = winRect.top + a.relY * winRect.height()
        val endX = winRect.left + (a.relEndX ?: 0.5f) * winRect.width()
        val endY = winRect.top + (a.relEndY ?: 0.5f) * winRect.height()
        return dispatchSwipeGesture(startX, startY, endX, endY, a.durationMs)
    }

    private fun resolveCurrentWindowRect(a: Action): Rect {
        // If targetPackage specified, try to find its window bounds live
        if (a.packageName.isNotEmpty()) {
            try {
                windows?.forEach { w ->
                    val r = Rect(); w.getBoundsInScreen(r)
                    if (r.width() > 200 && r.height() > 200) {
                        // Heuristically, pick window whose bounds contain previous window size ratio
                        // For floating/split, we just take the largest foreground window that is not our overlay
                        return r
                    }
                }
            } catch (_: Exception) {}
        }
        // fallback: try to get active window's root bounds
        rootInActiveWindow?.let { root ->
            val r = Rect(); root.getBoundsInScreen(r); if (!r.isEmpty) return r
        }
        // fallback to last known or display
        lastWindowBounds?.let { return it }
        val dm = resources.displayMetrics
        return Rect(0,0,dm.widthPixels, dm.heightPixels)
    }

    private fun findNodeByViewId(viewId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val list = root.findAccessibilityNodeInfosByViewId(viewId)
        return list.firstOrNull()
    }

    private suspend fun dispatchGestureAt(x: Float, y: Float, duration: Long): Boolean {
        return suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
            dispatchGesture(gesture, object: GestureResultCallback(){
                override fun onCompleted(g: GestureDescription?) { cont.resume(true) {} }
                override fun onCancelled(g: GestureDescription?) { cont.resume(false) {} }
            }, null)
        }
    }

    private suspend fun dispatchSwipeGesture(sx: Float, sy: Float, ex: Float, ey: Float, dur: Long): Boolean {
        return suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, dur.coerceAtLeast(100))).build()
            dispatchGesture(gesture, object: GestureResultCallback(){
                override fun onCompleted(g: GestureDescription?) { cont.resume(true) {} }
                override fun onCancelled(g: GestureDescription?) { cont.resume(false) {} }
            }, null)
        }
    }
}
