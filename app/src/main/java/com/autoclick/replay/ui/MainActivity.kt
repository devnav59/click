package com.autoclick.replay.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autoclick.replay.databinding.ActivityMainBinding
import com.autoclick.replay.floating.FloatingControlService
import com.autoclick.replay.service.AutoClickAccessibilityService
import com.autoclick.replay.util.PermissionUtil

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.btnEnableAccessibility.setOnClickListener { PermissionUtil.openAccessibilitySettings(this) }
        binding.btnEnableOverlay.setOnClickListener { requestOverlay() }
        binding.btnStartFloating.setOnClickListener { startFloating() }
        binding.btnHowToFreeform.setOnClickListener { showFreeformHelp() }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val accOk = PermissionUtil.isAccessibilityEnabled(this, AutoClickAccessibilityService::class.java)
        val overlayOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

        binding.cardAccOk.visibility = if (accOk) android.view.View.VISIBLE else android.view.View.GONE
        binding.cardAccNeed.visibility = if (!accOk) android.view.View.VISIBLE else android.view.View.GONE
        binding.cardOverlayOk.visibility = if (overlayOk) android.view.View.VISIBLE else android.view.View.GONE
        binding.cardOverlayNeed.visibility = if (!overlayOk) android.view.View.VISIBLE else android.view.View.GONE

        val allOk = accOk && overlayOk
        binding.btnStartFloating.isEnabled = allOk
        binding.btnStartFloating.alpha = if (allOk) 1f else 0.5f
        binding.txtStartHint.text = if (allOk) "آماده — پنل شناور را باز کن و اپ هدف را به صورت شناور باز کن" else "ابتدا مجوزهای بالا را فعال کن"
    }

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun startFloating() {
        val overlayOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        if (!overlayOk) { Toast.makeText(this,"مجوز شناور لازم است", Toast.LENGTH_SHORT).show(); requestOverlay(); return }
        if (!PermissionUtil.isAccessibilityEnabled(this, AutoClickAccessibilityService::class.java)) {
            Toast.makeText(this,"سرویس دسترسی را فعال کن", Toast.LENGTH_LONG).show()
            PermissionUtil.openAccessibilitySettings(this); return
        }
        val i = Intent(this, FloatingControlService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        Toast.makeText(this,"پنل شناور باز شد — آن را روی صفحه می‌بینی", Toast.LENGTH_LONG).show()
    }

    private fun showFreeformHelp() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("چطور اپ هدف را شناور کنیم؟")
            .setMessage(
                "۱. پنل شناور ما را باز کن (دکمه بالا)\n" +
                "۲. اپ هدف (مثل اینستاگرام) را باز کن\n" +
                "۳. دکمه مربع (Recent) را بزن → روی آیکون اپ هدف بزن → «باز کردن در پنجره شناور» یا «Freeform» را انتخاب کن\n" +
                "۴. حالا هر دو پنجره (پنل ما + اپ هدف) روی هم هستند — بدون خروج از اپ می‌توانی ضبط کنی\n" +
                "۵. در پنل، وظیفه بساز → پارامترهای اعشاری را پر کن → «شروع ضبط» بزن و روی اپ هدف کلیک/اسکرول کن — دکمه‌های پنل ذخیره نمی‌شوند\n" +
                "۶. «توقف» بزن و با «اجرا» تست کن — مختصات به صورت نسبی ذخیره می‌شود و در سایزهای مختلف دقیق است"
            )
            .setPositiveButton("متوجه شدم", null)
            .show()
    }
}
