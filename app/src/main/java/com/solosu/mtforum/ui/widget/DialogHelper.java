package com.solosu.mtforum.ui.widget;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * 对话框毛玻璃统一入口。
 */
public final class DialogHelper {

    private DialogHelper() {}

    public static void applyToBottomSheet(BottomSheetDialog dialog, Activity activity) {
        if (dialog == null || activity == null) return;
        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            View parent = window != null ? window.getDecorView() : null;
            if (parent == null) return;
            parent.setBackgroundResource(android.R.color.transparent);
            FrostedGlassHelper.applyToCardViews(parent, activity);
            View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                sheet.setBackground(new ColorDrawable(Color.TRANSPARENT));
                sheet.setClipToOutline(false);
            }
        });
    }

    public static void applyToBottomSheet(BottomSheetDialog dialog, View contentView, Activity activity) {
        if (dialog == null || activity == null) return;
        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            View parent = window != null ? window.getDecorView() : null;
            if (parent != null) {
                parent.setBackgroundResource(android.R.color.transparent);
                FrostedGlassHelper.applyToCardViews(parent, activity);
            }
            View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                sheet.setBackground(new ColorDrawable(Color.TRANSPARENT));
                sheet.setClipToOutline(false);
            }
            if (contentView != null) {
                FrostedGlassHelper.applyToCardViews(contentView, activity);
            }
        });
    }

    /**
     * 给 AlertDialog 应用毛玻璃背景。
     */
    public static void applyToAlertDialog(android.app.Dialog dialog, Context context) {
        if (dialog == null || context == null) return;

        if (dialog.isShowing()) {
            applyFrostedNow(dialog, context);
        } else {
            dialog.setOnShowListener(d -> applyFrostedNow(dialog, context));
        }
    }

    private static void applyFrostedNow(android.app.Dialog dialog, Context context) {
        Window window = dialog.getWindow();
        if (window == null) return;

        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        View decorView = window.getDecorView();
        if (decorView == null) return;

        FrostedGlassHelper.applyToCardViews(decorView, context);

        boolean isDark = isDarkMode(context);
        float density = context.getResources().getDisplayMetrics().density;
        float radius = 12f * density;

        // 找 Dialog 内容根容器:android.R.id.content 的第一个子 View
        View content = decorView.findViewById(android.R.id.content);
        if (content == null) content = decorView;

        View root = null;
        if (content instanceof ViewGroup) {
            ViewGroup contentGroup = (ViewGroup) content;
            if (contentGroup.getChildCount() > 0) {
                root = contentGroup.getChildAt(0);
            }
        }
        if (root == null) root = content;

        // 直接替换根容器背景为毛玻璃
        root.setBackground(new FrostedGlassDrawable(
                isDark ? 0xFF1E1E1E : 0xFFFFFFFF, radius, density));

        // 递归清空子 ViewGroup 背景,让毛玻璃透出
        clearChildBackgrounds(root);
    }

    private static void clearChildBackgrounds(View view) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup) {
                child.setBackground(new ColorDrawable(Color.TRANSPARENT));
                clearChildBackgrounds(child);
            }
        }
    }

    private static boolean isDarkMode(Context context) {
        return (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }
}
