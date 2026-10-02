package com.solosu.mtforum.ui.post;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ImageSpan;
import android.view.View;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.solosu.mtforum.R;
import com.solosu.mtforum.model.ForumCategory;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.session.DraftManager;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.solosu.mtforum.ui.widget.DialogHelper;

/**
 * 发帖页面 Activity
 * 完全对接网页端功能：表情、@朋友、插入、附件、高级
 */
public class PostActivity extends AppCompatActivity {

    // views
    private TextView tvCancel;
    private TextInputEditText etTitle;
    private TextView tvTitleCount;
    private LinearLayout llCircleSelector;
    private TextView tvSelectedForum;
    private TextInputEditText etContent;
    private CheckBox cbAnonymous;
    private MaterialButton btnPublish;
    private MaterialButton btnAiOptimize;
    private MaterialButton btnAiPost;
    private TextView tvError;

    // 五大功能按钮 + 图片按钮
    private TextView btnSmiley, btnAt, btnInsert, btnImage, btnAttach, btnAdvanced;
    private LinearLayout llSmileyPanel, llAtPanel;
    private TextInputEditText etAtUsername;
    private TextView btnAtInsert;
    private LinearLayout llAdvancedOptions, llAttachList;
    private LinearLayout llImagePreview;

    // data
    private String selectedFid;
    private String selectedForumName;
    private String currentFormhash;
    private String currentUid;
    private String currentHash;
    private long draftId;
    private boolean postedDone;
    // build73: 编辑模式(本人帖)上下文
    private String editTid;
    private String editPid;

    // 附件列表
    private final List<AttachFile> attachFiles = new ArrayList<>();
    private static final int REQUEST_FILE_PICK = 1001;
    private static final int REQUEST_IMAGE_PICK = 1002;

    // 表情列表（从网页端提取）
    private static final String[][] DEFAULT_SMILEYS = {
            {":)", "smile.gif"}, {":D", "biggrin.gif"}, {":'(", "cry.gif"},
            {":@", "huffy.gif"}, {":o", "shocked.gif"}, {":P", "tongue.gif"},
            {":$", "sweat.gif"}, {":L", "sad.gif"}, {";P", "titter.gif"},
            {":lol", "lol.gif"}, {":victory:", "victory.gif"}, {":time:", "time.gif"},
            {":kiss:", "kiss.gif"}, {":handshake", "handshake.gif"}, {":call:", "call.gif"}
    };

    private static class AttachFile {
        String name;
        String path;
        String url;
        String bbcode;
        String aid;

        AttachFile(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.post_activity);

        initViews();
        setupTitleCounter();
        setupCircleSelector();
        setupToolbarButtons();
        setupPublishButton();
        // build73: 编辑模式优先于草稿恢复
        loadFormhashAndUserInfo();
        setupEditMode();
        if (!isEditMode()) {
            restoreDraft();
        }
    }

    private void initViews() {
        tvCancel = findViewById(R.id.tv_cancel);
        tvCancel.setOnClickListener(v -> finish());
        TextView tvDrafts = findViewById(R.id.tv_drafts);
        if (tvDrafts != null) tvDrafts.setOnClickListener(v -> showDraftsDialog());

        etTitle = findViewById(R.id.et_title);
        tvTitleCount = findViewById(R.id.tv_title_count);
        llCircleSelector = findViewById(R.id.ll_circle_selector);
        tvSelectedForum = findViewById(R.id.tv_selected_forum);
        etContent = findViewById(R.id.et_content);
        cbAnonymous = findViewById(R.id.cb_anonymous);
        btnPublish = findViewById(R.id.btn_publish);
        btnAiOptimize = findViewById(R.id.btn_ai_optimize);
        btnAiPost = findViewById(R.id.btn_ai_post);
        tvError = findViewById(R.id.tv_error);

        // 五大功能按钮
        btnSmiley = findViewById(R.id.btn_smiley);
        btnAt = findViewById(R.id.btn_at);
        btnInsert = findViewById(R.id.btn_insert);
        btnImage = findViewById(R.id.btn_image);
        btnAttach = findViewById(R.id.btn_attach);
        btnAdvanced = findViewById(R.id.btn_advanced);

        // 功能面板
        llSmileyPanel = findViewById(R.id.ll_smiley_panel);
        llAtPanel = findViewById(R.id.ll_at_panel);
        etAtUsername = findViewById(R.id.et_at_username);
        btnAtInsert = findViewById(R.id.btn_at_insert);
        llAdvancedOptions = findViewById(R.id.ll_advanced_options);
        llAttachList = findViewById(R.id.ll_attach_list);
        llImagePreview = findViewById(R.id.ll_image_preview);
    }

    private void setupTitleCounter() {
        etTitle.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                int len = s != null ? s.length() : 0;
                tvTitleCount.setText(len + "/80");
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void setupCircleSelector() {
        llCircleSelector.setOnClickListener(v -> showForumPicker());
    }

    // ==================== 五大功能 ====================

    private void setupToolbarButtons() {
        // 1. 表情
        btnSmiley.setOnClickListener(v -> toggleSmileyPanel());

        // 2. @朋友
        btnAt.setOnClickListener(v -> {
            hideAllPanels();
            llAtPanel.setVisibility(View.VISIBLE);
        });
        btnAtInsert.setOnClickListener(v -> {
            String name = etAtUsername.getText() != null ?
                    etAtUsername.getText().toString().trim() : "";
            if (name.isEmpty()) {
                Toast.makeText(this, "请输入用户名", Toast.LENGTH_SHORT).show();
                return;
            }
            insertIntoContent("@ " + name + " ");
            llAtPanel.setVisibility(View.GONE);
            etAtUsername.setText("");
            Toast.makeText(this, "已插入 @" + name, Toast.LENGTH_SHORT).show();
        });

        // 3. 插入（引用/代码/Free/Hide）
        btnInsert.setOnClickListener(v -> showInsertDialog());

        // 4. 🖼 图片上传（从相册选图，与网页端对齐）
        btnImage.setOnClickListener(v -> {
            hideAllPanels();
            pickImage();
        });

        // 5. 文件附件
        btnAttach.setOnClickListener(v -> pickFile());

        // 5. 高级
        btnAdvanced.setOnClickListener(v -> {
            hideAllPanels();
            llAdvancedOptions.setVisibility(
                    llAdvancedOptions.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });
    }

    // ---- 表情功能 ----
    private void toggleSmileyPanel() {
        // 自绘制快速回复图标,替代 emoji 表情
        hideAllPanelsExcept(llSmileyPanel);
        if (llSmileyPanel.getVisibility() == View.VISIBLE) {
            llSmileyPanel.setVisibility(View.GONE);
            return;
        }
        llSmileyPanel.setVisibility(View.VISIBLE);
        LinearLayout container = findViewById(R.id.ll_smiley_container);
        if (container == null) return;
        container.removeAllViews();

        int[] iconIds = {
            R.drawable.ic_smile, R.drawable.ic_heart, R.drawable.ic_thumbs_up,
            R.drawable.ic_fire, R.drawable.ic_star_filled, R.drawable.ic_check,
            R.drawable.ic_cross, R.drawable.ic_lightbulb, R.drawable.ic_pin
        };
        String[] labels = {"微笑", "爱心", "点赞", "火热", "收藏", "同意", "反对", "想法", "置顶"};
        int size = (int) (48 * getResources().getDisplayMetrics().density);
        int padding = (int)(6 * getResources().getDisplayMetrics().density);

        for (int idx = 0; idx < iconIds.length; idx++) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(padding, padding / 2, padding, padding / 2);
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(size, size);
            item.setLayoutParams(itemLp);
            item.setBackgroundResource(android.R.drawable.list_selector_background);
            item.setClickable(true);
            item.setFocusable(true);

            ImageView iv = new ImageView(this);
            int iconSize = (int) (28 * getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(iconSize, iconSize);
            iv.setLayoutParams(ivLp);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageResource(iconIds[idx]);
            item.addView(iv);

            TextView tv = new TextView(this);
            tv.setText(labels[idx]);
            tv.setTextSize(9f);
            tv.setTextColor(0xFF9CA3AF);
            tv.setGravity(Gravity.CENTER);
            tv.setMaxLines(1);
            item.addView(tv);

            final String tag = "[" + labels[idx] + "]";
            item.setOnClickListener(v -> {
                Editable editable = etContent.getText();
                if (editable == null) {
                    etContent.setText(tag);
                    return;
                }
                int selStart = etContent.getSelectionStart();
                if (selStart < 0) selStart = editable.length();
                editable.insert(selStart, tag);
                etContent.setSelection(selStart + tag.length());
            });
            container.addView(item);
        }
    }

    // ---- 插入功能 ----
    private void showInsertDialog() {
        hideAllPanels();
        final String[] items = {"引用", "代码", "免费信息", "隐藏内容"};
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("插入内容");
        builder.setItems(items, (dialog, which) -> {
            String bbcode = "";
            switch (which) {
                case 0: // 引用
                    bbcode = "\n[quote]请输入引用内容[/quote]\n";
                    break;
                case 1: // 代码
                    bbcode = "\n[code]请输入代码[/code]\n";
                    break;
                case 2: // 免费信息
                    bbcode = "\n[free]请输入免费内容[/free]\n";
                    break;
                case 3: // 隐藏内容
                    bbcode = "\n[hide]请输入隐藏内容[/hide]\n";
                    break;
            }
            insertIntoContent(bbcode);
            Toast.makeText(this, "已插入" + items[which], Toast.LENGTH_SHORT).show();
        });
        android.app.Dialog alertDialog = builder.show();
        DialogHelper.applyToAlertDialog(alertDialog, this);
    }

    // ---- 🖼 图片上传功能（与网页端对齐：支持多选、预览、上传） ----

    private void pickImage() {
        hideAllPanels();
        // 使用 Intent.ACTION_PICK 打开系统相册，支持多图选择
        Intent intent;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR2) {
            intent = new Intent(Intent.ACTION_PICK);
            intent.setData(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.setType("image/*");
        } else {
            intent = new Intent(Intent.ACTION_PICK);
            intent.setType("image/*");
        }
        startActivityForResult(intent, REQUEST_IMAGE_PICK);
    }

private void uploadImages(List<Uri> uris) {
        Toast.makeText(this, "正在上传 " + uris.size() + " 张图片...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                if (!ensureUploadAuth()) {
                    runOnUiThread(() -> Toast.makeText(this, "获取上传授权失败，请先选择版块或重新登录", Toast.LENGTH_SHORT).show());
                    return;
                }

                // 与网页端 buildfileupload 完全一致：simple=2 + inajax/infloat。
                String uploadUrl = HttpClient.BASE_URL
                        + "misc.php?mod=swfupload&operation=upload"
                        + "&type=image&inajax=yes&infloat=yes&simple=2";

                for (int i = 0; i < uris.size(); i++) {
                    Uri uri = uris.get(i);
                    String rawName = getFileNameFromUri(uri);
                    String fileName = rawName.matches("(?i).*\\.(jpg|jpeg|png|gif|bmp|webp)$")
                            ? rawName : "image_" + System.currentTimeMillis() + ".jpg";
                    File tempFile = copyUriToTempFile(uri, fileName);
                    if (tempFile == null) continue;

                    Map<String, String> extraFields = new HashMap<>();
                    extraFields.put("uid", currentUid);
                    extraFields.put("hash", currentHash);
                    String response = HttpClient.getInstance().uploadFile(
                            uploadUrl, tempFile, "Filedata", extraFields);
                    String aid = parseDiscuzUploadResponse(response);
                    if (aid == null) {
                        runOnUiThread(() -> Toast.makeText(this,
                                fileName + " 上传失败", Toast.LENGTH_SHORT).show());
                        continue;
                    }

                    String bbcode = "\n[attachimg]" + aid + "[/attachimg]\n";
                    AttachFile af = new AttachFile(fileName, tempFile.getAbsolutePath());
                    af.aid = aid;
                    af.bbcode = bbcode;
                    synchronized (attachFiles) { attachFiles.add(af); }
                    runOnUiThread(() -> {
                        insertIntoContent(bbcode);
                        updateAttachList();
                        updateImagePreview();
                    });
                }
                runOnUiThread(() -> Toast.makeText(this,
                        "图片上传处理完成", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "图片上传异常: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void updateImagePreview() {
        if (llImagePreview == null) return;
        llImagePreview.removeAllViews();
        // 筛选出图片类型的附件
        List<AttachFile> images = new ArrayList<>();
        for (AttachFile af : attachFiles) {
            if (af.name != null && af.name.matches("(?i).*\\.(jpg|jpeg|png|gif|bmp|webp)$")) {
                images.add(af);
            }
        }
        if (images.isEmpty()) {
            llImagePreview.setVisibility(View.GONE);
            return;
        }
        llImagePreview.setVisibility(View.VISIBLE);

        LinearLayout row = null;
        int colCount = 4;
        int density = (int) getResources().getDisplayMetrics().density;
        int imgSize = (int) (75 * density);

        for (int i = 0; i < images.size(); i++) {
            if (i % colCount == 0) {
                row = new LinearLayout(this);
                row.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, 0, 0, (int)(4 * density));
                llImagePreview.addView(row);
            }

            AttachFile af = images.get(i);
            ImageView iv = new ImageView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(imgSize, imgSize);
            lp.setMargins(0, 0, (int)(4 * density), 0);
            if (row != null) {
                if (i % colCount < colCount - 1) lp.weight = 1;
                iv.setLayoutParams(lp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(getColor(R.color.background_secondary));

                Glide.with(this)
                        .load(af.path)
                        .placeholder(new android.graphics.drawable.ColorDrawable(getColor(R.color.background_secondary)))
                        .error(new android.graphics.drawable.ColorDrawable(getColor(R.color.divider)))
                        .into(iv);

                // 点击查看大图
                iv.setOnClickListener(v -> {
                    // 简单预览：Toast提示点击删除
                    Toast.makeText(PostActivity.this, "点击移除图片", Toast.LENGTH_SHORT).show();
                });
                // 长按删除
                final AttachFile afFinal = af;
                iv.setOnLongClickListener(v -> {
                    attachFiles.remove(afFinal);
                    updateAttachList();
                    updateImagePreview();
                    return true;
                });

                row.addView(iv);
            }
        }
    }

    // ---- 附件功能 ----
    private void pickFile() {
        hideAllPanels();
        // 先选文件，上传时在后台线程统一获取授权
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_FILE_PICK);
    }

    // 在后台线程安全地预加载上传授权（必须使用桌面版UA，移动端UA会被Discuz!强制返回移动版页面，不含hash令牌）
    private boolean ensureUploadAuth() {
        // 登录可能由 WebView 完成；仅在原生 CookieJar 未登录时才同步，避免覆盖有效会话。
        if (!HttpClient.getInstance().isLoggedIn()) {
            HttpClient.getInstance().syncFromCookieManager();
        }
        if (!HttpClient.getInstance().isLoggedIn()) return false;
        if (isValidUploadAuth()) return true;
        try {
            // 桌面版发帖页面（含hash上传令牌）— 必须用桌面版 UA，不能带 mobile=2
            String fid = selectedFid != null ? selectedFid : "39";
            String url = HttpClient.BASE_URL + "forum.php?mod=post&action=newthread&fid=" + fid;
            String html = HttpClient.getInstance().getDesktop(url);
            if (html != null) {
                if (currentFormhash == null) currentFormhash = ForumParser.parseFormhash(html);
                extractUidAndHash(html);
            }
            return isValidUploadAuth();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isValidUploadAuth() {
        return currentUid != null && currentUid.matches("[1-9]\\d*")
                && currentHash != null && !currentHash.trim().isEmpty();
    }

    private void preloadUploadAuth() {
        new Thread(() -> {
            ensureUploadAuth();
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FILE_PICK && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                uploadFile(uri);
            }
        } else if (requestCode == REQUEST_IMAGE_PICK && resultCode == RESULT_OK && data != null) {
            List<Uri> imageUris = new ArrayList<>();
            // 多图选择
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count; i++) {
                    Uri uri = data.getClipData().getItemAt(i).getUri();
                    if (uri != null) imageUris.add(uri);
                }
            } else if (data.getData() != null) {
                imageUris.add(data.getData());
            }
            if (!imageUris.isEmpty()) {
                uploadImages(imageUris);
            }
        }
    }

    private void uploadFile(Uri uri) {
        Toast.makeText(this, "正在上传附件...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String fileName = getFileNameFromUri(uri);
                File tempFile = copyUriToTempFile(uri, fileName);
                if (tempFile == null) {
                    runOnUiThread(() -> Toast.makeText(this, "无法读取文件", Toast.LENGTH_SHORT).show());
                    return;
                }
                if (!ensureUploadAuth()) {
                    runOnUiThread(() -> Toast.makeText(this, "获取上传授权失败，请先选择版块或重新登录", Toast.LENGTH_SHORT).show());
                    return;
                }

                String uploadUrl = HttpClient.BASE_URL
                        + "misc.php?mod=swfupload&operation=upload"
                        + "&type=attach&inajax=yes&infloat=yes&simple=2";
                Map<String, String> extraFields = new HashMap<>();
                extraFields.put("uid", currentUid);
                extraFields.put("hash", currentHash);
                String response = HttpClient.getInstance().uploadFile(
                        uploadUrl, tempFile, "Filedata", extraFields);
                String aid = parseDiscuzUploadResponse(response);
                if (aid == null) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "附件上传失败", Toast.LENGTH_SHORT).show());
                    return;
                }

                String bbcode = fileName.matches("(?i).*\\.(jpg|jpeg|png|gif|bmp|webp)$")
                        ? "\n[attachimg]" + aid + "[/attachimg]\n"
                        : "\n[attach]" + aid + "[/attach]\n";
                AttachFile af = new AttachFile(fileName, tempFile.getAbsolutePath());
                af.aid = aid;
                af.bbcode = bbcode;
                synchronized (attachFiles) { attachFiles.add(af); }
                runOnUiThread(() -> {
                    insertIntoContent(bbcode);
                    updateAttachList();
                    updateImagePreview();
                    Toast.makeText(this, "附件已上传: " + fileName, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "附件上传异常: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    /**
     * 解析 Discuz! 附件上传响应（管道分隔符格式）
     * 成功: DISCUZUPLOAD|aid|uploadId|imageWidth|imageHeight
     * 失败: DISCUZUPLOAD|error|错误码|错误信息
     */
    private String parseDiscuzUploadResponse(String response) {
        if (response == null) return null;
        String text = response.trim();
        if (text.isEmpty()) return null;
        try {
            // Discuz! 实际格式：DISCUZUPLOAD|状态|错误码|aid|hash|路径|文件名...
            // 成功条件是 data[0]=DISCUZUPLOAD 且 data[2]=0，aid 在 data[3]。
            if (text.startsWith("DISCUZUPLOAD|")) {
                String[] parts = text.split("\\|", -1);
                if (parts.length > 3 && "0".equals(parts[2])
                        && parts[3].matches("\\d+")) {
                    return parts[3];
                }
                if (parts.length > 3 && "error".equalsIgnoreCase(parts[1])) {
                    String errorMsg = parts.length > 7 ? parts[7]
                            : (parts.length > 3 ? parts[3] : "未知错误");
                    runOnUiThread(() -> Toast.makeText(PostActivity.this,
                            "上传错误: " + errorMsg, Toast.LENGTH_SHORT).show());
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String extractAttachUrlFromResponse(String response) {
        try {
            JSONObject json = new JSONObject(response);
            if (json.has("url")) return json.getString("url");
            if (json.has("attach")) return json.getJSONObject("attach").optString("url");
        } catch (Exception ignored) {}
        return null;
    }

    private String extractAidFromHtml(String html) {
        try {
            Document doc = Jsoup.parse(html);
            Element input = doc.select("input[name=aid]").first();
            if (input != null) return input.val();
            // 从attachnotice中提取
            Element notice = doc.getElementById("attachnotice_attach");
            if (notice != null) {
                String text = notice.text();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(text);
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getFileNameFromUri(Uri uri) {
        String name = "attachment";
        try {
            String path = uri.getPath();
            if (path != null) name = path.substring(path.lastIndexOf('/') + 1);
        } catch (Exception ignored) {}
        if (!name.contains(".")) name += ".dat";
        return name;
    }

    private File copyUriToTempFile(Uri uri, String fileName) throws Exception {
        File tmpDir = new File(getCacheDir(), "attachments");
        if (!tmpDir.exists()) tmpDir.mkdirs();
        File tmpFile = new File(tmpDir, fileName);
        try (InputStream is = getContentResolver().openInputStream(uri);
             FileOutputStream os = new FileOutputStream(tmpFile)) {
            if (is == null) return null;
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) os.write(buf, 0, len);
        }
        return tmpFile;
    }

    private void updateAttachList() {
        llAttachList.removeAllViews();
        if (attachFiles.isEmpty()) {
            llAttachList.setVisibility(View.GONE);
            return;
        }
        llAttachList.setVisibility(View.VISIBLE);
        for (int i = 0; i < attachFiles.size(); i++) {
            AttachFile af = attachFiles.get(i);
            boolean isImage = af.name.matches("(?i).*\\.(jpg|jpeg|png|gif|bmp|webp)$");
            if (isImage) {
                // 图片附件：显示缩略图预览
                ImageView iv = new ImageView(this);
                int size = (int) (120 * getResources().getDisplayMetrics().density);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.setMargins(8, 8, 8, 8);
                iv.setLayoutParams(lp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(getColor(R.color.background_secondary));
                Glide.with(this)
                        .load(af.path)
                        .placeholder(new android.graphics.drawable.ColorDrawable(getColor(R.color.background_secondary)))
                        .error(new android.graphics.drawable.ColorDrawable(getColor(R.color.divider)))
                        .into(iv);
                // 点击删除
                final int idx = i;
                iv.setOnClickListener(v -> {
                    attachFiles.remove(idx);
                    updateAttachList();
                });
                llAttachList.addView(iv);
            } else {
                // 非图片附件：显示文件名
                TextView tv = new TextView(this);
                tv.setText("📎 " + af.name);
                tv.setPadding(8, 8, 8, 8);
                tv.setTextSize(13);
                tv.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.ic_menu_delete, 0);
                final int idx = i;
                tv.setOnClickListener(v -> {
                    attachFiles.remove(idx);
                    updateAttachList();
                });
                llAttachList.addView(tv);
            }
        }
    }

    // ---- 高级功能 ----
    // llAdvancedOptions 中已包含：cb_hidden_replies, cb_reverse_order, cb_usesig, cb_smiley_off, cb_bbcode_off

    // ---- 面板管理 ----
    private void hideAllPanels() {
        llSmileyPanel.setVisibility(View.GONE);
        llAtPanel.setVisibility(View.GONE);
        llAdvancedOptions.setVisibility(View.GONE);
    }

    private void hideAllPanelsExcept(View except) {
        if (except != llSmileyPanel) llSmileyPanel.setVisibility(View.GONE);
        if (except != llAtPanel) llAtPanel.setVisibility(View.GONE);
        if (except != llAdvancedOptions) llAdvancedOptions.setVisibility(View.GONE);
    }

    // 插入内容到正文
    private void insertIntoContent(String text) {
        Editable editable = etContent.getText();
        if (editable == null) {
            etContent.setText(text);
            return;
        }
        int start = etContent.getSelectionStart();
        int end = etContent.getSelectionEnd();
        if (start < 0) start = editable.length();
        if (start >= 0 && end > start) {
            editable.replace(start, end, text);
        } else {
            editable.insert(start >= 0 ? start : editable.length(), text);
        }
    }

    // ==================== 版块选择 ====================

    private void showForumPicker() {
        new Thread(() -> {
            try {
                String url = HttpClient.BASE_URL + "forum.php?forumlist=1&mobile=2";
                String html = HttpClient.getInstance().get(url);
                if (html == null || html.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(PostActivity.this,
                            R.string.network_error, Toast.LENGTH_SHORT).show());
                    return;
                }
                List<ForumCategory> categories = ForumParser.parseForumCategories(html);
                runOnUiThread(() -> buildForumPickerDialog(categories));
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(PostActivity.this,
                        R.string.network_error, Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void buildForumPickerDialog(List<ForumCategory> categories) {
        final List<ForumCategory.Forum> allForums = new ArrayList<>();
        final List<String> groupNames = new ArrayList<>();
        final List<int[]> groupRanges = new ArrayList<>();

        for (ForumCategory category : categories) {
            if (category.getForums() == null || category.getForums().isEmpty()) continue;
            int start = allForums.size();
            allForums.addAll(category.getForums());
            int end = allForums.size() - 1;
            groupNames.add(category.getName());
            groupRanges.add(new int[]{start, end});
        }
        if (allForums.isEmpty()) {
            Toast.makeText(this, "暂无可用版块", Toast.LENGTH_SHORT).show();
            return;
        }

        final int totalItems = allForums.size() + groupNames.size();
        final String[] displayItems = new String[totalItems];
        final boolean[] isHeader = new boolean[totalItems];
        final int[] forumIndex = new int[totalItems];

        int pos = 0;
        for (int g = 0; g < groupNames.size(); g++) {
            displayItems[pos] = "╲╱ " + groupNames.get(g);
            isHeader[pos] = true;
            forumIndex[pos] = -1;
            pos++;
            int[] range = groupRanges.get(g);
            for (int i = range[0]; i <= range[1]; i++) {
                displayItems[pos] = "  " + allForums.get(i).getName();
                isHeader[pos] = false;
                forumIndex[pos] = i;
                pos++;
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择版块");
        builder.setAdapter(new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, displayItems) {
            @Override
            public boolean isEnabled(int position) {
                return !isHeader[position];
            }
        }, (dialog, which) -> {
            int idx = forumIndex[which];
            if (idx >= 0) {
                selectForum(allForums.get(idx));
            }
        });

        AlertDialog dialog = builder.create();
        dialog.show();
        DialogHelper.applyToAlertDialog(dialog, this);
        if (dialog.getListView() != null) {
            dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
                if (isHeader[position]) return;
                int idx = forumIndex[position];
                if (idx >= 0) {
                    selectForum(allForums.get(idx));
                    dialog.dismiss();
                }
            });
        }
    }

    private void selectForum(ForumCategory.Forum forum) {
        selectedFid = forum.getFid();
        selectedForumName = forum.getName();
        tvSelectedForum.setText(selectedForumName);
        tvSelectedForum.setVisibility(View.VISIBLE);
        // 切换圈子后重新加载formhash
        loadFormhashAndUserInfo();
    }

    // ==================== formhash & hash 加载 ====================

    private void loadFormhashAndUserInfo() {
        new Thread(() -> {
            try {
                if (selectedFid == null) return;
                // 获取 formhash 用移动端页面（更轻量，formhash 双端都有）
                String mobileUrl = ForumParser.getNewThreadUrl(selectedFid);
                String mobileHtml = HttpClient.getInstance().get(mobileUrl);
                if (mobileHtml != null) {
                    currentFormhash = ForumParser.parseFormhash(mobileHtml);
                }
                // 获取 uid + hash 必须用桌面版页面（移动版不含 hash 令牌）
                String desktopUrl = HttpClient.BASE_URL + "forum.php?mod=post&action=newthread&fid=" + selectedFid;
                String desktopHtml = HttpClient.getInstance().getDesktop(desktopUrl);
                if (desktopHtml != null) {
                    if (currentFormhash == null) currentFormhash = ForumParser.parseFormhash(desktopHtml);
                    extractUidAndHash(desktopHtml);
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void loadFormhashSync() {
        try {
            if (selectedFid == null) return;
            // formhash 可从移动端获取
            String mobileUrl = ForumParser.getNewThreadUrl(selectedFid);
            String mobileHtml = HttpClient.getInstance().get(mobileUrl);
            if (mobileHtml != null) {
                currentFormhash = ForumParser.parseFormhash(mobileHtml);
            }
            // uid + hash 必须桌面版
            String desktopUrl = HttpClient.BASE_URL + "forum.php?mod=post&action=newthread&fid=" + selectedFid;
            String desktopHtml = HttpClient.getInstance().getDesktop(desktopUrl);
            if (desktopHtml != null) {
                if (currentFormhash == null) currentFormhash = ForumParser.parseFormhash(desktopHtml);
                extractUidAndHash(desktopHtml);
            }
        } catch (Exception ignored) {}
    }

    private void extractUidAndHash(String html) {
        if (TextUtils.isEmpty(html)) return;
        try {
            // 兼容 JS 变量、JSON 对象以及隐藏 input 的属性顺序/单双引号差异。
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(?:var\\s+|\\b)discuz_uid\\s*(?:=|:)\\s*['\\\"]([1-9]\\d*)['\\\"]",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) currentUid = m.group(1);

            m = java.util.regex.Pattern.compile(
                    "<input[^>]+name\\s*=\\s*['\\\"]hash['\\\"][^>]+value\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
            boolean hashFound = m.find();
            if (!hashFound) {
                m = java.util.regex.Pattern.compile(
                        "<input[^>]+value\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"][^>]+name\\s*=\\s*['\\\"]hash['\\\"]",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
                hashFound = m.find();
            }
            if (!hashFound) {
                m = java.util.regex.Pattern.compile(
                        "(?:var\\s+|\\b)hash\\s*(?:=|:)\\s*['\\\"]([^'\\\"]+)['\\\"]",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
                hashFound = m.find();
            }
            if (hashFound) currentHash = m.group(1);
        } catch (Exception ignored) {
        }
    }

    // ==================== 发布 ====================

    // ═══ 草稿箱(build52) ═══
    private void restoreDraft() {
        DraftManager.Entry e = DraftManager.latest(this);
        if (e == null) return;
        boolean empty = (etTitle.getText() == null || etTitle.getText().toString().trim().isEmpty())
                && (etContent.getText() == null || etContent.getText().toString().trim().isEmpty());
        if (!empty) return; // 已有内容不覆盖
        draftId = e.id;
        if (e.title != null) etTitle.setText(e.title);
        if (e.content != null) etContent.setText(e.content);
        if (e.fid != null && !e.fid.isEmpty()) {
            selectedFid = e.fid;
            selectedForumName = e.forumName == null ? "" : e.forumName;
            tvSelectedForum.setText(selectedForumName.isEmpty() ? "已选版块 " + e.fid : selectedForumName);
        }
        cbAnonymous.setChecked(e.anonymous);
        Toast.makeText(this, "已恢复上次草稿", Toast.LENGTH_SHORT).show();
    }

    private void saveDraftNow() {
        if (postedDone) return;
        String title = etTitle.getText() != null ? etTitle.getText().toString().trim() : "";
        String content = etContent.getText() != null ? etContent.getText().toString().trim() : "";
        if (title.isEmpty() && content.isEmpty() && selectedFid == null) {
            if (draftId > 0) { DraftManager.delete(this, draftId); draftId = 0; }
            return;
        }
        draftId = DraftManager.saveDraft(this, draftId, title, content,
                selectedFid, selectedForumName, cbAnonymous.isChecked());
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveDraftNow();
    }

    private String draftLabel(DraftManager.Entry e) {
        String t = e.title == null || e.title.isEmpty() ? "(无标题)" : e.title;
        String preview = e.content == null ? "" : e.content.replace(String.valueOf((char) 10), " ");
        if (preview.length() > 18) preview = preview.substring(0, 18);
        String fm = e.forumName == null || e.forumName.isEmpty() ? "" : " · " + e.forumName;
        String ts = e.time > 0 ? new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(e.time)) : "";
        return t + fm + String.valueOf((char) 10) + ts + " · " + preview;
    }

    private void loadDraftToEditor(DraftManager.Entry e) {
        draftId = e.id;
        if (e.title != null) etTitle.setText(e.title);
        if (e.content != null) etContent.setText(e.content);
        if (e.fid != null && !e.fid.isEmpty()) {
            selectedFid = e.fid;
            selectedForumName = e.forumName == null ? "" : e.forumName;
            tvSelectedForum.setText(selectedForumName.isEmpty() ? "已选版块 " + e.fid : selectedForumName);
        }
        cbAnonymous.setChecked(e.anonymous);
        Toast.makeText(this, "草稿已载入", Toast.LENGTH_SHORT).show();
    }

    private void showDraftsDialog() {
        final List<DraftManager.Entry> l = DraftManager.list(this);
        if (l.isEmpty()) { Toast.makeText(this, "草稿箱是空的", Toast.LENGTH_SHORT).show(); return; }
        String[] items = new String[l.size()];
        for (int i = 0; i < l.size(); i++) items[i] = draftLabel(l.get(i));
        new AlertDialog.Builder(this)
                .setTitle("草稿箱(" + l.size() + ") · 点条目载入")
                .setItems(items, (d, which) -> loadDraftToEditor(l.get(which)))
                .setNeutralButton("删单条", (d, w) -> showDraftDeleteDialog())
                .setNegativeButton("清空", (d, w) -> {
                    DraftManager.clear(PostActivity.this);
                    draftId = 0;
                    Toast.makeText(PostActivity.this, "草稿箱已清空", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showDraftDeleteDialog() {
        final List<DraftManager.Entry> l = DraftManager.list(this);
        if (l.isEmpty()) { Toast.makeText(this, "草稿箱是空的", Toast.LENGTH_SHORT).show(); return; }
        String[] items = new String[l.size()];
        for (int i = 0; i < l.size(); i++) items[i] = draftLabel(l.get(i));
        new AlertDialog.Builder(this)
                .setTitle("点要删除的草稿")
                .setItems(items, (d, which) -> {
                    DraftManager.Entry e = l.get(which);
                    DraftManager.delete(PostActivity.this, e.id);
                    if (e.id == draftId) draftId = 0;
                    Toast.makeText(PostActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("返回", null)
                .show();
    }

    private void setupPublishButton() {
        btnPublish.setOnClickListener(v -> attemptPost());
        if (btnAiOptimize != null) btnAiOptimize.setOnClickListener(v -> aiOptimizeContent());
        if (btnAiPost != null) btnAiPost.setOnClickListener(v -> aiAutoPost());
    }

    private void attemptPost() {
        // build73: 编辑模式走 action=edit,与发新帖完全分开
        if (isEditMode()) {
            tvError.setVisibility(View.GONE);
            btnPublish.setEnabled(false);
            btnPublish.setText("保存中...");
            new Thread(() -> {
                try {
                    attemptEdit();
                } catch (Exception e) {
                    final String msg = android.text.TextUtils.isEmpty(e.getMessage())
                            ? getString(R.string.network_error) : e.getMessage();
                    runOnUiThread(() -> { showError(msg); resetPublishButton(); });
                }
            }).start();
            return;
        }
        tvError.setVisibility(View.GONE);

        String title = etTitle.getText() != null ? etTitle.getText().toString().trim() : "";
        String content = etContent.getText() != null
                        ? etContent.getText().toString().trim() : "";

        if (title.isEmpty()) { showError("请输入标题"); return; }
        if (selectedFid == null) { showError("请选择版块"); return; }
        if (content.isEmpty()) { showError("请输入正文内容"); return; }

        btnPublish.setEnabled(false);
        btnPublish.setText("发布中...");

        new Thread(() -> {
            try {
                // 获取formhash
                if (currentFormhash == null) {
                    loadFormhashSync();
                }
                if (currentFormhash == null) {
                    String postUrl = ForumParser.getNewThreadUrl(selectedFid);
                    String postHtml = HttpClient.getInstance().get(postUrl);
                    if (postHtml != null) {
                        currentFormhash = ForumParser.parseFormhash(postHtml);
                    }
                }
                if (currentFormhash == null || currentFormhash.isEmpty()) {
                    runOnUiThread(() -> { showError("获取安全验证失败，请重试"); resetPublishButton(); });
                    return;
                }

                // 组装 POST 参数（与网页端完全对齐）
                Map<String, String> params = new HashMap<>();
                params.put("formhash", currentFormhash);
                params.put("subject", title);
                params.put("message", content);
                params.put("allownoticeauthor", "1");

                // 上传接口返回的 aid 只是暂存附件，发帖时还必须提交 attachnew[aid][description]，
                // Discuz! 才会把附件正式关联到新主题。
                synchronized (attachFiles) {
                    for (AttachFile af : attachFiles) {
                        if (af != null && af.aid != null && af.aid.matches("\\d+")) {
                            params.put("attachnew[" + af.aid + "][description]", "");
                        }
                    }
                }

                // 高级选项参数
                CheckBox cbHiddenReplies = findViewById(R.id.cb_hidden_replies);
                if (cbHiddenReplies != null && cbHiddenReplies.isChecked()) {
                    params.put("hiddenreplies", "1");
                }
                CheckBox cbReverseOrder = findViewById(R.id.cb_reverse_order);
                if (cbReverseOrder != null && cbReverseOrder.isChecked()) {
                    params.put("ordertype", "1");
                }
                CheckBox cbUsesig = findViewById(R.id.cb_usesig);
                if (cbUsesig != null && cbUsesig.isChecked()) {
                    params.put("usesig", "1");
                }
                CheckBox cbSmileyOff = findViewById(R.id.cb_smiley_off);
                if (cbSmileyOff != null && cbSmileyOff.isChecked()) {
                    params.put("smileyoff", "1");
                }
                CheckBox cbBbcodeOff = findViewById(R.id.cb_bbcode_off);
                if (cbBbcodeOff != null && cbBbcodeOff.isChecked()) {
                    params.put("bbcodeoff", "1");
                }

                if (cbAnonymous.isChecked()) {
                    params.put("anonymous", "1");
                }

                // POST 提交
                String submitUrl = HttpClient.BASE_URL + "forum.php?mod=post&action=newthread&fid=" + selectedFid + "&topicsubmit=yes";
                String response = HttpClient.getInstance().post(submitUrl, params);

                // 解析结果
                if (response != null && (response.contains("tid=") || response.contains("viewthread"))) {
                    runOnUiThread(() -> {
                        postedDone = true;
                        if (draftId > 0) { DraftManager.delete(PostActivity.this, draftId); draftId = 0; }
                        Toast.makeText(PostActivity.this, R.string.post_success, Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK, new Intent().putExtra("tid", extractTid(response)));
                        finish();
                    });
                } else {
                    String errorMsg = extractErrorFromResponse(response);
                    runOnUiThread(() -> { showError(errorMsg); resetPublishButton(); });
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> { showError(getString(R.string.network_error)); resetPublishButton(); });
            }
        }).start();
    }

    // ==================== build73: 编辑帖子模式 ====================

    /** 编辑模式:详情页传入 tid/pid/fid/title/message */
    private void setupEditMode() {
        android.content.Intent it = getIntent();
        editTid = it.getStringExtra("edit_tid");
        editPid = it.getStringExtra("edit_pid");
        if (android.text.TextUtils.isEmpty(editTid) || android.text.TextUtils.isEmpty(editPid)) {
            return; // 普通发帖模式
        }
        String fname = it.getStringExtra("edit_forum_name");
        if (!android.text.TextUtils.isEmpty(fname)) {
            selectedForumName = fname;
            if (tvSelectedForum != null) tvSelectedForum.setText(fname);
        }
        String fid = it.getStringExtra("edit_fid");
        if (!android.text.TextUtils.isEmpty(fid)) {
            selectedFid = fid;
        }
        if (etTitle != null) {
            etTitle.setText(it.getStringExtra("edit_title"));
            etTitle.setEnabled(false); // 编辑不改标题,避免触发审核
        }
        if (etContent != null) {
            etContent.setText(it.getStringExtra("edit_message"));
        }
        if (btnPublish != null) btnPublish.setText("保存修改");
        if (tvTitleCount != null) tvTitleCount.setVisibility(View.GONE);
        // 编辑模式:不给改版块,AI 按钮也用不上
        if (llCircleSelector != null) llCircleSelector.setVisibility(View.GONE);
        if (btnAiOptimize != null) btnAiOptimize.setVisibility(View.GONE);
        if (btnAiPost != null) btnAiPost.setVisibility(View.GONE);
    }

    private boolean isEditMode() {
        return !android.text.TextUtils.isEmpty(editTid) && !android.text.TextUtils.isEmpty(editPid);
    }

    /** 编辑模式提交:forum.php?mod=post&action=edit */
    private void attemptEdit() throws Exception {
        String message = etContent.getText() != null ? etContent.getText().toString().trim() : "";
        if (message.isEmpty()) {
            runOnUiThread(() -> { showError("请输入正文内容"); resetPublishButton(); });
            return;
        }
        String fh = null;
        try {
            String form = HttpClient.getInstance().get(HttpClient.BASE_URL
                    + "forum.php?mod=post&action=edit&tid=" + editTid + "&pid=" + editPid + "&mobile=2");
            fh = ForumParser.parseFormhash(form);
        } catch (Exception ignored) {
        }
        if (android.text.TextUtils.isEmpty(fh)) fh = currentFormhash;
        if (android.text.TextUtils.isEmpty(fh)) {
            runOnUiThread(() -> { showError("获取安全验证失败，请重试"); resetPublishButton(); });
            return;
        }

        Map<String, String> params = new HashMap<>();
        params.put("formhash", fh);
        params.put("subject", etTitle.getText() == null ? "" : etTitle.getText().toString().trim());
        params.put("message", message);
        params.put("editsubmit", "yes");
        // 附件同样要带上 attachnew,否则编辑会丢附件
        synchronized (attachFiles) {
            for (AttachFile af : attachFiles) {
                if (af != null && af.aid != null && af.aid.matches("\\d+")) {
                    params.put("attachnew[" + af.aid + "][description]", "");
                }
            }
        }

        String url = HttpClient.BASE_URL + "forum.php?mod=post&action=edit&extra=&editsubmit=yes&mobile=2"
                + "&handlekey=editform&tid=" + editTid + "&pid=" + editPid + "&page=1";
        String response = HttpClient.getInstance().post(url, params);

        boolean ok = response != null && !ForumParser.isLoginPage(response)
                && (response.contains("viewthread") || response.contains("thread-" + editTid)
                    || response.contains("成功") || response.contains("回复"));
        if (ok) {
            runOnUiThread(() -> {
                postedDone = true;
                Toast.makeText(PostActivity.this, "已保存修改", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            });
        } else {
            final String err = extractErrorFromResponse(response);
            runOnUiThread(() -> { showError(err); resetPublishButton(); });
        }
    }

    private String extractTid(String html) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("tid=(\\d+)").matcher(html);
            if (m.find()) return m.group(1);
            m = java.util.regex.Pattern.compile("thread-(\\d+)").matcher(html);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return "";
    }

    private String extractErrorFromResponse(String html) {
        if (html == null || html.isEmpty()) return "发帖失败，请稍后重试";
        try {
            Document doc = Jsoup.parse(html);
            Elements alerts = doc.select("div.alert_error, div.alert_info, p.alert, div.alert");
            if (!alerts.isEmpty()) {
                String text = alerts.first().text();
                if (text != null && !text.isEmpty()) return text;
            }
            String title = doc.title();
            if (title != null && !title.isEmpty() && !title.contains("发表帖子") && !title.contains("MT论坛"))
                return title;
        } catch (Exception ignored) {}
        return "发帖失败，请检查内容或稍后重试";
    }

    private void showError(String msg) {
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }

    private void resetPublishButton() {
        btnPublish.setEnabled(true);
        btnPublish.setText(R.string.post_publish);
    }


    // ==================== build70: AI 发帖 ====================

    /** 帖子内容最大注入长度(字符) */
    private static final int AI_POST_CONTENT_MAX = 6000;

    /** 「优化」: 用 AI 把当前标题+正文改写得更规范易读 */
    private void aiOptimizeContent() {
        if (!com.solosu.mtforum.ai.AiConfigManager.isConfigured(this)) {
            showError("请先在 AI 配置中填写接口地址与 API Key");
            return;
        }
        final String title = etTitle.getText() == null ? "" : etTitle.getText().toString().trim();
        final String content = etContent.getText() == null ? "" : etContent.getText().toString().trim();
        if (title.isEmpty() && content.isEmpty()) {
            showError("请先输入标题或正文");
            return;
        }
        setAiButtonsBusy(true, "优化中…");
        new Thread(() -> {
            String res = null;
            try {
                String sys = "你是论坛发帖优化助手。基于用户给出的标题与正文，在不改变原意、不编造事实的前提下，优化语句使其更通顺、层次更清晰；"
                        + "可适当补充排版(分段)。输出格式严格如下三行标签：\n"
                        + "【标题】优化后的标题(仅一行)\n【正文】优化后的正文\n"
                        + "不要输出除标签外的任何解释。";
                StringBuilder u = new StringBuilder();
                u.append("原标题：").append(title).append("\n\n原正文：\n").append(content);
                res = com.solosu.mtforum.ai.AiClient.simpleChat(this, sys, u.toString());
            } catch (Exception e) {
                res = null;
            }
            final String out = res;
            runOnUiThread(() -> {
                setAiButtonsBusy(false, null);
                if (TextUtils.isEmpty(out)) { showError("AI 优化失败，请检查 AI 配置或网络"); return; }
                String nt = extractLabeled(out, "标题");
                String nb = extractLabeled(out, "正文");
                if (TextUtils.isEmpty(nt) && TextUtils.isEmpty(nb)) {
                    // 模型没按标签输出: 整体作为正文
                    nb = com.solosu.mtforum.ai.AiSummarizeActivity.extractText(out);
                }
                if (!TextUtils.isEmpty(nt)) etTitle.setText(nt);
                if (!TextUtils.isEmpty(nb)) etContent.setText(nb);
                Toast.makeText(this, "已优化", Toast.LENGTH_SHORT).show();
            });
        }, "ai-optimize").start();
    }

    /** 「AI发帖」: 2. AI 生成标题+正文 → 3. 自动提交 */
    private void aiAutoPost() {
        if (selectedFid == null) { showError("请先选择版块"); return; }
        if (!com.solosu.mtforum.ai.AiConfigManager.isConfigured(this)) {
            showError("请先在 AI 配置中填写接口地址与 API Key");
            return;
        }
        final String title = etTitle.getText() == null ? "" : etTitle.getText().toString().trim();
        final String content = etContent.getText() == null ? "" : etContent.getText().toString().trim();
        if (title.isEmpty() && content.isEmpty()) {
            showError("请先输入主题或要点，AI 将据此生成帖子");
            return;
        }
        setAiButtonsBusy(true, "AI 生成中…");
        new Thread(() -> {
            String res = null;
            try {
                String sys = "你是 MT 论坛(技术向)的发帖助手。根据用户给出的主题或要点，生成一篇可直接发布的帖子。要求："
                        + "1. 标题 <=30 字，概括主题，不要加【】等括号标签；"
                        + "2. 正文用中文、分段、条理清晰，技术内容可用编号步骤；"
                        + "3. 允许结合你自己的知识补充，但不得编造与主题无关的信息；"
                        + "4. 不要使用 Markdown 记号(如 # 、 * 、 ` )；"
                        + (TextUtils.isEmpty(selectedForumName) ? "" : ("当前版块：" + selectedForumName + "。"))
                        + "输出格式严格：\n【标题】一行标题\n【正文】帖子正文\n不要输出除标签外的任何解释。";
                StringBuilder u = new StringBuilder();
                if (!title.isEmpty()) u.append("主题/标题：").append(title).append("\n");
                if (!content.isEmpty()) u.append("要点/正文：\n").append(content);
                res = com.solosu.mtforum.ai.AiClient.simpleChat(this, sys, u.toString());
            } catch (Exception e) {
                res = null;
            }
            final String out = res;
            runOnUiThread(() -> {
                setAiButtonsBusy(false, null);
                if (TextUtils.isEmpty(out)) { showError("AI 生成失败，请检查 AI 配置或网络"); return; }
                String nt = extractLabeled(out, "标题");
                String nb = extractLabeled(out, "正文");
                if (TextUtils.isEmpty(nb)) nb = com.solosu.mtforum.ai.AiSummarizeActivity.extractText(out);
                if (TextUtils.isEmpty(nt)) nt = "分享";
                etTitle.setText(nt);
                etContent.setText(nb);
                Toast.makeText(this, "已生成，正在发布…", Toast.LENGTH_SHORT).show();
                // 立即自动提交
                if (!btnPublish.isEnabled()) return;
                attemptPost();
            });
        }, "ai-autopost").start();
    }

    private void setAiButtonsBusy(boolean busy, String label) {
        if (btnAiOptimize != null) btnAiOptimize.setEnabled(!busy);
        if (btnAiPost != null) btnAiPost.setEnabled(!busy);
        if (btnPublish != null) btnPublish.setEnabled(!busy);
        if (btnAiPost != null) btnAiPost.setText(busy && label != null ? label : "AI发帖");
    }

    /** 从形如「【标题】xxx【正文】yyy」的输出里取某标签后的内容 */
    private String extractLabeled(String ai, String label) {
        if (ai == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("【" + java.util.regex.Pattern.quote(label) + "】\\s*([\\s\\S]*?)(?=【|$)")
                .matcher(ai);
        if (m.find()) return m.group(1).trim();
        return "";
    }
}