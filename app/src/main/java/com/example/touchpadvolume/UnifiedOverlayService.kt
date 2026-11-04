package com.example.volumescroll

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Birleşik arka plan servisi.
 *
 * MOD 1 (görünmez şeritler): ses ve parlaklık için iki dikdörtgen şerit; normalde tamamen şeffaf,
 *        üzerlerinde touchpad iki parmak kaydırma ile seviye değişir. Şeride tıklayınca 3 sn "hayalet"
 *        (FLAG_NOT_TOUCHABLE) olur ve altındaki uygulamaya tıklanabilir.
 * MOD 2 (yüzen topçuklar): iki yarı saydam top; tıklayınca altından dikey slider açılır.
 * KALİBRASYON: şeritler/toplar görünür ve sürüklenebilir olur; üst-ortadaki onay barından
 *        (uygulama dışındayken bile) "Onayla ve Gizle" ile kapatılır.
 * EK: parlaklık mini OSD'si, dış değişiklik senkronizasyonu, ekran döndürme koruması,
 *     çift tık / sağ tık ile sessize alma.
 */
class UnifiedOverlayService : Service() {

    private lateinit var settings: AppSettings
    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var config: AppConfig

    private val strips = mutableMapOf<OverlayTarget, StripOverlay>()
    private val bubbles = mutableMapOf<OverlayTarget, BubbleOverlay>()
    private var banner: CalibrationBanner? = null
    private var osd: LevelOsd? = null
    private var lastVolumeToastTime = 0L

    private var overlaysActive = false
    private var appliedMode = -1
    private var lastNotifiedCalibration: Boolean? = null
    private var lastNotifiedMode = -1

    private val lastScrollAt = mutableMapOf<OverlayTarget, Long>()
    private val scrollAccumulator = mutableMapOf<OverlayTarget, Float>()
    private var lastToastTime = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    // Ayarlardan herhangi biri değiştiği anda pencereler anında güncellenir (servisi yeniden başlatmadan).
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        applyConfig(settings.read())
    }

    // 5b) Parlaklık sistem panelinden / başka uygulamadan değişirse topçuk % değeri ve slider güncellenir.
    private val brightnessObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            syncBubblesFromSystem()
        }
    }

    // 5a) Fiziksel tuşlar / klavye / sistem paneli ile ses (veya sessize alma) değişirse topçuklar güncellenir.
    private val systemChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            syncBubblesFromSystem()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings.get(this)
        windowManager = getSystemService(WindowManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        config = settings.read()
        createNotificationChannel()
        settings.registerChangeListener(prefListener)

        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false,
            brightnessObserver
        )
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(BRIGHTNESS_FLOAT_SETTING),
            false,
            brightnessObserver
        )

        val filter = IntentFilter().apply {
            addAction(VOLUME_CHANGED_ACTION)
            addAction(STREAM_MUTE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            this,
            systemChangeReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isAlive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() sonrası 5 sn içinde startForeground çağrılmalı.
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_CALIBRATION ->
                settings.setCalibrationMode(!settings.read().isCalibrationMode)
        }

        if (!Settings.canDrawOverlays(this)) {
            shutdown()
            return START_NOT_STICKY
        }

        settings.setServiceRunning(true)
        overlaysActive = true
        applyConfig(settings.read())
        return START_STICKY
    }

    /**
     * 2) Ekran döndürme koruması: yatay/dikey geçişte şerit ve topçuklar ekran dışında kalmasın.
     * Yeni ekran ölçüleri alınır; konum ve boyutlar coerceIn ile yeni sınırlara çekilir ve
     * updateViewLayout ile ekrana uygulanır. Pencere ölçüleri geç güncellenebildiği için 400 ms sonra
     * ikinci kez doğrulanır.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        reclampToScreen()
        mainHandler.postDelayed({ reclampToScreen() }, 400)
    }

    override fun onDestroy() {
        settings.unregisterChangeListener(prefListener)
        contentResolver.unregisterContentObserver(brightnessObserver)
        try {
            unregisterReceiver(systemChangeReceiver)
        } catch (_: IllegalArgumentException) {
            // Zaten kayıtlı değil.
        }
        mainHandler.removeCallbacksAndMessages(null)
        overlaysActive = false
        releaseModeOverlays()
        banner?.hide()
        banner = null
        osd?.hide()
        osd = null
        isAlive = false
        super.onDestroy()
    }

    // ------------------------------------------------------------------ servis yaşam döngüsü

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(settings.read()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        true
    } catch (e: Exception) {
        false
    }

    private fun shutdown() {
        // Servis kapanırken kalibrasyon açık kalmasın (bir sonraki başlatmada şeritler görünmesin).
        settings.setCalibrationMode(false)
        settings.setServiceRunning(false)
        osd?.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun reclampToScreen() {
        val (screenWidth, screenHeight) = settings.screenSize()
        if (screenWidth <= 0 || screenHeight <= 0) return
        settings.reclampAll()
        applyConfig(settings.read())
    }

    private fun releaseModeOverlays() {
        strips.values.forEach { it.release() }
        strips.clear()
        bubbles.values.forEach { it.release() }
        bubbles.clear()
    }

    private fun syncBubblesFromSystem() {
        bubbles.values.forEach { it.syncFromSystem() }
    }

    /** Mod yöneticisi: aktif moda göre doğru pencereleri kurar, eskilerini kaldırır. */
    private fun applyConfig(cfg: AppConfig) {
        config = cfg
        if (!overlaysActive) return

        if (appliedMode != cfg.activeMode) {
            // Mod değişti: eski modun pencerelerini windowManager.removeView ile kaldır.
            releaseModeOverlays()
            appliedMode = cfg.activeMode
        }

        if (cfg.activeMode == AppMode.INVISIBLE_STRIPS) {
            for (target in OverlayTarget.values()) {
                if (cfg.stripEnabled(target)) {
                    strips.getOrPut(target) { StripOverlay(target) }.applyConfig()
                } else {
                    strips.remove(target)?.release()
                }
            }
        } else {
            for (target in OverlayTarget.values()) {
                if (cfg.bubbleEnabled(target)) {
                    bubbles.getOrPut(target) { BubbleOverlay(target) }.applyConfig()
                } else {
                    bubbles.remove(target)?.release()
                }
            }
        }

        if (cfg.isCalibrationMode) {
            if (banner == null) banner = CalibrationBanner()
            banner?.show()
        } else {
            banner?.hide()
        }

        if (lastNotifiedCalibration != cfg.isCalibrationMode || lastNotifiedMode != cfg.activeMode) {
            lastNotifiedCalibration = cfg.isCalibrationMode
            lastNotifiedMode = cfg.activeMode
            notificationManager.notify(NOTIFICATION_ID, buildNotification(cfg))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun showWriteSettingsToast() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastToastTime < 3000) return
        lastToastTime = now
        Toast.makeText(
            this,
            "Parlaklık için 'Sistem ayarlarını değiştir' izni gerekli. Uygulamayı açıp izni verin.",
            Toast.LENGTH_LONG
        ).show()
    }

    // ------------------------------------------------------------------ ses ve parlaklık motoru

    private fun maxVolume(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    private fun currentBrightness(): Int = try {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (e: Settings.SettingNotFoundException) {
        128
    }

    /** Seviye 0..1 aralığında. Sessize alınmış ses akışında Android getStreamVolume() = 0 döndürür. */
    private fun readLevel(target: OverlayTarget): Float =
        if (target == OverlayTarget.VOLUME) {
            val maxVol = maxVolume()
            if (maxVol > 0) audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVol.toFloat() else 0f
        } else {
            currentBrightness() / 255f
        }

    /**
     * Slider sürükleme için yüzdelik (0..1) değişim.
     * Ses: setStreamVolume (doğrulamalı motor). Parlaklık: 0..255 SCREEN_BRIGHTNESS.
     * Sistem ses göstergesi (FLAG_SHOW_UI) hiçbir zaman kullanılmaz; geri bildirimi yalnızca kendi
     * HUD'ımız (topçuk veya mini OSD) verir.
     */
    private fun setLevel(target: OverlayTarget, fraction: Float): Float? {
        val f = fraction.coerceIn(0f, 1f)
        if (target == OverlayTarget.VOLUME) {
            val result = VolumeEngine.setFraction(audioManager, f)
            handleVolumeResult(result)
            return readLevel(OverlayTarget.VOLUME)
        }
        return setBrightnessRaw((f * 255f).roundToInt())
    }

    /**
     * Parlaklığı 0..255 aralığında ayarlar; izin yoksa null döner.
     * Otomatik parlaklık manuele çekilir. Tamamen siyah ekranda kalmamak için alt sınır 1'dir.
     * Şerit modunda başarılı her değişimde mini HUD gösterilir.
     */
    private fun setBrightnessRaw(target: Int): Float? {
        if (!Settings.System.canWrite(this)) {
            showWriteSettingsToast()
            return null
        }
        val mode = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        } catch (e: Settings.SettingNotFoundException) {
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        }
        if (mode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
        }
        val value = target.coerceIn(1, 255)
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        showHud(OverlayTarget.BRIGHTNESS, value / 255f)
        return value / 255f
    }

    /** 9) Medya sesini anında sessize alır / geri açar (sistem göstergesi çıkmaz). */
    private fun toggleMute() {
        val result = VolumeEngine.toggleMute(audioManager)
        if (result.failed) {
            showVolumeFailure(result)
        } else if (config.activeMode == AppMode.INVISIBLE_STRIPS) {
            showOsdText(
                if (result.muted) "\uD83D\uDD07 Ses kapalı"
                else "\uD83D\uDD0A Ses: %${percentOf(result.after, result.max)}",
                COLOR_VOLUME
            )
        }
        syncBubblesFromSystem()
    }

    private fun percentOf(value: Int, max: Int): Int =
        if (max > 0) (value * 100f / max).roundToInt() else 0

    /**
     * Ses değişimi sonucunu kullanıcıya yansıtır:
     * - Başarısızsa: kısa uyarı + OSD (nedeni uygulamadaki "Ses Tanılama" kartı gösterir).
     * - Başarılıysa: şerit modunda mini HUD ile yeni seviye (topçuk modunda topçuğun kendisi gösterir).
     */
    private fun handleVolumeResult(result: VolumeEngine.Result) {
        if (result.failed) {
            showVolumeFailure(result)
        } else {
            showHud(
                OverlayTarget.VOLUME,
                if (result.max > 0) result.after / result.max.toFloat() else 0f
            )
        }
    }

    private fun showVolumeFailure(result: VolumeEngine.Result) {
        showOsdText("\uD83D\uDD0A Ses değiştirilemedi", COLOR_WARNING)
        val now = SystemClock.elapsedRealtime()
        if (now - lastVolumeToastTime < 4000) return
        lastVolumeToastTime = now
        Toast.makeText(
            this,
            "Ses değiştirilemedi (${result.note ?: "bilinmeyen neden"}). " +
                "Uygulamadaki \"Ses Tanılama\" kartından test edin.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showOsdText(text: String, accent: Int) {
        if (osd == null) osd = LevelOsd()
        osd?.show(text, accent)
    }

    /**
     * Görünmez şerit modunda sistem ses/parlaklık göstergesi olmadığı için tek geri bildirim kaynağı
     * bu mini HUD'dır. Topçuk modunda topçuğun kendisi (yüzde etiketi + slider) gösterge olduğundan
     * HUD çıkmaz.
     */
    private fun showHud(target: OverlayTarget, level: Float) {
        if (config.activeMode != AppMode.INVISIBLE_STRIPS) return
        val percent = (level.coerceIn(0f, 1f) * 100f).roundToInt()
        if (target == OverlayTarget.VOLUME) {
            showOsdText("\uD83D\uDD0A Ses: %$percent", COLOR_VOLUME)
        } else {
            showOsdText("\u2600\uFE0F Parlaklık: %$percent", COLOR_BRIGHTNESS)
        }
    }

    /** Dikey kaydırma miktarı: önce AXIS_VSCROLL, yoksa genel AXIS_SCROLL. Pozitif = yukarı. */
    private fun scrollDelta(event: MotionEvent): Float {
        val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        return if (vertical != 0f) vertical else event.getAxisValue(MotionEvent.AXIS_SCROLL)
    }

    /**
     * ACTION_SCROLL olayını akıcı şekilde işler.
     *
     * Touchpad iki parmak kaydırması çok sık ve çok küçük değerlerle gelir; bu yüzden değerler biriktirilir:
     * - Ses: her 0.5 birim kaydırma = 1 kademe (bir tepki süresinde en fazla 3 kademe).
     * - Parlaklık: her 1.0 birim kaydırma = 10/255 (≈%4); küçük hareketler birikip ince ayar yapar.
     * Yön değişince birikim sıfırlanır. Tepki süresi (cooldown) en sık uygulama aralığını sınırlar.
     *
     * @return seviye değiştiyse yeni seviye, aksi halde null.
     */
    private fun processScroll(target: OverlayTarget, event: MotionEvent): Float? {
        val delta = scrollDelta(event)
        if (delta == 0f) return null
        if (target == OverlayTarget.BRIGHTNESS && !Settings.System.canWrite(this)) {
            showWriteSettingsToast()
            return null
        }

        // AXIS_VSCROLL > 0 = yukarı kaydırma. Varsayılan: yukarı = artır.
        val signed = if ((delta > 0f) != config.invertScroll) abs(delta) else -abs(delta)
        var acc = scrollAccumulator[target] ?: 0f
        if (acc != 0f && (acc > 0f) != (signed > 0f)) acc = 0f // yön değişti
        acc = (acc + signed).coerceIn(-MAX_SCROLL_ACCUMULATION, MAX_SCROLL_ACCUMULATION)

        val now = SystemClock.elapsedRealtime()
        val last = lastScrollAt[target] ?: 0L
        if (now - last < config.scrollCooldownMs) {
            scrollAccumulator[target] = acc
            return null
        }

        val level: Float?
        if (target == OverlayTarget.VOLUME) {
            val steps = (abs(acc) / VOLUME_SCROLL_UNITS_PER_STEP).toInt()
                .coerceAtMost(MAX_VOLUME_STEPS_PER_TICK)
            if (steps == 0) {
                scrollAccumulator[target] = acc
                return null
            }
            val raise = acc > 0f
            var lastResult: VolumeEngine.Result? = null
            for (i in 0 until steps) {
                val result = VolumeEngine.step(audioManager, raise)
                lastResult = result
                if (result.failed || result.method == "limit") break
            }
            lastResult?.let { handleVolumeResult(it) }
            acc -= (if (raise) 1f else -1f) * steps * VOLUME_SCROLL_UNITS_PER_STEP
            level = readLevel(OverlayTarget.VOLUME)
        } else {
            val change = (acc * BRIGHTNESS_UNITS_PER_SCROLL).roundToInt()
            if (change == 0) {
                scrollAccumulator[target] = acc
                return null
            }
            level = setBrightnessRaw(currentBrightness() + change)
            acc -= change / BRIGHTNESS_UNITS_PER_SCROLL
        }

        lastScrollAt[target] = now
        scrollAccumulator[target] = acc
        return level
    }

    // ------------------------------------------------------------------ bildirim

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Touchpad Kontrol Servisi",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Ses ve parlaklık kontrol servisinin çalıştığını gösterir"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(cfg: AppConfig): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        val toggleCalibration = PendingIntent.getService(
            this, 1,
            Intent(this, UnifiedOverlayService::class.java).setAction(ACTION_TOGGLE_CALIBRATION),
            flags
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, UnifiedOverlayService::class.java).setAction(ACTION_STOP),
            flags
        )

        val modeName =
            if (cfg.activeMode == AppMode.INVISIBLE_STRIPS) "Görünmez Şerit Modu" else "Yüzen Topçuk Modu"
        val text = if (cfg.isCalibrationMode) {
            "$modeName — Düzenleme açık: kutuları sürükleyin, bitince onaylayın"
        } else {
            "$modeName aktif"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Touchpad ses ve parlaklık kontrolü çalışıyor")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                android.R.drawable.ic_menu_edit,
                if (cfg.isCalibrationMode) "Onayla (Gizle)" else "Ayarla (Göster)",
                toggleCalibration
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Servisi Kapat", stop)
            .build()
    }

    // ================================================================== MOD 1: görünmez şerit

    /**
     * Ses veya parlaklık için tek bir dikdörtgen şerit penceresi = GÖRÜNMEZ DİKEY SLIDER.
     *
     * Girdiler (kalibrasyon kapalıyken):
     * - Touchpad iki parmak kaydırma (ACTION_SCROLL): birikimli, akıcı artır/azalt.
     * - Fare/parmakla tıkla-sürükle: yukarı sürükle = artır, aşağı = azalt (göreli; tıklayınca değer zıplamaz).
     * - Sürüklemeden tek tıklama: click-through (aşağıya bakın). Çift tık / touchpad sağ tık: sessize al (ses).
     * - İmleç şeridin üzerine gelince kısa bir ipucu HUD'ı (ayarlardan kapatılabilir).
     *
     * Click-through bypass: Kaydırmayı alabilmek için şerit dokunulabilir olmak zorundadır; bu yüzden kapladığı
     * bölgedeki tıklamalar normalde altındaki uygulamaya geçmez. Sürüklemeden yapılan tek tıklamada pencereye
     * 3 sn FLAG_NOT_TOUCHABLE eklenir ve şerit "hayalet" olur; süre dolunca bayrak kaldırılır. Bu sürede şeritte
     * kaydırma/sürükleme çalışmaz; tıklamayı başlatan ilk tıklama şerit tarafından alınır. Yanlışlıkla
     * tetiklenmemesi için, son 1.2 sn içinde kaydırma görüldüyse hayalet moda geçilmez.
     * Android 12+ "güvenilmeyen dokunuş" kuralı gereği hayalet modda pencere alpha değeri ≤ 0.8 yapılır.
     */
    private inner class StripOverlay(val target: OverlayTarget) {

        private val ctx: Context = this@UnifiedOverlayService
        private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val accent = if (target == OverlayTarget.VOLUME) COLOR_VOLUME else COLOR_BRIGHTNESS

        private val view = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            setAutoSizeTextTypeUniformWithConfiguration(8, 20, 1, TypedValue.COMPLEX_UNIT_SP)
            setBackgroundColor(Color.TRANSPARENT)
        }

        private val params = WindowManager.LayoutParams(
            AppSettings.DEFAULT_STRIP_WIDTH,
            AppSettings.MIN_STRIP_HEIGHT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            STRIP_BASE_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // (0,0) = ekranın sol üst köşesi; x/y değerleri piksel cinsinden öngörülebilir çalışır.
            gravity = Gravity.TOP or Gravity.START
            // 8) Tam ekran video ve Samsung DeX'te ekranın en ucuna sıfır boşlukla yerleşir.
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        }

        private var attached = false
        private var ghost = false
        private var rightClickActive = false
        private var appliedCalibration: Boolean? = null

        private var lastScrollSeenAt = 0L
        private var lastHintAt = 0L

        // Görünmez slider (tıkla-sürükle) durumu
        private var sliderTracking = false
        private var sliderDragging = false
        private var sliderStartY = 0f
        private var sliderStartLevel = 0f

        // Kalibrasyonda şerit sürükleme
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0

        private val endGhostRunnable = Runnable { endGhost() }

        private val gestureDetector = GestureDetector(
            ctx,
            object : GestureDetector.SimpleOnGestureListener() {
                // Parlaklık şeridi: sürüklemeden bırakılan tek tıklama hayalet modu başlatır.
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (target == OverlayTarget.BRIGHTNESS) startGhostIfEnabled()
                    return false
                }

                // Ses şeridi: çift tıklamayı ayırt edebilmek için hayalet mod kısa bir gecikmeyle başlar.
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (target == OverlayTarget.VOLUME) startGhostIfEnabled()
                    return false
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (target != OverlayTarget.VOLUME) return false
                    toggleMute()
                    return true
                }
            }
        )

        init {
            view.setOnTouchListener { _, event -> handleTouch(event) }
            view.setOnGenericMotionListener { _, event -> handleGenericMotion(event) }
            view.setOnHoverListener { _, event -> handleHover(event) }
        }

        fun applyConfig() {
            val cfg = config
            val box = cfg.stripBox(target)
            params.x = box.x
            params.y = box.y
            params.width = box.width
            params.height = box.height

            if (ghost && (!cfg.clickThroughBypass || cfg.isCalibrationMode)) endGhost()

            if (appliedCalibration != cfg.isCalibrationMode) {
                appliedCalibration = cfg.isCalibrationMode
                sliderTracking = false
                sliderDragging = false
                styleFor(cfg.isCalibrationMode)
            }

            if (attached) {
                pushLayout()
            } else {
                attach()
            }
        }

        private fun styleFor(calibrating: Boolean) {
            if (calibrating) {
                val isVolume = target == OverlayTarget.VOLUME
                view.text = if (isVolume) "SES ŞERİDİ - Sürükle" else "PARLAKLIK ŞERİDİ - Sürükle"
                view.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    // Ses: yarı saydam mavi/turkuaz + sarı çerçeve. Parlaklık: yarı saydam turuncu + beyaz çerçeve.
                    setColor(if (isVolume) Color.argb(120, 0, 188, 212) else Color.argb(120, 255, 152, 0))
                    setStroke(dp(3), if (isVolume) Color.YELLOW else Color.WHITE)
                }
            } else {
                view.text = ""
                view.setBackgroundColor(Color.TRANSPARENT)
            }
        }

        private fun attach() {
            try {
                windowManager.addView(view, params)
                attached = true
            } catch (e: Exception) {
                // Overlay izni kaldırılmış olabilir; servis bir sonraki başlatmada kontrol eder.
            }
        }

        private fun pushLayout() {
            if (!attached) return
            try {
                windowManager.updateViewLayout(view, params)
            } catch (_: IllegalArgumentException) {
                // Pencere henüz eklenmemiş ya da kaldırılmış.
            }
        }

        fun release() {
            mainHandler.removeCallbacks(endGhostRunnable)
            ghost = false
            if (attached) {
                try {
                    windowManager.removeView(view)
                } catch (_: IllegalArgumentException) {
                    // Zaten kaldırılmış.
                }
                attached = false
            }
        }

        // -------------------------------------------------------------- click-through (hayalet) modu

        private fun startGhostIfEnabled() {
            val cfg = config
            if (!attached || !cfg.clickThroughBypass || cfg.isCalibrationMode) return
            // Kaydırma sırasında touchpad'in oluşturabileceği yanlış "tık"lar şeridi kapatmasın.
            if (SystemClock.elapsedRealtime() - lastScrollSeenAt < SCROLL_TAP_GUARD_MS) return
            if (sliderDragging) return
            startGhost()
        }

        private fun startGhost() {
            ghost = true
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            params.alpha = GHOST_WINDOW_ALPHA
            pushLayout()
            mainHandler.removeCallbacks(endGhostRunnable)
            mainHandler.postDelayed(endGhostRunnable, GHOST_DURATION_MS)
        }

        private fun endGhost() {
            mainHandler.removeCallbacks(endGhostRunnable)
            if (!ghost) return
            ghost = false
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            params.alpha = 1f
            pushLayout()
        }

        // -------------------------------------------------------------- dokunma / fare

        private fun handleTouch(event: MotionEvent): Boolean {
            if (config.isCalibrationMode) return dragStrip(event)

            // Sağ tık (touchpad iki parmak dokunuş / alt-sağ tık) → sessize al / aç (yalnızca ses şeridi)
            if (rightClickActive) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    rightClickActive = false
                }
                return true
            }
            if (target == OverlayTarget.VOLUME &&
                event.actionMasked == MotionEvent.ACTION_DOWN &&
                (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
            ) {
                rightClickActive = true
                toggleMute()
                return true
            }

            // Görünmez slider: tıkla ve sürükle (göreli). Yukarı = artır, aşağı = azalt.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sliderTracking = (event.buttonState and MotionEvent.BUTTON_SECONDARY) == 0
                    sliderDragging = false
                    sliderStartY = event.rawY
                    sliderStartLevel = readLevel(target)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (sliderTracking) {
                        val dy = sliderStartY - event.rawY
                        if (!sliderDragging && abs(dy) > touchSlop) sliderDragging = true
                        if (sliderDragging) {
                            // Şerit yüksekliği kadar (en az MIN_DRAG_TRAVEL_PX) sürükleme = %100 değişim.
                            val travel = max(params.height, MIN_DRAG_TRAVEL_PX).toFloat()
                            setLevel(target, sliderStartLevel + dy / travel)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sliderTracking = false
                    sliderDragging = false
                }
            }

            // Tek tık (click-through) ve çift tık (sessize al) algılama
            gestureDetector.onTouchEvent(event)
            return true
        }

        /** Kalibrasyon: sürükle-bırak ile şeridi taşıma. */
        private fun dragStrip(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    settings.setStripGeometry(
                        target,
                        x = startX + (event.rawX - downRawX).roundToInt(),
                        y = startY + (event.rawY - downRawY).roundToInt()
                    )
                }
            }
            return true
        }

        /** İmleç şeride girince kısa bir ipucu (şeridin çalıştığını ve yerini gösterir). */
        private fun handleHover(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER &&
                config.stripHoverHint && !config.isCalibrationMode && !ghost
            ) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastHintAt >= HINT_DEBOUNCE_MS) {
                    lastHintAt = now
                    showOsdText(
                        if (target == OverlayTarget.VOLUME) "\uD83D\uDD0A Ses şeridi · kaydır veya sürükle"
                        else "\u2600\uFE0F Parlaklık şeridi · kaydır veya sürükle",
                        accent
                    )
                }
            }
            return false
        }

        /** Touchpad iki parmak kaydırma (ACTION_SCROLL). Kalibrasyonda yanlışlıkla değişmesin diye yok sayılır. */
        private fun handleGenericMotion(event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_SCROLL) return false
            lastScrollSeenAt = SystemClock.elapsedRealtime()
            if (!config.isCalibrationMode) processScroll(target, event)
            return true
        }
    }

    // ================================================================== MOD 2: yüzen topçuk

    /**
     * Bir topçuk + altında açılan dikey slider içeren tek bir overlay penceresi.
     * Kalibrasyonda: düzenleme çerçevesi, slider önizlemesi açık, tüm pencere sürüklenebilir.
     * Kalibrasyon dışında: konum kilitli; tıklama slider'ı açar/kapatır.
     *
     * 3) Pencere bayrakları her zaman FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH | FLAG_NOT_FOCUSABLE
     * birlikte aktiftir; kapsayıcı View ACTION_OUTSIDE olayında açık slider'ı anında kapatır.
     * 9) Ses topçuğuna çift tıklama veya touchpad sağ tık = sessize al / aç.
     */
    private inner class BubbleOverlay(val target: OverlayTarget) {

        private val ctx: Context = this@UnifiedOverlayService
        private val accent = if (target == OverlayTarget.VOLUME) COLOR_VOLUME else COLOR_BRIGHTNESS
        private val icon = if (target == OverlayTarget.VOLUME) ICON_VOLUME else ICON_BRIGHTNESS

        private val bubbleView = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            includeFontPadding = false
        }

        private val slider = CustomVerticalSliderView(ctx, accent).apply {
            visibility = View.GONE
        }

        private val root = BubbleRootLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                bubbleView,
                LinearLayout.LayoutParams(AppSettings.DEFAULT_BUBBLE_SIZE, AppSettings.DEFAULT_BUBBLE_SIZE)
            )
            addView(
                slider,
                LinearLayout.LayoutParams(
                    AppSettings.DEFAULT_BUBBLE_SIZE,
                    AppSettings.DEFAULT_DROPDOWN_HEIGHT
                ).apply { topMargin = AppSettings.DROPDOWN_GAP_PX }
            )
        }

        private val params = WindowManager.LayoutParams(
            AppSettings.DEFAULT_BUBBLE_SIZE,
            AppSettings.DEFAULT_BUBBLE_SIZE,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            BUBBLE_REQUIRED_FLAGS or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 8) Tam ekran video ve Samsung DeX'te ekranın en ucuna sıfır boşlukla yerleşir.
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        }

        private var attached = false
        private var expanded = false     // kullanıcı topçuğa tıklayıp slider'ı açtı mı
        private var highlighted = false  // topçuk aktif (0.95 opaklık) durumda mı
        private var expandedBeforeTap = false
        private var rightClickActive = false

        private var appliedSize = -1
        private var appliedDropdown = -1
        private var appliedCalibration: Boolean? = null

        // Kalibrasyonda pencere sürükleme
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0

        private val collapseRunnable = Runnable { collapse() }

        private val gestureDetector = GestureDetector(
            ctx,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    expandedBeforeTap = expanded
                    toggle()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (target == OverlayTarget.VOLUME) {
                        // İlk tıklamanın slider'ı açıp/kapatmasını geri al, sonra sessize al / aç.
                        if (expanded != expandedBeforeTap) {
                            if (expandedBeforeTap) expand() else collapse()
                        }
                        toggleMute()
                    } else {
                        // Parlaklıkta çift tık = iki ayrı tıklama gibi davranır.
                        expandedBeforeTap = expanded
                        toggle()
                    }
                    return true
                }
            }
        )

        init {
            bubbleView.setOnTouchListener { _, event -> handleBubbleTouch(event) }

            val scrollListener = View.OnGenericMotionListener { _, event -> handleScroll(event) }
            bubbleView.setOnGenericMotionListener(scrollListener)
            slider.setOnGenericMotionListener(scrollListener)
            root.setOnGenericMotionListener(scrollListener)

            root.setOnTouchListener { _, event -> handleRootTouch(event) }

            slider.onInteraction = { touchActivity() }
            slider.onUserChange = { fraction -> onSliderChanged(fraction) }
        }

        // -------------------------------------------------------------- yapılandırma

        fun applyConfig() {
            val cfg = config

            if (appliedSize != cfg.bubbleSizePx || appliedDropdown != cfg.dropdownSliderHeightPx) {
                appliedSize = cfg.bubbleSizePx
                appliedDropdown = cfg.dropdownSliderHeightPx
                bubbleView.layoutParams = LinearLayout.LayoutParams(cfg.bubbleSizePx, cfg.bubbleSizePx)
                slider.layoutParams =
                    LinearLayout.LayoutParams(cfg.bubbleSizePx, cfg.dropdownSliderHeightPx)
                        .apply { topMargin = AppSettings.DROPDOWN_GAP_PX }
                bubbleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, cfg.bubbleSizePx * 0.2f)
            }

            if (appliedCalibration != cfg.isCalibrationMode) {
                appliedCalibration = cfg.isCalibrationMode
                mainHandler.removeCallbacks(collapseRunnable)
                expanded = false
                highlighted = false
                root.interceptAll = cfg.isCalibrationMode
                styleFor(cfg.isCalibrationMode)
                if (attached) refreshDisplay(readLevel(target))
            }

            syncSliderVisibility()
            updateWindowGeometry()
            updateAlpha()
            attach()
        }

        private fun styleFor(calibrating: Boolean) {
            bubbleView.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(230, 32, 33, 36))
                if (calibrating) {
                    // Belirgin düzenleme çerçevesi (kesikli sarı)
                    setStroke(dp(4), Color.YELLOW, dp(8).toFloat(), dp(5).toFloat())
                } else {
                    setStroke(dp(2), accent)
                }
            }
            root.background = if (calibrating) {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.argb(40, 255, 255, 255))
                    setStroke(dp(2), Color.WHITE, dp(8).toFloat(), dp(4).toFloat())
                }
            } else {
                null
            }
        }

        /** Dikey slider: kullanıcı açtıysa veya kalibrasyon önizlemesi için görünür. */
        private fun isOpen(): Boolean = expanded || config.isCalibrationMode

        private fun syncSliderVisibility() {
            slider.visibility = if (isOpen()) View.VISIBLE else View.GONE
        }

        private fun updateWindowGeometry() {
            val cfg = config
            val screenHeight = settings.screenSize().second
            val size = cfg.bubbleSizePx
            val totalHeight = size + AppSettings.DROPDOWN_GAP_PX + cfg.dropdownSliderHeightPx
            val baseY = cfg.bubbleY(target)

            // 3) ACTION_OUTSIDE için gerekli üç bayrak her güncellemede birlikte aktif tutulur.
            params.flags = params.flags or BUBBLE_REQUIRED_FLAGS
            params.width = size
            params.height = if (isOpen()) totalHeight else size
            params.x = cfg.bubbleX(target)
            // Slider aşağıya sığmıyorsa pencere geçici olarak yukarı kaydırılır (kayıtlı konum değişmez).
            params.y = if (expanded && !cfg.isCalibrationMode) {
                baseY.coerceAtMost(max(0, screenHeight - totalHeight))
            } else {
                baseY
            }

            if (attached) {
                try {
                    windowManager.updateViewLayout(root, params)
                } catch (_: IllegalArgumentException) {
                    // Pencere henüz eklenmemiş ya da kaldırılmış.
                }
            }
        }

        private fun updateAlpha() {
            root.alpha = when {
                config.isCalibrationMode -> 1f
                highlighted || expanded -> ACTIVE_ALPHA
                else -> config.bubbleIdleAlpha
            }
        }

        private fun attach() {
            if (attached) return
            try {
                windowManager.addView(root, params)
                attached = true
                refreshDisplay(readLevel(target))
            } catch (e: Exception) {
                // Overlay izni kaldırılmış olabilir; servis bir sonraki başlatmada kontrol eder.
            }
        }

        fun release() {
            mainHandler.removeCallbacks(collapseRunnable)
            expanded = false
            highlighted = false
            if (attached) {
                try {
                    windowManager.removeView(root)
                } catch (_: IllegalArgumentException) {
                    // Zaten kaldırılmış.
                }
                attached = false
            }
        }

        /** 5) Dış müdahale (tuş, klavye, sistem paneli): % değeri ve açık slider anında güncellenir. */
        fun syncFromSystem() {
            if (attached) refreshDisplay(readLevel(target))
        }

        // -------------------------------------------------------------- aç / kapat

        private fun expand() {
            expanded = true
            highlighted = true
            refreshDisplay(readLevel(target))
            syncSliderVisibility()
            updateWindowGeometry()
            updateAlpha()
            scheduleCollapse()
        }

        fun collapse() {
            mainHandler.removeCallbacks(collapseRunnable)
            expanded = false
            highlighted = false
            syncSliderVisibility()   // View.GONE
            updateWindowGeometry()
            updateAlpha()            // bekleme saydamlığına (idleAlpha) dön
        }

        private fun toggle() {
            if (expanded) {
                collapse()
                return
            }
            if (target == OverlayTarget.BRIGHTNESS && !Settings.System.canWrite(ctx)) {
                showWriteSettingsToast()
                return
            }
            expand()
        }

        private fun scheduleCollapse() {
            mainHandler.removeCallbacks(collapseRunnable)
            mainHandler.postDelayed(collapseRunnable, config.autoCollapseSec * 1000L)
        }

        /** Her dokunma / kaydırmada topçuk aktif görünür ve otomatik kapanma süresi sıfırlanır. */
        private fun touchActivity() {
            if (!highlighted) {
                highlighted = true
                updateAlpha()
            }
            scheduleCollapse()
        }

        // -------------------------------------------------------------- dokunma

        /**
         * Kök pencere dokunmaları:
         * - ACTION_OUTSIDE: pencere dışına tıklandı → slider anında kapanır (kalibrasyonda yok sayılır).
         * - Kalibrasyonda tüm pencere sürüklenebilir (BubbleRootLayout tüm dokunmaları yakalar).
         */
        private fun handleRootTouch(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                if (!config.isCalibrationMode && (expanded || highlighted)) collapse()
                return true
            }
            if (config.isCalibrationMode) return dragWindow(event)
            return false
        }

        private fun dragWindow(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    settings.setBubblePosition(
                        target,
                        x = startX + (event.rawX - downRawX).roundToInt(),
                        y = startY + (event.rawY - downRawY).roundToInt()
                    )
                }
            }
            return true
        }

        /** Kalibrasyon dışında: tıklama = slider aç/kapat, çift tık veya sağ tık = sessize al (ses topçuğu). */
        private fun handleBubbleTouch(event: MotionEvent): Boolean {
            if (config.isCalibrationMode) return true

            if (rightClickActive) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    rightClickActive = false
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                if (target == OverlayTarget.VOLUME && (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0) {
                    rightClickActive = true
                    toggleMute()
                    return true
                }
                if (expanded) scheduleCollapse()
            }
            gestureDetector.onTouchEvent(event)
            return true
        }

        // -------------------------------------------------------------- touchpad kaydırma

        /** İmleç topçuk veya slider üzerindeyken iki parmak kaydırma (ACTION_SCROLL). */
        private fun handleScroll(event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_SCROLL) return false
            if (config.isCalibrationMode) return true

            val level = processScroll(target, event)
            if (level != null) {
                refreshDisplay(level)
                touchActivity()
            }
            return true
        }

        // -------------------------------------------------------------- slider

        private fun onSliderChanged(fraction: Float) {
            val level = setLevel(target, fraction)
            if (level != null) refreshDisplay(level)
        }

        // -------------------------------------------------------------- görünüm

        private fun refreshDisplay(level: Float) {
            bubbleView.text = labelFor(level)
            slider.progress = level
        }

        private fun labelFor(level: Float): CharSequence {
            val text = SpannableString("$icon\n${(level * 100f).roundToInt()}%")
            text.setSpan(RelativeSizeSpan(1.35f), 0, icon.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            return text
        }
    }

    // ================================================================== kalibrasyon onay barı

    /**
     * Kalibrasyon sırasında ekranın üst-ortasında beliren küçük yüzen kutu.
     * Uygulama dışındayken bile "Onayla ve Gizle" ile isCalibrationMode = false yapar.
     */
    private inner class CalibrationBanner {

        private val ctx: Context = this@UnifiedOverlayService

        private val message = TextView(ctx).apply {
            text = "Düzenleme Modu Aktif: Kutuları sürükleyin veya uygulamadan boyutlandırın"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxWidth = dp(320)
        }

        private val confirmButton = TextView(ctx).apply {
            text = "[ \u2713 Onayla ve Gizle ]"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.rgb(46, 125, 50)) // yeşil
            }
            setOnClickListener { settings.setCalibrationMode(false) }
        }

        private val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(235, 32, 33, 36))
                setStroke(dp(2), Color.rgb(255, 167, 38))
            }
            addView(
                message,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(12) }
            )
            addView(
                confirmButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        private val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(32) // kamera çentiğinin ve durum çubuğunun altında kalsın
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        }

        private var attached = false

        fun show() {
            if (attached) return
            try {
                windowManager.addView(container, params)
                attached = true
            } catch (e: Exception) {
                // Overlay izni kaldırılmış olabilir.
            }
        }

        fun hide() {
            if (!attached) return
            try {
                windowManager.removeView(container)
            } catch (_: IllegalArgumentException) {
                // Zaten kaldırılmış.
            }
            attached = false
        }
    }

    // ================================================================== parlaklık mini OSD

    /**
     * Mini HUD (kapsül gösterge): ekranın üst-ortasında beliren küçük, tıklanamayan (FLAG_NOT_TOUCHABLE),
     * yarı saydam kapsül. Görünmez şerit modunda tek geri bildirim kaynağıdır (sistem ses/parlaklık
     * göstergesi gösterilmez). Son değişimden 1 sn sonra otomatik gizlenir.
     * (Android 12+ güvenilmeyen dokunuş kuralı için pencere alpha değeri 0.8'in altındadır.)
     */
    private inner class LevelOsd {

        private val ctx: Context = this@UnifiedOverlayService

        private val capsule = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(40).toFloat() // kapsül
            setColor(Color.argb(215, 32, 33, 36))
            setStroke(dp(2), COLOR_VOLUME)
        }

        private val label = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = capsule
        }

        private val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(32)
            alpha = OSD_WINDOW_ALPHA
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        }

        private var attached = false
        private val hideRunnable = Runnable { hide() }

        fun show(text: String, accent: Int) {
            label.text = text
            capsule.setStroke(dp(2), accent)
            if (!attached) {
                try {
                    windowManager.addView(label, params)
                    attached = true
                } catch (e: Exception) {
                    return
                }
            }
            mainHandler.removeCallbacks(hideRunnable)
            mainHandler.postDelayed(hideRunnable, OSD_HIDE_DELAY_MS)
        }

        fun hide() {
            mainHandler.removeCallbacks(hideRunnable)
            if (!attached) return
            try {
                windowManager.removeView(label)
            } catch (_: IllegalArgumentException) {
                // Zaten kaldırılmış.
            }
            attached = false
        }
    }

    companion object {
        const val ACTION_STOP = "com.example.volumescroll.action.STOP"
        const val ACTION_TOGGLE_CALIBRATION = "com.example.volumescroll.action.TOGGLE_CALIBRATION"

        private const val CHANNEL_ID = "unified_overlay_channel"
        private const val NOTIFICATION_ID = 4103
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val STREAM_MUTE_CHANGED_ACTION = "android.media.STREAM_MUTE_CHANGED_ACTION"
        private const val BRIGHTNESS_FLOAT_SETTING = "screen_brightness_float"

        private const val ICON_VOLUME = "\uD83D\uDD0A"       // 🔊
        private const val ICON_BRIGHTNESS = "\u2600\uFE0F"   // ☀️

        private val COLOR_VOLUME = Color.rgb(0, 188, 212)      // Mavi / turkuaz
        private val COLOR_BRIGHTNESS = Color.rgb(255, 193, 7)  // Sarı / kehribar
        private val COLOR_WARNING = Color.rgb(255, 167, 38)    // Uyarı (turuncu)

        private const val ACTIVE_ALPHA = 0.95f
        private const val BRIGHTNESS_UNITS_PER_SCROLL = 10f // 1.0 birim kaydırma = 10/255 (≈%4)
        private const val VOLUME_SCROLL_UNITS_PER_STEP = 0.5f // 0.5 birim kaydırma = 1 ses kademesi
        private const val MAX_VOLUME_STEPS_PER_TICK = 3
        private const val MAX_SCROLL_ACCUMULATION = 3f
        private const val MIN_DRAG_TRAVEL_PX = 480 // sürükleyerek %100 değişim için en az mesafe
        private const val SCROLL_TAP_GUARD_MS = 1200L
        private const val HINT_DEBOUNCE_MS = 2500L

        private const val GHOST_DURATION_MS = 3000L
        private const val GHOST_WINDOW_ALPHA = 0.5f
        private const val OSD_HIDE_DELAY_MS = 1000L
        private const val OSD_WINDOW_ALPHA = 0.75f

        private const val STRIP_BASE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        private const val BUBBLE_REQUIRED_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        /** Servis bu süreçte gerçekten çalışıyor mu? (Arayüzün bayat durumu düzeltmesi için) */
        @Volatile
        var isAlive: Boolean = false
            private set

        fun start(context: Context) {
            AppSettings.get(context).setServiceRunning(true)
            ContextCompat.startForegroundService(
                context,
                Intent(context, UnifiedOverlayService::class.java)
            )
        }

        fun stop(context: Context) {
            val settings = AppSettings.get(context)
            settings.setCalibrationMode(false)
            settings.setServiceRunning(false)
            context.stopService(Intent(context, UnifiedOverlayService::class.java))
        }
    }
}

/**
 * Kalibrasyonda tüm dokunmaları yakalayıp (alt görünümlere vermeden) pencerenin tamamını
 * sürüklenebilir yapan kök yerleşim.
 */
class BubbleRootLayout(context: Context) : LinearLayout(context) {
    var interceptAll: Boolean = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        interceptAll || super.onInterceptTouchEvent(ev)
}

/**
 * Canvas ile çizilen dikey slider: yuvarlatılmış kanal, doluluk çubuğu ve tutamaç.
 * Fare/parmak sürükleme (ACTION_DOWN / ACTION_MOVE) ile kontrol edilir; touchpad kaydırması
 * (ACTION_SCROLL) üst katmanda [UnifiedOverlayService] tarafından dinlenir.
 */
class CustomVerticalSliderView(
    context: Context,
    private val accentColor: Int
) : View(context) {

    /** 0f (en alt) .. 1f (en üst) */
    var progress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped != field) {
                field = clamped
                invalidate()
            }
        }

    /** Kullanıcı slider'a dokunup sürüklediğinde yeni 0..1 değeriyle çağrılır. */
    var onUserChange: ((Float) -> Unit)? = null

    /** Her dokunmada (otomatik kapanma süresini sıfırlamak için) çağrılır. */
    var onInteraction: (() -> Unit)? = null

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(179, 32, 33, 36)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(51, 255, 255, 255)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val thumbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
    }
    private val rect = RectF()

    private fun trackWidth(): Float = width * 0.38f
    private fun thumbRadius(): Float = width * 0.26f
    private fun trackTop(): Float = thumbRadius() + 6f
    private fun trackBottom(): Float = height - thumbRadius() - 6f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Arka plan kapsülü
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, w / 2f, w / 2f, backgroundPaint)

        val trackW = trackWidth()
        val left = (w - trackW) / 2f
        val right = left + trackW
        val top = trackTop()
        val bottom = trackBottom()
        val radius = trackW / 2f

        // Boş kanal
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)

        // Doluluk çubuğu (alttan yukarı)
        val thumbY = bottom - progress * (bottom - top)
        rect.set(left, thumbY, right, bottom)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        // Tutamaç
        val thumbR = thumbRadius()
        thumbRingPaint.strokeWidth = max(3f, w * 0.03f)
        canvas.drawCircle(w / 2f, thumbY, thumbR, thumbPaint)
        canvas.drawCircle(w / 2f, thumbY, thumbR, thumbRingPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.y)
            }
            MotionEvent.ACTION_MOVE -> updateFromTouch(event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun updateFromTouch(y: Float) {
        val top = trackTop()
        val bottom = trackBottom()
        if (bottom <= top) return
        val fraction = 1f - ((y - top) / (bottom - top)).coerceIn(0f, 1f)
        onInteraction?.invoke()
        progress = fraction
        onUserChange?.invoke(fraction)
    }
}

/**
 * 6) Tablet açılışında (veya uygulama güncellendiğinde) kullanıcı servisi açık bırakmışsa ve overlay izni
 * hâlâ verilmişse servisi otomatik başlatır.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val settings = AppSettings.get(context)
        if (settings.read().isServiceRunning && Settings.canDrawOverlays(context)) {
            // Yeniden başlatma sırasında kalmış olabilecek kalibrasyon durumunu sıfırla.
            settings.setCalibrationMode(false)
            try {
                UnifiedOverlayService.start(context)
            } catch (_: Exception) {
                settings.setServiceRunning(false)
            }
        }
    }
}

/**
 * Medya sesini değiştiren ortak, DOĞRULAMALI motor (servis ve uygulamadaki "Ses Tanılama" kullanır).
 *
 * Her değişiklikten sonra getStreamVolume() ile sonuç doğrulanır; ses değişmediyse sıradaki yedek yönteme
 * geçilir. Hiçbiri işe yaramazsa neden bilgisiyle birlikte "failed" döner (sessizce yutulmaz).
 */
object VolumeEngine {

    private const val STREAM = AudioManager.STREAM_MUSIC

    /** Sessize almadan önceki seviye (yazılımsal sessize alma yedeği için). */
    @Volatile
    private var volumeBeforeSoftMute = -1

    data class Result(
        val before: Int,
        val after: Int,
        val max: Int,
        val method: String,
        val failed: Boolean,
        val muted: Boolean,
        val note: String? = null
    ) {
        fun summary(): String = when {
            failed -> "Başarısız: ses değişmedi (${note ?: "bilinmeyen neden"})"
            method == "limit" -> "Ses zaten sınırda: $after/$max"
            muted -> "Sessize alındı"
            else -> "Başarılı ($method): $before → $after / $max"
        }
    }

    /** Sistem ses göstergesi (FLAG_SHOW_UI) bilerek hiç kullanılmaz: bayrak her zaman 0'dır. */
    private const val NO_UI_FLAGS = 0

    /** Touchpad kaydırması gibi tek adımlık değişim (+1 / −1 kademe). */
    fun step(am: AudioManager, raise: Boolean): Result {
        val flags = NO_UI_FLAGS
        val max = am.getStreamMaxVolume(STREAM)
        val min = am.getStreamMinVolume(STREAM)
        val before = am.getStreamVolume(STREAM)
        val wasMuted = am.isStreamMute(STREAM)
        val target = (before + if (raise) 1 else -1).coerceIn(min, max)

        if (target == before && !wasMuted) {
            return Result(before, before, max, "limit", failed = false, muted = false)
        }
        if (am.isVolumeFixed) {
            return Result(
                before, before, max, "fixed", failed = true, muted = wasMuted,
                note = "cihaz sabit ses politikası kullanıyor"
            )
        }

        val direction = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        var note: String? = null

        // 1) adjustStreamVolume
        try {
            am.adjustStreamVolume(STREAM, direction, flags)
        } catch (e: SecurityException) {
            note = "izin hatası: ${e.message}"
        }
        var after = am.getStreamVolume(STREAM)
        if (after != before) {
            return Result(before, after, max, "adjustStreamVolume", failed = false, muted = am.isStreamMute(STREAM))
        }

        // 2) setStreamVolume (açık hedef değerle)
        try {
            am.setStreamVolume(STREAM, target, flags)
        } catch (e: SecurityException) {
            note = "izin hatası: ${e.message}"
        }
        after = am.getStreamVolume(STREAM)
        if (after != before) {
            return Result(before, after, max, "setStreamVolume", failed = false, muted = am.isStreamMute(STREAM))
        }

        // 3) adjustSuggestedStreamVolume (en alttaki yedek)
        try {
            am.adjustSuggestedStreamVolume(direction, STREAM, flags)
        } catch (e: SecurityException) {
            note = "izin hatası: ${e.message}"
        }
        after = am.getStreamVolume(STREAM)
        val changed = after != before
        return Result(
            before, after, max,
            if (changed) "adjustSuggestedStreamVolume" else "none",
            failed = !changed,
            muted = am.isStreamMute(STREAM),
            note = if (changed) null else (note ?: "üç yöntem de sesi değiştiremedi")
        )
    }

    /** Slider sürükleme gibi yüzdelik (0..1) değişim. */
    fun setFraction(am: AudioManager, fraction: Float): Result {
        val flags = NO_UI_FLAGS
        val max = am.getStreamMaxVolume(STREAM)
        val min = am.getStreamMinVolume(STREAM)
        val before = am.getStreamVolume(STREAM)
        val wasMuted = am.isStreamMute(STREAM)
        val target = (fraction.coerceIn(0f, 1f) * max).roundToInt().coerceIn(min, max)

        if (target == before && !wasMuted) {
            return Result(before, before, max, "limit", failed = false, muted = false)
        }
        if (am.isVolumeFixed) {
            return Result(
                before, before, max, "fixed", failed = true, muted = wasMuted,
                note = "cihaz sabit ses politikası kullanıyor"
            )
        }

        var note: String? = null
        try {
            am.setStreamVolume(STREAM, target, flags)
        } catch (e: SecurityException) {
            note = "izin hatası: ${e.message}"
        }
        if (am.getStreamVolume(STREAM) == target) {
            return Result(before, target, max, "setStreamVolume", failed = false, muted = am.isStreamMute(STREAM))
        }

        // Yedek: adjustStreamVolume ile hedefe adım adım yürü.
        var guard = 0
        while (am.getStreamVolume(STREAM) != target && guard < max + 2) {
            val current = am.getStreamVolume(STREAM)
            val direction = if (target > current) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            try {
                am.adjustStreamVolume(STREAM, direction, 0)
            } catch (e: SecurityException) {
                note = "izin hatası: ${e.message}"
                break
            }
            if (am.getStreamVolume(STREAM) == current) break
            guard++
        }
        val after = am.getStreamVolume(STREAM)
        val changed = after != before
        return Result(
            before, after, max,
            if (changed) "adjustStreamVolume (adım adım)" else "none",
            failed = !changed,
            muted = am.isStreamMute(STREAM),
            note = if (changed) null else (note ?: "ses hedef değere getirilemedi")
        )
    }

    /** Sessize al / geri aç. ADJUST_TOGGLE_MUTE işe yaramazsa sesi 0'a çekip eski seviyeye döndürür. */
    fun toggleMute(am: AudioManager): Result {
        val flags = NO_UI_FLAGS
        val max = am.getStreamMaxVolume(STREAM)
        val before = am.getStreamVolume(STREAM)
        val wasMuted = am.isStreamMute(STREAM)
        var note: String? = null

        try {
            am.adjustStreamVolume(STREAM, AudioManager.ADJUST_TOGGLE_MUTE, flags)
        } catch (e: SecurityException) {
            note = "izin hatası: ${e.message}"
        }
        if (am.isStreamMute(STREAM) != wasMuted) {
            return Result(
                before, am.getStreamVolume(STREAM), max, "ADJUST_TOGGLE_MUTE",
                failed = false, muted = am.isStreamMute(STREAM)
            )
        }

        // Yedek: yazılımsal sessize alma
        try {
            if (!wasMuted && before > 0) {
                volumeBeforeSoftMute = before
                am.setStreamVolume(STREAM, 0, flags)
            } else {
                val restore = if (volumeBeforeSoftMute > 0) volumeBeforeSoftMute else max / 2
                am.setStreamVolume(STREAM, restore.coerceIn(1, max), flags)
            }
        } catch (e: SecurityException) {
            note = "izin hatası: ${e.message}"
        }
        val after = am.getStreamVolume(STREAM)
        val changed = after != before
        return Result(
            before, after, max, "setStreamVolume (yazılımsal)",
            failed = !changed,
            muted = after == 0,
            note = if (changed) null else (note ?: "sessize alma uygulanamadı")
        )
    }

    /** Tanılama kartında gösterilen durum raporu. */
    fun describe(am: AudioManager, context: Context): String {
        val max = am.getStreamMaxVolume(STREAM)
        val current = am.getStreamVolume(STREAM)
        val muted = am.isStreamMute(STREAM)
        val permission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.MODIFY_AUDIO_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { deviceName(it.type) }
            .distinct()
            .joinToString(", ")
            .ifEmpty { "bilinmiyor" }

        return "Medya sesi: $current/$max" + (if (muted) " (sessiz)" else "") +
            "\nSabit ses politikası: " + (if (am.isVolumeFixed) "EVET (ses değiştirilemez)" else "hayır") +
            "\nMODIFY_AUDIO_SETTINGS izni: " + (if (permission) "verildi" else "YOK") +
            "\nÇıkış cihazları: $outputs"
    }

    private fun deviceName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "hoparlör"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "kulaklık"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> "diğer"
    }
}
