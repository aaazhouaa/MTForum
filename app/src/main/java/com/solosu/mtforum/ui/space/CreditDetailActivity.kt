package com.solosu.mtforum.ui.space

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatActivity

import com.solosu.mtforum.R
import com.solosu.mtforum.databinding.ActivityCreditDetailBinding
import com.solosu.mtforum.network.ForumParser
import com.solosu.mtforum.network.HttpClient
import com.solosu.mtforum.ui.widget.FrostedGlassHelper

/**
 * 积分详情页（原生）
 * 展示积分/金币/好评/信誉以及详细资料信息
 */
class CreditDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreditDetailBinding
    private lateinit var httpClient: HttpClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreditDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        FrostedGlassHelper.applyToCardViews(binding.root, this)

        httpClient = HttpClient.getInstance()

        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_left)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.swipeRefresh.setOnRefreshListener { loadData() }
        binding.swipeRefresh.setColorSchemeResources(R.color.primary)

        loadData()
    }

    private fun loadData() {
        binding.progressBar.visibility = View.VISIBLE
        binding.swipeRefresh.isEnabled = false

        Thread {
            try {
                val url = HttpClient.BASE_URL + "home.php?mod=space&do=profile&mobile=2"
                val html = httpClient.get(url)

                if (ForumParser.isLoginPage(html)) {
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        binding.swipeRefresh.isRefreshing = false
                        binding.swipeRefresh.isEnabled = true
                        Toast.makeText(this, "登录已过期，请重新登录", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@Thread
                }

                val creditDetails = ForumParser.parseCreditDetails(html)

                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                    binding.swipeRefresh.isEnabled = true

                    binding.layoutCreditsGrid.removeAllViews()
                    binding.layoutProfileDetail.removeAllViews()

                    var hasCredits = false
                    var hasProfile = false
                    val creditKeys = arrayOf("积分", "好评", "金币", "信誉")

                    for ((label, value) in creditDetails) {
                        var isCreditItem = false
                        for (k in creditKeys) {
                            if (label.contains(k)) {
                                isCreditItem = true
                                break
                            }
                        }

                        // 积分类 → 积分卡片
                        if (isCreditItem) {
                            hasCredits = true
                            binding.layoutCreditsGrid.addView(createDetailRow(label, value))
                        } else if (!label.contains("用户ID") && !label.contains("在线时间")
                            && !label.contains("注册时间") && !label.contains("最后访问")
                            && !label.contains("性别") && !label.contains("生日")
                        ) {
                            // 这些资料已由 ProfileFragment 展示，不重复显示
                        }

                        // 详细资料项（注册时间、最后访问、在线时间、性别等）
                        if (label.contains("注册时间") || label.contains("最后访问")
                            || label.contains("在线时间") || label.contains("性别")
                            || label.contains("生日") || label.contains("用户ID")
                        ) {
                            hasProfile = true
                            binding.layoutProfileDetail.addView(createDetailRow(label, value))
                        }
                    }

                    if (!hasCredits) {
                        binding.layoutCreditsGrid.addView(createDetailRow("暂无积分数据", ""))
                    }
                    if (!hasProfile) {
                        binding.layoutProfileDetail.addView(createDetailRow("暂无详细资料", ""))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                    binding.swipeRefresh.isEnabled = true
                    Toast.makeText(this, "加载失败: " + e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun createDetailRow(label: String, value: String): View {
        val row = LinearLayout(this)
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, 8, 0, 8)

        val tvLabel = TextView(this)
        tvLabel.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        )
        tvLabel.text = label
        tvLabel.setTextSize(14f)
        tvLabel.setTextColor(getColor(R.color.text_secondary))

        val tvValue = TextView(this)
        tvValue.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        tvValue.text = value
        tvValue.setTextSize(14f)
        tvValue.setTextColor(getColor(R.color.text_primary))
        tvValue.setTypeface(null, android.graphics.Typeface.BOLD)

        row.addView(tvLabel)
        row.addView(tvValue)

        // 分割线
        val divider = View(this)
        divider.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        )
        divider.setBackgroundColor(getColor(R.color.divider))

        val container = LinearLayout(this)
        container.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        container.orientation = LinearLayout.VERTICAL
        container.addView(row)
        container.addView(divider)

        return container
    }
}
