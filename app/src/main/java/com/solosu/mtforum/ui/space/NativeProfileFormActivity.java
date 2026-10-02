package com.solosu.mtforum.ui.space;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.solosu.mtforum.R;
import com.solosu.mtforum.databinding.ActivityNativeProfileFormBinding;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.google.android.material.tabs.TabLayout;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.solosu.mtforum.ui.widget.DialogHelper;

/**
 * 原生编辑资料页面
 *
 * 完全去除 WebView 套壳，使用原生表单编辑 Discuz! 基本资料、
 * 联系方式、个人信息，并支持原生选图上传修改头像。
 *
 * 字段说明（基于抓包分析）：
 * - 基本资料 (op=base): occupation, resideprovince/residecity, realname,
 *   birthprovince/birthcity, birthyear/birthmonth/birthday, gender
 * - 联系方式 (op=contact): qq, mobile
 * - 个人信息 (op=info): bio, customstatus, sightml, timeoffset
 * - 每个字段对应的隐私开关: privacy[字段名]
 *
 * 头像上传: 使用 avatarform 的 Filedata 字段上传图片文件，
 * 以 multipart/form-data 形式 POST 到 home.php?mod=spacecp&ac=avatar&ref
 */
public class NativeProfileFormActivity extends AppCompatActivity {

    private ActivityNativeProfileFormBinding binding;
    private HttpClient httpClient;
    private ExecutorService executor;

    private String currentFormhash;
    private String currentOp = "base";
    private String currentAvatarUrl;

    // 存储当前表单参数值
    private final Map<String, String> formValues = new HashMap<>();
    // 存储当前表单的隐私开关值
    private final Map<String, String> privacyValues = new HashMap<>();
    // 存储所有表单字段定义
    private final List<FormField> formFields = new ArrayList<>();

    // 头像选择临时文件
    private File tempAvatarFile;

    private static final int REQUEST_PICK_IMAGE = 1001;
    private static final int REQUEST_CAMERA = 1002;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityNativeProfileFormBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        httpClient = HttpClient.getInstance();
        executor = Executors.newSingleThreadExecutor();

        // ★ 修复：移除返回箭头图标（仅保留点击返回功能）
        // Discuz! 原生模板默认带箭头，显式设置为 null 可移除
        binding.toolbar.setNavigationIcon(null);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        // 处理传入的 tab 参数
        Intent intent = getIntent();
        String initialTab = intent != null ? intent.getStringExtra("tab") : null;
        if (initialTab == null) initialTab = "base";

        int tabIndex = 0;
        switch (initialTab) {
            case "contact": tabIndex = 1; break;
            case "info": tabIndex = 2; break;
            default: tabIndex = 0;
        }

        // Tab 切换
        binding.tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                switch (tab.getPosition()) {
                    case 0: currentOp = "base"; break;
                    case 1: currentOp = "contact"; break;
                    case 2: currentOp = "info"; break;
                }
                showLoading(true);
                loadProfileForm(currentOp);
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        // 保存按钮
        binding.btnSave.setOnClickListener(v -> saveCurrentForm());

        // 加载初始 tab
        if (tabIndex > 0) {
            TabLayout.Tab tab = binding.tabLayout.getTabAt(tabIndex);
            if (tab != null) tab.select();
        } else {
            showLoading(true);
            loadProfileForm("base");
        }
    }

    // ================================================================
    // 1. 从服务器加载表单 HTML 并解析填写
    // ================================================================

    private void loadProfileForm(String op) {
        executor.execute(() -> {
            try {
                String url = HttpClient.BASE_URL
                        + "home.php?mod=spacecp&ac=profile&op=" + op;
                String html = httpClient.get(url);

                if (html == null || html.isEmpty()) {
                    runOnUiThread(() -> {
                        showLoading(false);
                        Toast.makeText(this, "加载失败，请检查网络", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                // 提取 formhash
                String fh = ForumParser.parseFormhash(html);
                if (fh == null) {
                    // 从 JS 变量中提取
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("formhash\\s*=\\s*['\"]([^'\"]+)['\"]")
                            .matcher(html);
                    if (m.find()) fh = m.group(1);
                }
                final String formhash = fh != null ? fh : "";

                // 解析当前值
                final Map<String, String> values = parseCurrentValues(html, op);
                final Map<String, String> privacies = parsePrivacyValues(html, op);

                // 解析字段定义
                final List<FormField> fields = buildFormFields(op);

                runOnUiThread(() -> {
                    currentFormhash = formhash;
                    formValues.clear();
                    formValues.putAll(values);
                    privacyValues.clear();
                    privacyValues.putAll(privacies);
                    formFields.clear();
                    formFields.addAll(fields);
                    renderForm(op);
                    showLoading(false);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    showLoading(false);
                    Toast.makeText(this, "加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 从桌面版 HTML 中解析当前已填写的字段值
     */
    private Map<String, String> parseCurrentValues(String html, String op) {
        Map<String, String> values = new HashMap<>();
        try {
            Document doc = Jsoup.parse(html);

            // 解析 input 字段
            Elements inputs = doc.select("input[type=text], input[type=hidden], input:not([type])");
            for (Element el : inputs) {
                String name = el.attr("name");
                String val = el.attr("value");
                if (!name.isEmpty() && !name.startsWith("privacy") && !name.equals("formhash")
                        && !name.equals("profilesubmit") && !name.equals("mod") && !name.equals("srchtxt")) {
                    values.put(name, val != null ? val : "");
                }
            }

            // 解析 select 当前选中值
            Elements selects = doc.select("select");
            for (Element sel : selects) {
                String name = sel.attr("name");
                if (name.isEmpty()) continue;
                Element selected = sel.select("option[selected]").first();
                if (selected != null) {
                    values.put(name, selected.attr("value"));
                } else {
                    // 取第一个 option 的值
                    Element first = sel.select("option").first();
                    if (first != null) {
                        values.put(name, first.attr("value"));
                    }
                }
            }

            // 解析 textarea
            Elements textareas = doc.select("textarea");
            for (Element ta : textareas) {
                String name = ta.attr("name");
                if (!name.isEmpty()) {
                    values.put(name, ta.text());
                }
            }

            // 特殊处理：从 HTML 中提取手机号等已填内容
            // 手机号在 input 中已经有 value 属性，上面已提取
        } catch (Exception ignored) {}

        return values;
    }

    /**
     * 解析隐私开关值
     */
    private Map<String, String> parsePrivacyValues(String html, String op) {
        Map<String, String> privacies = new HashMap<>();
        try {
            Document doc = Jsoup.parse(html);
            // 查找所有 select[name^=privacy] 的当前选中值
            Elements privacySelects = doc.select("select[name^=privacy]");
            for (Element sel : privacySelects) {
                String name = sel.attr("name");
                Element selected = sel.select("option[selected]").first();
                if (selected != null) {
                    privacies.put(name, selected.attr("value"));
                } else {
                    // 默认公开
                    privacies.put(name, "0");
                }
            }
        } catch (Exception ignored) {}
        return privacies;
    }

    /**
     * 构建每个 tab 下的表单字段定义
     */
    private List<FormField> buildFormFields(String op) {
        List<FormField> list = new ArrayList<>();
        switch (op) {
            case "base":
                list.add(new FormField("occupation", "职业", FormField.TYPE_TEXT, ""));
                list.add(new FormField("resideprovince", "居住地（省）", FormField.TYPE_TEXT, ""));
                list.add(new FormField("residecity", "居住地（市）", FormField.TYPE_TEXT, ""));
                list.add(new FormField("realname", "真实姓名", FormField.TYPE_TEXT, ""));
                list.add(new FormField("birthprovince", "出生地（省）", FormField.TYPE_TEXT, ""));
                list.add(new FormField("birthcity", "出生地（市）", FormField.TYPE_TEXT, ""));
                list.add(new FormField("birthyear", "出生年份", FormField.TYPE_TEXT, ""));
                list.add(new FormField("birthmonth", "出生月份", FormField.TYPE_TEXT, ""));
                list.add(new FormField("birthday", "出生日期", FormField.TYPE_TEXT, ""));
                list.add(new FormField("gender", "性别", FormField.TYPE_SELECT,
                        new String[][]{{"0", "保密"}, {"1", "男"}, {"2", "女"}}));
                break;
            case "contact":
                list.add(new FormField("qq", "QQ", FormField.TYPE_TEXT, ""));
                list.add(new FormField("mobile", "手机号", FormField.TYPE_TEXT, ""));
                break;
            case "info":
                list.add(new FormField("customstatus", "自定义头衔", FormField.TYPE_TEXT, ""));
                list.add(new FormField("bio", "自我介绍", FormField.TYPE_TEXT_AREA, ""));
                list.add(new FormField("sightml", "个人签名", FormField.TYPE_TEXT_AREA, ""));
                break;
        }
        return list;
    }

    // ================================================================
    // 2. 渲染原生表单
    // ================================================================

    private void renderForm(String op) {
        binding.formContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        // 针对联系方式 tab，第一行显示用户名（不可编辑）
        if (op.equals("contact")) {
            View row = inflater.inflate(R.layout.item_form_field_readonly, binding.formContainer, false);
            TextView tvLabel = row.findViewById(R.id.tv_field_label);
            TextView tvValue = row.findViewById(R.id.tv_field_value);
            tvLabel.setText("用户名");
            String selfName = com.solosu.mtforum.session.UserSessionManager
                    .getInstance().getUsername(this);
            tvValue.setText(TextUtils.isEmpty(selfName) ? "" : selfName);
            binding.formContainer.addView(row);
        }

        // 遍历字段并创建 UI
        for (FormField field : formFields) {
            View row = null;
            String privacyKey = "privacy[" + field.name + "]";
            String currentVal = formValues.containsKey(field.name) ? formValues.get(field.name) : "";
            String currentPrivacy = privacyValues.containsKey(privacyKey) ? privacyValues.get(privacyKey) : "0";

            switch (field.type) {
                case FormField.TYPE_TEXT:
                    row = inflater.inflate(R.layout.item_form_field_text, binding.formContainer, false);
                    setupTextField(row, field, currentVal, privacyKey, currentPrivacy);
                    break;
                case FormField.TYPE_TEXT_AREA:
                    row = inflater.inflate(R.layout.item_form_field_textarea, binding.formContainer, false);
                    setupTextAreaField(row, field, currentVal, privacyKey, currentPrivacy);
                    break;
                case FormField.TYPE_SELECT:
                    row = inflater.inflate(R.layout.item_form_field_select, binding.formContainer, false);
                    setupSelectField(row, field, currentVal, privacyKey, currentPrivacy);
                    break;
            }
            if (row != null) {
                binding.formContainer.addView(row);
            }
        }

    }

    private void setupTextField(View row, FormField field, String currentVal,
                                String privacyKey, String currentPrivacy) {
        TextView tvLabel = row.findViewById(R.id.tv_field_label);
        EditText etInput = row.findViewById(R.id.et_field_input);
        Spinner spPrivacy = row.findViewById(R.id.sp_privacy);

        tvLabel.setText(field.label);
        etInput.setText(currentVal);
        etInput.setTag(field.name);

        // 设置隐私开关适配器
        setupPrivacySpinner(spPrivacy, privacyKey, currentPrivacy);
    }

    private void setupTextAreaField(View row, FormField field, String currentVal,
                                    String privacyKey, String currentPrivacy) {
        TextView tvLabel = row.findViewById(R.id.tv_field_label);
        EditText etInput = row.findViewById(R.id.et_field_textarea);
        Spinner spPrivacy = row.findViewById(R.id.sp_privacy);

        tvLabel.setText(field.label);
        etInput.setText(currentVal);
        etInput.setTag(field.name);

        setupPrivacySpinner(spPrivacy, privacyKey, currentPrivacy);
    }

    private void setupSelectField(View row, FormField field, String currentVal,
                                  String privacyKey, String currentPrivacy) {
        TextView tvLabel = row.findViewById(R.id.tv_field_label);
        Spinner spSelect = row.findViewById(R.id.sp_field_select);
        Spinner spPrivacy = row.findViewById(R.id.sp_privacy);

        tvLabel.setText(field.label);

        // 设置选项
        String[] displayValues = new String[field.options.length];
        final String[] optionValues = new String[field.options.length];
        for (int i = 0; i < field.options.length; i++) {
            optionValues[i] = field.options[i][0];
            displayValues[i] = field.options[i][1];
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, displayValues);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSelect.setAdapter(adapter);

        // 设置当前选中值
        for (int i = 0; i < optionValues.length; i++) {
            if (optionValues[i].equals(currentVal)) {
                spSelect.setSelection(i);
                break;
            }
        }

        spSelect.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                formValues.put(field.name, optionValues[position]);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        setupPrivacySpinner(spPrivacy, privacyKey, currentPrivacy);
    }

    /**
     * 设置隐私开关 Spinner
     * 隐私值：0=公开, 1=好友可见, 3=保密
     */
    private void setupPrivacySpinner(Spinner spPrivacy, String privacyKey, String currentPrivacy) {
        String[] privacyDisplay = {"公开", "仅好友可见", "保密"};
        final String[] privacyVals = {"0", "1", "3"};

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, privacyDisplay);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spPrivacy.setAdapter(adapter);

        // 设置当前值
        for (int i = 0; i < privacyVals.length; i++) {
            if (privacyVals[i].equals(currentPrivacy)) {
                spPrivacy.setSelection(i);
                break;
            }
        }

        final String key = privacyKey;
        spPrivacy.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                privacyValues.put(key, privacyVals[position]);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    // ================================================================
    // 3. 头像上传
    // ================================================================

    private void showAvatarPicker() {
        String[] options = {"从相册选择", "拍照"};
        android.app.Dialog alertDialog = new AlertDialog.Builder(this)
                .setTitle("选择头像")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        // 从相册选择
                        Intent intent = new Intent(Intent.ACTION_PICK,
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                        intent.setType("image/*");
                        startActivityForResult(intent, REQUEST_PICK_IMAGE);
                    } else {
                        // 拍照
                        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        if (intent.resolveActivity(getPackageManager()) != null) {
                            tempAvatarFile = new File(getCacheDir(),
                                    "avatar_" + System.currentTimeMillis() + ".jpg");
                            intent.putExtra(MediaStore.EXTRA_OUTPUT,
                                    Uri.fromFile(tempAvatarFile));
                            startActivityForResult(intent, REQUEST_CAMERA);
                        } else {
                            Toast.makeText(this, "未找到相机应用", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
        DialogHelper.applyToAlertDialog(alertDialog, this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK) return;

        if (requestCode == REQUEST_PICK_IMAGE && data != null && data.getData() != null) {
            Uri imageUri = data.getData();
            File tempFile = copyUriToCache(imageUri, "avatar_pick.jpg");
            if (tempFile != null) {
                uploadAvatar(tempFile);
            }
        } else if (requestCode == REQUEST_CAMERA) {
            if (tempAvatarFile != null && tempAvatarFile.exists()) {
                uploadAvatar(tempAvatarFile);
            }
        }
    }
    /**
      * 上传头像 — V4 双重方案（彻底修复 Cookie 同步问题）
      *
      * ★ 关键修复：
      * 1. 先检查 OkHttp CookieJar 中是否有有效 Cookie，避免被 WebView 空 Cookie 覆盖
      * 2. 使用 HttpClient 自己的 Cookie 而非依赖自动 CookieJar
      * 3. 强制携带 Cookie Header 和桌面 UA
      */
     private void uploadAvatar(File imageFile) {
         showLoading(true);
         executor.execute(() -> {
             try {
                 // ★ 关键修复：先检查 OkHttp CookieJar 是否已有有效 Cookie
                 // 如果已有有效 Cookie，则不覆盖；如果为空，才从 WebView 同步
                 boolean hasValidCookie = httpClient.isLoggedIn();
                 if (!hasValidCookie) {
                     // OkHttp CookieJar 为空，从 WebView 同步
                     httpClient.syncFromCookieManager();
                 }
                 
                 // 同步后再次检查
                 if (!httpClient.isLoggedIn()) {
                     throw new IllegalStateException("未检测到登录态，请先登录");
                 }
                 
                 // 确保 Cookie 双向同步（OkHttp -> WebView）
                 httpClient.syncToCookieManager();
                 
                 String avatarPageUrl = HttpClient.BASE_URL
                         + "home.php?mod=spacecp&ac=avatar";
                 
                 // 使用桌面 UA 获取头像页面（formhash 更稳定）
                 String avatarPageHtml = httpClient.getDesktop(avatarPageUrl);
                 
                 // 如果桌面 UA 返回登录页，用移动 UA 重试
                 if (ForumParser.isLoginPage(avatarPageHtml)) {
                     avatarPageHtml = httpClient.get(avatarPageUrl);
                 }
                 
                 // 解析 formhash
                 String fh = ForumParser.parseFormhash(avatarPageHtml);
                 if (TextUtils.isEmpty(fh)) {
                     Matcher m = Pattern.compile("formhash\\s*=\\s*['\"]([^'\"]+)['\"]")
                             .matcher(avatarPageHtml != null ? avatarPageHtml : "");
                     if (m.find()) fh = m.group(1);
                 }
                 
                 // 双重检查：必须是有效页面且有 formhash
                 if (ForumParser.isLoginPage(avatarPageHtml) || TextUtils.isEmpty(fh)) {
                     throw new IllegalStateException("登录已过期或未获取到头像页面令牌");
                 }
                 
                 // 头像上传 URL
                 String uploadUrl = HttpClient.BASE_URL
                         + "home.php?mod=spacecp&ac=avatar&ref";
                 String mime = getContentMime(imageFile);
                 okhttp3.RequestBody fileBody = okhttp3.RequestBody.create(
                         okhttp3.MediaType.parse(mime), imageFile);
                 
                 // ★ 关键：构建 multipart 请求体
                 okhttp3.MultipartBody requestBody = new okhttp3.MultipartBody.Builder()
                         .setType(okhttp3.MultipartBody.FORM)
                         .addFormDataPart("formhash", fh)
                         .addFormDataPart("avatarsubmit", "yes")
                         .addFormDataPart("upload", "1")
                         .addFormDataPart("Filedata", imageFile.getName(), fileBody)
                         .build();
                 
                 // ★ 关键：显式获取 Cookie Header，避免依赖 CookieJar 自动处理
                 String cookieHeader = httpClient.getCookieHeader();
                 if (TextUtils.isEmpty(cookieHeader)) {
                     throw new IllegalStateException("未找到论坛登录Cookie，请先在软件内重新登录");
                 }
                 
                 // 构建请求：强制使用桌面 UA 和显式 Cookie Header
                 okhttp3.Request request = new okhttp3.Request.Builder()
                         .url(uploadUrl)
                         .header("User-Agent", HttpClient.DESKTOP_USER_AGENT)
                         .header("Cookie", cookieHeader)
                         .header("Referer", avatarPageUrl)
                         .header("Origin", HttpClient.BASE_URL)
                         .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                         .post(requestBody)
                         .build();
                 
                  // 执行请求
                  String response;
                  try (okhttp3.Response resp = httpClient.executeDirect(request)) {
                      response = resp.body() != null ? resp.body().string() : "";
                  }
                  
                  // 解析响应
                  final String result = response == null ? "" : response.trim();
                  final String serverMessage = extractAvatarXmlMessage(result);
                  final boolean success = isAvatarUploadSuccess(result);
                  
                  // ★ 关键修复：如果主方案失败，自动切换到 UCenter API
                  if (!success) {
                      runOnUiThread(() -> {
                          showLoading(false);
                          Toast.makeText(this, "标准上传失败，尝试备用方案...", Toast.LENGTH_SHORT).show();
                      });
                      try {
                          uploadAvatarViaUCenter(imageFile);
                          return;
                      } catch (Exception e) {
                          runOnUiThread(() -> {
                              showLoading(false);
                              Toast.makeText(this, "头像上传失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                          });
                          return;
                      }
                  }
                  
                  runOnUiThread(() -> {
                      showLoading(false);
                      if (success) {
                          Toast.makeText(this, "头像上传成功！请返回刷新页面查看", Toast.LENGTH_SHORT).show();
                      } else {
                          String detail = TextUtils.isEmpty(serverMessage) ? result : serverMessage;
                          detail = detail.replaceAll("\\s+", " ").trim();
                          Toast.makeText(this, "头像上传失败: "
                                  + detail.substring(0, Math.min(160, detail.length())),
                                  Toast.LENGTH_SHORT).show();
                      }
                  });
             } catch (Exception e) {
                 runOnUiThread(() -> {
                     showLoading(false);
                     Toast.makeText(this, "头像上传失败: " + e.getMessage(),
                             Toast.LENGTH_SHORT).show();
                 });
             }
      });
      }
      
      private String getContentMime(File file) {
        String name = file != null ? file.getName().toLowerCase() : "";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    /**
     * Discuz! 头像接口返回的是 XML。提取 message，避免把整段 XML 直接显示给用户。
     */
    private String extractAvatarXmlMessage(String response) {
        if (TextUtils.isEmpty(response)) return "";
        try {
            Matcher m = Pattern.compile("(?is)<message[^>]*>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?\\s*</message>")
                    .matcher(response);
            if (m.find()) {
                return android.text.Html.fromHtml(m.group(1), android.text.Html.FROM_HTML_MODE_LEGACY)
                        .toString().trim();
            }
            m = Pattern.compile("(?is)<error[^>]*>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?\\s*</error>")
                    .matcher(response);
            if (m.find()) return m.group(1).trim();
        } catch (Exception ignored) {
        }
        return "";
    }

    private boolean isAvatarUploadSuccess(String response) {
        if (TextUtils.isEmpty(response)) return false;
        String lower = response.toLowerCase();
        String message = extractAvatarXmlMessage(response).toLowerCase();
        // Discuz XML 成功响应通常含 message=上传成功，或返回头像地址/文件名。
        if (lower.contains("<error") || message.contains("失败") || message.contains("错误")) {
            return false;
        }
        return message.contains("成功")
                || message.contains("success")
                || lower.contains("上传成功")
                || lower.contains("success")
                || lower.contains("avatar1")
                || lower.contains("avatar2")
                || lower.contains("avatar3")
                || lower.contains("<url>")
                || lower.contains("<avatar");
    }
    
    /**
     * 备用方案：UCenter rectavatar API（base64方式）
     */
    private void uploadAvatarViaUCenter(File imageFile) throws Exception {
        String avatarPageHtml = httpClient.get(
                HttpClient.BASE_URL + "home.php?mod=spacecp&ac=avatar&mobile=2");
        
        String inputParam = "";
        String agentParam = "";
        String ucApi = "";
        
        java.util.regex.Matcher dataMatcher;
        
        dataMatcher = java.util.regex.Pattern.compile("input=([^&,]+)").matcher(avatarPageHtml);
        if (dataMatcher.find()) inputParam = java.net.URLDecoder.decode(dataMatcher.group(1), "UTF-8");
        dataMatcher = java.util.regex.Pattern.compile("agent=([^&,]+)").matcher(avatarPageHtml);
        if (dataMatcher.find()) agentParam = java.net.URLDecoder.decode(dataMatcher.group(1), "UTF-8");
        dataMatcher = java.util.regex.Pattern.compile("ucapi=([^&,]+)").matcher(avatarPageHtml);
        if (dataMatcher.find()) ucApi = java.net.URLDecoder.decode(dataMatcher.group(1), "UTF-8");
        
        if (inputParam.isEmpty()) {
            dataMatcher = java.util.regex.Pattern.compile("var\\s+input\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(avatarPageHtml);
            if (dataMatcher.find()) inputParam = dataMatcher.group(1);
        }
        if (agentParam.isEmpty()) {
            dataMatcher = java.util.regex.Pattern.compile("var\\s+agent\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(avatarPageHtml);
            if (dataMatcher.find()) agentParam = dataMatcher.group(1);
        }
        if (ucApi.isEmpty()) {
            dataMatcher = java.util.regex.Pattern.compile("var\\s+ucapi\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(avatarPageHtml);
            if (dataMatcher.find()) ucApi = dataMatcher.group(1);
        }
        
        String baseUcApi = ucApi;
        if (TextUtils.isEmpty(baseUcApi)) {
            baseUcApi = HttpClient.BASE_URL + "uc_server";
        } else if (!baseUcApi.startsWith("http://") && !baseUcApi.startsWith("https://")) {
            if (baseUcApi.startsWith("//")) {
                baseUcApi = "https:" + baseUcApi;
            } else {
                baseUcApi = HttpClient.BASE_URL + (baseUcApi.startsWith("/") ? baseUcApi.substring(1) : baseUcApi);
            }
        }
        if (baseUcApi.endsWith("/")) baseUcApi = baseUcApi.substring(0, baseUcApi.length() - 1);
        
        String rectavatarUrl = baseUcApi + "/index.php?m=user&a=rectavatar&base64=yes";
        
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        Bitmap originalBitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath(), opts);
        if (originalBitmap == null) {
            runOnUiThread(() -> Toast.makeText(this, "无法解码图片", Toast.LENGTH_SHORT).show());
            return;
        }
        
        int imgW = originalBitmap.getWidth();
        int imgH = originalBitmap.getHeight();
        String avatar1Base64 = generateAvatarBase64(originalBitmap, imgW, imgH, 200, 250);
        String avatar2Base64 = generateAvatarBase64(originalBitmap, imgW, imgH, 120, 120);
        String avatar3Base64 = generateAvatarBase64(originalBitmap, imgW, imgH, 48, 48);
        originalBitmap.recycle();
        
        okhttp3.MultipartBody.Builder builder = new okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("avatar1", avatar1Base64)
                .addFormDataPart("avatar2", avatar2Base64)
                .addFormDataPart("avatar3", avatar3Base64)
                .addFormDataPart("input", inputParam)
                .addFormDataPart("agent", agentParam)
                .addFormDataPart("appid", "1");
        
        okhttp3.RequestBody requestBody = builder.build();
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(rectavatarUrl)
                .header("User-Agent", HttpClient.USER_AGENT)
                .header("Cookie", httpClient.getCookieHeader())
                .header("Referer", HttpClient.BASE_URL + "home.php?mod=spacecp&ac=avatar&mobile=2")
                .post(requestBody)
                .build();
        
        String rectResponse = "";
        try (okhttp3.Response resp = httpClient.executeDirect(request)) {
            rectResponse = resp.body() != null ? resp.body().string() : "";
        }
        
        final String finalResponse = rectResponse;
        runOnUiThread(() -> {
            showLoading(false);
            if (finalResponse != null && (finalResponse.contains("success")
                    || finalResponse.contains("SUCCESS"))) {
                Toast.makeText(this, "头像上传成功！请返回刷新页面查看", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "头像上传失败: " + (finalResponse != null ? finalResponse.substring(0, Math.min(100, finalResponse.length())) : ""), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 生成指定尺寸的头像 JPEG base64 数据
     * 从原图中心截取正方形区域，缩放至目标尺寸
     */
    private String generateAvatarBase64(Bitmap src, int srcW, int srcH, int maxW, int maxH) {
        try {
            // 取正方形：从中心截取
            int squareSize = Math.min(srcW, srcH);
            int x = (srcW - squareSize) / 2;
            int y = (srcH - squareSize) / 2;
            Bitmap square = Bitmap.createBitmap(src, x, y, squareSize, squareSize);

            // 缩放至目标尺寸
            int tw = Math.min(maxW, squareSize);
            int th = Math.min(maxH, squareSize);
            if (tw > th) th = tw;
            else tw = th;
            tw = Math.min(tw, 200);
            th = Math.min(th, 250);
            if (squareSize < tw) {
                tw = squareSize;
                th = squareSize;
            }
            Bitmap scaled = Bitmap.createScaledBitmap(square, tw, th, true);
            if (scaled != square) square.recycle();

            // 转为 JPEG base64
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, baos);
            scaled.recycle();

            return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    // ================================================================
    // 4. 保存表单
    // ================================================================

    private void saveCurrentForm() {
        // 先收集所有 EditText 的值
        collectCurrentValues();

        if (currentFormhash == null || currentFormhash.isEmpty()) {
            Toast.makeText(this, "表单状态异常，请重新加载", Toast.LENGTH_SHORT).show();
            return;
        }

        showLoading(true);
        executor.execute(() -> {
            try {
                // 构造 POST 参数
                String postUrl = HttpClient.BASE_URL
                        + "home.php?mod=spacecp&ac=profile&op=" + currentOp;
                Map<String, String> params = new HashMap<>();
                params.put("formhash", currentFormhash);
                params.put("profilesubmit", "true");

                // 添加当前 tab 下的所有字段值
                for (FormField field : formFields) {
                    String val = formValues.get(field.name);
                    if (val != null) {
                        params.put(field.name, val);
                    }
                    // 添加隐私开关
                    String privacyKey = "privacy[" + field.name + "]";
                    String privacyVal = privacyValues.get(privacyKey);
                    if (privacyVal != null) {
                        params.put(privacyKey, privacyVal);
                    }
                }

                String response = httpClient.post(postUrl, params);

                // 检查响应是否成功（包含 show_success 或提示成功信息）
                boolean success = false;
                if (response != null) {
                    if (response.contains("show_success") || response.contains("资料更新成功")
                            || response.contains("success") || response.contains("成功")) {
                        success = true;
                    }
                }

                final boolean finalSuccess = success;
                runOnUiThread(() -> {
                    showLoading(false);
                    if (finalSuccess) {
                        Toast.makeText(this, "资料更新成功！", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "资料更新失败，请重试", Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    showLoading(false);
                    Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 遍历当前所有可见的 EditText，收集值
     */
    private void collectCurrentValues() {
        ViewGroup container = binding.formContainer;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof LinearLayout) {
                collectFromRow((LinearLayout) child);
            }
        }
    }

    private void collectFromRow(LinearLayout row) {
        // 查找 EditText
        EditText et = row.findViewById(R.id.et_field_input);
        if (et != null && et.getTag() instanceof String) {
            formValues.put((String) et.getTag(), et.getText().toString());
            return;
        }
        EditText eta = row.findViewById(R.id.et_field_textarea);
        if (eta != null && eta.getTag() instanceof String) {
            formValues.put((String) eta.getTag(), eta.getText().toString());
            return;
        }
    }

    // ================================================================
    // 工具方法
    // ================================================================

    private void showLoading(boolean show) {
        binding.progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private String getUid() {
        // 从 cookie 中提取 uid（Discuz! 的 home 等页面需要）
        // 这里从当前会话中尝试获取
        try {
            String html = httpClient.getDesktop(HttpClient.BASE_URL + "home.php?mod=spacecp");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("uid=(\\d+)").matcher(html);
            if (m.find()) return m.group(1);
            // 尝试从 Cookie 中的 7ZiC_uid 获取
            String uidCookie = httpClient.getCookieValue(
                    java.net.URI.create(HttpClient.BASE_URL).getHost(), "7ZiC_uid");
            if (uidCookie != null && !uidCookie.isEmpty()) return uidCookie;
        } catch (Exception ignored) {}
        return "0";
    }

    private File copyUriToCache(Uri uri, String fileName) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) return null;
            File cacheFile = new File(getCacheDir(), fileName);
            FileOutputStream fos = new FileOutputStream(cacheFile);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
            fos.close();
            inputStream.close();
            return cacheFile;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    // ================================================================
    // 内部类：表单字段定义
    // ================================================================

    public static class FormField {
        public static final int TYPE_TEXT = 0;
        public static final int TYPE_TEXT_AREA = 1;
        public static final int TYPE_SELECT = 2;

        public String name;
        public String label;
        public int type;
        public String[][] options; // 仅 TYPE_SELECT 使用，[值, 显示文本]

        public FormField(String name, String label, int type, String defaultValue) {
            this.name = name;
            this.label = label;
            this.type = type;
            this.options = new String[][]{{"", defaultValue}};
        }

        public FormField(String name, String label, int type, String[][] options) {
            this.name = name;
            this.label = label;
            this.type = type;
            this.options = options;
        }
    }
}
