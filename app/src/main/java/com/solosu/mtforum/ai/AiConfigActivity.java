package com.solosu.mtforum.ai;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.solosu.mtforum.databinding.ActivityAiConfigBinding;

import java.util.List;
import java.util.Locale;

/**
 * AI 配置页：模型接入 + 生成参数 + 提示词 + 自动回复策略。
 * 全部参数写入 AiConfigManager，供后台上传/自动回复引擎读取。
 */
public class AiConfigActivity extends AppCompatActivity {

    private ActivityAiConfigBinding b;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityAiConfigBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        b.btnBack.setOnClickListener(v -> finish());
        b.btnSave.setOnClickListener(v -> saveAll());
        b.btnResetPrompt.setOnClickListener(v -> resetPrompts());
        b.btnTest.setOnClickListener(v -> testConnection());
        b.btnFetchModels.setOnClickListener(v -> fetchModels());
        b.btnToolCheck.setOnClickListener(v -> checkToolCalling());

        // 下拉选中某个模型后回填到输入框
        b.spinnerModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Object sel = parent.getItemAtPosition(position);
                if (sel != null) {
                    String m = String.valueOf(sel);
                    // 这里原来要求模型名必须含「.」才回填，结果 deepseek-chat / qwen-plus /
                    // moonshot-v1-8k / sn-kimi-k3 这类不含点的模型选了不生效。改成非空就回填。
                    if (!TextUtils.isEmpty(m)) {
                        b.etModel.setText(m);
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        loadAll();
        applyQuickFill();
    }

    // ==================== 模型列表 ====================

    /**
     * 拉取服务端 /models 列表并填入下拉。
     * 地址或 Key 有改动时先把它们落盘，AiClient 读的是 SharedPreferences。
     */
    private void fetchModels() {
        if (TextUtils.isEmpty(text(b.etApiKey))) {
            toast("请先填写 API Key");
            return;
        }
        AiConfigManager.setBaseUrl(this,
                TextUtils.isEmpty(text(b.etBaseUrl)) ? "https://api.openai.com/v1" : text(b.etBaseUrl));
        AiConfigManager.setApiKey(this, text(b.etApiKey));

        b.btnFetchModels.setEnabled(false);
        b.tvModelStatus.setText("正在获取…");
        b.spinnerModel.setVisibility(View.GONE);

        new Thread(() -> {
            AiClient.Result r = AiClient.listModels(this);
            runOnUiThread(() -> {
                b.btnFetchModels.setEnabled(true);
                if (!r.success || r.models == null || r.models.isEmpty()) {
                    b.tvModelStatus.setText("获取失败：" + (r.error == null ? "未知错误" : r.error));
                    return;
                }
                List<String> models = r.models;
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_dropdown_item, models);
                b.spinnerModel.setAdapter(adapter);
                b.spinnerModel.setVisibility(View.VISIBLE);

                // 当前模型若在列表中，直接选中它
                String cur = text(b.etModel);
                int sel = models.indexOf(cur);
                if (sel >= 0) b.spinnerModel.setSelection(sel);

                b.tvModelStatus.setText("共 " + models.size() + " 个可用模型，下拉选择即可");
            });
        }, "ai-models").start();
    }

    // ==================== 读取 ====================

    private void loadAll() {
        b.etBaseUrl.setText(AiConfigManager.getBaseUrl(this));
        b.etApiKey.setText(AiConfigManager.getApiKey(this));
        b.etModel.setText(AiConfigManager.getModel(this));
        b.etSystemPrompt.setText(AiConfigManager.getSystemPrompt(this));
        b.etReplyPrompt.setText(AiConfigManager.getReplyPrompt(this));
        b.etMaxTokens.setText(String.valueOf(AiConfigManager.getMaxTokens(this)));
        b.etTimeout.setText(String.valueOf(AiConfigManager.getTimeoutSeconds(this)));

        b.etMaxPerRun.setText(String.valueOf(AiConfigManager.getMaxReplyPerRun(this)));
        b.etMinLength.setText(String.valueOf(AiConfigManager.getMinReplyLength(this)));
        b.etInterval.setText(String.valueOf(AiConfigManager.getReplyInterval(this)));

        b.switchOnlyOwn.setChecked(AiConfigManager.isOnlyReplyOwnThreads(this));
        b.switchUnlockMode.setChecked(AiConfigManager.isUnlockMode(this));
        b.switchUnlockOnView.setChecked(AiConfigManager.isUnlockOnView(this));
        b.switchDryRun.setChecked(AiConfigManager.isDryRun(this));
        b.switchSendTemperature.setChecked(AiConfigManager.isSendTemperature(this));

        // 温度 0.0 - 2.0，SeekBar 0-20 映射
        float temp = AiConfigManager.getTemperature(this);
        int progress = Math.round(temp * 10f);
        if (progress < 0) progress = 0;
        if (progress > 20) progress = 20;
        b.seekTemperature.setProgress(progress);
        updateTempLabel(progress);
        b.seekTemperature.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                updateTempLabel(p);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
    }

    private void updateTempLabel(int progress) {
        b.tvTemperature.setText(String.format(Locale.US, "%.1f", progress / 10f));
    }

    // ==================== 快捷填充 ====================

    private void applyQuickFill() {
        b.chipOpenai.setOnClickListener(v -> {
            b.etBaseUrl.setText("https://api.openai.com/v1");
            if (TextUtils.isEmpty(b.etModel.getText())) b.etModel.setText("gpt-4o-mini");
            resetModelList();
        });
        b.chipDeepseek.setOnClickListener(v -> {
            b.etBaseUrl.setText("https://api.deepseek.com/v1");
            b.etModel.setText("deepseek-chat");
            resetModelList();
        });
        b.chipDashscope.setOnClickListener(v -> {
            b.etBaseUrl.setText("https://dashscope.aliyuncs.com/compatible-mode/v1");
            b.etModel.setText("qwen-plus");
            resetModelList();
        });
        b.chipMoonshot.setOnClickListener(v -> {
            b.etBaseUrl.setText("https://api.moonshot.cn/v1");
            b.etModel.setText("moonshot-v1-8k");
            resetModelList();
        });
    }

    /** 切换服务商后清掉旧的下拉列表，避免张冠李戴 */
    private void resetModelList() {
        b.spinnerModel.setVisibility(View.GONE);
        b.tvModelStatus.setText("");
    }

    // ==================== 保存 ====================

    private void saveAll() {
        String baseUrl = text(b.etBaseUrl);
        String apiKey = text(b.etApiKey);
        String model = text(b.etModel);

        if (TextUtils.isEmpty(apiKey)) {
            toast("请填写 API Key");
            return;
        }
        if (TextUtils.isEmpty(model)) {
            toast("请填写模型名称");
            return;
        }

        AiConfigManager.setBaseUrl(this,
                TextUtils.isEmpty(baseUrl) ? "https://api.openai.com/v1" : baseUrl);
        AiConfigManager.setApiKey(this, apiKey);
        AiConfigManager.setModel(this, model);

        AiConfigManager.setSystemPrompt(this, text(b.etSystemPrompt));
        AiConfigManager.setReplyPrompt(this, text(b.etReplyPrompt));

        AiConfigManager.setTemperature(this, b.seekTemperature.getProgress() / 10f);
        AiConfigManager.setMaxTokens(this, intOf(b.etMaxTokens, 8192, 1, 128000));
        AiConfigManager.setTimeoutSeconds(this, intOf(b.etTimeout, 60, 10, 600));

        AiConfigManager.setMaxReplyPerRun(this, intOf(b.etMaxPerRun, 3, 1, 50));
        AiConfigManager.setMinReplyLength(this, intOf(b.etMinLength, 8, 1, 200));
        AiConfigManager.setReplyInterval(this, intOf(b.etInterval, 300, 30, 86400));

        AiConfigManager.setOnlyReplyOwnThreads(this, b.switchOnlyOwn.isChecked());
        AiConfigManager.setUnlockMode(this, b.switchUnlockMode.isChecked());
        AiConfigManager.setUnlockOnView(this, b.switchUnlockOnView.isChecked());
        AiConfigManager.setDryRun(this, b.switchDryRun.isChecked());
        AiConfigManager.setSendTemperature(this, b.switchSendTemperature.isChecked());

        toast("已保存");
        finish();
    }

    private void resetPrompts() {
        b.etSystemPrompt.setText(AiConfigManager.defaultSystemPrompt());
        b.etReplyPrompt.setText(AiConfigManager.defaultReplyPrompt());
        toast("已恢复默认提示词，记得点保存");
    }

    // ==================== 测试连接 ====================

    private void testConnection() {
        String apiKey = text(b.etApiKey);
        if (TextUtils.isEmpty(apiKey)) {
            toast("请先填写 API Key");
            return;
        }
        // 先落盘，AiClient 读的是 SharedPreferences
        AiConfigManager.setBaseUrl(this,
                TextUtils.isEmpty(text(b.etBaseUrl)) ? "https://api.openai.com/v1" : text(b.etBaseUrl));
        AiConfigManager.setApiKey(this, apiKey);
        AiConfigManager.setModel(this, text(b.etModel));
        AiConfigManager.setMaxTokens(this, intOf(b.etMaxTokens, 8192, 1, 128000));
        AiConfigManager.setTimeoutSeconds(this, intOf(b.etTimeout, 60, 10, 600));

        showResult("正在测试…", true);
        b.btnTest.setEnabled(false);

        new Thread(() -> {
            AiClient.Result r = AiClient.chat(this,
                    java.util.Arrays.asList(
                            AiClient.Msg.system("你是一个测试助手，只回一句话。"),
                            AiClient.Msg.user("回复：连接成功")),
                    null);
            runOnUiThread(() -> {
                b.btnTest.setEnabled(true);
                if (r.success) {
                    showResult("✅ 连接成功\n模型返回：" + (r.content == null ? "" : r.content)
                            + "\nToken 用量：prompt=" + r.promptTokens
                            + ", completion=" + r.completionTokens, false);
                } else {
                    showResult("❌ 连接失败\n" + r.error, false);
                }
            });
        }, "ai-test").start();
    }

    private void showResult(String text, boolean loading) {
        b.tvTestResult.setVisibility(View.VISIBLE);
        b.tvTestResult.setText(text);
    }

    // ==================== 工具调用自检 ====================

    /**
     * 用一个必调工具的微型请求探测该模型/中转是否支持 function calling。
     * 结果直接写明是"支持"还是"不支持"，并给出当前模型更适合走哪条路。
     */
    private void checkToolCalling() {
        if (TextUtils.isEmpty(text(b.etApiKey))) {
            toast("请先填写 API Key");
            return;
        }
        AiConfigManager.setBaseUrl(this,
                TextUtils.isEmpty(text(b.etBaseUrl)) ? "https://api.openai.com/v1" : text(b.etBaseUrl));
        AiConfigManager.setApiKey(this, text(b.etApiKey));
        AiConfigManager.setModel(this, text(b.etModel));
        AiConfigManager.setMaxTokens(this, intOf(b.etMaxTokens, 8192, 1, 128000));

        showResult("正在探测…（会分别发一次带 tools 和不带 tools 的请求）", true);
        b.btnToolCheck.setEnabled(false);

        new Thread(() -> {
            // 一个极小的测试工具，参数固定，模型只要支持工具调用就必然会调它
            java.util.List<AiClient.ToolDef> tools = java.util.Arrays.asList(
                    new AiClient.ToolDef("get_weather", "查询指定城市的天气。",
                            "{\"type\":\"object\",\"properties\":{"
                                    + "\"city\":{\"type\":\"string\",\"description\":\"城市名\"}},"
                                    + "\"required\":[\"city\"]}"));
            java.util.List<AiClient.Msg> msgs = java.util.Arrays.asList(
                    AiClient.Msg.system("你必须使用提供的工具来回答，不要直接回答。"),
                    AiClient.Msg.user("北京今天天气怎么样？"));

            AiClient.Result withTools = AiClient.chat(this, msgs, tools, 1024);

            // 对照：同一问题不带 tools，看它是否会"只描述要调用什么"
            AiClient.Result withoutTools = AiClient.chat(this, java.util.Arrays.asList(
                    AiClient.Msg.system("你可以调用 get_weather(city) 这个工具。"),
                    AiClient.Msg.user("北京今天天气怎么样？")), null);

            runOnUiThread(() -> {
                b.btnToolCheck.setEnabled(true);

                StringBuilder sb = new StringBuilder();
                boolean called = withTools.success && withTools.toolCalls != null
                        && withTools.toolCalls.length() > 0;

                sb.append(called ? "✅ 支持工具调用（function calling）\n\n"
                        : "❌ 不支持、或中转没转发 tools\n\n");

                sb.append("带 tools 请求：");
                if (!withTools.success) {
                    sb.append("失败 → ").append(withTools.error).append('\n');
                } else {
                    sb.append("finish_reason=").append(TextUtils.isEmpty(withTools.finishReason)
                            ? "空" : withTools.finishReason)
                            .append("，tool_calls=")
                            .append(withTools.toolCalls == null ? 0 : withTools.toolCalls.length())
                            .append("，content=")
                            .append(TextUtils.isEmpty(withTools.content) ? "空" : "有")
                            .append('\n');
                }

                sb.append("\n不带 tools 请求：");
                if (!withoutTools.success) {
                    sb.append("失败 → ").append(withoutTools.error).append('\n');
                } else {
                    sb.append("content=")
                            .append(TextUtils.isEmpty(withoutTools.content) ? "空" : "有")
                            .append('\n');
                }

                sb.append('\n');
                if (called) {
                    sb.append("结论：这个模型可以直接用工具读论坛数据，走标准模式即可。");
                } else {
                    sb.append("结论：模型只会输出文字。应用已内置兼容层，"
                            + "会自动从文字里识别调用意图并代它执行，所以功能仍可用；"
                            + "若想更快更稳，建议换成 gpt-4o-mini / deepseek-chat。");
                }

                // 把模型实际说的话也带上一小段，便于肉眼判断
                if (withTools.success && !TextUtils.isEmpty(withTools.content)) {
                    sb.append("\n\n模型带 tools 时的原话：\n")
                            .append(AiLog.clip(withTools.content, 300));
                } else if (withoutTools.success && !TextUtils.isEmpty(withoutTools.content)) {
                    sb.append("\n\n模型原话：\n").append(AiLog.clip(withoutTools.content, 300));
                }

                showResult(sb.toString(), false);
            });
        }, "ai-tool-check").start();
    }

    // ==================== 工具 ====================

    private static String text(EditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private static int intOf(EditText et, int def, int min, int max) {
        try {
            int v = Integer.parseInt(text(et));
            if (v < min) v = min;
            if (v > max) v = max;
            return v;
        } catch (Exception e) {
            return def;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
