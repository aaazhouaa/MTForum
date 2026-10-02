package com.solosu.mtforum.ai

import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity

import com.solosu.mtforum.databinding.ActivityAiConfigBinding

import java.util.Locale

/**
 * AI 配置页：模型接入 + 生成参数 + 提示词 + 自动回复策略。
 * 全部参数写入 AiConfigManager，供后台上传/自动回复引擎读取。
 */
class AiConfigActivity : AppCompatActivity() {

    private lateinit var b: ActivityAiConfigBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAiConfigBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.btnSave.setOnClickListener { saveAll() }
        b.btnResetPrompt.setOnClickListener { resetPrompts() }
        b.btnTest.setOnClickListener { testConnection() }
        b.btnFetchModels.setOnClickListener { fetchModels() }
        b.btnToolCheck.setOnClickListener { checkToolCalling() }

        // 下拉选中某个模型后回填到输入框
        b.spinnerModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val sel = parent!!.getItemAtPosition(position)
                if (sel != null) {
                    val m = sel.toString()
                    // 这里原来要求模型名必须含「.」才回填，结果 deepseek-chat / qwen-plus /
                    // moonshot-v1-8k / sn-kimi-k3 这类不含点的模型选了不生效。改成非空就回填。
                    if (!TextUtils.isEmpty(m)) {
                        b.etModel.setText(m)
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }

        loadAll()
        applyQuickFill()
    }

    // ==================== 模型列表 ====================

    /**
     * 拉取服务端 /models 列表并填入下拉。
     * 地址或 Key 有改动时先把它们落盘，AiClient 读的是 SharedPreferences。
     */
    private fun fetchModels() {
        if (TextUtils.isEmpty(text(b.etApiKey))) {
            toast("请先填写 API Key")
            return
        }
        AiConfigManager.setBaseUrl(
            this,
            if (TextUtils.isEmpty(text(b.etBaseUrl))) "https://api.openai.com/v1" else text(b.etBaseUrl)
        )
        AiConfigManager.setApiKey(this, text(b.etApiKey))

        b.btnFetchModels.isEnabled = false
        b.tvModelStatus.text = "正在获取…"
        b.spinnerModel.visibility = View.GONE

        Thread({
            val r = AiClient.listModels(this)
            runOnUiThread {
                b.btnFetchModels.isEnabled = true
                if (!r.success || r.models == null || r.models.isEmpty()) {
                    b.tvModelStatus.text = "获取失败：" + (if (r.error == null) "未知错误" else r.error)
                    return@runOnUiThread
                }
                val models = r.models
                val adapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_dropdown_item, models!!
                )
                b.spinnerModel.adapter = adapter
                b.spinnerModel.visibility = View.VISIBLE

                // 当前模型若在列表中，直接选中它
                val cur = text(b.etModel)
                val sel = models.indexOf(cur)
                if (sel >= 0) b.spinnerModel.setSelection(sel)

                b.tvModelStatus.text = "共 " + models.size + " 个可用模型，下拉选择即可"
            }
        }, "ai-models").start()
    }

    // ==================== 读取 ====================

    private fun loadAll() {
        b.etBaseUrl.setText(AiConfigManager.getBaseUrl(this))
        b.etApiKey.setText(AiConfigManager.getApiKey(this))
        b.etModel.setText(AiConfigManager.getModel(this))
        b.etSystemPrompt.setText(AiConfigManager.getSystemPrompt(this))
        b.etReplyPrompt.setText(AiConfigManager.getReplyPrompt(this))
        b.etMaxTokens.setText(AiConfigManager.getMaxTokens(this).toString())
        b.etTimeout.setText(AiConfigManager.getTimeoutSeconds(this).toString())

        b.etMaxPerRun.setText(AiConfigManager.getMaxReplyPerRun(this).toString())
        b.etMinLength.setText(AiConfigManager.getMinReplyLength(this).toString())
        b.etInterval.setText(AiConfigManager.getReplyInterval(this).toString())

        b.switchOnlyOwn.isChecked = AiConfigManager.isOnlyReplyOwnThreads(this)
        b.switchUnlockMode.isChecked = AiConfigManager.isUnlockMode(this)
        b.switchUnlockOnView.isChecked = AiConfigManager.isUnlockOnView(this)
        b.switchDryRun.isChecked = AiConfigManager.isDryRun(this)
        b.switchSendTemperature.isChecked = AiConfigManager.isSendTemperature(this)

        // 温度 0.0 - 2.0，SeekBar 0-20 映射
        val temp = AiConfigManager.getTemperature(this)
        var progress = Math.round(temp * 10f)
        if (progress < 0) progress = 0
        if (progress > 20) progress = 20
        b.seekTemperature.progress = progress
        updateTempLabel(progress)
        b.seekTemperature.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, p: Int, fromUser: Boolean) {
                updateTempLabel(p)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
            }
        })
    }

    private fun updateTempLabel(progress: Int) {
        b.tvTemperature.text = String.format(Locale.US, "%.1f", progress / 10f)
    }

    // ==================== 快捷填充 ====================

    private fun applyQuickFill() {
        b.chipOpenai.setOnClickListener {
            b.etBaseUrl.setText("https://api.openai.com/v1")
            if (TextUtils.isEmpty(b.etModel.text)) b.etModel.setText("gpt-4o-mini")
            resetModelList()
        }
        b.chipDeepseek.setOnClickListener {
            b.etBaseUrl.setText("https://api.deepseek.com/v1")
            b.etModel.setText("deepseek-chat")
            resetModelList()
        }
        b.chipDashscope.setOnClickListener {
            b.etBaseUrl.setText("https://dashscope.aliyuncs.com/compatible-mode/v1")
            b.etModel.setText("qwen-plus")
            resetModelList()
        }
        b.chipMoonshot.setOnClickListener {
            b.etBaseUrl.setText("https://api.moonshot.cn/v1")
            b.etModel.setText("moonshot-v1-8k")
            resetModelList()
        }
    }

    /** 切换服务商后清掉旧的下拉列表，避免张冠李戴 */
    private fun resetModelList() {
        b.spinnerModel.visibility = View.GONE
        b.tvModelStatus.text = ""
    }

    // ==================== 保存 ====================

    private fun saveAll() {
        val baseUrl = text(b.etBaseUrl)
        val apiKey = text(b.etApiKey)
        val model = text(b.etModel)

        if (TextUtils.isEmpty(apiKey)) {
            toast("请填写 API Key")
            return
        }
        if (TextUtils.isEmpty(model)) {
            toast("请填写模型名称")
            return
        }

        AiConfigManager.setBaseUrl(
            this,
            if (TextUtils.isEmpty(baseUrl)) "https://api.openai.com/v1" else baseUrl
        )
        AiConfigManager.setApiKey(this, apiKey)
        AiConfigManager.setModel(this, model)

        AiConfigManager.setSystemPrompt(this, text(b.etSystemPrompt))
        AiConfigManager.setReplyPrompt(this, text(b.etReplyPrompt))

        AiConfigManager.setTemperature(this, b.seekTemperature.progress / 10f)
        AiConfigManager.setMaxTokens(this, intOf(b.etMaxTokens, 8192, 1, 128000))
        AiConfigManager.setTimeoutSeconds(this, intOf(b.etTimeout, 60, 10, 600))

        AiConfigManager.setMaxReplyPerRun(this, intOf(b.etMaxPerRun, 3, 1, 50))
        AiConfigManager.setMinReplyLength(this, intOf(b.etMinLength, 8, 1, 200))
        AiConfigManager.setReplyInterval(this, intOf(b.etInterval, 300, 30, 86400))

        AiConfigManager.setOnlyReplyOwnThreads(this, b.switchOnlyOwn.isChecked)
        AiConfigManager.setUnlockMode(this, b.switchUnlockMode.isChecked)
        AiConfigManager.setUnlockOnView(this, b.switchUnlockOnView.isChecked)
        AiConfigManager.setDryRun(this, b.switchDryRun.isChecked)
        AiConfigManager.setSendTemperature(this, b.switchSendTemperature.isChecked)

        toast("已保存")
        finish()
    }

    private fun resetPrompts() {
        b.etSystemPrompt.setText(AiConfigManager.defaultSystemPrompt())
        b.etReplyPrompt.setText(AiConfigManager.defaultReplyPrompt())
        toast("已恢复默认提示词，记得点保存")
    }

    // ==================== 测试连接 ====================

    private fun testConnection() {
        val apiKey = text(b.etApiKey)
        if (TextUtils.isEmpty(apiKey)) {
            toast("请先填写 API Key")
            return
        }
        // 先落盘，AiClient 读的是 SharedPreferences
        AiConfigManager.setBaseUrl(
            this,
            if (TextUtils.isEmpty(text(b.etBaseUrl))) "https://api.openai.com/v1" else text(b.etBaseUrl)
        )
        AiConfigManager.setApiKey(this, apiKey)
        AiConfigManager.setModel(this, text(b.etModel))
        AiConfigManager.setMaxTokens(this, intOf(b.etMaxTokens, 8192, 1, 128000))
        AiConfigManager.setTimeoutSeconds(this, intOf(b.etTimeout, 60, 10, 600))

        showResult("正在测试…", true)
        b.btnTest.isEnabled = false

        Thread({
            val r = AiClient.chat(
                this,
                java.util.Arrays.asList(
                    AiClient.Msg.system("你是一个测试助手，只回一句话。"),
                    AiClient.Msg.user("回复：连接成功")
                ),
                null
            )
            runOnUiThread {
                b.btnTest.isEnabled = true
                if (r.success) {
                    showResult(
                        "✅ 连接成功\n模型返回：" + (if (r.content == null) "" else r.content) +
                                "\nToken 用量：prompt=" + r.promptTokens +
                                ", completion=" + r.completionTokens, false
                    )
                } else {
                    showResult("❌ 连接失败\n" + r.error, false)
                }
            }
        }, "ai-test").start()
    }

    private fun showResult(text: String, loading: Boolean) {
        b.tvTestResult.visibility = View.VISIBLE
        b.tvTestResult.text = text
    }

    // ==================== 工具调用自检 ====================

    /**
     * 用一个必调工具的微型请求探测该模型/中转是否支持 function calling。
     * 结果直接写明是"支持"还是"不支持"，并给出当前模型更适合走哪条路。
     */
    private fun checkToolCalling() {
        if (TextUtils.isEmpty(text(b.etApiKey))) {
            toast("请先填写 API Key")
            return
        }
        AiConfigManager.setBaseUrl(
            this,
            if (TextUtils.isEmpty(text(b.etBaseUrl))) "https://api.openai.com/v1" else text(b.etBaseUrl)
        )
        AiConfigManager.setApiKey(this, text(b.etApiKey))
        AiConfigManager.setModel(this, text(b.etModel))
        AiConfigManager.setMaxTokens(this, intOf(b.etMaxTokens, 8192, 1, 128000))

        showResult("正在探测…（会分别发一次带 tools 和不带 tools 的请求）", true)
        b.btnToolCheck.isEnabled = false

        Thread({
            // 一个极小的测试工具，参数固定，模型只要支持工具调用就必然会调它
            val tools: List<AiClient.ToolDef> = java.util.Arrays.asList(
                AiClient.ToolDef(
                    "get_weather", "查询指定城市的天气。",
                    "{\"type\":\"object\",\"properties\":{" +
                            "\"city\":{\"type\":\"string\",\"description\":\"城市名\"}}," +
                            "\"required\":[\"city\"]}"
                )
            )
            val msgs: List<AiClient.Msg> = java.util.Arrays.asList(
                AiClient.Msg.system("你必须使用提供的工具来回答，不要直接回答。"),
                AiClient.Msg.user("北京今天天气怎么样？")
            )

            val withTools = AiClient.chat(this, msgs, tools, 1024)

            // 对照：同一问题不带 tools，看它是否会"只描述要调用什么"
            val withoutTools = AiClient.chat(
                this, java.util.Arrays.asList(
                    AiClient.Msg.system("你可以调用 get_weather(city) 这个工具。"),
                    AiClient.Msg.user("北京今天天气怎么样？")
                ), null
            )

            runOnUiThread {
                b.btnToolCheck.isEnabled = true

                val sb = StringBuilder()
                val called = withTools.success && withTools.toolCalls != null &&
                        withTools.toolCalls!!.length() > 0

                sb.append(
                    if (called) "✅ 支持工具调用（function calling）\n\n"
                    else "❌ 不支持、或中转没转发 tools\n\n"
                )

                sb.append("带 tools 请求：")
                if (!withTools.success) {
                    sb.append("失败 → ").append(withTools.error).append('\n')
                } else {
                    sb.append("finish_reason=").append(
                        if (TextUtils.isEmpty(withTools.finishReason))
                            "空" else withTools.finishReason
                    )
                        .append("，tool_calls=")
                        .append(if (withTools.toolCalls == null) 0 else withTools.toolCalls!!.length())
                        .append("，content=")
                        .append(if (TextUtils.isEmpty(withTools.content)) "空" else "有")
                        .append('\n')
                }

                sb.append("\n不带 tools 请求：")
                if (!withoutTools.success) {
                    sb.append("失败 → ").append(withoutTools.error).append('\n')
                } else {
                    sb.append("content=")
                        .append(if (TextUtils.isEmpty(withoutTools.content)) "空" else "有")
                        .append('\n')
                }

                sb.append('\n')
                if (called) {
                    sb.append("结论：这个模型可以直接用工具读论坛数据，走标准模式即可。")
                } else {
                    sb.append(
                        "结论：模型只会输出文字。应用已内置兼容层，" +
                                "会自动从文字里识别调用意图并代它执行，所以功能仍可用；" +
                                "若想更快更稳，建议换成 gpt-4o-mini / deepseek-chat。"
                    )
                }

                // 把模型实际说的话也带上一小段，便于肉眼判断
                if (withTools.success && !TextUtils.isEmpty(withTools.content)) {
                    sb.append("\n\n模型带 tools 时的原话：\n")
                        .append(AiLog.clip(withTools.content, 300))
                } else if (withoutTools.success && !TextUtils.isEmpty(withoutTools.content)) {
                    sb.append("\n\n模型原话：\n").append(AiLog.clip(withoutTools.content, 300))
                }

                showResult(sb.toString(), false)
            }
        }, "ai-tool-check").start()
    }

    // ==================== 工具 ====================

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private fun text(et: EditText): String {
            return if (et.text == null) "" else et.text.toString().trim()
        }

        private fun intOf(et: EditText, def: Int, min: Int, max: Int): Int {
            return try {
                var v = text(et).toInt()
                if (v < min) v = min
                if (v > max) v = max
                v
            } catch (e: Exception) {
                def
            }
        }
    }
}
