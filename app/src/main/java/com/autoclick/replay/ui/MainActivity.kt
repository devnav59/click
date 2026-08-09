package com.autoclick.replay.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.autoclick.replay.databinding.ActivityMainBinding
import com.autoclick.replay.databinding.DialogCreateTaskBinding
import com.autoclick.replay.model.Task
import com.autoclick.replay.service.AutoClickAccessibilityService
import com.autoclick.replay.util.PermissionUtil
import com.autoclick.replay.util.TaskRepository

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var tasks = mutableListOf<Task>()
    private lateinit var adapter: TaskAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = TaskAdapter(
            onOpen = { startActivity(Intent(this, TaskDetailActivity::class.java).putExtra("taskId", it.id)) },
            onPlay = { playTask(it) },
            onDelete = { deleteTask(it) }
        )
        binding.recyclerTasks.layoutManager = LinearLayoutManager(this)
        binding.recyclerTasks.adapter = adapter

        binding.fabAdd.setOnClickListener { showCreateDialog() }
        binding.btnEnableAccessibility.setOnClickListener { PermissionUtil.openAccessibilitySettings(this) }
        binding.btnEnableOverlay.setOnClickListener { requestOverlay() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        checkPermissionsBanner()
    }

    private fun checkPermissionsBanner() {
        val accOk = PermissionUtil.isAccessibilityEnabled(this, AutoClickAccessibilityService::class.java)
        val overlayOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        val need = !accOk || !overlayOk
        binding.cardWarning.visibility = if (need) android.view.View.VISIBLE else android.view.View.GONE
        binding.txtWarning.text = buildString {
            if (!accOk) append("• سرویس دسترسی غیرفعال است\n")
            if (!overlayOk) append("• مجوز نمایش شناور لازم است")
        }
        binding.btnEnableAccessibility.visibility = if (!accOk) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnEnableOverlay.visibility = if (!overlayOk) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun refresh() {
        tasks = TaskRepository.load(this)
        adapter.submit(tasks)
        binding.txtEmpty.visibility = if (tasks.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun showCreateDialog() {
        val view = LayoutInflater.from(this).inflate(com.autoclick.replay.R.layout.dialog_create_task, null)
        val editName = view.findViewById<android.widget.EditText>(com.autoclick.replay.R.id.editName)
        val editPkg = view.findViewById<android.widget.EditText>(com.autoclick.replay.R.id.editPackage)
        AlertDialog.Builder(this)
            .setTitle(com.autoclick.replay.R.string.create_task)
            .setView(view)
            .setPositiveButton("ساخت") { _, _ ->
                val name = editName.text.toString().trim()
                if (name.isEmpty()) { Toast.makeText(this,"نام را وارد کنید", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val t = Task(name=name, targetPackage=editPkg.text.toString().trim())
                TaskRepository.upsert(this, t)
                refresh()
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    private fun playTask(task: Task) {
        val svc = AutoClickAccessibilityService.instance
        if (svc == null) { Toast.makeText(this, "ابتدا سرویس دسترسی را فعال کنید", Toast.LENGTH_LONG).show(); PermissionUtil.openAccessibilitySettings(this); return }
        Toast.makeText(this,"در حال اجرای ${task.name}", Toast.LENGTH_SHORT).show()
        svc.playTask(task.id) { ok -> runOnUiThread { Toast.makeText(this, if(ok) "اجرا موفق" else "اجرا با خطا", Toast.LENGTH_SHORT).show() } }
    }

    private fun deleteTask(task: Task) {
        AlertDialog.Builder(this).setMessage("حذف ${task.name}؟").setPositiveButton("حذف"){_,_-> TaskRepository.delete(this, task.id); refresh()}.setNegativeButton("لغو",null).show()
    }
}
