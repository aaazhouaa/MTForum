package com.solosu.mtforum.ui.space;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.solosu.mtforum.R;
import com.solosu.mtforum.databinding.ActivityEditProfileBinding;
import com.solosu.mtforum.network.ForumParser;
import com.solosu.mtforum.network.HttpClient;
import com.solosu.mtforum.ui.widget.FrostedGlassHelper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 编辑资料导航页。头像在本页使用原生选图、规范化和上传流程。 */
public class EditProfileActivity extends AppCompatActivity {

    private static final int REQUEST_PICK_AVATAR = 5101;
    private ActivityEditProfileBinding binding;
    private HttpClient httpClient;
    private ExecutorService executor;
    private boolean avatarUploadInProgress;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityEditProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        FrostedGlassHelper.applyToCardViews(binding.getRoot(), this);
        httpClient = HttpClient.getInstance();
        executor = Executors.newSingleThreadExecutor();

        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_left);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        // 不再打开 WebView：直接交给系统照片选择器，然后走原生上传。
        binding.btnEditAvatar.setOnClickListener(v -> pickAvatar());
        binding.btnEditBaseInfo.setOnClickListener(v -> openNativeForm("base"));
        binding.btnEditContact.setOnClickListener(v -> openNativeForm("contact"));
        binding.btnEditInfo.setOnClickListener(v -> openNativeForm("info"));
    }

    private void pickAvatar() {
        if (!httpClient.isLoggedIn()) {
            Toast.makeText(this, R.string.login_required_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, REQUEST_PICK_AVATAR);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开系统相册", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_AVATAR || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        uploadAvatar(data.getData());
    }

    /**
     * 统一转成受尺寸、体积和透明通道约束的 JPEG，避免旧 Discuz 头像接口拒绝 HEIC、
     * 透明 PNG、超大照片或厂商私有编码。服务端会继续负责最终的头像裁剪。
     */
    private File normalizeAvatar(Uri uri) throws Exception {
        final int maxSide = 1024;
        ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
        Bitmap decoded = ImageDecoder.decodeBitmap(source, (decoder, info, ignored) -> {
            int largest = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
            if (largest > maxSide) decoder.setTargetSize(
                    Math.max(1, Math.round(info.getSize().getWidth() * maxSide / (float) largest)),
                    Math.max(1, Math.round(info.getSize().getHeight() * maxSide / (float) largest)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        if (decoded == null) throw new IllegalStateException("无法解码所选图片");

        // JPEG 不支持透明像素；先在白底画布压平，保证真实字节、后缀和 MIME 一致。
        Bitmap flattened = Bitmap.createBitmap(decoded.getWidth(), decoded.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(flattened);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(decoded, 0, 0, null);
        decoded.recycle();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int quality = 90;
        do {
            output.reset();
            flattened.compress(Bitmap.CompressFormat.JPEG, quality, output);
            quality -= 5;
        } while (output.size() > 400 * 1024 && quality >= 55);
        flattened.recycle();
        if (output.size() == 0) throw new IllegalStateException("图片压缩失败");

        File dir = new File(getCacheDir(), "avatar_uploads");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建临时目录");
        File file = new File(dir, "avatar_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(output.toByteArray());
            stream.flush();
        }
        return file;
    }

    private void uploadAvatar(Uri uri) {
        if (avatarUploadInProgress) return;
        avatarUploadInProgress = true;
        binding.btnEditAvatar.setEnabled(false);
        Toast.makeText(this, "正在处理并上传头像…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            File imageFile = null;
            try {
                imageFile = normalizeAvatar(uri);
                ensureLoggedIn();
                String avatarPageUrl = HttpClient.BASE_URL + "home.php?mod=spacecp&ac=avatar";
                String page = httpClient.getDesktop(avatarPageUrl);
                if (ForumParser.isLoginPage(page)) page = httpClient.get(avatarPageUrl);
                if (ForumParser.isLoginPage(page)) {
                    throw new IllegalStateException("登录已过期，请重新登录");
                }

                // Discuz 的头像设置页只是承载 UCenter Flash/裁剪组件；真正写入头像的接口是
                // 组件携带 input、agent、ucapi 参数调用的 rectavatar，而不是 home.php 的 &ref 路由。
                // 之前先 POST &ref，会在本站路由下稳定返回 HTTP 404，且异常使备用流程无法运行。
                String response = uploadAvatarViaUCenter(imageFile, page);
                if (!isAvatarUploadSuccess(response)) {
                    String detail = extractAvatarMessage(response);
                    throw new IllegalStateException(TextUtils.isEmpty(detail) ? "服务器未确认头像上传" : detail);
                }
                runOnUiThread(() -> Toast.makeText(this,
                        "头像上传成功，请返回个人页面刷新查看", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                String message = TextUtils.isEmpty(e.getMessage()) ? "网络异常，请稍后重试" : e.getMessage();
                runOnUiThread(() -> Toast.makeText(this, "头像上传失败：" + message,
                        Toast.LENGTH_LONG).show());
            } finally {
                if (imageFile != null && imageFile.exists()) imageFile.delete();
                runOnUiThread(() -> {
                    avatarUploadInProgress = false;
                    binding.btnEditAvatar.setEnabled(true);
                });
            }
        });
    }

    private void ensureLoggedIn() {
        if (!httpClient.isLoggedIn()) httpClient.syncFromCookieManager();
        if (!httpClient.isLoggedIn() || TextUtils.isEmpty(httpClient.getCookieHeader())) {
            throw new IllegalStateException("未检测到登录状态，请重新登录");
        }
        httpClient.syncToCookieManager();
    }

    private String uploadAvatarViaUCenter(File imageFile, String avatarPage) throws Exception {
        // input、agent、ucapi 是头像页面下发的短期授权参数；优先使用同一次页面请求，
        // 避免重新请求页面造成 token 或登录态不一致。
        String page = avatarPage;
        String input = extractJsAvatarValue(page, "input");
        String agent = extractJsAvatarValue(page, "agent");
        String ucApi = extractJsAvatarValue(page, "ucapi");
        if (TextUtils.isEmpty(input) || TextUtils.isEmpty(agent)) {
            page = httpClient.get(HttpClient.BASE_URL + "home.php?mod=spacecp&ac=avatar&mobile=2");
            input = extractJsAvatarValue(page, "input");
            agent = extractJsAvatarValue(page, "agent");
            if (TextUtils.isEmpty(ucApi)) ucApi = extractJsAvatarValue(page, "ucapi");
        }
        if (TextUtils.isEmpty(input) || TextUtils.isEmpty(agent)) {
            throw new IllegalStateException("未获取到头像上传授权参数");
        }
        if (TextUtils.isEmpty(ucApi)) ucApi = HttpClient.BASE_URL + "uc_server";
        if (ucApi.startsWith("//")) {
            ucApi = "https:" + ucApi;
        } else if (!ucApi.startsWith("http://") && !ucApi.startsWith("https://")) {
            ucApi = HttpClient.BASE_URL + (ucApi.startsWith("/") ? ucApi.substring(1) : ucApi);
        }
        if (ucApi.endsWith("/")) ucApi = ucApi.substring(0, ucApi.length() - 1);

        // ImageDecoder 默认可能返回 Hardware Bitmap；而 avatarBase64 需要使用 Canvas 软件绘制。
        // 即使临时文件是本地 JPEG，也必须在此二次解码时明确要求 SOFTWARE allocator。
        Bitmap original = ImageDecoder.decodeBitmap(ImageDecoder.createSource(imageFile),
                (decoder, info, source) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
        if (original == null || original.isRecycled()) {
            throw new IllegalStateException("无法解码头像图片");
        }
        try {
            okhttp3.MultipartBody form = new okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("avatar1", avatarBase64(original, 200))
                    .addFormDataPart("avatar2", avatarBase64(original, 120))
                    .addFormDataPart("avatar3", avatarBase64(original, 48))
                    .addFormDataPart("input", input)
                    .addFormDataPart("agent", agent)
                    .addFormDataPart("appid", "1")
                    .build();
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(ucApi + "/index.php?m=user&a=rectavatar&base64=yes")
                    .header("User-Agent", HttpClient.USER_AGENT)
                    .header("Cookie", httpClient.getCookieHeader())
                    .header("Referer", HttpClient.BASE_URL + "home.php?mod=spacecp&ac=avatar&mobile=2")
                    .post(form).build();
            try (okhttp3.Response response = httpClient.executeDirect(request)) {
                if (!response.isSuccessful()) throw new IllegalStateException("备用上传请求失败（HTTP " + response.code() + "）");
                return response.body() == null ? "" : response.body().string();
            }
        } finally {
            original.recycle();
        }
    }

    private String extractJsAvatarValue(String html, String name) {
        if (TextUtils.isEmpty(html)) return "";
        Matcher matcher = Pattern.compile("(?:var\\s+)?" + Pattern.quote(name)
                + "\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]").matcher(html);
        if (matcher.find()) return matcher.group(1);
        matcher = Pattern.compile("[?&]" + Pattern.quote(name) + "=([^&,\\\"']+)").matcher(html);
        try {
            return matcher.find() ? java.net.URLDecoder.decode(matcher.group(1), "UTF-8") : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private String avatarBase64(Bitmap source, int size) {
        if (source == null || source.isRecycled()) {
            throw new IllegalArgumentException("头像源图片不可用");
        }
        int edge = Math.min(source.getWidth(), source.getHeight());
        int targetSize = Math.max(1, Math.min(size, edge));

        // 不能用 createBitmap(source, 0, 0, source.getWidth(), source.getHeight()) 后回收：
        // 当源图本身为正方形时，框架可直接返回 source；回收中间图会把 source 一并回收，
        // 导致随后生成 avatar2/avatar3 时抛出 "cannot use a recycled source"。
        // 始终新建目标 Bitmap 并以 Canvas 绘制，整个过程中 source 的所有权仍属于调用方。
        Bitmap target = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888);
        try {
            int left = (source.getWidth() - edge) / 2;
            int top = (source.getHeight() - edge) / 2;
            Canvas canvas = new Canvas(target);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(source,
                    new android.graphics.Rect(left, top, left + edge, top + edge),
                    new android.graphics.Rect(0, 0, targetSize, targetSize), null);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!target.compress(Bitmap.CompressFormat.JPEG, 90, output) || output.size() == 0) {
                throw new IllegalStateException("头像图片编码失败");
            }
            return android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP);
        } finally {
            target.recycle();
        }
    }

    private boolean isAvatarUploadSuccess(String response) {
        if (TextUtils.isEmpty(response)) return false;
        String trimmed = response.trim();
        // UCenter rectavatar 的标准成功响应就是纯文本 "1"。
        if ("1".equals(trimmed)) return true;
        String lower = trimmed.toLowerCase();
        String message = extractAvatarMessage(response).toLowerCase();
        if (lower.contains("<error") || message.contains("失败") || message.contains("错误")) return false;
        return message.contains("成功") || message.contains("success") || lower.contains("上传成功")
                || lower.contains("success") || lower.contains("avatar1") || lower.contains("avatar2")
                || lower.contains("avatar3") || lower.contains("<url>") || lower.contains("<avatar");
    }

    private String extractAvatarMessage(String response) {
        if (TextUtils.isEmpty(response)) return "";
        Matcher matcher = Pattern.compile("(?is)<(?:message|error)[^>]*>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?\\s*</(?:message|error)>")
                .matcher(response);
        if (matcher.find()) return android.text.Html.fromHtml(matcher.group(1),
                android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim();
        return response.replaceAll("(?s)<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private void openNativeForm(String tab) {
        if (!httpClient.isLoggedIn()) {
            Toast.makeText(this, R.string.login_required_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, NativeProfileFormActivity.class);
        intent.putExtra("tab", tab);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) executor.shutdownNow();
    }
}
