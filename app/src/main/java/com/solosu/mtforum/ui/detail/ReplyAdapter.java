package com.solosu.mtforum.ui.detail;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.TextPaint;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.solosu.mtforum.R;
import com.solosu.mtforum.model.ReplyItem;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.util.BBCodeUtil;
import com.solosu.mtforum.util.NavigationHelper;
import com.solosu.mtforum.ui.space.UserProfileActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 回复列表适配器
 * 支持 Glide 加载头像、楼层标签、楼主标识、等级、时间、地点等完整信息
 */
public class ReplyAdapter extends RecyclerView.Adapter<ReplyAdapter.ViewHolder> {

    private List<ReplyItem> replyList;
    private OnReplyClickListener replyClickListener;
    private OnUserClickListener userClickListener;
    private OnReplyLongClickListener replyLongClickListener; // build73: 长按出操作菜单

    public interface OnReplyClickListener {
        void onReplyClick(ReplyItem item, int position);
    }

    public interface OnUserClickListener {
        void onUserClick(ReplyItem item, int position);
    }

    /** build73: 长按评论 -> 回复/举报/删除 菜单 */
    public interface OnReplyLongClickListener {
        void onReplyLongClick(ReplyItem item, int position);
    }

    public void setOnReplyLongClickListener(OnReplyLongClickListener listener) {
        this.replyLongClickListener = listener;
    }

    public void setOnReplyClickListener(OnReplyClickListener listener) {
        this.replyClickListener = listener;
    }

    public void setOnUserClickListener(OnUserClickListener listener) {
        this.userClickListener = listener;
    }

    public ReplyAdapter(List<ReplyItem> replyList) {
        this.replyList = replyList;
    }

    public void updateData(List<ReplyItem> newList) {
        this.replyList = newList;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_reply, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ReplyItem item = replyList.get(position);
        holder.bind(item);
    }

    @Override
    public int getItemCount() {
        return replyList == null ? 0 : replyList.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {

        private final ImageView ivAvatar;
        private final TextView tvFloorLabel;
        private final TextView tvAuthor;
        private final TextView tvOpBadge;
        private final TextView tvLevel;
        private final TextView tvTime;
        private final TextView tvContent;
        private final LinearLayout layoutReplyQuote;
        private final TextView tvReplyQuote;
        private final LinearLayout llReplyImages;
        private final TextView btnReplyTo;
        private final ImageView ivReplyMore;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivAvatar = itemView.findViewById(R.id.iv_reply_avatar);
            tvFloorLabel = itemView.findViewById(R.id.tv_floor_label);
            tvAuthor = itemView.findViewById(R.id.tv_reply_author);
            tvOpBadge = itemView.findViewById(R.id.tv_op_badge);
            tvLevel = itemView.findViewById(R.id.tv_reply_level);
            tvTime = itemView.findViewById(R.id.tv_reply_time);
            tvContent = itemView.findViewById(R.id.tv_reply_content);
            layoutReplyQuote = itemView.findViewById(R.id.layout_reply_quote);
            tvReplyQuote = itemView.findViewById(R.id.tv_reply_quote);
            llReplyImages = itemView.findViewById(R.id.ll_reply_images);
            btnReplyTo = itemView.findViewById(R.id.btn_reply_to);
            ivReplyMore = itemView.findViewById(R.id.iv_reply_more);
        }

        void bind(ReplyItem item) {
            // 头像 - Glide 加载圆图
            String avatarUrl = item.getAvatarUrl();
            if (!TextUtils.isEmpty(avatarUrl)) {
                Glide.with(ivAvatar.getContext())
                        .load(avatarUrl)
                        .transform(new CircleCrop())
                        .placeholder(R.drawable.ic_account)
                        .error(R.drawable.ic_account)
                        .into(ivAvatar);
            } else {
                ivAvatar.setImageResource(R.drawable.ic_account);
            }

            // ★ 新增：头像和用户名点击 → 打开原生用户资料页
            final int pos = getAdapterPosition();
            final ReplyItem currentItem = item;
            ivAvatar.setOnClickListener(v -> {
                if (userClickListener != null && !TextUtils.isEmpty(currentItem.getAuthorUid())) {
                    userClickListener.onUserClick(currentItem, pos);
                }
            });
            tvAuthor.setOnClickListener(v -> {
                if (userClickListener != null && !TextUtils.isEmpty(currentItem.getAuthorUid())) {
                    userClickListener.onUserClick(currentItem, pos);
                }
            });

            // build73: 整项长按 -> 操作菜单(回复/举报/删除)
            itemView.setOnLongClickListener(v -> {
                if (replyLongClickListener != null) {
                    replyLongClickListener.onReplyLongClick(currentItem, getAdapterPosition());
                    return true;
                }
                return false;
            });

            // 楼层标签（沙发/椅子/地毯/报纸/N#）
            String floorLabel = item.getFloorLabel();
            if (!TextUtils.isEmpty(floorLabel)) {
                tvFloorLabel.setVisibility(View.VISIBLE);
                tvFloorLabel.setText(floorLabel);
            } else {
                tvFloorLabel.setVisibility(View.GONE);
            }

            // 作者名
            tvAuthor.setText(!TextUtils.isEmpty(item.getAuthor()) ? item.getAuthor() : "匿名");

            // 楼主标识
            if (item.isOP()) {
                tvOpBadge.setVisibility(View.VISIBLE);
            } else {
                tvOpBadge.setVisibility(View.GONE);
            }

            // 等级
            String level = item.getAuthorLevel();
            if (!TextUtils.isEmpty(level)) {
                tvLevel.setVisibility(View.VISIBLE);
                tvLevel.setText(level);
            } else {
                tvLevel.setVisibility(View.GONE);
            }

            // 时间
            String time = item.getTime();
            if (!TextUtils.isEmpty(time)) {
                tvTime.setVisibility(View.VISIBLE);
                tvTime.setText(time);
            } else {
                tvTime.setVisibility(View.GONE);
            }

            // 评论区按参考样式仅显示时间，不显示回复项地点，避免与回复按钮并列出现重复灰色定位文字。

            // 回复按钮 + 更多(⋮)
            String author = item.getAuthor();
            if (!TextUtils.isEmpty(author)) {
                btnReplyTo.setVisibility(View.VISIBLE);
                btnReplyTo.setOnClickListener(v -> {
                    if (replyClickListener != null) {
                        replyClickListener.onReplyClick(item, getAdapterPosition());
                    }
                });
                if (ivReplyMore != null) {
                    ivReplyMore.setVisibility(View.VISIBLE);
                    ivReplyMore.setOnClickListener(v -> {
                        if (replyLongClickListener != null) {
                            replyLongClickListener.onReplyLongClick(item, getAdapterPosition());
                        }
                    });
                }
            } else {
                btnReplyTo.setVisibility(View.GONE);
                if (ivReplyMore != null) ivReplyMore.setVisibility(View.GONE);
            }

            // 内容 - 优先显示纯文本

            // 引用内容单独显示，模拟网页端的浅黄色引用框；当前回复保持深色正文。
            String quotedText = item.getQuotedContentText();
            if (!TextUtils.isEmpty(quotedText)) {
                layoutReplyQuote.setVisibility(View.VISIBLE);
                tvReplyQuote.setText(Html.fromHtml(quotedText, Html.FROM_HTML_MODE_COMPACT,
                        createInlineImageGetter(tvReplyQuote), BBCodeUtil.createTagHandler(itemView.getContext())));
                attachCopyOnLongClick(tvReplyQuote);
            } else {
                layoutReplyQuote.setVisibility(View.GONE);
                tvReplyQuote.setText("");
            }

            String contentText = item.getContentText();
            String htmlContent = item.getContentHtml();
            boolean hasCodeBlock = htmlContent != null
                    && (htmlContent.contains("comiis_blockcode") || htmlContent.contains("<pre"));

            if (!TextUtils.isEmpty(contentText) && !hasCodeBlock) {
                tvContent.setVisibility(View.VISIBLE);
                // ★ 修复1：使用 ImageGetter 渲染内联图片（表情等），否则 <img> 标签被静默丢弃
                tvContent.setText(Html.fromHtml(contentText, Html.FROM_HTML_MODE_COMPACT,
                        createInlineImageGetter(tvContent), BBCodeUtil.createTagHandler(itemView.getContext())));
                // ★ 修复2：先设置链接，再配置其他属性（避免 setText 覆盖文本）
                setupClickableLinks(tvContent);
                attachCopyOnLongClick(tvContent);
                // 回复内容中的大图处理
                if (!TextUtils.isEmpty(htmlContent)) {
                    loadReplyImages(itemView.getContext(), htmlContent, llReplyImages);
                } else {
                    llReplyImages.setVisibility(View.GONE);
                }
            } else if (!TextUtils.isEmpty(htmlContent)) {
                tvContent.setVisibility(View.VISIBLE);
                // 提取图片URL，移除img标签后渲染文本（也保留内联表情）
                List<String> replyImageUrls = new ArrayList<>();
                String cleanHtml = extractImagesFromHtml(htmlContent, replyImageUrls);
                // ★ 修复2：用 ImageGetter 渲染剩下的内联图片
                tvContent.setText(Html.fromHtml(cleanHtml, Html.FROM_HTML_MODE_COMPACT,
                        createInlineImageGetter(tvContent), BBCodeUtil.createTagHandler(itemView.getContext())));
                // ★ 修复2：先设置链接，再配置其他属性（避免 setText 覆盖文本）
                setupClickableLinks(tvContent);
                attachCopyOnLongClick(tvContent);
                // 加载图片
                if (!replyImageUrls.isEmpty()) {
                    llReplyImages.removeAllViews();
                    llReplyImages.setVisibility(View.VISIBLE);
                    for (String imgUrl : replyImageUrls) {
                        ImageView imageView = new ImageView(itemView.getContext());
                        imageView.setLayoutParams(new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT));
                        imageView.setAdjustViewBounds(true);
                        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        imageView.setBackgroundColor(itemView.getContext().getColor(R.color.background_secondary));
                        // 限制图片最大尺寸，防止撑爆屏幕
                        int maxImgWidth = getMaxImageWidth(itemView.getContext());
                        imageView.setMaxWidth(maxImgWidth);
                        imageView.setMaxHeight((int)(maxImgWidth * 1.2f));
                        // ★ 添加点击预览
                        imageView.setClickable(true);
                        imageView.setFocusable(true);
                        setImageClick(itemView.getContext(), imageView, imgUrl);
                        Glide.with(itemView.getContext())
                                .load(imgUrl)
                                .placeholder(new ColorDrawable(itemView.getContext().getColor(R.color.background_secondary)))
                                .error(new ColorDrawable(itemView.getContext().getColor(R.color.divider)))
                                .into(imageView);
                        llReplyImages.addView(imageView);
                    }
                } else {
                    llReplyImages.setVisibility(View.GONE);
                }
            } else {
                tvContent.setVisibility(View.GONE);
                llReplyImages.setVisibility(View.GONE);
            }
        }
    }

    /**
     * 为 TextView 设置可点击链接（蓝色高亮 + 可点击跳转）
     * ★ 统一方案：与 ThreadDetailActivity.setupClickableLinks 保持一致
     * 
     * 注意：此方法不会覆盖已有文本，只会在现有 Spannable 上添加链接处理
     */
    /**
     * build71: 长按复制回复内容。
     * 之前 setupClickableLinks 里写死 setLongClickable(false)(为了不拦截链接点击),
     * 导致别人回复根本没法复制。长按和单击是两个事件,挂长按不影响链接跳转。
     */
    private static void attachCopyOnLongClick(final TextView textView) {
        if (textView == null) return;
        textView.setLongClickable(true);
        textView.setOnLongClickListener(v -> {
            CharSequence cs = textView.getText();
            String text = cs == null ? "" : cs.toString().trim();
            if (text.isEmpty()) return false;
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        textView.getContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("回复内容", text));
                    android.widget.Toast.makeText(textView.getContext(), "已复制回复内容",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                }
            } catch (Exception ignored) {
            }
            return false;
        });
    }

    private static void setupClickableLinks(TextView textView) {
        if (textView == null) return;
        
        // ★ 关键：禁用文本选择和长按，避免拦截触摸事件
        textView.setTextIsSelectable(false);
        textView.setFocusable(false);
        textView.setClickable(false);
        textView.setLongClickable(false);
        textView.setHighlightColor(0x332196F3);
        
        // 获取当前文本（可能已经是 Spannable）
        CharSequence value = textView.getText();
        if (!(value instanceof Spannable)) {
            // 如果不是 Spannable，创建一个并设置回 TextView
            value = new SpannableString(value == null ? "" : value);
            textView.setText(value, TextView.BufferType.SPANNABLE);
        }
        
        // 强制重新获取 Spannable（确保是最新的）
        Spannable spannable = (Spannable) textView.getText();
        
        // 使用自定义正则识别URL，点击时打开链接
        Pattern urlPattern = Pattern.compile(
            "(?<!\\w)" +  // 前面不是单词字符
            "(?:" +
                "https?://[^\\s<>\"\\x00-\\x1f\\x7f-\\xff]+" +  // http/https开头的URL
                "|" +
                "www\\.[^\\s<>\"\\x00-\\x1f\\x7f-\\xff]+" +      // www开头的URL
            ")" +
            "(?<![,.;:!?)>])",  // 后面不是标点符号
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        FixNestedScrollLinkMovementMethod.matcherLinkify(
            spannable, 
            urlPattern, 
            url -> url,  // URL处理器：直接返回原始URL
            url -> openContentLink(url, textView.getContext())  // 点击处理器：打开链接
        );
        
        // Html.fromHtml() 产生 URLSpan；统一替换为应用自己的 ClickableSpan
        URLSpan[] urlSpans = spannable.getSpans(0, spannable.length(), URLSpan.class);
        for (URLSpan oldSpan : urlSpans) {
            String targetUrl = oldSpan.getURL();
            // 补全相对路径
            if (targetUrl.startsWith("//")) {
                targetUrl = "https:" + targetUrl;
            } else if (targetUrl.startsWith("/")) {
                targetUrl = HttpClient.BASE_URL + targetUrl.substring(1);
            } else if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
                targetUrl = HttpClient.BASE_URL + targetUrl;
            }
            
            int start = spannable.getSpanStart(oldSpan);
            int end = spannable.getSpanEnd(oldSpan);
            int flags = spannable.getSpanFlags(oldSpan);
            spannable.removeSpan(oldSpan);
            if (start >= 0 && end > start && !TextUtils.isEmpty(targetUrl)) {
                final String finalUrl = targetUrl;
                spannable.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        openContentLink(finalUrl, widget.getContext());
                    }
                    @Override
                    public void updateDrawState(TextPaint ds) {
                        ds.setColor(0xFF1976D2);
                        ds.setUnderlineText(true);
                    }
                }, start, end, flags);
            }
        }
        
        // ★ 关键修复：不要再次调用 setText()，直接更新 movementMethod
        textView.setMovementMethod(LinkMovementMethod.getInstance());
        textView.setAutoLinkMask(0);
    }
    
    /**
     * 打开链接：直接交给系统默认浏览器处理，不再进入应用内 WebView。
     */
    private static void openContentLink(String url, Context context) {
        if (TextUtils.isEmpty(url) || context == null) return;
        try {
            String lower = url.toLowerCase(java.util.Locale.ROOT);
            boolean isForumLink = lower.contains("bbs.binmt.cc");

            if (isForumLink) {
                // 论坛帖子链接
                Matcher threadMatcher = Pattern.compile("thread[-=]?(\\d+)").matcher(lower);
                if (threadMatcher.find()) {
                    NavigationHelper.openThread(context, threadMatcher.group(1));
                    return;
                }
                // 用户空间链接
                Matcher uidMatcher = Pattern.compile("(?:uid[-=]|(?<=[?&])uid=)(\\d+)").matcher(lower);
                if (uidMatcher.find()) {
                    Intent intent = new Intent(context, (Class<?>) UserProfileActivity.class);
                    intent.putExtra("uid", uidMatcher.group(1));
                    context.startActivity(intent);
                    return;
                }
                Matcher usernameMatcher = Pattern.compile("space-username-([^./?&]+)").matcher(lower);
                if (usernameMatcher.find()) {
                    Intent intent = new Intent(context, (Class<?>) UserProfileActivity.class);
                    intent.putExtra("username", usernameMatcher.group(1));
                    context.startActivity(intent);
                    return;
                }
            }
            // 非论坛链接,用浏览器打开
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(browserIntent);
        } catch (Exception ignored) {
        }
    }

    /**
     * 为评论中的图片 ImageView 添加点击预览监听
     */
    private static void setImageClick(Context context, ImageView imageView, String imgUrl) {
        imageView.setOnClickListener(v -> {
            if (context != null && !android.text.TextUtils.isEmpty(imgUrl)) {
                Intent intent = new Intent(context, ImagePreviewActivity.class);
                intent.putExtra("image_url", imgUrl);
                context.startActivity(intent);
            }
        });
    }

    /**
     * 从 HTML 内容中提取所有 img 标签的 src URL，并返回去掉 img 标签后的纯 HTML
     */
    private static String extractImagesFromHtml(String html, List<String> outImageUrls) {
        if (TextUtils.isEmpty(html)) {
            return "";
        }
        Pattern pattern = Pattern.compile("<img[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String url = matcher.group(1);
            // ★ 修复3：补全所有相对路径，否则图片全部丢失
            String fullUrl = normalizeImageUrl(url);
            if (fullUrl == null) fullUrl = url;

            // 过滤内联小图/表情，保留在文本中
            if (fullUrl.contains("smiley") || fullUrl.contains("emoticon")
                    || fullUrl.contains("face") || fullUrl.contains("/static/image/")
                    || fullUrl.contains("stamp") || fullUrl.contains("magic")
                    || fullUrl.contains("mini") || fullUrl.contains("icon")) {
                // 将原img标签中的src替换为补全后的完整URL
                String origTag = matcher.group(0);
                String newTag = origTag.replaceFirst("src\\s*=\\s*['\"][^'\"]*['\"]",
                        "src=\"" + fullUrl + "\"");
                matcher.appendReplacement(sb, Matcher.quoteReplacement(newTag));
                continue;
            }
            // 大图：只接受 http/https
            if (fullUrl.startsWith("http://") || fullUrl.startsWith("https://")) {
                outImageUrls.add(fullUrl);
            }
            matcher.appendReplacement(sb, "");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 补全图片URL：处理 //、/ 和 ./ 开头的相对路径
     */
    private static String normalizeImageUrl(String url) {
        if (TextUtils.isEmpty(url)) return null;
        if (url.startsWith("//")) {
            return "https:" + url;
        } else if (url.startsWith("/")) {
            return HttpClient.BASE_URL + url.substring(1);
        } else if (url.startsWith("./")) {
            return HttpClient.BASE_URL + url.substring(2);
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        // 其他情况（不含协议的相对路径如 "data/attachment/..."）
        return HttpClient.BASE_URL + url;
    }

    /**
     * 从 HTML 内容中提取图片 URL 列表，并用 Glide 加载到 llReplyImages 布局中
     */
    private static void loadReplyImages(Context context, String html, LinearLayout container) {
        List<String> urls = new ArrayList<>();
        extractImagesFromHtml(html, urls);
        if (urls.isEmpty()) {
            container.setVisibility(View.GONE);
            return;
        }
        container.removeAllViews();
        container.setVisibility(View.VISIBLE);
        int maxImgWidth = getMaxImageWidth(context);
        for (String imgUrl : urls) {
            ImageView imageView = new ImageView(context);
            imageView.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            imageView.setAdjustViewBounds(true);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imageView.setBackgroundColor(context.getColor(R.color.background_secondary));
            imageView.setMaxWidth(maxImgWidth);
            imageView.setMaxHeight((int)(maxImgWidth * 1.2f));
            // ★ 添加点击预览
            imageView.setClickable(true);
            imageView.setFocusable(true);
            setImageClick(context, imageView, imgUrl);
            Glide.with(context)
                    .load(imgUrl)
                    .placeholder(new ColorDrawable(context.getColor(R.color.background_secondary)))
                    .error(new ColorDrawable(context.getColor(R.color.divider)))
                    .into(imageView);
            container.addView(imageView);
        }
    }

    /**
     * 获取评论图片的最大显示宽度（屏幕宽度的80%，最多不超过360dp）
     */
    private static int getMaxImageWidth(Context context) {
        int screenWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
        int maxDp = 360;
        int maxPx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, maxDp, context.getResources().getDisplayMetrics());
        int width80 = (int)(screenWidth * 0.8f);
        return Math.min(width80, maxPx);
    }

    // ==================== 内联图片渲染（表情等） ====================

    /**
     * 为 Html.fromHtml 提供 ImageGetter，用 Glide 异步加载内联图片（表情等小图）
     * 修复：旧实现 Glide 加载完成后未回填 ImageSpan 持有的占位 Drawable，导致内联图永远透明。
     * 现改用 UrlDrawable 占位 + setReal 回填 + 宿主 TextView 重排。表情限 24dp，大图限宽。
     */
    private static Html.ImageGetter createInlineImageGetter(TextView targetView) {
        return source -> {
            String imgUrl = source;
            if (imgUrl.startsWith("//")) {
                imgUrl = "https:" + imgUrl;
            } else if (imgUrl.startsWith("/")) {
                imgUrl = HttpClient.BASE_URL + imgUrl.substring(1);
            } else if (!imgUrl.startsWith("http")) {
                imgUrl = HttpClient.BASE_URL + imgUrl;
            }

            final TextView tv = targetView;
            final com.solosu.mtforum.util.UrlDrawable placeholder =
                    new com.solosu.mtforum.util.UrlDrawable(tv, dpToPx(tv.getContext(), 24));
            final int maxW = getMaxImageWidth(tv.getContext());

            Glide.with(tv.getContext())
                    .load(imgUrl)
                    .into(new CustomTarget<Drawable>() {
                        @Override
                        public void onResourceReady(@NonNull Drawable resource,
                                                    @Nullable Transition<? super Drawable> transition) {
                            int w = resource.getIntrinsicWidth();
                            int h = resource.getIntrinsicHeight();
                            int emotSize = dpToPx(tv.getContext(), 24);
                            int maxSize = maxW > 0 ? maxW : dpToPx(tv.getContext(), 320);
                            if (w <= 0) {
                                w = emotSize;
                            }
                            if (h <= 0) {
                                h = emotSize;
                            }
                            // 表情类小图（≤32dp）保持原尺寸；大图限宽
                            if (w <= dpToPx(tv.getContext(), 32)) {
                                if (w > emotSize || h > emotSize) {
                                    float r = (float) emotSize / Math.max(w, h);
                                    w = (int) (w * r);
                                    h = (int) (h * r);
                                }
                            } else if (w > maxSize) {
                                h = (int) ((long) h * maxSize / Math.max(1, w));
                                w = maxSize;
                            }
                            resource.setBounds(0, 0, w, h);
                            placeholder.setReal(resource, tv);
                        }

                        @Override
                        public void onLoadCleared(@Nullable Drawable placeholderD) {
                        }
                    });
            return placeholder;
        };
    }

    private static int dpToPx(Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }
}
