package com.autoclick.replay.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.autoclick.replay.R
import com.autoclick.replay.databinding.ActivityTaskDetailBinding
import com.autoclick.replay.databinding.ItemActionBinding
import com.autoclick.replay.databinding.ItemParamBinding
import com.autoclick.replay.floating.FloatingControlService
import com.autoclick.replay.model.Action
import com.autoclick.replay.model.ActionType
import com.autoclick.replay.model.Task
import com.autoclick.replay.model.TaskParam
import com.autoclick.replay.service.AutoClickAccessibilityService
import com.autoclick.replay.util.PermissionUtil
import com.autoclick.replay.util.TaskRepository

class TaskDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTaskDetailBinding
    private var taskId: String? = null
    private var task: Task? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        taskId = intent.getStringExtra("taskId")
        binding.recyclerActions.layoutManager = LinearLayoutManager(this)
        binding.recyclerParams.layoutManager = LinearLayoutManager(this)

        binding.btnRecord.setOnClickListener { toggleRecord() }
        binding.btnPlay.setOnClickListener { play() }
        binding.btnClear.setOnClickListener { clearActions() }
        binding.btnFloating.setOnClickListener { startFloating() }
        binding.btnAddParam.setOnClickListener { addParam() }
        binding.btnAddInput.setOnClickListener { showAddInputDialog() }

        AutoClickAccessibilityService.listeners.add { runOnUiThread { refresh() } }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        task = taskId?.let { TaskRepository.get(this, it) }
        if (task == null) { finish(); return }
        binding.txtTaskName.text = task!!.name
        val isRec = AutoClickAccessibilityService.isRecording && AutoClickAccessibilityService.currentTaskId == taskId
        binding.txtStatus.text = if (isRec) getString(R.string.recording) else getString(R.string.idle)
        binding.btnRecord.text = if (isRec) getString(R.string.stop_recording) else getString(R.string.start_recording)
        binding.txtActionsCount.text = getString(R.string.actions, task!!.actions.size)
        binding.toolbar.title = task!!.name
        binding.recyclerActions.adapter = ActionAdapter(task!!.actions, task!!,
            onDelete = { idx -> task!!.actions.removeAt(idx); TaskRepository.upsert(this, task!!); refresh() },
            onEditInput = { idx -> showEditInputDialog(idx) }
        )
        binding.recyclerParams.adapter = ParamAdapter(task!!.params,
            onChange = { TaskRepository.upsert(this, task!!); binding.recyclerActions.adapter?.notifyDataSetChanged() },
            onDelete = { idx -> task!!.params.removeAt(idx); TaskRepository.upsert(this, task!!); refresh() }
        )
    }

    private fun toggleRecord() {
        if (!PermissionUtil.isAccessibilityEnabled(this, AutoClickAccessibilityService::class.java)) {
            Toast.makeText(this, "سرویس دسترسی را فعال کنید", Toast.LENGTH_LONG).show()
            PermissionUtil.openAccessibilitySettings(this); return
        }
        if (AutoClickAccessibilityService.isRecording && AutoClickAccessibilityService.currentTaskId == taskId) {
            AutoClickAccessibilityService.isRecording = false
            AutoClickAccessibilityService.currentTaskId = null
            Toast.makeText(this,"ضبط متوقف شد", Toast.LENGTH_SHORT).show()
        } else {
            AutoClickAccessibilityService.currentTaskId = taskId
            AutoClickAccessibilityService.isRecording = true
            Toast.makeText(this,"ضبط شروع شد - به اپ هدف بروید و کارها را انجام دهید", Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    private fun play() {
        val svc = AutoClickAccessibilityService.instance
        if (svc == null) { Toast.makeText(this,"سرویس دسترسی فعال نیست", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this,"در حال اجرا...", Toast.LENGTH_SHORT).show()
        svc.playTask(taskId!!) { ok -> runOnUiThread { Toast.makeText(this, if(ok) "انجام شد" else "خطا", Toast.LENGTH_SHORT).show() } }
    }

    private fun clearActions() { task?.let { it.actions.clear(); TaskRepository.upsert(this, it); refresh() } }

    private fun addParam() {
        val t = task ?: return
        val p = TaskParam(label = "عدد ${t.params.size+1}", value = "")
        t.params.add(p)
        TaskRepository.upsert(this, t)
        refresh()
        Toast.makeText(this, "پارامتر اضافه شد - مقدار اعشاری را وارد کنید", Toast.LENGTH_SHORT).show()
    }

    private fun startFloating() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return
        }
        val i = Intent(this, FloatingControlService::class.java).putExtra("taskId", taskId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        Toast.makeText(this,"پنل شناور نمایش داده شد", Toast.LENGTH_SHORT).show()
    }

    private fun showAddInputDialog() {
        val t = task ?: return
        if (t.params.isEmpty()) {
            AlertDialog.Builder(this).setMessage("ابتدا حداقل یک پارامتر عددی اضافه کن (مثلا ۳ عدد). سپس هر ورودی را به یک پارامتر وصل خواهی کرد.")
                .setPositiveButton("افزودن پارامتر"){_,_-> addParam()}
                .setNegativeButton("لغو",null).show()
            return
        }
        showInputDialog(null, -1)
    }

    private fun showEditInputDialog(idx: Int) {
        showInputDialog(task!!.actions[idx], idx)
    }

    private fun showInputDialog(existing: Action?, idx: Int) {
        val t = task ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_input_action, null)
        val editText = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editInputText)
        val spinner = view.findViewById<Spinner>(R.id.spinnerParam)
        val options = mutableListOf("مقدار ثابت (همین متن)")
        t.params.forEachIndexed { i, p -> options.add("پارامتر ${i+1}: ${p.label.ifEmpty { "عدد ${i+1}" }} = ${p.value.ifEmpty { "خالی" }}  [{{p${i}}}]") }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        // preset
        if (existing != null) {
            editText.setText(existing.inputText ?: "")
            val sel = when {
                existing.paramIndex != null -> existing.paramIndex!! + 1
                existing.paramId != null -> { val pi = t.params.indexOfFirst { it.id == existing.paramId }; if (pi>=0) pi+1 else 0 }
                else -> 0
            }
            spinner.setSelection(sel.coerceIn(0, options.size-1))
        }
        AlertDialog.Builder(this)
            .setTitle(if(existing==null) "افزودن ورودی متنی" else "ویرایش ورودی")
            .setView(view)
            .setPositiveButton("ذخیره") { _, _ ->
                val text = editText.text.toString()
                val selPos = spinner.selectedItemPosition
                val action: Action
                if (existing != null) {
                    action = existing
                    action.inputText = text
                    if (selPos==0) { action.paramIndex=null; action.paramId=null }
                    else { action.paramIndex = selPos-1; action.paramId = t.params[selPos-1].id }
                } else {
                    val rel = 0.5f
                    action = Action(type = ActionType.INPUT_TEXT, relX = rel, relY = rel, inputText = text, delayMs = 600)
                    if (selPos>0) { action.paramIndex = selPos-1; action.paramId = t.params[selPos-1].id }
                }
                if (existing==null) t.actions.add(action)
                TaskRepository.upsert(this, t)
                refresh()
                Toast.makeText(this, if(selPos>0) "ورودی به پارامتر ${selPos} وصل شد - هنگام اجرا مقدار ${t.params[selPos-1].value} جایگذاری می‌شود" else "ورودی ثابت ذخیره شد", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("لغو",null)
            .setNeutralButton("راهنما"){_,_->
                AlertDialog.Builder(this).setMessage("برای ۳ عدد: ۳ پارامتر بساز (مثلا 12.5 ، 3.14 ، 99.9) سپس ۳ اقدام INPUT_TEXT بساز و هرکدام را به یک پارامتر وصل کن. هنگام اجرا به ترتیب هر ورودی مقدار پارامتر مربوطه را وارد می‌کند. می‌توانی در متن از {{p0}} {{p1}} هم استفاده کنی.").show()
            }
            .show()
    }
}

class ParamAdapter(
    private val list: MutableList<TaskParam>,
    private val onChange: ()->Unit,
    private val onDelete: (Int)->Unit
): androidx.recyclerview.widget.RecyclerView.Adapter<ParamAdapter.VH>(){
    class VH(val b: com.autoclick.replay.databinding.ItemParamBinding): androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemParamBinding.inflate(LayoutInflater.from(parent.context), parent,false))
    }
    override fun onBindViewHolder(h: VH, pos: Int) {
        val p = list[pos]
        h.b.editLabel.setText(p.label)
        h.b.editValue.setText(p.value)
        h.b.txtHint.text = "در اقدام‌ها با {{p${pos}}} قابل استفاده است"
        h.b.editLabel.addTextChangedListener(object: android.text.TextWatcher{ override fun beforeTextChanged(s: CharSequence?,a:Int,b:Int,c:Int){} override fun onTextChanged(s: CharSequence?,a:Int,b:Int,c:Int){} override fun afterTextChanged(s: android.text.Editable?){ p.label = s.toString(); onChange() } })
        h.b.editValue.addTextChangedListener(object: android.text.TextWatcher{ override fun beforeTextChanged(s: CharSequence?,a:Int,b:Int,c:Int){} override fun onTextChanged(s: CharSequence?,a:Int,b:Int,c:Int){} override fun afterTextChanged(s: android.text.Editable?){ p.value = s.toString(); onChange() } })
        h.b.btnDeleteParam.setOnClickListener { onDelete(pos) }
    }
    override fun getItemCount() = list.size
}

class ActionAdapter(
    private val list: List<Action>,
    private val task: Task,
    private val onDelete: (Int)->Unit,
    private val onEditInput: (Int)->Unit
): androidx.recyclerview.widget.RecyclerView.Adapter<ActionAdapter.VH>(){
    class VH(val b: ItemActionBinding): androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(ItemActionBinding.inflate(LayoutInflater.from(parent.context), parent,false))
    override fun onBindViewHolder(h: VH, pos: Int) {
        val a = list[pos]
        val paramDesc = if (a.type==ActionType.INPUT_TEXT) {
            val v = a.paramIndex?.let { if(it in task.params.indices) task.params[it].value else "-" } ?: a.inputText ?: "-"
            val label = a.paramIndex?.let { "→ پارامتر ${it+1} = $v" } ?: "ثابت"
            " ورودی: ${a.inputText} $label"
        } else ""
        h.b.txtType.text = "${pos+1}. ${a.type}${if(a.packageName.isNotEmpty()) " - ${a.packageName}" else ""}$paramDesc"
        h.b.txtDetail.text = "viewId=${a.viewId ?: "-"}  text=${a.text ?: "-"}"
        h.b.txtCoords.text = "rel=(%.3f,%.3f) win=%dx%d delay=%dms".format(a.relX,a.relY,a.windowWidth,a.windowHeight,a.delayMs) + if(a.type==ActionType.INPUT_TEXT) " paramIdx=${a.paramIndex}" else ""
        // show edit/delete for input
        h.b.root.setOnLongClickListener {
            androidx.appcompat.app.AlertDialog.Builder(h.b.root.context)
                .setItems(arrayOf("ویرایش ورودی","حذف")) { _, which ->
                    if (which==0 && a.type==ActionType.INPUT_TEXT) onEditInput(pos) else onDelete(pos)
                }.show(); true
        }
        // quick delete button via click
        h.b.root.setOnClickListener {
            if (a.type==ActionType.INPUT_TEXT) onEditInput(pos)
        }
    }
    override fun getItemCount() = list.size
}
