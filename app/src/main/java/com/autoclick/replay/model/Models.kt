package com.autoclick.replay.model

data class Task(
    val id: String = java.util.UUID.randomUUID().toString(),
    var name: String,
    var targetPackage: String = "",
    var actions: MutableList<Action> = mutableListOf(),
    var params: MutableList<TaskParam> = mutableListOf(),
    var isQuick: Boolean = false,
    var createdAt: Long = System.currentTimeMillis()
)

data class TaskParam(
    val id: String = java.util.UUID.randomUUID().toString(),
    var label: String = "", // مثلا "قیمت 1"
    var value: String = ""  // عدد اعشاری به صورت رشته مثلا "12.34"
)

data class Action(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: ActionType,
    // normalized coordinates 0..1 relative to target window bounds
    val relX: Float = 0.5f,
    val relY: Float = 0.5f,
    // for scroll/swipe
    val relEndX: Float? = null,
    val relEndY: Float? = null,
    // window bounds at record time (for reference)
    val windowLeft: Int = 0,
    val windowTop: Int = 0,
    val windowWidth: Int = 0,
    val windowHeight: Int = 0,
    // package and view info
    val packageName: String = "",
    val className: String = "",
    val viewId: String? = null,
    val text: String? = null,
    // برای INPUT_TEXT: می‌تواند مقدار ثابت یا placeholder مثل {{p0}} یا {{paramId}} باشد
    var inputText: String? = null,
    // اگر INPUT_TEXT به پارامتر وصل باشد
    var paramId: String? = null,
    var paramIndex: Int? = null,
    val delayMs: Long = 300,
    val durationMs: Long = 150 // for swipe/gesture
)

enum class ActionType { CLICK, LONG_CLICK, SWIPE, SCROLL, BACK, INPUT_TEXT }
