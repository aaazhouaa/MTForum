package com.solosu.mtforum.ui.detail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.solosu.mtforum.R

import java.util.ArrayList

/**
 * 全屏图片预览 Activity（多图翻页版）
 * - ViewPager2 横向翻页（一帖多图自由滑动切换）
 * - 单页内 ZoomableImageView：双指缩放/双击放大/拖动/单击关闭
 * - 页码指示器 1/N
 */
class ImagePreviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 沉浸式全屏
        val w: Window = window
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        w.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        setContentView(R.layout.activity_image_preview)

        val pager: ViewPager2 = findViewById(R.id.vp_images)
        val tvIndicator: TextView = findViewById(R.id.tv_page_indicator)
        val btnClose: ImageButton = findViewById(R.id.btn_close)
        btnClose.setOnClickListener { v -> finish() }

        // 图片列表 + 初始位置
        var urls: MutableList<String>? = intent.getStringArrayListExtra("image_urls")
        val singleUrl: String? = intent.getStringExtra("image_url")
        var initPos = intent.getIntExtra("image_index", 0)
        if (urls == null) {
            urls = ArrayList()
        }
        if (urls.isEmpty() && singleUrl != null && !singleUrl.isEmpty()) {
            urls.add(singleUrl)
        }
        if (urls.isEmpty()) {
            Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (initPos < 0 || initPos >= urls.size) {
            initPos = 0
        }

        val fUrls: MutableList<String> = urls // lambda 引用需 final
        val adapter = PagerAdapter(fUrls)
        pager.setAdapter(adapter)
        pager.setCurrentItem(initPos, false)
        if (fUrls.size > 1) {
            tvIndicator.setText((initPos + 1).toString() + "/" + fUrls.size)
        } else {
            tvIndicator.visibility = View.GONE
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (fUrls.size > 1) {
                    tvIndicator.setText((position + 1).toString() + "/" + fUrls.size)
                }
            }
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    /** 每页一个 ZoomableImageView */
    private class PagerAdapter(private val urls: MutableList<String>) : RecyclerView.Adapter<PagerAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v: View = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_image_preview_page, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val url: String = urls[position]
            Glide.with(holder.itemView.context)
                    .load(url)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(android.graphics.drawable.ColorDrawable(0xFF333333.toInt()))
                    .error(android.graphics.drawable.ColorDrawable(0xFF111111.toInt()))
                    .into(holder.ivImage)
        }

        override fun getItemCount(): Int {
            return urls.size
        }

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val ivImage: ZoomableImageView = itemView.findViewById(R.id.iv_page_image)

            init {
                // 单击关闭(context 非 Activity 时静默)
                ivImage.setOnViewTapListener {
                    val ctx = itemView.context
                    if (ctx is ImagePreviewActivity) {
                        ctx.finish()
                    }
                }
            }
        }
    }
}
