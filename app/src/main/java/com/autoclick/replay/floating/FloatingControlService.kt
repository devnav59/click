package com.autoclick.replay.floating

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.autoclick.replay.R
import com.autoclick.replay.model.Action
import com.autoclick.replay.model.ActionType
import com.autoclick.replay.model.Task
import com.autoclick.replay.model.TaskParam
import com.autoclick.replay.service.AutoClickAccessibilityService
import com.autoclick.replay.util.TaskRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import androidx.core.app.NotificationCompat

class FloatingControlService : Service() {
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var selectedTaskId: String? = null
    private var isMinimized = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        selectedTaskId = intent?.getStringExtra("taskId") ?: selectedTaskId
        if (intent?.getStringExtra("action") == "close") { stopSelf(); return START_NOT_STICKY }
        showFloating()
        return START_STICKY
    }

    private fun showFloating() {
        if (floatingView != null) return
        try {
            startForegroundNotification()
        } catch (_: Exception) {}
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // Use Material theme for inflation — Service context has no theme, so wrap it
        val themedContext = androidx.appcompat.view.ContextThemeWrapper(this, R.style.Theme_AutoClick)
        val inflater = LayoutInflater.from(themedContext)
        try {
            floatingView = inflater.cloneInContext(themedContext).inflate(R.layout.view_floating_full, null)
        } catch (e: Exception) {
            // Fallback to simple view if Material inflation fails
            Toast.makeText(this, "خطا در نمایش پنل: ${e.message}", Toast.LENGTH_LONG).show()
            try {
                floatingView = LayoutInflater.from(this).inflate(R.layout.view_floating, null)
            } catch (_: Exception) { return }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            360.dp(), WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 20; params.y = 120

        // Drag
        val header = floatingView!!.findViewById<View>(R.id.headerDrag)
        var initialX = 0; var initialY = 0; var initialTouchX = 0f; var initialTouchY = 0f
        var isDragging = false
        header.setOnTouchListener { _, event ->
            when(event.action){
                MotionEvent.ACTION_DOWN -> { initialX=params.x; initialY=params.y; initialTouchX=event.rawX; initialTouchY=event.rawY; isDragging=false; true}
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt(); val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx)>10 || Math.abs(dy)>10) isDragging=true
                    params.x = initialX + dx; params.y = initialY + dy
                    windowManager?.updateViewLayout(floatingView, params); true
                }
                MotionEvent.ACTION_UP -> { isDragging }
                else -> false
            }
        }

        // Views
        val txtStatus = floatingView!!.findViewById<TextView>(R.id.txtFloatingStatusFull)
        val btnRec = floatingView!!.findViewById<MaterialButton>(R.id.btnFloatingRecFull)
        val btnPlay = floatingView!!.findViewById<MaterialButton>(R.id.btnFloatingPlayFull)
        val btnClose = floatingView!!.findViewById<View>(R.id.btnFloatingCloseFull)
        val btnMin = floatingView!!.findViewById<View>(R.id.btnFloatingMinimize)
        val toggle = floatingView!!.findViewById<MaterialButtonToggleGroup>(R.id.toggleGroup)
        val btnTabTasks = floatingView!!.findViewById<View>(R.id.btnTabTasks)
        val btnTabCurrent = floatingView!!.findViewById<View>(R.id.btnTabCurrent)
        val sectionTasks = floatingView!!.findViewById<View>(R.id.sectionTasks)
        val sectionCurrent = floatingView!!.findViewById<View>(R.id.sectionCurrent)
        val recyclerTasks = floatingView!!.findViewById<RecyclerView>(R.id.recyclerTasksFloat)
        val recyclerParams = floatingView!!.findViewById<RecyclerView>(R.id.recyclerParamsFloat)
        val recyclerActions = floatingView!!.findViewById<RecyclerView>(R.id.recyclerActionsFloat)
        val txtEmpty = floatingView!!.findViewById<View>(R.id.txtEmptyFloat)
        val txtCurrentName = floatingView!!.findViewById<TextView>(R.id.txtCurrentTaskName)
        val txtCurrentCount = floatingView!!.findViewById<TextView>(R.id.txtCurrentCount)
        val txtCurrentPkg = floatingView!!.findViewById<TextView>(R.id.txtCurrentPkg)
        val btnCreate = floatingView!!.findViewById<View>(R.id.btnCreateTaskFloat)
        val btnAddParam = floatingView!!.findViewById<View>(R.id.btnAddParamFloat)
        val btnAddInput = floatingView!!.findViewById<View>(R.id.btnAddInputFloat)
        val btnClear = floatingView!!.findViewById<View>(R.id.btnClearFloat)

        recyclerTasks.layoutManager = LinearLayoutManager(this)
        recyclerParams.layoutManager = LinearLayoutManager(this)
        recyclerActions.layoutManager = LinearLayoutManager(this)

        // Auto select first task if none
        if (selectedTaskId == null) {
            selectedTaskId = TaskRepository.load(this).firstOrNull()?.id
        }

        fun refreshAll() {
            val tasks = TaskRepository.load(this)
            val isRec = AutoClickAccessibilityService.isRecording && AutoClickAccessibilityService.currentTaskId == selectedTaskId
            txtStatus.text = if (isRec) "● در حال ضبط — روی اپ شناور کلیک کن" else "آماده • ${tasks.size} وظیفه"
            txtStatus.setTextColor(ContextCompat.getColor(this, if(isRec) android.R.color.holo_red_dark else android.R.color.darker_gray))
            btnRec.text = if (isRec) "■ توقف ضبط" else "● شروع ضبط"
            // Use direct color int, not resource ID
            btnRec.setBackgroundColor(if(isRec) 0xFFB3261E.toInt() else 0xFF6750A4.toInt())
            btnPlay.isEnabled = selectedTaskId != null && !isRec

            // Tasks list
            val adapterTasks = FloatingTaskAdapter(tasks, selectedTaskId,
                onSelect = { t -> selectedTaskId = t.id; refreshAll() },
                onPlay = { t -> playTask(t.id) },
                onDelete = { t -> TaskRepository.delete(this, t.id); if(selectedTaskId==t.id) selectedTaskId = TaskRepository.load(this).firstOrNull()?.id; refreshAll() }
            )
            recyclerTasks.adapter = adapterTasks
            txtEmpty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE

            // Current task details
            val cur = selectedTaskId?.let { TaskRepository.get(this, it) }
            if (cur == null) {
                txtCurrentName.text = "— وظیفه‌ای انتخاب نشده"
                txtCurrentCount.text = "0"
                txtCurrentPkg.text = ""
                recyclerParams.adapter = null
                recyclerActions.adapter = null
            } else {
                txtCurrentName.text = cur.name
                txtCurrentCount.text = "${cur.actions.size} مرحله"
                txtCurrentPkg.text = if (cur.targetPackage.isEmpty()) "همه اپ‌ها • ${cur.actions.size} اقدام" else cur.targetPackage
                recyclerParams.adapter = FloatingParamAdapter(cur.params,
                    onChange = { TaskRepository.upsert(this, cur); refreshAll() },
                    onDelete = { idx -> cur.params.removeAt(idx); TaskRepository.upsert(this, cur); refreshAll() }
                )
                recyclerActions.adapter = FloatingActionAdapter(cur.actions, cur,
                    onDelete = { idx -> cur.actions.removeAt(idx); TaskRepository.upsert(this, cur); refreshAll() },
                    onEdit = { idx -> showEditInputDialog(cur, idx) { refreshAll() } }
                )
            }
            // Toggle visibility is handled by toggle state
        }

        // Toggle
        toggle.check(R.id.btnTabTasks)
        sectionTasks.visibility = View.VISIBLE
        sectionCurrent.visibility = View.GONE
        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when(checkedId){
                R.id.btnTabTasks -> { sectionTasks.visibility = View.VISIBLE; sectionCurrent.visibility = View.GONE }
                R.id.btnTabCurrent -> { sectionTasks.visibility = View.GONE; sectionCurrent.visibility = View.VISIBLE }
            }
        }

        btnCreate.setOnClickListener { showCreateTaskDialog { refreshAll() } }
        btnAddParam.setOnClickListener {
            val cur = selectedTaskId?.let { TaskRepository.get(this, it) } ?: run { Toast.makeText(this,"ابتدا وظیفه بساز", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val p = TaskParam(label = "عدد ${cur.params.size+1}", value = "")
            cur.params.add(p); TaskRepository.upsert(this, cur); refreshAll()
        }
        btnAddInput.setOnClickListener {
            val cur = selectedTaskId?.let { TaskRepository.get(this, it) } ?: return@setOnClickListener
            if (cur.params.isEmpty()) { Toast.makeText(this,"اول یک پارامتر عددی بساز", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            showCreateInputDialog(cur) { refreshAll() }
        }
        btnClear.setOnClickListener {
            val cur = selectedTaskId?.let { TaskRepository.get(this, it) } ?: return@setOnClickListener
            cur.actions.clear(); TaskRepository.upsert(this, cur); refreshAll()
        }

        btnRec.setOnClickListener {
            val svc = AutoClickAccessibilityService.instance
            if (svc == null) { Toast.makeText(this,"سرویس دسترسی خاموش است — از تنظیمات فعال کن", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            val curId = selectedTaskId ?: run { Toast.makeText(this,"اول یک وظیفه انتخاب کن", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (AutoClickAccessibilityService.isRecording) {
                AutoClickAccessibilityService.isRecording = false
                AutoClickAccessibilityService.currentTaskId = null
                Toast.makeText(this,"ضبط متوقف شد ✓", Toast.LENGTH_SHORT).show()
            } else {
                AutoClickAccessibilityService.currentTaskId = curId
                AutoClickAccessibilityService.isRecording = true
                Toast.makeText(this,"ضبط شروع شد — حالا روی اپ هدف (پنجره شناور) کلیک/اسکرول کن", Toast.LENGTH_LONG).show()
                // auto switch to current tab
                toggle.check(R.id.btnTabCurrent)
            }
            refreshAll()
        }
        btnPlay.setOnClickListener {
            val curId = selectedTaskId ?: return@setOnClickListener
            playTask(curId)
        }
        btnClose.setOnClickListener { stopSelf() }
        btnMin.setOnClickListener {
            isMinimized = !isMinimized
            val fullContent = floatingView!!.findViewById<View>(R.id.sectionTasks).parent as View
            // simple minimize: hide sections, keep header and bottom controls
            if (isMinimized) {
                sectionTasks.visibility = View.GONE
                sectionCurrent.visibility = View.GONE
                floatingView!!.findViewById<View>(R.id.toggleGroup).visibility = View.GONE
                (btnMin as ImageButton).setImageResource(android.R.drawable.arrow_up_float)
            } else {
                floatingView!!.findViewById<View>(R.id.toggleGroup).visibility = View.VISIBLE
                if (toggle.checkedButtonId == R.id.btnTabTasks) sectionTasks.visibility = View.VISIBLE else sectionCurrent.visibility = View.VISIBLE
                (btnMin as ImageButton).setImageResource(android.R.drawable.arrow_down_float)
            }
            // update window size
            params.width = if(isMinimized) 360.dp() else 360.dp()
            windowManager?.updateViewLayout(floatingView, params)
        }

        // Listen for recorded actions
        AutoClickAccessibilityService.listeners.add { refreshAll() }

        refreshAll()
        windowManager?.addView(floatingView, params)
    }

    private fun playTask(taskId: String) {
        val svc = AutoClickAccessibilityService.instance
        if (svc == null) { Toast.makeText(this,"سرویس دسترسی فعال نیست", Toast.LENGTH_SHORT).show(); return }
        if (AutoClickAccessibilityService.isRecording) { Toast.makeText(this,"اول ضبط را متوقف کن", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this,"در حال اجرا...", Toast.LENGTH_SHORT).show()
        svc.playTask(taskId) { ok ->
            Toast.makeText(this, if(ok) "اجرا تمام شد ✓" else "خطا در اجرا", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCreateTaskDialog(onDone: ()->Unit) {
        val themedContext = androidx.appcompat.view.ContextThemeWrapper(this, R.style.Theme_AutoClick)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.dialog_create_task, null)
        val editName = view.findViewById<EditText>(R.id.editName)
        val editPkg = view.findViewById<EditText>(R.id.editPackage)
        val dialog = android.app.AlertDialog.Builder(themedContext, R.style.Theme_AutoClick)
            .setTitle("وظیفه جدید")
            .setView(view)
            .setPositiveButton("ساخت", null)
            .setNegativeButton("لغو", null)
            .create()
        // make it overlay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = editName.text.toString().trim()
            if (name.isEmpty()) { editName.error="نام لازم است"; return@setOnClickListener }
            val t = Task(name=name, targetPackage=editPkg.text.toString().trim())
            TaskRepository.upsert(this, t)
            selectedTaskId = t.id
            dialog.dismiss()
            onDone()
        }
    }

    private fun showCreateInputDialog(task: Task, onDone: ()->Unit) {
        val themedContext = androidx.appcompat.view.ContextThemeWrapper(this, R.style.Theme_AutoClick)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.dialog_input_action, null)
        val editText = view.findViewById<EditText>(R.id.editInputText)
        val spinner = view.findViewById<Spinner>(R.id.spinnerParam)
        val options = mutableListOf("مقدار ثابت")
        task.params.forEachIndexed { i, p -> options.add("پارامتر ${i+1}: ${p.label.ifEmpty{"عدد ${i+1}"}} = ${p.value.ifEmpty{"خالی"}}") }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        val dialog = android.app.AlertDialog.Builder(themedContext, R.style.Theme_AutoClick)
            .setTitle("افزودن ورودی")
            .setView(view)
            .setPositiveButton("افزودن", null)
            .setNegativeButton("لغو", null)
            .create()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = editText.text.toString()
            val pos = spinner.selectedItemPosition
            val a = Action(type = ActionType.INPUT_TEXT, relX = 0.5f, relY = 0.5f, inputText = text, delayMs = 600)
            if (pos>0) { a.paramIndex = pos-1; a.paramId = task.params[pos-1].id }
            task.actions.add(a); TaskRepository.upsert(this, task); dialog.dismiss(); onDone()
        }
    }

    private fun showEditInputDialog(task: Task, idx: Int, onDone: ()->Unit) {
        val existing = task.actions[idx]
        val themedContext = androidx.appcompat.view.ContextThemeWrapper(this, R.style.Theme_AutoClick)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.dialog_input_action, null)
        val editText = view.findViewById<EditText>(R.id.editInputText)
        val spinner = view.findViewById<Spinner>(R.id.spinnerParam)
        val options = mutableListOf("مقدار ثابت")
        task.params.forEachIndexed { i, p -> options.add("پارامتر ${i+1}: ${p.label.ifEmpty{"عدد ${i+1}"}} = ${p.value.ifEmpty{"خالی"}}") }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        editText.setText(existing.inputText ?: "")
        val sel = when { existing.paramIndex != null -> existing.paramIndex!!+1; existing.paramId != null -> { val pi=task.params.indexOfFirst{it.id==existing.paramId}; if(pi>=0) pi+1 else 0 }; else -> 0 }
        spinner.setSelection(sel.coerceIn(0, options.size-1))
        val dialog = android.app.AlertDialog.Builder(themedContext, R.style.Theme_AutoClick)
            .setTitle("ویرایش ورودی")
            .setView(view)
            .setPositiveButton("ذخیره", null)
            .setNegativeButton("لغو", null)
            .setNeutralButton("حذف") { _,_ -> task.actions.removeAt(idx); TaskRepository.upsert(this, task); onDone() }
            .create()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            existing.inputText = editText.text.toString()
            val pos = spinner.selectedItemPosition
            if (pos==0) { existing.paramIndex=null; existing.paramId=null } else { existing.paramIndex=pos-1; existing.paramId=task.params[pos-1].id }
            TaskRepository.upsert(this, task); dialog.dismiss(); onDone()
        }
    }

    private fun startForegroundNotification() {
        val channelId = "floating"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Floating Control", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val pending = PendingIntent.getActivity(this,0, Intent(this, com.autoclick.replay.ui.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("پنل شناور فعال")
            .setContentText("برای مدیریت وظایف ضربه بزن")
            .setContentIntent(pending).setOngoing(true).build()
        startForeground(1, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingView?.let { windowManager?.removeView(it) }
        floatingView = null
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}

// Adapters for floating panel
class FloatingTaskAdapter(
    private val tasks: List<Task>,
    private val selectedId: String?,
    private val onSelect: (Task)->Unit,
    private val onPlay: (Task)->Unit,
    private val onDelete: (Task)->Unit
): RecyclerView.Adapter<FloatingTaskAdapter.VH>() {
    class VH(val view: View): RecyclerView.ViewHolder(view) {
        val txtName: TextView = view.findViewById(R.id.txtName)
        val txtPkg: TextView = view.findViewById(R.id.txtPkg)
        val txtCount: TextView = view.findViewById(R.id.txtCount)
        val btnPlay: View = view.findViewById(R.id.btnPlay)
        val btnDelete: View = view.findViewById(R.id.btnDelete)
        val card: View = view
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_task, parent, false)
        return VH(v)
    }
    override fun onBindViewHolder(h: VH, pos: Int) {
        val t = tasks[pos]
        h.txtName.text = t.name
        h.txtPkg.text = if (t.targetPackage.isEmpty()) "همه اپ‌ها" else t.targetPackage
        h.txtCount.text = "${t.actions.size} مرحله • ${t.params.size} پارامتر"
        h.card.isSelected = t.id == selectedId
        h.card.setOnClickListener { onSelect(t) }
        h.btnPlay.setOnClickListener { onPlay(t) }
        h.btnDelete.setOnClickListener { onDelete(t) }
        // highlight selected
        h.card.alpha = if (t.id == selectedId) 1f else 0.9f
        if (t.id == selectedId) h.card.setBackgroundResource(R.drawable.bg_chip)
    }
    override fun getItemCount() = tasks.size
}

class FloatingParamAdapter(
    private val list: MutableList<TaskParam>,
    private val onChange: ()->Unit,
    private val onDelete: (Int)->Unit
): RecyclerView.Adapter<FloatingParamAdapter.VH>() {
    class VH(val b: com.autoclick.replay.databinding.ItemParamBinding): RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(com.autoclick.replay.databinding.ItemParamBinding.inflate(LayoutInflater.from(parent.context), parent,false))
    override fun onBindViewHolder(h: VH, pos: Int) {
        val p = list[pos]
        h.b.editLabel.setText(p.label)
        h.b.editValue.setText(p.value)
        h.b.txtHint.text = "{{p${pos}}} • اعشاری"
        h.b.editLabel.addTextChangedListener(object: android.text.TextWatcher{ override fun beforeTextChanged(s: CharSequence?,a:Int,b:Int,c:Int){} override fun onTextChanged(s: CharSequence?,a:Int,b:Int,c:Int){} override fun afterTextChanged(s: android.text.Editable?){ p.label = s.toString(); onChange() } })
        h.b.editValue.addTextChangedListener(object: android.text.TextWatcher{ override fun beforeTextChanged(s: CharSequence?,a:Int,b:Int,c:Int){} override fun onTextChanged(s: CharSequence?,a:Int,b:Int,c:Int){} override fun afterTextChanged(s: android.text.Editable?){ p.value = s.toString(); onChange() } })
        h.b.btnDeleteParam.setOnClickListener { onDelete(pos) }
    }
    override fun getItemCount() = list.size
}

class FloatingActionAdapter(
    private val list: List<Action>,
    private val task: Task,
    private val onDelete: (Int)->Unit,
    private val onEdit: (Int)->Unit
): RecyclerView.Adapter<FloatingActionAdapter.VH>() {
    class VH(val b: com.autoclick.replay.databinding.ItemActionBinding): RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(com.autoclick.replay.databinding.ItemActionBinding.inflate(LayoutInflater.from(parent.context), parent,false))
    override fun onBindViewHolder(h: VH, pos: Int) {
        val a = list[pos]
        val isInput = a.type==ActionType.INPUT_TEXT
        val paramDesc = if(isInput){
            val v = a.paramIndex?.let{ if(it in task.params.indices) task.params[it].value else "-" } ?: a.inputText ?: "-"
            if(a.paramIndex!=null) "→ پارامتر ${a.paramIndex!!+1} = $v" else "ثابت: $v"
        } else ""
        h.b.txtType.text = "${pos+1}. ${a.type} $paramDesc"
        h.b.txtDetail.text = if(isInput) "متن: ${a.inputText}" else "viewId=${a.viewId ?: "-"}"
        h.b.txtCoords.text = "rel=%.2f,%.2f • %dms".format(a.relX,a.relY,a.delayMs)
        h.b.root.setOnClickListener { if(isInput) onEdit(pos) else {} }
        h.b.root.setOnLongClickListener { onDelete(pos); true }
    }
    override fun getItemCount() = list.size
}
