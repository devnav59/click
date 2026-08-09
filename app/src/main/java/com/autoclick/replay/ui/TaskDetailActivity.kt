package com.autoclick.replay.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.autoclick.replay.databinding.ActivityTaskDetailBinding
import com.autoclick.replay.floating.FloatingControlService
import com.autoclick.replay.model.Action
import com.autoclick.replay.model.Task
import com.autoclick.replay.service.AutoClickAccessibilityService
import com.autoclick.replay.util.PermissionUtil
import com.autoclick.replay.util.TaskRepository
import android.view.LayoutInflater
import android.view.ViewGroup
import com.autoclick.replay.databinding.ItemActionBinding

class TaskDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTaskDetailBinding
    private var taskId: String? = null
    private var task: Task? = null
    private var isRecordingLocal = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        taskId = intent.getStringExtra("taskId")
        binding.recyclerActions.layoutManager = LinearLayoutManager(this)

        binding.btnRecord.setOnClickListener { toggleRecord() }
        binding.btnPlay.setOnClickListener { play() }
        binding.btnClear.setOnClickListener { clearActions() }
        binding.btnFloating.setOnClickListener { startFloating() }

        AutoClickAccessibilityService.listeners.add { runOnUiThread { refresh() } }
    }

    override fun onResume() {
        super.onResume(); refresh()
    }

    private fun refresh() {
        task = taskId?.let { TaskRepository.get(this, it) }
        if (task == null) { finish(); return }
        binding.txtTaskName.text = task!!.name
        isRecordingLocal = AutoClickAccessibilityService.isRecording && AutoClickAccessibilityService.currentTaskId == taskId
        binding.txtStatus.text = if (isRecordingLocal) getString(com.autoclick.replay.R.string.recording) else getString(com.autoclick.replay.R.string.idle)
        binding.btnRecord.text = if (isRecordingLocal) getString(com.autoclick.replay.R.string.stop_recording) else getString(com.autoclick.replay.R.string.start_recording)
        binding.txtActionsCount.text = getString(com.autoclick.replay.R.string.actions, task!!.actions.size)
        binding.toolbar.title = task!!.name
        binding.recyclerActions.adapter = ActionAdapter(task!!.actions)
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

    private fun clearActions() {
        task?.let { it.actions.clear(); TaskRepository.upsert(this, it); refresh() }
    }

    private fun startFloating() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return
        }
        val i = Intent(this, FloatingControlService::class.java).putExtra("taskId", taskId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        Toast.makeText(this,"پنل شناور نمایش داده شد", Toast.LENGTH_SHORT).show()
    }
}

class ActionAdapter(private val list: List<Action>): androidx.recyclerview.widget.RecyclerView.Adapter<ActionAdapter.VH>(){
    class VH(val b: ItemActionBinding): androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemActionBinding.inflate(LayoutInflater.from(parent.context), parent,false))
    }
    override fun onBindViewHolder(h: VH, pos: Int) {
        val a = list[pos]
        h.b.txtType.text = "${pos+1}. ${a.type} - ${a.packageName}"
        h.b.txtDetail.text = "viewId=${a.viewId ?: "-"}  text=${a.text ?: "-"}"
        h.b.txtCoords.text = "rel=(%.3f,%.3f) win=%dx%d delay=%dms dur=%dms".format(a.relX,a.relY,a.windowWidth,a.windowHeight,a.delayMs,a.durationMs)
    }
    override fun getItemCount() = list.size
}
