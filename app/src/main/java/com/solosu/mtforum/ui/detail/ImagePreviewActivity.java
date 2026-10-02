package com.solosu.mtforum.ui.detail;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.solosu.mtforum.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 全屏图片预览 Activity（多图翻页版）
 * - ViewPager2 横向翻页（一帖多图自由滑动切换）
 * - 单页内 ZoomableImageView：双指缩放/双击放大/拖动/单击关闭
 * - 页码指示器 1/N
 */
public class ImagePreviewActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 沉浸式全屏
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(R.layout.activity_image_preview);

        ViewPager2 pager = findViewById(R.id.vp_images);
        TextView tvIndicator = findViewById(R.id.tv_page_indicator);
        ImageButton btnClose = findViewById(R.id.btn_close);
        btnClose.setOnClickListener(v -> finish());

        // 图片列表 + 初始位置
        List<String> urls = getIntent().getStringArrayListExtra("image_urls");
        String singleUrl = getIntent().getStringExtra("image_url");
        int initPos = getIntent().getIntExtra("image_index", 0);
        if (urls == null) {
            urls = new ArrayList<>();
        }
        if ((urls.isEmpty()) && singleUrl != null && !singleUrl.isEmpty()) {
            urls.add(singleUrl);
        }
        if (urls.isEmpty()) {
            Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (initPos < 0 || initPos >= urls.size()) {
            initPos = 0;
        }

        final List<String> fUrls = urls; // lambda 引用需 final
        PagerAdapter adapter = new PagerAdapter(fUrls);
        pager.setAdapter(adapter);
        pager.setCurrentItem(initPos, false);
        if (fUrls.size() > 1) {
            tvIndicator.setText((initPos + 1) + "/" + fUrls.size());
        } else {
            tvIndicator.setVisibility(View.GONE);
        }
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (fUrls.size() > 1) {
                    tvIndicator.setText((position + 1) + "/" + fUrls.size());
                }
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    /** 每页一个 ZoomableImageView */
    private static class PagerAdapter extends RecyclerView.Adapter<PagerAdapter.VH> {

        private final List<String> urls;

        PagerAdapter(List<String> urls) {
            this.urls = urls;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_image_preview_page, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            String url = urls.get(position);
            Glide.with(holder.itemView.getContext())
                    .load(url)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(new android.graphics.drawable.ColorDrawable(0xFF333333))
                    .error(new android.graphics.drawable.ColorDrawable(0xFF111111))
                    .into(holder.ivImage);
        }

        @Override
        public int getItemCount() {
            return urls.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            final ZoomableImageView ivImage;

            VH(@NonNull View itemView) {
                super(itemView);
                ivImage = (ZoomableImageView) itemView.findViewById(R.id.iv_page_image);
                // 单击关闭(context 非 Activity 时静默)
                ivImage.setOnViewTapListener(() -> {
                    android.content.Context ctx = itemView.getContext();
                    if (ctx instanceof ImagePreviewActivity) {
                        ((ImagePreviewActivity) ctx).finish();
                    }
                });
            }
        }
    }
}