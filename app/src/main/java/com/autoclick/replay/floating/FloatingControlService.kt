package com.autoclick.replay.floating

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.autoclick.replay.R
import com.autoclick.replay.service.AutoClickAccessibilityService
import com.autoclick.replay.util.TaskRepository

class FloatingControlService : Service() {
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var taskId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        taskId = intent?.getStringExtra("taskId")
        when(intent?.getStringExtra("action")) {
            "close" -> stopSelf()
            else -> showFloating()
        }
        return START_STICKY
    }

    private fun showFloating() {
        if (floatingView != null) return
        startForegroundNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        floatingView = inflater.inflate(R.layout.view_floating, null)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0; params.y = 200

        // Drag handling
        var initialX = 0; var initialY = 0; var initialTouchX = 0f; var initialTouchY = 0f
        val touchListener = View.OnTouchListener { _, event ->
            when(event.action){
                MotionEvent.ACTION_DOWN -> { initialX=params.x; initialY=params.y; initialTouchX=event.rawX; initialTouchY=event.rawY; true}
                MotionEvent.ACTION_MOVE -> { params.x = initialX + (event.rawX - initialTouchX).toInt(); params.y = initialY + (event.rawY - initialTouchY).toInt(); windowManager?.updateViewLayout(floatingView, params); true}
                else -> false
            }
        }
        floatingView?.setOnTouchListener(touchListener)

        val btnRec = floatingView?.findViewById<View>(R.id.btnFloatingRec)
        val btnPlay = floatingView?.findViewById<View>(R.id.btnFloatingPlay)
        val btnClose = floatingView?.findViewById<View>(R.id.btnFloatingClose)
        val txtStatus = floatingView?.findViewById<android.widget.TextView>(R.id.txtFloatingStatus)

        fun refresh() {
            val rec = AutoClickAccessibilityService.isRecording && AutoClickAccessibilityService.currentTaskId == taskId
            txtStatus?.text = if (rec) getString(R.string.recording) else getString(R.string.idle)
            (btnRec as? com.google.android.material.button.MaterialButton)?.text = if (rec) "■ توقف" else "● ضبط"
        }
        refresh()

        btnRec?.setOnClickListener {
            val svc = AutoClickAccessibilityService.instance
            if (svc == null) { Toast.makeText(this,"سرویس دسترسی فعال نیست", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (AutoClickAccessibilityService.isRecording) {
                AutoClickAccessibilityService.isRecording = false
                AutoClickAccessibilityService.currentTaskId = null
                Toast.makeText(this,"ضبط متوقف شد", Toast.LENGTH_SHORT).show()
            } else {
                taskId?.let { id ->
                    AutoClickAccessibilityService.currentTaskId = id
                    AutoClickAccessibilityService.isRecording = true
                    Toast.makeText(this,"ضبط شروع شد - روی اپ هدف کلیک/اسکرول کنید", Toast.LENGTH_LONG).show()
                }
            }
            refresh()
        }
        btnPlay?.setOnClickListener {
            val svc = AutoClickAccessibilityService.instance
            if (svc == null) { Toast.makeText(this,"سرویس دسترسی فعال نیست", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            taskId?.let { id ->
                Toast.makeText(this,"در حال اجرا...", Toast.LENGTH_SHORT).show()
                svc.playTask(id) { ok ->
                    Toast.makeText(this, if(ok) "اجرا تمام شد" else "خطا در اجرا", Toast.LENGTH_SHORT).show()
                }
            }
        }
        btnClose?.setOnClickListener { stopSelf() }

        windowManager?.addView(floatingView, params)
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
            .setContentTitle("کنترل شناور فعال")
            .setContentText("برای ضبط/اجرای وظیفه")
            .setContentIntent(pending).setOngoing(true).build()
        startForeground(1, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingView?.let { windowManager?.removeView(it) }
        floatingView = null
    }
}
