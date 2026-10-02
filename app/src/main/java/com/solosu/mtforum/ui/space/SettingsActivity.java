package com.solosu.mtforum.ui.space;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;


import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.solosu.mtforum.BuildConfig;
import com.solosu.mtforum.R;
import com.solosu.mtforum.databinding.ActivitySettingsBinding;
import com.solosu.mtforum.session.AutoSignInManager;
import com.solosu.mtforum.util.CrashHandler;
import androidx.appcompat.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.TextView;
import android.widget.ScrollView;
import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import java.io.File;

import com.solosu.mtforum.ui.widget.DialogHelper;
import com.solosu.mtforum.ui.widget.FrostedGlassHelper;

/**
 * 设置页（原生）
 * 展示应用信息和缓存清理功能
 */
public class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.tvVersion.setText(BuildConfig.VERSION_NAME);

        FrostedGlassHelper.applyToCardViews(binding.getRoot(), this);

        boolean autoSignInEnabled = AutoSignInManager.isEnabled(this);
        binding.switchAutoSignIn.setChecked(autoSignInEnabled);
        binding.switchAutoSignIn.setOnCheckedChangeListener((buttonView, isChecked) ->
                AutoSignInManager.setEnabled(this, isChecked));

        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_left);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        // 错误日志查看
        binding.layoutErrorLog.setOnClickListener(v -> showErrorLogDialog());
        updateErrorLogCount();

        // 清除缓存
        binding.btnClearCache.setOnClickListener(v -> {
            try {
                // 清除 Glide 缓存
                new Thread(() -> {
                    com.bumptech.glide.Glide.get(this).clearDiskCache();
                    runOnUiThread(() -> {
                        com.bumptech.glide.Glide.get(this).clearMemory();
                        Toast.makeText(this, "缓存已清除", Toast.LENGTH_SHORT).show();
                    });
                }).start();
            } catch (Exception e) {
                Toast.makeText(this, "清除失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateErrorLogCount() {
        File[] files = CrashHandler.getCrashLogFiles();
        binding.tvErrorLogCount.setText(files.length > 0 ? files.length + "条" : "无");
    }

    private void showErrorLogDialog() {
        File[] files = CrashHandler.getCrashLogFiles();
        if (files.length == 0) {
            android.app.Dialog alertDialog2 = new AlertDialog.Builder(this)
                    .setTitle("错误日志")
                    .setMessage("暂无错误日志")
                    .setPositiveButton("确定", null)
                    .show();
            DialogHelper.applyToAlertDialog(alertDialog2, this);
            return;
        }

        // 构建日志列表
        String[] items = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            items[i] = files[i].getName();
        }

        android.app.Dialog alertDialog3 = new AlertDialog.Builder(this)
                .setTitle("错误日志 (" + files.length + "条)")
                .setItems(items, (dialog, which) -> showLogDetail(files[which]))
                .setNeutralButton("清除全部", (dialog, which) -> {
                    CrashHandler.clearAllLogs();
                    updateErrorLogCount();
                })
                .setPositiveButton("关闭", null)
                .show();
        DialogHelper.applyToAlertDialog(alertDialog3, this);
    }

    private void showLogDetail(File file) {
        String content = CrashHandler.readLogFile(file);
        TextView textView = new TextView(this);
        textView.setText(content);
        textView.setTextSize(12f);
        textView.setTextColor(0xFFE0E0E0);
        textView.setBackgroundColor(0xFF1A1D23);
        textView.setPadding(24, 24, 24, 24);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setLineSpacing(4f, 1.2f);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(textView);

        android.app.Dialog alertDialog4 = new AlertDialog.Builder(this)
                .setTitle(file.getName())
                .setView(scrollView)
                .setPositiveButton("关闭", null)
                .setNeutralButton("分享日志", (dialog, which) -> {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_TEXT, content);
                    startActivity(Intent.createChooser(share, "分享错误日志"));
                })
                .setNegativeButton("删除此条", (dialog, which) -> {
                    file.delete();
                    updateErrorLogCount();
                })
                .show();
        DialogHelper.applyToAlertDialog(alertDialog4, this);
    }
}