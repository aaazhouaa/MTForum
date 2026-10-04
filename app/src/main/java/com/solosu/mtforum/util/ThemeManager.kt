package com.solosu.mtforum.util

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.solosu.mtforum.R

/**
 * 全局现代主题色与深浅模式管理中枢
 */
object ThemeManager {

    private const val PREF_NAME = "app_theme_prefs"
    private const val KEY_THEME_COLOR = "theme_color_index"
    private const val KEY_NIGHT_MODE = "night_mode_setting"
    const val DEFAULT_THEME_INDEX = 1 // 1: 极光薄荷青

    data class ThemeColorItem(
        val id: Int,
        val name: String,
        val primaryDay: Int,
        val primaryNight: Int,
        val themeRes: Int
    )

    val THEME_COLORS = listOf(
        ThemeColorItem(0, "经典科技蓝", 0xFF2563EB.toInt(), 0xFF60A5FA.toInt(), R.style.Theme_AppTheme),
        ThemeColorItem(1, "极光薄荷青", 0xFF0D9488.toInt(), 0xFF2DD4BF.toInt(), R.style.Theme_AppTheme_Teal),
        ThemeColorItem(2, "幻夜紫罗兰", 0xFF7C3AED.toInt(), 0xFFA78BFA.toInt(), R.style.Theme_AppTheme_Purple),
        ThemeColorItem(3, "活力珊瑚橙", 0xFFEA580C.toInt(), 0xFFFB923C.toInt(), R.style.Theme_AppTheme_Orange),
        ThemeColorItem(4, "极简冷石灰", 0xFF475569.toInt(), 0xFF94A3B8.toInt(), R.style.Theme_AppTheme_Graphite),
        ThemeColorItem(5, "灵动玫瑰粉", 0xFFE11D48.toInt(), 0xFFFB7185.toInt(), R.style.Theme_AppTheme_Rose)
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /** 初始化（Application 启动时调用） */
    @JvmStatic
    fun init(context: Context) {
        val nightMode = getNightMode(context)
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    /** 为 Activity 设置主题色（需在 super.onCreate 或 setContentView 前调用） */
    @JvmStatic
    fun applyTheme(activity: Activity) {
        val index = getThemeColorIndex(activity)
        val item = THEME_COLORS.getOrNull(index) ?: THEME_COLORS[DEFAULT_THEME_INDEX]
        activity.setTheme(item.themeRes)
        setupWindow(activity)
    }

    /** 统一将状态栏与导航栏底色设为背景色，并自动调适图标深浅色 */
    @JvmStatic
    fun setupWindow(activity: Activity) {
        activity.window.statusBarColor = ContextCompat.getColor(activity, R.color.top_bar)
        activity.window.navigationBarColor = ContextCompat.getColor(activity, R.color.background)
        androidx.core.view.WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            val isDark = isDarkMode(activity)
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }

    @JvmStatic
    fun getThemeColorIndex(context: Context): Int {
        return getPrefs(context).getInt(KEY_THEME_COLOR, DEFAULT_THEME_INDEX).coerceIn(0, THEME_COLORS.size - 1)
    }

    @JvmStatic
    fun setThemeColorIndex(context: Context, index: Int) {
        getPrefs(context).edit().putInt(KEY_THEME_COLOR, index).apply()
    }

    @JvmStatic
    fun getCurrentThemeColor(context: Context): ThemeColorItem {
        val index = getThemeColorIndex(context)
        return THEME_COLORS.getOrNull(index) ?: THEME_COLORS[DEFAULT_THEME_INDEX]
    }

    @JvmStatic
    fun getThemeColor(context: Context): Int {
        val item = getCurrentThemeColor(context)
        return if (isDarkMode(context)) item.primaryNight else item.primaryDay
    }

    @JvmStatic
    fun getThemeLightColor(context: Context): Int {
        val color = getThemeColor(context)
        val r = (android.graphics.Color.red(color) * 0.7f + 255 * 0.3f).toInt()
        val g = (android.graphics.Color.green(color) * 0.7f + 255 * 0.3f).toInt()
        val b = (android.graphics.Color.blue(color) * 0.7f + 255 * 0.3f).toInt()
        return android.graphics.Color.rgb(r, g, b)
    }

    @JvmStatic
    fun getNightMode(context: Context): Int {
        return getPrefs(context).getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    @JvmStatic
    fun setNightMode(context: Context, mode: Int) {
        getPrefs(context).edit().putInt(KEY_NIGHT_MODE, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** 判断当前是否处于暗色模式 */
    @JvmStatic
    fun isDarkMode(context: Context): Boolean {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    /** 切换深浅色主题（太阳与月亮切换） */
    @JvmStatic
    fun toggleNightMode(activity: Activity): Boolean {
        val currentIsDark = isDarkMode(activity)
        val newMode = if (currentIsDark) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        setNightMode(activity, newMode)
        return !currentIsDark
    }

    /** 弹出现代感极简圆环主题颜色选择器 */
    @JvmStatic
    fun showColorPickerDialog(activity: Activity, onColorSelected: (() -> Unit)? = null) {
        val dialog = Dialog(activity)
        val isDark = isDarkMode(activity)
        val density = activity.resources.displayMetrics.density

        val root = LinearLayout(activity)
        root.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.cornerRadius = 24f * density
        bg.setColor(ContextCompat.getColor(activity, R.color.surface))
        root.background = bg
        root.setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (24 * density).toInt())

        // 标题行
        val tvTitle = TextView(activity)
        tvTitle.text = "选择主题色彩"
        tvTitle.textSize = 18f
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD)
        tvTitle.setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
        root.addView(tvTitle)

        val tvSub = TextView(activity)
        tvSub.text = "打造个性化简约论坛质感"
        tvSub.textSize = 12f
        tvSub.setTextColor(ContextCompat.getColor(activity, R.color.text_hint))
        tvSub.setPadding(0, (4 * density).toInt(), 0, (16 * density).toInt())
        root.addView(tvSub)

        // 颜色网格 (3列 x 2行)
        val grid = GridLayout(activity)
        grid.columnCount = 3
        grid.rowCount = 2
        val currentIndex = getThemeColorIndex(activity)

        for ((idx, item) in THEME_COLORS.withIndex()) {
            val color = if (isDark) item.primaryNight else item.primaryDay
            val itemBox = LinearLayout(activity)
            itemBox.orientation = LinearLayout.VERTICAL
            itemBox.gravity = Gravity.CENTER
            itemBox.setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())

            // 圆形色块容器
            val circleBox = android.widget.FrameLayout(activity)
            val circleSize = (44 * density).toInt()

            val circleBg = GradientDrawable()
            circleBg.shape = GradientDrawable.OVAL
            circleBg.setColor(color)
            circleBox.background = circleBg

            // 勾选图标
            if (idx == currentIndex) {
                val ivCheck = ImageView(activity)
                ivCheck.setImageResource(R.drawable.ic_check)
                ivCheck.setColorFilter(Color.WHITE)
                ivCheck.setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
                circleBox.addView(ivCheck)
            }

            itemBox.addView(circleBox, LinearLayout.LayoutParams(circleSize, circleSize))

            // 颜色名称
            val tvName = TextView(activity)
            tvName.text = item.name
            tvName.textSize = 11f
            tvName.setTextColor(
                if (idx == currentIndex) color else ContextCompat.getColor(activity, R.color.text_secondary)
            )
            tvName.setPadding(0, (6 * density).toInt(), 0, 0)
            itemBox.addView(tvName)

            itemBox.isClickable = true
            itemBox.setOnClickListener {
                setThemeColorIndex(activity, idx)
                dialog.dismiss()
                ToastUtil.show(activity, "已应用「${item.name}」")
                onColorSelected?.invoke()
                activity.recreate()
            }

            val params = GridLayout.LayoutParams()
            params.width = 0
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            params.setMargins((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            grid.addView(itemBox, params)
        }

        root.addView(grid)
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }
}
