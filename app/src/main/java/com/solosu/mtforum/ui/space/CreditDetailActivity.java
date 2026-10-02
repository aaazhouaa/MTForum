package com.solosu.mtforum.ui.space;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.solosu.mtforum.R;
import com.solosu.mtforum.databinding.ActivityCreditDetailBinding;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.ui.widget.FrostedGlassHelper;

import java.util.Map;

/**
 * 积分详情页（原生）
 * 展示积分/金币/好评/信誉以及详细资料信息
 */
public class CreditDetailActivity extends AppCompatActivity {

    private ActivityCreditDetailBinding binding;
    private HttpClient httpClient;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCreditDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        FrostedGlassHelper.applyToCardViews(binding.getRoot(), this);

        httpClient = HttpClient.getInstance();

        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_left);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        binding.swipeRefresh.setOnRefreshListener(this::loadData);
        binding.swipeRefresh.setColorSchemeResources(R.color.primary);

        loadData();
    }

    private void loadData() {
        binding.progressBar.setVisibility(View.VISIBLE);
        binding.swipeRefresh.setEnabled(false);

        new Thread(() -> {
            try {
                String url = HttpClient.BASE_URL + "home.php?mod=space&do=profile&mobile=2";
                String html = httpClient.get(url);

                if (ForumParser.isLoginPage(html)) {
                    runOnUiThread(() -> {
                        binding.progressBar.setVisibility(View.GONE);
                        binding.swipeRefresh.setRefreshing(false);
                        binding.swipeRefresh.setEnabled(true);
                        Toast.makeText(this, "登录已过期，请重新登录", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                    return;
                }

                Map<String, String> creditDetails = ForumParser.parseCreditDetails(html);

                runOnUiThread(() -> {
                    binding.progressBar.setVisibility(View.GONE);
                    binding.swipeRefresh.setRefreshing(false);
                    binding.swipeRefresh.setEnabled(true);

                    binding.layoutCreditsGrid.removeAllViews();
                    binding.layoutProfileDetail.removeAllViews();

                    boolean hasCredits = false;
                    boolean hasProfile = false;
                    String[] creditKeys = {"积分", "好评", "金币", "信誉"};

                    for (Map.Entry<String, String> entry : creditDetails.entrySet()) {
                        String label = entry.getKey();
                        String value = entry.getValue();

                        boolean isCreditItem = false;
                        for (String k : creditKeys) {
                            if (label.contains(k)) {
                                isCreditItem = true;
                                break;
                            }
                        }

                        // 积分类 → 积分卡片
                        if (isCreditItem) {
                            hasCredits = true;
                            binding.layoutCreditsGrid.addView(createDetailRow(label, value));
                        } else if (!label.contains("用户ID") && !label.contains("在线时间")
                                && !label.contains("注册时间") && !label.contains("最后访问")
                                && !label.contains("性别") && !label.contains("生日")) {
                            // 这些资料已由 ProfileFragment 展示，不重复显示
                        }

                        // 详细资料项（注册时间、最后访问、在线时间、性别等）
                        if (label.contains("注册时间") || label.contains("最后访问")
                                || label.contains("在线时间") || label.contains("性别")
                                || label.contains("生日") || label.contains("用户ID")) {
                            hasProfile = true;
                            binding.layoutProfileDetail.addView(createDetailRow(label, value));
                        }
                    }

                    if (!hasCredits) {
                        binding.layoutCreditsGrid.addView(createDetailRow("暂无积分数据", ""));
                    }
                    if (!hasProfile) {
                        binding.layoutProfileDetail.addView(createDetailRow("暂无详细资料", ""));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    binding.progressBar.setVisibility(View.GONE);
                    binding.swipeRefresh.setRefreshing(false);
                    binding.swipeRefresh.setEnabled(true);
                    Toast.makeText(this, "加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private View createDetailRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 8, 0, 8);

        TextView tvLabel = new TextView(this);
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        tvLabel.setText(label);
        tvLabel.setTextSize(14);
        tvLabel.setTextColor(getColor(R.color.text_secondary));

        TextView tvValue = new TextView(this);
        tvValue.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        tvValue.setText(value);
        tvValue.setTextSize(14);
        tvValue.setTextColor(getColor(R.color.text_primary));
        tvValue.setTypeface(null, android.graphics.Typeface.BOLD);

        row.addView(tvLabel);
        row.addView(tvValue);

        // 分割线
        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(getColor(R.color.divider));

        LinearLayout container = new LinearLayout(this);
        container.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(row);
        container.addView(divider);

        return container;
    }
}