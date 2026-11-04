package com.example.volumescroll

import android.content.Context
import android.content.SharedPreferences
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/** Kontrol edilen iki hedef. */
enum class OverlayTarget { VOLUME, BRIGHTNESS }

/** activeMode değerleri. */
object AppMode {
    const val INVISIBLE_STRIPS = 0
    const val FLOATING_BUBBLES = 1
}

/** themeMode değerleri. */
object ThemeMode {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

enum class StripPreset { VOLUME_RIGHT_BRIGHTNESS_LEFT, BOTH_RIGHT_STACKED }

data class StripBox(val x: Int, val y: Int, val width: Int, val height: Int)

/** Tüm ayarların anlık görüntüsü. Arayüz bunu StateFlow üzerinden dinler. */
data class AppConfig(
    // Genel
    val isServiceRunning: Boolean,
    val activeMode: Int,
    val isCalibrationMode: Boolean,
    val scrollCooldownMs: Long,
    val invertScroll: Boolean,
    val showSystemVolumeUI: Boolean, // kullanılmıyor: sistem ses göstergesi artık her zaman gizli
    val clickThroughBypass: Boolean,
    val stripHoverHint: Boolean,
    val themeMode: Int,
    // Mod 1: görünmez şeritler
    val volStripEnabled: Boolean,
    val briStripEnabled: Boolean,
    val volStripX: Int,
    val volStripY: Int,
    val volStripWidth: Int,
    val volStripHeight: Int,
    val briStripX: Int,
    val briStripY: Int,
    val briStripWidth: Int,
    val briStripHeight: Int,
    // Mod 2: yüzen topçuklar
    val volBubbleEnabled: Boolean,
    val briBubbleEnabled: Boolean,
    val volBubbleX: Int,
    val volBubbleY: Int,
    val briBubbleX: Int,
    val briBubbleY: Int,
    val bubbleSizePx: Int,
    val dropdownSliderHeightPx: Int,
    val bubbleIdleAlpha: Float,
    val autoCollapseSec: Int
) {
    fun stripEnabled(target: OverlayTarget): Boolean =
        if (target == OverlayTarget.VOLUME) volStripEnabled else briStripEnabled

    fun stripBox(target: OverlayTarget): StripBox =
        if (target == OverlayTarget.VOLUME) {
            StripBox(volStripX, volStripY, volStripWidth, volStripHeight)
        } else {
            StripBox(briStripX, briStripY, briStripWidth, briStripHeight)
        }

    fun bubbleEnabled(target: OverlayTarget): Boolean =
        if (target == OverlayTarget.VOLUME) volBubbleEnabled else briBubbleEnabled

    fun bubbleX(target: OverlayTarget): Int =
        if (target == OverlayTarget.VOLUME) volBubbleX else briBubbleX

    fun bubbleY(target: OverlayTarget): Int =
        if (target == OverlayTarget.VOLUME) volBubbleY else briBubbleY
}

/**
 * SharedPreferences tabanlı merkezi ayar yöneticisi.
 *
 * - Konum ve boyutlar her okumada / yazmada ekran sınırlarına göre kısıtlanır.
 * - Her değişiklikte hem OnSharedPreferenceChangeListener'lar tetiklenir (servis dinler)
 *   hem de [config] StateFlow'u güncellenir (Compose arayüzü dinler).
 */
class AppSettings private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(readInternal())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    // SharedPreferences dinleyicileri zayıf referansla tutulur; bu yüzden alan olarak saklanır.
    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    // ---------------------------------------------------------------- okuma

    fun read(): AppConfig = readInternal()

    fun refresh() {
        _config.value = readInternal()
    }

    /** Cihazın gerçek ekran boyutu (piksel). Döndürmeye duyarlıdır. */
    fun screenSize(): Pair<Int, Int> {
        val bounds = appContext.getSystemService(WindowManager::class.java)
            .maximumWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }

    private fun stripWidthRange(sw: Int): IntRange =
        MIN_STRIP_WIDTH..max(MIN_STRIP_WIDTH, kotlin.math.min(MAX_STRIP_WIDTH, sw))

    private fun stripHeightRange(sh: Int): IntRange {
        val hi = max(1, sh)
        return kotlin.math.min(MIN_STRIP_HEIGHT, hi)..hi
    }

    private fun clampBox(box: StripBox, sw: Int, sh: Int): StripBox {
        val w = box.width.coerceIn(stripWidthRange(sw))
        val h = box.height.coerceIn(stripHeightRange(sh))
        val x = box.x.coerceIn(0, max(0, sw - w))
        val y = box.y.coerceIn(0, max(0, sh - h))
        return StripBox(x, y, w, h)
    }

    private fun readInternal(): AppConfig {
        val (sw, sh) = screenSize()

        // Şerit varsayılanları: ses = sağ kenar üst yarı, parlaklık = sol kenar üst yarı.
        val vol = clampBox(
            StripBox(
                prefs.getInt(KEY_VOL_STRIP_X, sw - DEFAULT_STRIP_WIDTH),
                prefs.getInt(KEY_VOL_STRIP_Y, 0),
                prefs.getInt(KEY_VOL_STRIP_W, DEFAULT_STRIP_WIDTH),
                prefs.getInt(KEY_VOL_STRIP_H, sh / 2)
            ), sw, sh
        )
        val bri = clampBox(
            StripBox(
                prefs.getInt(KEY_BRI_STRIP_X, 0),
                prefs.getInt(KEY_BRI_STRIP_Y, 0),
                prefs.getInt(KEY_BRI_STRIP_W, DEFAULT_STRIP_WIDTH),
                prefs.getInt(KEY_BRI_STRIP_H, sh / 2)
            ), sw, sh
        )

        // Topçuk varsayılanları: sağ kenara yakın; parlaklık topu, ses topunun slider'ının altında.
        val size = prefs.getInt(KEY_BUBBLE_SIZE, DEFAULT_BUBBLE_SIZE)
            .coerceIn(MIN_BUBBLE_SIZE, MAX_BUBBLE_SIZE)
        val dropdown = prefs.getInt(KEY_DROPDOWN_HEIGHT, DEFAULT_DROPDOWN_HEIGHT)
            .coerceIn(MIN_DROPDOWN_HEIGHT, MAX_DROPDOWN_HEIGHT)
        val maxX = max(0, sw - size)
        val maxY = max(0, sh - size)
        val defX = sw - size - EDGE_MARGIN_PX
        val defVolY = sh / 6
        val defBriY = defVolY + size + DROPDOWN_GAP_PX + dropdown + STACK_SPACING_PX

        return AppConfig(
            isServiceRunning = prefs.getBoolean(KEY_SERVICE_RUNNING, false),
            activeMode = prefs.getInt(KEY_ACTIVE_MODE, AppMode.INVISIBLE_STRIPS)
                .coerceIn(AppMode.INVISIBLE_STRIPS, AppMode.FLOATING_BUBBLES),
            isCalibrationMode = prefs.getBoolean(KEY_CALIBRATION, false),
            scrollCooldownMs = prefs.getLong(KEY_COOLDOWN, DEFAULT_COOLDOWN_MS)
                .coerceIn(MIN_COOLDOWN_MS, MAX_COOLDOWN_MS),
            invertScroll = prefs.getBoolean(KEY_INVERT, false),
            showSystemVolumeUI = prefs.getBoolean(KEY_SHOW_SYSTEM_UI, true),
            clickThroughBypass = prefs.getBoolean(KEY_CLICK_THROUGH, true),
            stripHoverHint = prefs.getBoolean(KEY_HOVER_HINT, true),
            themeMode = prefs.getInt(KEY_THEME, ThemeMode.SYSTEM)
                .coerceIn(ThemeMode.SYSTEM, ThemeMode.DARK),
            volStripEnabled = prefs.getBoolean(KEY_VOL_STRIP_ENABLED, true),
            briStripEnabled = prefs.getBoolean(KEY_BRI_STRIP_ENABLED, true),
            volStripX = vol.x,
            volStripY = vol.y,
            volStripWidth = vol.width,
            volStripHeight = vol.height,
            briStripX = bri.x,
            briStripY = bri.y,
            briStripWidth = bri.width,
            briStripHeight = bri.height,
            volBubbleEnabled = prefs.getBoolean(KEY_VOL_BUBBLE_ENABLED, true),
            briBubbleEnabled = prefs.getBoolean(KEY_BRI_BUBBLE_ENABLED, true),
            volBubbleX = prefs.getInt(KEY_VOL_BUBBLE_X, defX).coerceIn(0, maxX),
            volBubbleY = prefs.getInt(KEY_VOL_BUBBLE_Y, defVolY).coerceIn(0, maxY),
            briBubbleX = prefs.getInt(KEY_BRI_BUBBLE_X, defX).coerceIn(0, maxX),
            briBubbleY = prefs.getInt(KEY_BRI_BUBBLE_Y, defBriY).coerceIn(0, maxY),
            bubbleSizePx = size,
            dropdownSliderHeightPx = dropdown,
            bubbleIdleAlpha = prefs.getFloat(KEY_IDLE_ALPHA, DEFAULT_IDLE_ALPHA)
                .coerceIn(MIN_IDLE_ALPHA, MAX_IDLE_ALPHA),
            autoCollapseSec = prefs.getInt(KEY_AUTO_COLLAPSE, DEFAULT_AUTO_COLLAPSE_SEC)
                .coerceIn(1, 30)
        )
    }

    // ---------------------------------------------------------------- yazma (genel)

    private fun commit(block: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs.edit()
        editor.block()
        editor.apply()
        refresh()
    }

    fun setServiceRunning(value: Boolean) = commit { putBoolean(KEY_SERVICE_RUNNING, value) }

    fun setActiveMode(value: Int) = commit {
        putInt(KEY_ACTIVE_MODE, value.coerceIn(AppMode.INVISIBLE_STRIPS, AppMode.FLOATING_BUBBLES))
    }

    fun setCalibrationMode(value: Boolean) = commit { putBoolean(KEY_CALIBRATION, value) }

    fun setScrollCooldownMs(value: Long) = commit {
        putLong(KEY_COOLDOWN, value.coerceIn(MIN_COOLDOWN_MS, MAX_COOLDOWN_MS))
    }

    fun setInvertScroll(value: Boolean) = commit { putBoolean(KEY_INVERT, value) }

    fun setShowSystemVolumeUI(value: Boolean) = commit { putBoolean(KEY_SHOW_SYSTEM_UI, value) }

    fun setClickThroughBypass(value: Boolean) = commit { putBoolean(KEY_CLICK_THROUGH, value) }

    fun setStripHoverHint(value: Boolean) = commit { putBoolean(KEY_HOVER_HINT, value) }

    fun setThemeMode(value: Int) = commit {
        putInt(KEY_THEME, value.coerceIn(ThemeMode.SYSTEM, ThemeMode.DARK))
    }

    /** Tüm konum/boyut/davranış ayarlarını varsayılana döndürür; servis durumu ve tema korunur. */
    fun resetToDefaults() {
        val running = prefs.getBoolean(KEY_SERVICE_RUNNING, false)
        val theme = prefs.getInt(KEY_THEME, ThemeMode.SYSTEM)
        commit {
            clear()
            putBoolean(KEY_SERVICE_RUNNING, running)
            putInt(KEY_THEME, theme)
        }
    }

    // ---------------------------------------------------------------- yazma (Mod 1: şeritler)

    fun setStripEnabled(target: OverlayTarget, value: Boolean) = commit {
        putBoolean(
            if (target == OverlayTarget.VOLUME) KEY_VOL_STRIP_ENABLED else KEY_BRI_STRIP_ENABLED,
            value
        )
    }

    /** Verilmeyen değerler mevcut değerini korur. Tüm değerler ekran sınırlarına göre kısıtlanır. */
    fun setStripGeometry(
        target: OverlayTarget,
        x: Int? = null,
        y: Int? = null,
        w: Int? = null,
        h: Int? = null
    ) {
        val (sw, sh) = screenSize()
        val box = readInternal().stripBox(target)
        val clamped = clampBox(
            StripBox(x ?: box.x, y ?: box.y, w ?: box.width, h ?: box.height), sw, sh
        )
        commit { putStripBox(target, clamped) }
    }

    /** Hızlı şablonlar (gerçek ekran boyutuna göre hesaplanır). */
    fun applyStripPreset(preset: StripPreset) {
        val (sw, sh) = screenSize()
        val cfg = readInternal()
        val vw = cfg.volStripWidth
        val bw = cfg.briStripWidth
        val vol: StripBox
        val bri: StripBox
        when (preset) {
            StripPreset.VOLUME_RIGHT_BRIGHTNESS_LEFT -> {
                vol = StripBox(sw - vw, 0, vw, sh)
                bri = StripBox(0, 0, bw, sh)
            }
            StripPreset.BOTH_RIGHT_STACKED -> {
                val top = sh / 2
                vol = StripBox(sw - vw, 0, vw, top)
                bri = StripBox(sw - bw, top, bw, sh - top)
            }
        }
        val cv = clampBox(vol, sw, sh)
        val cb = clampBox(bri, sw, sh)
        commit {
            putStripBox(OverlayTarget.VOLUME, cv)
            putStripBox(OverlayTarget.BRIGHTNESS, cb)
        }
    }

    private fun SharedPreferences.Editor.putStripBox(target: OverlayTarget, box: StripBox) {
        if (target == OverlayTarget.VOLUME) {
            putInt(KEY_VOL_STRIP_X, box.x)
            putInt(KEY_VOL_STRIP_Y, box.y)
            putInt(KEY_VOL_STRIP_W, box.width)
            putInt(KEY_VOL_STRIP_H, box.height)
        } else {
            putInt(KEY_BRI_STRIP_X, box.x)
            putInt(KEY_BRI_STRIP_Y, box.y)
            putInt(KEY_BRI_STRIP_W, box.width)
            putInt(KEY_BRI_STRIP_H, box.height)
        }
    }

    // ---------------------------------------------------------------- yazma (Mod 2: topçuklar)

    fun setBubbleEnabled(target: OverlayTarget, value: Boolean) = commit {
        putBoolean(
            if (target == OverlayTarget.VOLUME) KEY_VOL_BUBBLE_ENABLED else KEY_BRI_BUBBLE_ENABLED,
            value
        )
    }

    /** Verilmeyen eksen mevcut değerini korur. Değerler ekran sınırlarına göre kısıtlanır. */
    fun setBubblePosition(target: OverlayTarget, x: Int? = null, y: Int? = null) {
        val (sw, sh) = screenSize()
        val cfg = readInternal()
        val nx = (x ?: cfg.bubbleX(target)).coerceIn(0, max(0, sw - cfg.bubbleSizePx))
        val ny = (y ?: cfg.bubbleY(target)).coerceIn(0, max(0, sh - cfg.bubbleSizePx))
        commit {
            if (target == OverlayTarget.VOLUME) {
                putInt(KEY_VOL_BUBBLE_X, nx)
                putInt(KEY_VOL_BUBBLE_Y, ny)
            } else {
                putInt(KEY_BRI_BUBBLE_X, nx)
                putInt(KEY_BRI_BUBBLE_Y, ny)
            }
        }
    }

    fun setBubbleSizePx(value: Int) = commit {
        putInt(KEY_BUBBLE_SIZE, value.coerceIn(MIN_BUBBLE_SIZE, MAX_BUBBLE_SIZE))
    }

    fun setDropdownSliderHeightPx(value: Int) = commit {
        putInt(KEY_DROPDOWN_HEIGHT, value.coerceIn(MIN_DROPDOWN_HEIGHT, MAX_DROPDOWN_HEIGHT))
    }

    fun setBubbleIdleAlpha(value: Float) = commit {
        putFloat(KEY_IDLE_ALPHA, value.coerceIn(MIN_IDLE_ALPHA, MAX_IDLE_ALPHA))
    }

    fun setAutoCollapseSec(value: Int) = commit {
        putInt(KEY_AUTO_COLLAPSE, value.coerceIn(1, 30))
    }

    // ---------------------------------------------------------------- ekran döndürme

    /** Ekran döndürüldüğünde kayıtlı tüm konum/boyutları yeni ekran sınırlarına göre kalıcı kısıtlar. */
    fun reclampAll() {
        val cfg = readInternal()
        commit {
            putStripBox(OverlayTarget.VOLUME, cfg.stripBox(OverlayTarget.VOLUME))
            putStripBox(OverlayTarget.BRIGHTNESS, cfg.stripBox(OverlayTarget.BRIGHTNESS))
            putInt(KEY_VOL_BUBBLE_X, cfg.volBubbleX)
            putInt(KEY_VOL_BUBBLE_Y, cfg.volBubbleY)
            putInt(KEY_BRI_BUBBLE_X, cfg.briBubbleX)
            putInt(KEY_BRI_BUBBLE_Y, cfg.briBubbleY)
        }
    }

    // ---------------------------------------------------------------- dinleyici

    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val PREFS_NAME = "unified_overlay_settings"

        private const val KEY_SERVICE_RUNNING = "isServiceRunning"
        private const val KEY_ACTIVE_MODE = "activeMode"
        private const val KEY_CALIBRATION = "isCalibrationMode"
        private const val KEY_COOLDOWN = "scrollCooldownMs"
        private const val KEY_INVERT = "invertScroll"
        private const val KEY_SHOW_SYSTEM_UI = "showSystemVolumeUI"
        private const val KEY_CLICK_THROUGH = "clickThroughBypass"
        private const val KEY_HOVER_HINT = "stripHoverHint"
        private const val KEY_THEME = "themeMode"

        private const val KEY_VOL_STRIP_ENABLED = "volStripEnabled"
        private const val KEY_BRI_STRIP_ENABLED = "briStripEnabled"
        private const val KEY_VOL_STRIP_X = "volStripX"
        private const val KEY_VOL_STRIP_Y = "volStripY"
        private const val KEY_VOL_STRIP_W = "volStripWidth"
        private const val KEY_VOL_STRIP_H = "volStripHeight"
        private const val KEY_BRI_STRIP_X = "briStripX"
        private const val KEY_BRI_STRIP_Y = "briStripY"
        private const val KEY_BRI_STRIP_W = "briStripWidth"
        private const val KEY_BRI_STRIP_H = "briStripHeight"

        private const val KEY_VOL_BUBBLE_ENABLED = "volBubbleEnabled"
        private const val KEY_BRI_BUBBLE_ENABLED = "briBubbleEnabled"
        private const val KEY_VOL_BUBBLE_X = "volBubbleX"
        private const val KEY_VOL_BUBBLE_Y = "volBubbleY"
        private const val KEY_BRI_BUBBLE_X = "briBubbleX"
        private const val KEY_BRI_BUBBLE_Y = "briBubbleY"
        private const val KEY_BUBBLE_SIZE = "bubbleSizePx"
        private const val KEY_DROPDOWN_HEIGHT = "dropdownSliderHeightPx"
        private const val KEY_IDLE_ALPHA = "bubbleIdleAlpha"
        private const val KEY_AUTO_COLLAPSE = "autoCollapseSec"

        const val MIN_COOLDOWN_MS = 10L
        const val MAX_COOLDOWN_MS = 500L
        const val DEFAULT_COOLDOWN_MS = 65L

        const val MIN_STRIP_WIDTH = 20
        const val MAX_STRIP_WIDTH = 1000
        const val DEFAULT_STRIP_WIDTH = 120
        const val MIN_STRIP_HEIGHT = 100

        const val MIN_BUBBLE_SIZE = 60
        const val MAX_BUBBLE_SIZE = 240
        const val DEFAULT_BUBBLE_SIZE = 105

        const val MIN_DROPDOWN_HEIGHT = 200
        const val MAX_DROPDOWN_HEIGHT = 700
        const val DEFAULT_DROPDOWN_HEIGHT = 340

        const val MIN_IDLE_ALPHA = 0.1f
        const val MAX_IDLE_ALPHA = 1.0f
        const val DEFAULT_IDLE_ALPHA = 0.45f

        const val DEFAULT_AUTO_COLLAPSE_SEC = 4

        /** Top ile altında açılan dikey slider arasındaki boşluk (piksel). */
        const val DROPDOWN_GAP_PX = 12

        private const val EDGE_MARGIN_PX = 24
        private const val STACK_SPACING_PX = 40

        @Volatile
        private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
    }
}
