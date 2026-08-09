package com.autoclick.replay.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class AutoClickAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AutoClickAccessibilityService? = null
        var isRecording = false
        var currentTaskId: String? = null
        private var lastRecordTime = 0L
        val listeners = mutableListOf<() -> Unit>()
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastWindowBounds: Rect? = null

    override fun onServiceConnected() {
        try {
            super.onServiceConnected()
            instance = this
            // Keep service alive, avoid MIUI "malfunctioning" by not doing heavy work here
        } catch (e: Exception) { instance = this }
    }
    override fun onDestroy() { super.onDestroy(); if (instance == this) instance = null; try { scope.cancel() } catch (_: Exception){} }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            if (!isRecording || currentTaskId == null) return
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName) return
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClick(event)
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> handleLongClick(event)
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> handleScroll(event)
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> updateWindowBounds()
                else -> {}
            }
        } catch (_: Exception) { /* swallow to avoid MIUI malfunction */ }
    }

    private fun updateWindowBounds() {
        try {
            val windows = windows
            val targetPkg = TaskRepository.get(this, currentTaskId!!)?.targetPackage
            var rect: Rect? = null
            if (!targetPkg.isNullOrEmpty()) {
                windows?.forEach { w -> val r = Rect(); w.getBoundsInScreen(r); if (rect == null || r.width()*r.height() > rect!!.width()*rect!!.height()) rect = r }
            } else { rootInActiveWindow?.let { root -> val r = Rect(); root.getBoundsInScreen(r); rect = r } }
            if (rect != null) lastWindowBounds = rect
        } catch (_: Exception) {}
    }

    private fun getCurrentWindowRect(eventPackage: String): Rect {
        try { windows?.forEach { w -> val r = Rect(); w.getBoundsInScreen(r); if (r.width() > 100 && r.height() > 100) return r } } catch (_: Exception) {}
        lastWindowBounds?.let { return it }
        val dm = resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun handleClick(event: AccessibilityEvent) {
        val source = event.source ?: return
        val bounds = Rect(); source.getBoundsInScreen(bounds); if (bounds.isEmpty) return
        val cx = bounds.centerX(); val cy = bounds.centerY()
        val winRect = getWindowRectForSource(source) ?: getCurrentWindowRect(event.packageName.toString())
        val relX = ((cx - winRect.left).toFloat() / winRect.width().coerceAtLeast(1)).coerceIn(0f,1f)
        val relY = ((cy - winRect.top).toFloat() / winRect.height().coerceAtLeast(1)).coerceIn(0f,1f)
        val classNameStr = (event.className?.toString() ?: source.className?.toString() ?: "")
        val isEditText = classNameStr.contains("EditText", ignoreCase = true)
        if (isEditText) {
            val task = currentTaskId?.let { TaskRepository.get(this, it) }
            if (task != null && task.params.isNotEmpty()) {
                val nextIdx = task.actions.count { it.type == ActionType.INPUT_TEXT } % task.params.size
                val param = task.params[nextIdx]
                val action = Action(
                    type = ActionType.INPUT_TEXT,
                    relX = relX, relY = relY,
                    windowLeft = winRect.left, windowTop = winRect.top, windowWidth = winRect.width(), windowHeight = winRect.height(),
                    packageName = event.packageName.toString(), className = classNameStr, viewId = source.viewIdResourceName,
                    inputText = param.value, paramId = param.id, paramIndex = nextIdx, delayMs = 600
                )
                addAction(action); lastRecordTime = System.currentTimeMillis()
                return
            }
        }
        val action = Action(type = ActionType.CLICK, relX = relX, relY = relY, windowLeft = winRect.left, windowTop = winRect.top, windowWidth = winRect.width(), windowHeight = winRect.height(), packageName = event.packageName.toString(), className = classNameStr, viewId = source.viewIdResourceName, text = source.text?.toString() ?: event.text?.joinToString(), delayMs = 350)
        addAction(action); lastRecordTime = System.currentTimeMillis()
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
        val source = event.source ?: return
        val winRect = getWindowRectForSource(source) ?: getCurrentWindowRect(event.packageName.toString())
        val fromX = 0.5f; val fromY = 0.7f; var relEndY = 0.3f
        if (event.scrollDeltaY < 0) relEndY = 0.7f
        addAction(Action(type=ActionType.SCROLL, relX=fromX, relY=fromY, relEndX=0.5f, relEndY=relEndY, windowLeft=winRect.left, windowTop=winRect.top, windowWidth=winRect.width(), windowHeight=winRect.height(), packageName=event.packageName.toString(), delayMs=500, durationMs=300))
        lastRecordTime = System.currentTimeMillis()
    }

    private fun getWindowRectForSource(source: AccessibilityNodeInfo): Rect? {
        return try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) { val win = source.window; if (win != null) { val r = Rect(); win.getBoundsInScreen(r); return r } }; null } catch (_: Exception) { null }
    }

    private fun addAction(action: Action) {
        val id = currentTaskId ?: return
        val task = TaskRepository.get(this, id) ?: return
        task.actions.add(action); TaskRepository.upsert(this, task); listeners.forEach { it.invoke() }
    }

    fun playTask(taskId: String, onDone: (Boolean)->Unit) {
        val task = TaskRepository.get(this, taskId) ?: run { onDone(false); return }
        scope.launch {
            var success = true
            for (a in task.actions) { val ok = executeAction(a, task); if (!ok) success = false; delay(a.delayMs) }
            onDone(success)
        }
    }

    private suspend fun executeAction(action: Action, task: com.autoclick.replay.model.Task): Boolean {
        return when(action.type) {
            ActionType.CLICK -> dispatchClick(action)
            ActionType.LONG_CLICK -> dispatchLongClick(action)
            ActionType.SWIPE, ActionType.SCROLL -> dispatchSwipe(action)
            ActionType.BACK -> { performGlobalAction(GLOBAL_ACTION_BACK); true }
            ActionType.INPUT_TEXT -> dispatchInput(action, task)
        }
    }

    private fun resolveInputText(action: Action, task: com.autoclick.replay.model.Task): String {
        action.paramId?.let { pid -> task.params.find { it.id == pid }?.let { return it.value } }
        action.paramIndex?.let { idx -> if (idx in task.params.indices) return task.params[idx].value }
        var txt = action.inputText ?: ""
        task.params.forEachIndexed { i, p -> txt = txt.replace("{{p${i}}}", p.value); txt = txt.replace("{{${p.id}}}", p.value); if (p.label.isNotEmpty()) txt = txt.replace("{{${p.label}}}", p.value) }
        return txt
    }

    private suspend fun dispatchInput(a: Action, task: com.autoclick.replay.model.Task): Boolean {
        val text = resolveInputText(a, task)
        var node: AccessibilityNodeInfo? = null
        if (!a.viewId.isNullOrEmpty()) node = findNodeByViewId(a.viewId)
        if (node == null) node = findEditableNode()
        if (node == null) {
            val winRect = resolveCurrentWindowRect(a)
            val x = winRect.left + a.relX * winRect.width(); val y = winRect.top + a.relY * winRect.height()
            dispatchGestureAt(x, y, 80); delay(300)
            node = findEditableNode() ?: findNodeByViewId(a.viewId ?: "")
        }
        if (node != null) {
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            val res = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (res) return true
            try {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS); delay(150)
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("autoclick", text))
                val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                if (pasted) return true
            } catch (_: Exception) {}
        }
        return false
    }

    private fun findEditableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findEditableRecursive(root)
    }
    private fun findEditableRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cls = node.className?.toString() ?: ""
        if (cls.contains("EditText")) return node
        for (i in 0 until node.childCount) { val c = node.getChild(i) ?: continue; val r = findEditableRecursive(c); if (r != null) return r }
        return null
    }

    private suspend fun dispatchClick(a: Action): Boolean {
        val winRect = resolveCurrentWindowRect(a)
        val x = winRect.left + a.relX * winRect.width(); val y = winRect.top + a.relY * winRect.height()
        if (!a.viewId.isNullOrEmpty()) { val node = findNodeByViewId(a.viewId); if (node != null) { val res = node.performAction(AccessibilityNodeInfo.ACTION_CLICK); if (res) return true } }
        return dispatchGestureAt(x, y, 80)
    }
    private suspend fun dispatchLongClick(a: Action): Boolean {
        val winRect = resolveCurrentWindowRect(a)
        val x = winRect.left + a.relX * winRect.width(); val y = winRect.top + a.relY * winRect.height()
        return dispatchGestureAt(x, y, a.durationMs.coerceAtLeast(600))
    }
    private suspend fun dispatchSwipe(a: Action): Boolean {
        val winRect = resolveCurrentWindowRect(a)
        val startX = winRect.left + a.relX * winRect.width(); val startY = winRect.top + a.relY * winRect.height()
        val endX = winRect.left + (a.relEndX ?: 0.5f) * winRect.width(); val endY = winRect.top + (a.relEndY ?: 0.5f) * winRect.height()
        return dispatchSwipeGesture(startX, startY, endX, endY, a.durationMs)
    }
    private fun resolveCurrentWindowRect(a: Action): Rect {
        if (a.packageName.isNotEmpty()) { try { windows?.forEach { w -> val r = Rect(); w.getBoundsInScreen(r); if (r.width() > 200 && r.height() > 200) return r } } catch (_: Exception) {} }
        rootInActiveWindow?.let { root -> val r = Rect(); root.getBoundsInScreen(r); if (!r.isEmpty) return r }
        lastWindowBounds?.let { return it }
        val dm = resources.displayMetrics
        return Rect(0,0,dm.widthPixels, dm.heightPixels)
    }
    private fun findNodeByViewId(viewId: String): AccessibilityNodeInfo? {
        if (viewId.isEmpty()) return null
        val root = rootInActiveWindow ?: return null
        val list = root.findAccessibilityNodeInfosByViewId(viewId)
        return list.firstOrNull()
    }
    private suspend fun dispatchGestureAt(x: Float, y: Float, duration: Long): Boolean {
        return suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
            dispatchGesture(gesture, object: GestureResultCallback(){ override fun onCompleted(g: GestureDescription?) { cont.resume(true) }; override fun onCancelled(g: GestureDescription?) { cont.resume(false) } }, null)
        }
    }
    private suspend fun dispatchSwipeGesture(sx: Float, sy: Float, ex: Float, ey: Float, dur: Long): Boolean {
        return suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, dur.coerceAtLeast(100))).build()
            dispatchGesture(gesture, object: GestureResultCallback(){ override fun onCompleted(g: GestureDescription?) { cont.resume(true) }; override fun onCancelled(g: GestureDescription?) { cont.resume(false) } }, null)
        }
    }
}
