package com.solosu.mtforum.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.solosu.mtforum.R;
import com.solosu.mtforum.session.BlacklistManager;
import com.solosu.mtforum.session.BlacklistSyncer;
import com.solosu.mtforum.ui.space.UserProfileActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 个人小黑屋管理页:合并展示 个人+服务端,支持移出/刷新。
 * 长按计数行 = 清空个人名单;点右上角刷新服务端。
 */
public class BlacklistActivity extends AppCompatActivity {

    private TextView tvCount;
    private EntryAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("个人小黑屋");
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F6F8);

        tvCount = new TextView(this);
        tvCount.setPadding(dp(20), dp(14), dp(20), dp(10));
        tvCount.setTextColor(0xFF888888);
        tvCount.setTextSize(13);
        tvCount.setClickable(true);
        // 长按计数行 = 清空个人名单
        tvCount.setOnLongClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("清空黑名单")
                    .setMessage("确定清空本地全部黑名单？")
                    .setPositiveButton("清空", (d, w) -> {
                        BlacklistManager.clearLocal(this);
                        Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                        render();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });
        root.addView(tvCount, new LinearLayout.LayoutParams(-1, -2));

        ListView listView = new ListView(this);
        listView.setDivider(new android.graphics.drawable.ColorDrawable(0x11000000));
        listView.setDividerHeight(1);
        adapter = new EntryAdapter(this);
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(-1, -1, 1f));

        setContentView(root);

        render();
        // 打开页面即同步服务端(7 天过期才真拉)
        BlacklistSyncer.syncIfNeeded(this, (count, err) -> runOnUiThread(this::render));
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void render() {
        List<BlacklistManager.Entry> all = new ArrayList<>();
        all.addAll(BlacklistManager.getLocalList(this));
        all.addAll(BlacklistManager.getServerList(this));
        adapter.reload(all);
        tvCount.setText("共 " + all.size() + " 人（个人 " + BlacklistManager.getLocalList(this).size()
                + " · 服务端 " + BlacklistManager.getServerList(this).size() + "）长按此处清空");
    }

    private int dp(float v) { return (int) (getResources().getDisplayMetrics().density * v + 0.5f); }

    /** 条目适配器 */
    private class EntryAdapter extends ArrayAdapter<BlacklistManager.Entry> {
        EntryAdapter(Context c) { super(c, 0); }
        void reload(List<BlacklistManager.Entry> data) {
            clear(); addAll(data); notifyDataSetChanged();
        }
        @Override
        public View getView(int position, View cv, ViewGroup parent) {
            ViewHolder h;
            if (cv == null) {
                cv = LayoutInflater.from(getContext()).inflate(R.layout.item_blacklist_entry, parent, false);
                h = new ViewHolder();
                h.name = cv.findViewById(R.id.tv_bl_name);
                h.meta = cv.findViewById(R.id.tv_bl_meta);
                h.remove = cv.findViewById(R.id.btn_bl_remove);
                h.avatar = cv.findViewById(R.id.iv_bl_avatar);
                cv.setTag(h);
            } else { h = (ViewHolder) cv.getTag(); }
            final BlacklistManager.Entry e = getItem(position);
            if (e == null) return cv;
            h.name.setText(!TextUtils.isEmpty(e.user) ? e.user : ("UID: " + e.uid));
            String src = "server".equals(e.source) ? "服务端" : "个人";
            String time = e.time > 0 ? new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date(e.time)) : "";
            h.meta.setText("UID " + e.uid + " · " + src + (time.isEmpty() ? "" : " · 拉黑于 " + time));
            h.remove.setOnClickListener(v -> {
                if ("server".equals(e.source)) {
                    BlacklistManager.removeServer(getContext(), e.uid);
                } else {
                    BlacklistManager.removeLocal(getContext(), e.uid);
                }
                render();
            });
            // 头像: 全站最可靠的 uc_server/avatar.php?uid=N 构造, 服务端条目也一样能拿
            Glide.with(BlacklistActivity.this)
                    .load("https://bbs.binmt.cc/uc_server/avatar.php?uid=" + e.uid + "&size=middle")
                    .placeholder(R.drawable.ic_account)
                    .error(R.drawable.ic_account)
                    .circleCrop()
                    .into(h.avatar);
            // 整行点击进用户详情
            cv.setOnClickListener(v -> {
                Intent it = new Intent(BlacklistActivity.this, UserProfileActivity.class);
                it.putExtra("uid", e.uid);
                it.putExtra("username", !TextUtils.isEmpty(e.user) ? e.user : "UID: " + e.uid);
                startActivity(it);
            });
            return cv;
        }
    }

    private static class ViewHolder {
        TextView name, meta, remove;
        android.widget.ImageView avatar;
    }
}
