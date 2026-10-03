package com.solosu.mtforum.ui.space

import android.content.Intent
import android.os.Bundle
import com.solosu.mtforum.util.ToastUtil as Toast

import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatActivity
import com.solosu.mtforum.BuildConfig
import com.solosu.mtforum.R
import com.solosu.mtforum.databinding.ActivitySettingsBinding
import com.solosu.mtforum.session.AutoSignInManager
import com.solosu.mtforum.util.CrashHandler
import androidx.appcompat.app.AlertDialog
import android.widget.TextView
import android.widget.ScrollView
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import java.io.File

import com.solosu.mtforum.ui.widget.DialogHelper
import com.solosu.mtforum.ui.widget.FrostedGlassHelper

/**
 * 设置页（原生）
 * 展示应用信息和缓存清理功能
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        com.solosu.mtforum.util.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvVersion.text = BuildConfig.VERSION_NAME

        FrostedGlassHelper.applyToCardViews(binding.root, this)


        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_left)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // AI 接口配置
        binding.layoutAiConfig.setOnClickListener {
            startActivity(Intent(this, com.solosu.mtforum.ai.AiConfigActivity::class.java))
        }
        updateAiConfigStatus()

        // 错误日志查看
        binding.layoutErrorLog.setOnClickListener { showErrorLogDialog() }
        updateErrorLogCount()

        // 清除缓存
        binding.btnClearCache.setOnClickListener {
            try {
                // 清除 Glide 缓存
                Thread {
                    com.bumptech.glide.Glide.get(this).clearDiskCache()
                    runOnUiThread {
                        com.bumptech.glide.Glide.get(this).clearMemory()
                        Toast.makeText(this, "缓存已清除", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            } catch (e: Exception) {
                Toast.makeText(this, "清除失败: " + e.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAiConfigStatus()
    }

    private fun updateAiConfigStatus() {
        val configured = com.solosu.mtforum.ai.AiConfigManager.isConfigured(this)
        if (configured) {
            val model = com.solosu.mtforum.ai.AiConfigManager.getModel(this)
            binding.tvAiConfigStatus.text = "已配置：$model"
        } else {
            binding.tvAiConfigStatus.text = "未配置，点击前往设置 API Key 与模型"
        }
    }

    private fun updateErrorLogCount() {
        val files = CrashHandler.getCrashLogFiles()
        binding.tvErrorLogCount.text = if (files.isNotEmpty()) files.size.toString() + "条" else "无"
    }

    private fun showErrorLogDialog() {
        val files = CrashHandler.getCrashLogFiles()
        if (files.isEmpty()) {
            val alertDialog2: android.app.Dialog = AlertDialog.Builder(this)
                .setTitle("错误日志")
                .setMessage("暂无错误日志")
                .setPositiveButton("确定", null)
                .show()
            DialogHelper.applyToAlertDialog(alertDialog2, this)
            return
        }

        // 构建日志列表
        val items = arrayOfNulls<String>(files.size)
        for (i in files.indices) {
            items[i] = files[i].name
        }

        val alertDialog3: android.app.Dialog = AlertDialog.Builder(this)
            .setTitle("错误日志 (" + files.size + "条)")
            .setItems(items) { dialog, which -> showLogDetail(files[which]) }
            .setNeutralButton("清除全部") { dialog, which ->
                CrashHandler.clearAllLogs()
                updateErrorLogCount()
            }
            .setPositiveButton("关闭", null)
            .show()
        DialogHelper.applyToAlertDialog(alertDialog3, this)
    }

    private fun showLogDetail(file: File) {
        val content = CrashHandler.readLogFile(file)
        val textView = TextView(this)
        textView.text = content
        textView.setTextSize(12f)
        textView.setTextColor(0xFFE0E0E0.toInt())
        textView.setBackgroundColor(0xFF1A1D23.toInt())
        textView.setPadding(24, 24, 24, 24)
        textView.typeface = Typeface.MONOSPACE
        textView.setLineSpacing(4f, 1.2f)

        val scrollView = ScrollView(this)
        scrollView.addView(textView)

        val alertDialog4: android.app.Dialog = AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(scrollView)
            .setPositiveButton("关闭", null)
            .setNeutralButton("分享日志") { dialog, which ->
                val share = Intent(Intent.ACTION_SEND)
                share.type = "text/plain"
                share.putExtra(Intent.EXTRA_TEXT, content)
                startActivity(Intent.createChooser(share, "分享错误日志"))
            }
            .setNegativeButton("删除此条") { dialog, which ->
                file.delete()
                updateErrorLogCount()
            }
            .show()
        DialogHelper.applyToAlertDialog(alertDialog4, this)
    }
}
