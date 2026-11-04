package com.example.volumescroll

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var settings: AppSettings

    private var overlayGranted by mutableStateOf(false)
    private var writeSettingsGranted by mutableStateOf(false)
    private var batteryIgnored by mutableStateOf(false)
    private var notificationGranted by mutableStateOf(false)
    private var notificationAsked = false

    // Ayar sayfalarından dönüşte izin durumları otomatik güncellenir.
    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissions()
        }

    private val writeSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissions()
        }

    private val batteryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissions()
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissions()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings.get(this)
        refreshPermissions()
        restoreServiceIfNeeded()

        setContent {
            val cfg by settings.config.collectAsStateWithLifecycle()
            AppTheme(themeMode = cfg.themeMode) {
                ControlPanelScreen(
                    cfg = cfg,
                    settings = settings,
                    permissions = PermissionState(
                        overlay = overlayGranted,
                        writeSettings = writeSettingsGranted,
                        battery = batteryIgnored,
                        notification = notificationGranted
                    ),
                    actions = PermissionActions(
                        onOverlay = { requestOverlayPermission() },
                        onWriteSettings = { requestWriteSettingsPermission() },
                        onBattery = { requestBatteryOptimization() },
                        onNotification = { requestNotificationPermission() },
                        onToggleService = { enable -> setServiceEnabled(enable) }
                    )
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        overlayGranted = Settings.canDrawOverlays(this)
        writeSettingsGranted = Settings.System.canWrite(this)
        batteryIgnored = getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)
        notificationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Kayıtlı durum "çalışıyor" ama servis gerçekte ölmüşse yeniden başlatır / durumu düzeltir. */
    private fun restoreServiceIfNeeded() {
        if (settings.read().isServiceRunning && !UnifiedOverlayService.isAlive) {
            if (overlayGranted) UnifiedOverlayService.start(this)
            else settings.setServiceRunning(false)
        } else if (!UnifiedOverlayService.isAlive && settings.read().isCalibrationMode) {
            settings.setCalibrationMode(false)
        }
    }

    private fun requestOverlayPermission() {
        overlayLauncher.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun requestWriteSettingsPermission() {
        writeSettingsLauncher.launch(
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
        )
    }

    /** 7) Samsung One UI pil optimizasyonundan muaf tutma isteği. */
    private fun requestBatteryOptimization() {
        try {
            batteryLauncher.launch(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: ActivityNotFoundException) {
            batteryLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun requestNotificationPermission() {
        if (!notificationAsked) {
            notificationAsked = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Sistem diyaloğu artık gösterilmeyebilir: uygulama bildirim ayarlarını aç.
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
    }

    private fun setServiceEnabled(enable: Boolean) {
        if (!enable) {
            UnifiedOverlayService.stop(this)
            return
        }
        when {
            !overlayGranted -> requestOverlayPermission()
            !writeSettingsGranted -> requestWriteSettingsPermission()
            else -> UnifiedOverlayService.start(this)
        }
    }
}

// ============================================================================ durum modelleri

private data class PermissionState(
    val overlay: Boolean,
    val writeSettings: Boolean,
    val battery: Boolean,
    val notification: Boolean
) {
    /** Servisin çalışması için zorunlu iki izin. */
    val canStart: Boolean get() = overlay && writeSettings
    val allGranted: Boolean get() = overlay && writeSettings && battery && notification
}

private class PermissionActions(
    val onOverlay: () -> Unit,
    val onWriteSettings: () -> Unit,
    val onBattery: () -> Unit,
    val onNotification: () -> Unit,
    val onToggleService: (Boolean) -> Unit
)

private class SetupItem(
    val label: String,
    val hint: String,
    val granted: Boolean,
    val required: Boolean,
    val buttonText: String,
    val onClick: () -> Unit
)

// ============================================================================ tema (aydınlık / karanlık)

private val VolumeAccent = Color(0xFF00BCD4)
private val BrightnessAccent = Color(0xFFFFC107)
private val WarningOrange = Color(0xFFFFA726)
private val ConfirmGreen = Color(0xFF2E7D32)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DD0E1),
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF0B3E45),
    onPrimaryContainer = Color(0xFFB2EBF2),
    secondary = Color(0xFFFFCA28),
    onSecondary = Color(0xFF3B2F00),
    secondaryContainer = Color(0xFF4A3B00),
    onSecondaryContainer = Color(0xFFFFE082),
    tertiary = Color(0xFF66BB6A),
    onTertiary = Color(0xFF00390F),
    background = Color(0xFF0E1114),
    onBackground = Color(0xFFE3E6EA),
    surface = Color(0xFF171B20),
    onSurface = Color(0xFFE3E6EA),
    surfaceVariant = Color(0xFF232830),
    onSurfaceVariant = Color(0xFFA9B0BA),
    outline = Color(0xFF59616C),
    outlineVariant = Color(0xFF2B313A),
    error = Color(0xFFFF6E6E),
    onError = Color(0xFF3A0000)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00838F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2EBF2),
    onPrimaryContainer = Color(0xFF00363D),
    secondary = Color(0xFF8D6E00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFECB3),
    onSecondaryContainer = Color(0xFF3B2F00),
    tertiary = Color(0xFF2E7D32),
    onTertiary = Color.White,
    background = Color(0xFFF4F7F9),
    onBackground = Color(0xFF14181C),
    surface = Color.White,
    onSurface = Color(0xFF14181C),
    surfaceVariant = Color(0xFFE4EAEE),
    onSurfaceVariant = Color(0xFF47525C),
    outline = Color(0xFF7A8691),
    outlineVariant = Color(0xFFD5DCE2),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp)
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
private fun AppTheme(themeMode: Int, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> systemDark
    }

    // Uygulama içi tema seçimine göre durum/gezinme çubuğu simgelerini ayarla (kenardan kenara).
    val activity = LocalContext.current as ComponentActivity
    DisposableEffect(dark) {
        val style = if (dark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose { }
    }

    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}

private fun accentOf(target: OverlayTarget): Color =
    if (target == OverlayTarget.VOLUME) VolumeAccent else BrightnessAccent

@Composable
private fun textAccentOf(target: OverlayTarget): Color =
    if (target == OverlayTarget.VOLUME) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.secondary

// ============================================================================ ana ekran

@Composable
private fun ControlPanelScreen(
    cfg: AppConfig,
    settings: AppSettings,
    permissions: PermissionState,
    actions: PermissionActions
) {
    val screenConfig = LocalConfiguration.current
    val screen = remember(screenConfig) { settings.screenSize() }
    val screenW = screen.first
    val screenH = screen.second
    val haptic = LocalHapticFeedback.current
    val stripMode = cfg.activeMode == AppMode.INVISIBLE_STRIPS

    val leftColumn: @Composable () -> Unit = {
        HeroCard(
            cfg = cfg,
            canStart = permissions.canStart,
            onToggleService = { enable ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                actions.onToggleService(enable)
            }
        )

        AnimatedVisibility(
            visible = cfg.isCalibrationMode,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            CalibrationNotice(
                stripMode = stripMode,
                onConfirm = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    settings.setCalibrationMode(false)
                }
            )
        }

        AnimatedVisibility(
            visible = !permissions.allGranted,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            SetupCard(
                items = listOf(
                    SetupItem(
                        label = "Diğer uygulamaların üzerinde gösterim",
                        hint = "Şeritlerin / topçukların ekranda çalışması için zorunlu.",
                        granted = permissions.overlay,
                        required = true,
                        buttonText = "1. İzin: Diğer Uygulamaların Üzerinde Göster",
                        onClick = actions.onOverlay
                    ),
                    SetupItem(
                        label = "Sistem ayarlarını değiştirme",
                        hint = "Parlaklığın değiştirilebilmesi için zorunlu.",
                        granted = permissions.writeSettings,
                        required = true,
                        buttonText = "2. İzin: Sistem Ayarlarını Değiştir (Parlaklık İçin)",
                        onClick = actions.onWriteSettings
                    ),
                    SetupItem(
                        label = "Pil kısıtlaması",
                        hint = "Samsung'un servisi uykuya almasını önler (önerilir).",
                        granted = permissions.battery,
                        required = false,
                        buttonText = "3. İzin: Pil Kısıtlamasını Kaldır (Uykuya Alınmayı Önle)",
                        onClick = actions.onBattery
                    ),
                    SetupItem(
                        label = "Bildirim izni",
                        hint = "Bildirimdeki \"Ayarla / Onayla\" ve \"Servisi Kapat\" düğmeleri için (önerilir).",
                        granted = permissions.notification,
                        required = false,
                        buttonText = "Bildirim İznini Ver",
                        onClick = actions.onNotification
                    )
                )
            )
        }

        LayoutCard(cfg = cfg, screenW = screenW, screenH = screenH, settings = settings)
        TouchpadTestCard(cfg = cfg)
    }

    val rightColumn: @Composable () -> Unit = {
        AnimatedContent(
            targetState = cfg.activeMode,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
            label = "modeSettings"
        ) { mode ->
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (mode == AppMode.INVISIBLE_STRIPS) {
                    StripSettings(cfg = cfg, screenW = screenW, screenH = screenH, settings = settings)
                } else {
                    BubbleSettings(cfg = cfg, screenW = screenW, screenH = screenH, settings = settings)
                }
            }
        }
        BehaviorCard(cfg = cfg, settings = settings)
        VolumeDiagnosticsCard()
        AppearanceCard(cfg = cfg, settings = settings)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            AnimatedVisibility(
                visible = cfg.isServiceRunning,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                CalibrationFab(
                    calibrating = cfg.isCalibrationMode,
                    stripMode = stripMode,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        settings.setCalibrationMode(!cfg.isCalibrationMode)
                    }
                )
            }
        }
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (maxWidth >= 900.dp) {
                // Tablet / yatay ekran: iki bölmeli yerleşim
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        leftColumn()
                        Spacer(Modifier.height(96.dp))
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        rightColumn()
                        Spacer(Modifier.height(96.dp))
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    leftColumn()
                    rightColumn()
                    Spacer(Modifier.height(96.dp))
                }
            }
        }
    }
}

// ============================================================================ ortak kart iskeleti

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun Pill(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(50), color = container, contentColor = content) {
        Text(
            text,
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

// ============================================================================ hero: durum + ana şalter

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroCard(cfg: AppConfig, canStart: Boolean, onToggleService: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val running = cfg.isServiceRunning

    val gradientStart by animateColorAsState(
        if (running) scheme.primaryContainer else scheme.surfaceVariant,
        label = "heroGradient"
    )
    val pulse by rememberInfiniteTransition(label = "statusPulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "statusPulseValue"
    )

    Card(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(gradientStart, scheme.surface)),
                MaterialTheme.shapes.extraLarge
            ),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, scheme.outlineVariant)
    ) {
        Column(
            Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Touchpad Ses & Parlaklık", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Klavyeli kılıf touchpad'i ve fare ile hızlı kontrol",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant
                    )
                }
                Box(
                    Modifier
                        .size(14.dp)
                        .background(
                            (if (running) scheme.tertiary else scheme.outline)
                                .copy(alpha = if (running) pulse else 1f),
                            CircleShape
                        )
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Pill(
                    if (running) "Çalışıyor" else "Durduruldu",
                    if (running) scheme.tertiary.copy(alpha = 0.18f) else scheme.surfaceVariant,
                    if (running) scheme.tertiary else scheme.onSurfaceVariant
                )
                Pill(
                    if (cfg.activeMode == AppMode.INVISIBLE_STRIPS) "\uD83D\uDCCF Görünmez Şerit"
                    else "\uD83E\uDEE7 Yüzen Topçuk",
                    scheme.primaryContainer,
                    scheme.onPrimaryContainer
                )
                if (cfg.isCalibrationMode) {
                    Pill("\uD83D\uDEE0\uFE0F Düzenleme açık", WarningOrange.copy(alpha = 0.25f), WarningOrange)
                }
            }

            Button(
                onClick = { onToggleService(!running) },
                enabled = canStart || running,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) scheme.error else scheme.primary,
                    contentColor = if (running) scheme.onError else scheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    if (running) "Servisi Durdur" else "Servisi Başlat",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            if (!canStart && !running) {
                Text(
                    "Başlatmak için aşağıdaki iki zorunlu izni verin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningOrange
                )
            }
        }
    }
}

// ============================================================================ kurulum kontrol listesi

@Composable
private fun SetupCard(items: List<SetupItem>) {
    val done = items.count { it.granted }
    val progress by animateFloatAsState(done / items.size.toFloat(), label = "setupProgress")

    SectionCard(
        title = "Kurulum  $done/${items.size}",
        subtitle = "Zorunlu izinler olmadan servis başlamaz; önerilenler kesintisiz çalışma içindir."
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        items.forEach { SetupRow(it) }
    }
}

@Composable
private fun SetupRow(item: SetupItem) {
    val scheme = MaterialTheme.colorScheme
    val statusColor = when {
        item.granted -> scheme.tertiary
        item.required -> scheme.error
        else -> WarningOrange
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .padding(top = 3.dp)
                    .size(18.dp)
                    .background(statusColor.copy(alpha = if (item.granted) 1f else 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (item.granted) "\u2713" else "!",
                    color = if (item.granted) scheme.onTertiary else statusColor,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (item.granted) "Verildi" else if (item.required) "Zorunlu" else "Önerilir",
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor
                    )
                }
                Text(
                    item.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
        if (!item.granted) {
            FilledTonalButton(onClick = item.onClick, modifier = Modifier.fillMaxWidth()) {
                Text(item.buttonText)
            }
        }
    }
}

// ============================================================================ kalibrasyon bileşenleri

@Composable
private fun CalibrationNotice(stripMode: Boolean, onConfirm: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = WarningOrange.copy(alpha = 0.16f)),
        border = BorderStroke(2.dp, WarningOrange)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "\uD83D\uDEE0\uFE0F Düzenleme Modu Aktif",
                style = MaterialTheme.typography.titleMedium,
                color = WarningOrange
            )
            Text(
                "Şu an " + (if (stripMode) "şeritler" else "toplar") +
                    " ekranda görünür durumda. Ekranda sürükleyebilir veya aşağıdaki barlarla " +
                    "boyutlandırabilirsiniz. Ekranın üstündeki yüzen kutudan da onaylayabilirsiniz.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = ConfirmGreen, contentColor = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            ) {
                Text(
                    if (stripMode) "\u2705 Ayarlamayı Onayla ve Görünmez Yap"
                    else "\u2705 Ayarlamayı Onayla ve Kilitle",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

/** En sık yapılan işlem: her zaman erişilebilir, durum değişince renk ve etiket animasyonlu geçer. */
@Composable
private fun CalibrationFab(calibrating: Boolean, stripMode: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (calibrating) ConfirmGreen else scheme.primaryContainer,
        label = "fabContainer"
    )
    val pulse by rememberInfiniteTransition(label = "fabPulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            tween(850, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "fabPulseValue"
    )

    ExtendedFloatingActionButton(
        onClick = onClick,
        containerColor = container,
        contentColor = if (calibrating) Color.White else scheme.onPrimaryContainer,
        modifier = Modifier.scale(if (calibrating) pulse else 1f)
    ) {
        AnimatedContent(targetState = calibrating, label = "fabLabel") { active ->
            Text(
                if (active) {
                    if (stripMode) "\u2705 Onayla ve Görünmez Yap" else "\u2705 Onayla ve Kilitle"
                } else {
                    "\uD83D\uDEE0\uFE0F Konum ve Boyut Ayarla"
                },
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

// ============================================================================ yerleşim: mod seçici + canlı önizleme

@Composable
private fun LayoutCard(cfg: AppConfig, screenW: Int, screenH: Int, settings: AppSettings) {
    SectionCard(
        title = "Mod ve Yerleşim",
        subtitle = "Önizlemedeki şekilleri sürükleyerek konumlandırabilirsiniz."
    ) {
        ModeSelector(activeMode = cfg.activeMode, onSelect = { settings.setActiveMode(it) })
        Text(
            if (cfg.activeMode == AppMode.INVISIBLE_STRIPS) {
                "İmleci şeridin üzerine getirin; touchpad'de iki parmakla kaydırın ya da fareyle tıklayıp " +
                    "yukarı/aşağı sürükleyin. " +
                    "Ses şeridine çift tıklayın veya sağ tıklayın: sessize al / aç."
            } else {
                "Topa tıklayınca altından dikey slider açılır; sürükleyin ya da topun üzerinde iki " +
                    "parmakla kaydırın. Ses topuna çift tıklayın veya sağ tıklayın: sessize al / aç."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ScreenPreview(cfg = cfg, screenW = screenW, screenH = screenH, settings = settings)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendDot(VolumeAccent, "Ses")
            LegendDot(BrightnessAccent, "Parlaklık")
            Text(
                "$screenW × $screenH px",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(activeMode: Int, onSelect: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val labels = listOf("\uD83D\uDCCF Görünmez Şerit Modu", "\uD83E\uDEE7 Yüzen Topçuk Modu")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, label ->
            SegmentedButton(
                selected = activeMode == index,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelect(index)
                },
                shape = SegmentedButtonDefaults.itemShape(index, labels.size)
            ) {
                Text(label)
            }
        }
    }
}

/**
 * Gerçek ekranın ölçekli önizlemesi. Şekiller sürüklenerek konumlandırılabilir; servis çalışmasa bile
 * değişiklikler anında kaydedilir. Dar şeritleri yakalamak için dokunma toleransı geniştir.
 */
@Composable
private fun ScreenPreview(cfg: AppConfig, screenW: Int, screenH: Int, settings: AppSettings) {
    val background = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outlineVariant
    val ratio = if (screenH > 0) screenW.toFloat() / screenH.toFloat() else 1.6f

    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .pointerInput(screenW, screenH) {
                var active: OverlayTarget? = null
                detectDragGestures(
                    onDragStart = { offset ->
                        val scale = size.width / screenW.toFloat()
                        val slop = 24.dp.toPx() / scale
                        active = hitTest(settings.read(), offset.x / scale, offset.y / scale, slop)
                    },
                    onDragEnd = { active = null },
                    onDragCancel = { active = null },
                    onDrag = { change, dragAmount ->
                        val target = active
                        if (target != null) {
                            change.consume()
                            val scale = size.width / screenW.toFloat()
                            val dx = (dragAmount.x / scale).roundToInt()
                            val dy = (dragAmount.y / scale).roundToInt()
                            val current = settings.read()
                            if (current.activeMode == AppMode.INVISIBLE_STRIPS) {
                                val box = current.stripBox(target)
                                settings.setStripGeometry(target, x = box.x + dx, y = box.y + dy)
                            } else {
                                settings.setBubblePosition(
                                    target,
                                    x = current.bubbleX(target) + dx,
                                    y = current.bubbleY(target) + dy
                                )
                            }
                        }
                    }
                )
            }
    ) {
        val s = size.width / screenW.toFloat()

        // Hafif çerçeve
        drawRoundRect(
            color = outline,
            size = size,
            cornerRadius = CornerRadius(36f, 36f),
            style = Stroke(width = 3f)
        )

        if (cfg.activeMode == AppMode.INVISIBLE_STRIPS) {
            for (target in OverlayTarget.values()) {
                val box = cfg.stripBox(target)
                val enabled = cfg.stripEnabled(target)
                val color = accentOf(target)
                val topLeft = Offset(box.x * s, box.y * s)
                val boxSize = Size(box.width * s, box.height * s)
                drawRoundRect(
                    color = color.copy(alpha = if (enabled) 0.40f else 0.10f),
                    topLeft = topLeft,
                    size = boxSize,
                    cornerRadius = CornerRadius(8f, 8f)
                )
                drawRoundRect(
                    color = color.copy(alpha = if (enabled) 1f else 0.35f),
                    topLeft = topLeft,
                    size = boxSize,
                    cornerRadius = CornerRadius(8f, 8f),
                    style = Stroke(width = 2.5f)
                )
            }
        } else {
            for (target in OverlayTarget.values()) {
                val enabled = cfg.bubbleEnabled(target)
                val color = accentOf(target)
                val size = cfg.bubbleSizePx * s
                val x = cfg.bubbleX(target) * s
                val y = cfg.bubbleY(target) * s
                // Açılır slider alanı (önizleme)
                drawRoundRect(
                    color = color.copy(alpha = if (enabled) 0.16f else 0.05f),
                    topLeft = Offset(x, y + (cfg.bubbleSizePx + AppSettings.DROPDOWN_GAP_PX) * s),
                    size = Size(size, cfg.dropdownSliderHeightPx * s),
                    cornerRadius = CornerRadius(size / 2f, size / 2f)
                )
                drawCircle(
                    color = color.copy(alpha = if (enabled) 0.85f else 0.20f),
                    radius = size / 2f,
                    center = Offset(x + size / 2f, y + size / 2f)
                )
            }
        }
    }
}

/** Önizlemede ekran koordinatındaki (x, y) noktasının hangi şekle denk geldiğini bulur. */
private fun hitTest(cfg: AppConfig, x: Float, y: Float, slop: Float): OverlayTarget? {
    for (target in listOf(OverlayTarget.BRIGHTNESS, OverlayTarget.VOLUME)) {
        if (cfg.activeMode == AppMode.INVISIBLE_STRIPS) {
            if (!cfg.stripEnabled(target)) continue
            val box = cfg.stripBox(target)
            if (x >= box.x - slop && x <= box.x + box.width + slop &&
                y >= box.y - slop && y <= box.y + box.height + slop
            ) return target
        } else {
            if (!cfg.bubbleEnabled(target)) continue
            val size = cfg.bubbleSizePx
            val totalHeight = size + AppSettings.DROPDOWN_GAP_PX + cfg.dropdownSliderHeightPx
            val left = cfg.bubbleX(target)
            val top = cfg.bubbleY(target)
            if (x >= left - slop && x <= left + size + slop &&
                y >= top - slop && y <= top + totalHeight + slop
            ) return target
        }
    }
    return null
}

// ============================================================================ touchpad test alanı

/**
 * Uygulama içinde iki parmak kaydırmayı, mevcut tepki süresi ve yön ayarlarıyla dener.
 * Gerçek ses/parlaklık değişmez; sadece sanal bir ölçeği oynatır.
 */
@Composable
private fun TouchpadTestCard(cfg: AppConfig) {
    val latestCfg by rememberUpdatedState(cfg)
    var level by remember { mutableStateOf(0.5f) }
    var detected by remember { mutableStateOf(0) }
    var applied by remember { mutableStateOf(0) }
    var lastApplied by remember { mutableStateOf(0L) }
    val animatedLevel by animateFloatAsState(level, label = "testLevel")

    SectionCard(
        title = "Touchpad Test Alanı",
        subtitle = "İmleci kutuya getirip iki parmakla kaydırın. Tepki süresi ve yön ayarları burada anında denenir; gerçek ses/parlaklık değişmez."
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .height(150.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Scroll) {
                                    val change = event.changes.firstOrNull() ?: continue
                                    val dy = change.scrollDelta.y
                                    if (dy != 0f) {
                                        detected += 1
                                        val now = SystemClock.elapsedRealtime()
                                        if (now - lastApplied >= latestCfg.scrollCooldownMs) {
                                            lastApplied = now
                                            applied += 1
                                            // Compose'ta yukarı kaydırma negatif değerdir.
                                            val raise = (dy < 0f) != latestCfg.invertScroll
                                            level = (level + if (raise) 0.05f else -0.05f).coerceIn(0f, 1f)
                                        }
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Buraya iki parmakla kaydırın",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            Box(
                Modifier
                    .width(30.dp)
                    .height(150.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(animatedLevel)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Algılanan: $detected  •  Uygulanan: $applied  •  Seviye: %${(level * 100f).roundToInt()}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = {
                detected = 0
                applied = 0
                level = 0.5f
            }) { Text("Sıfırla") }
        }
    }
}

// ============================================================================ Mod 1 ayarları (şeritler)

@Composable
private fun StripSettings(cfg: AppConfig, screenW: Int, screenH: Int, settings: AppSettings) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StripCard(OverlayTarget.VOLUME, "\uD83D\uDD0A Ses Şeridi", cfg, screenW, screenH, settings)
        StripCard(OverlayTarget.BRIGHTNESS, "\u2600\uFE0F Parlaklık Şeridi", cfg, screenW, screenH, settings)
        StripExtrasCard(cfg = cfg, settings = settings)
    }
}

@Composable
private fun StripCard(
    target: OverlayTarget,
    title: String,
    cfg: AppConfig,
    screenW: Int,
    screenH: Int,
    settings: AppSettings
) {
    val box = cfg.stripBox(target)
    SectionCard(title = title, titleColor = textAccentOf(target)) {
        SwitchRow(
            title = "Şeridi Etkinleştir",
            description = "Kapalıyken bu şerit ekranda bulunmaz.",
            checked = cfg.stripEnabled(target),
            onCheckedChange = { settings.setStripEnabled(target, it) }
        )
        ValueSliderRow(
            label = "X Konumu",
            value = box.x,
            rangeMin = 0,
            rangeMax = max(1, screenW - box.width),
            onChange = { settings.setStripGeometry(target, x = it) }
        )
        ValueSliderRow(
            label = "Y Konumu",
            value = box.y,
            rangeMin = 0,
            rangeMax = max(1, screenH - box.height),
            onChange = { settings.setStripGeometry(target, y = it) }
        )
        ValueSliderRow(
            label = "Genişlik (Width)",
            value = box.width,
            rangeMin = AppSettings.MIN_STRIP_WIDTH,
            rangeMax = min(AppSettings.MAX_STRIP_WIDTH, screenW),
            onChange = { settings.setStripGeometry(target, w = it) }
        )
        ValueSliderRow(
            label = "Yükseklik (Height)",
            value = box.height,
            rangeMin = min(AppSettings.MIN_STRIP_HEIGHT, screenH),
            rangeMax = screenH,
            onChange = { settings.setStripGeometry(target, h = it) }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StripExtrasCard(cfg: AppConfig, settings: AppSettings) {
    SectionCard(title = "Şerit Davranışı ve Şablonlar") {
        SwitchRow(
            title = "Tıklayınca Geçici Olarak Alta Geç (Click-Through)",
            description = "Açıkken görünmez şeride sürüklemeden tıkladığınızda şerit 3 saniye tıklama geçirgen olur ve " +
                "altındaki uygulama düğmesine tıklayabilirsiniz. Bu sürede şeritte kaydırma çalışmaz; " +
                "şeride yapılan ilk tıklama şerit tarafından alınır.",
            checked = cfg.clickThroughBypass,
            onCheckedChange = { settings.setClickThroughBypass(it) }
        )
        SwitchRow(
            title = "Üzerine Gelince İpucu Göster",
            description = "İmleç bir şeridin üzerine girince kısa bir bilgi kapsülü çıkar; şeridin yerini ve çalıştığını doğrular.",
            checked = cfg.stripHoverHint,
            onCheckedChange = { settings.setStripHoverHint(it) }
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { settings.applyStripPreset(StripPreset.VOLUME_RIGHT_BRIGHTNESS_LEFT) }) {
                Text("Ses Sağda / Parlaklık Solda")
            }
            OutlinedButton(onClick = { settings.applyStripPreset(StripPreset.BOTH_RIGHT_STACKED) }) {
                Text("İkisi Sağ Kenarda Alt-Üst")
            }
        }
    }
}

// ============================================================================ Mod 2 ayarları (topçuklar)

@Composable
private fun BubbleSettings(cfg: AppConfig, screenW: Int, screenH: Int, settings: AppSettings) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BubbleCard(OverlayTarget.VOLUME, "\uD83D\uDD0A Ses Topçuğu", cfg, screenW, screenH, settings)
        BubbleCard(OverlayTarget.BRIGHTNESS, "\u2600\uFE0F Parlaklık Topçuğu", cfg, screenW, screenH, settings)
        BubbleAppearanceCard(cfg = cfg, settings = settings)
    }
}

@Composable
private fun BubbleCard(
    target: OverlayTarget,
    title: String,
    cfg: AppConfig,
    screenW: Int,
    screenH: Int,
    settings: AppSettings
) {
    SectionCard(title = title, titleColor = textAccentOf(target)) {
        SwitchRow(
            title = "Topçuğu Etkinleştir",
            description = "Kapalıyken bu topçuk ekranda gösterilmez.",
            checked = cfg.bubbleEnabled(target),
            onCheckedChange = { settings.setBubbleEnabled(target, it) }
        )
        ValueSliderRow(
            label = "X Konumu",
            value = cfg.bubbleX(target),
            rangeMin = 0,
            rangeMax = max(1, screenW - cfg.bubbleSizePx),
            onChange = { settings.setBubblePosition(target, x = it) }
        )
        ValueSliderRow(
            label = "Y Konumu",
            value = cfg.bubbleY(target),
            rangeMin = 0,
            rangeMax = max(1, screenH - cfg.bubbleSizePx),
            onChange = { settings.setBubblePosition(target, y = it) }
        )
    }
}

@Composable
private fun BubbleAppearanceCard(cfg: AppConfig, settings: AppSettings) {
    SectionCard(title = "Topçuk Görünümü ve Davranışı") {
        ValueSliderRow(
            label = "Top Çapı (Boyutu)",
            value = cfg.bubbleSizePx,
            rangeMin = AppSettings.MIN_BUBBLE_SIZE,
            rangeMax = AppSettings.MAX_BUBBLE_SIZE,
            onChange = { settings.setBubbleSizePx(it) }
        )
        ValueSliderRow(
            label = "Açılır Slider Uzunluğu",
            value = cfg.dropdownSliderHeightPx,
            rangeMin = AppSettings.MIN_DROPDOWN_HEIGHT,
            rangeMax = AppSettings.MAX_DROPDOWN_HEIGHT,
            onChange = { settings.setDropdownSliderHeightPx(it) }
        )
        ValueSliderRow(
            label = "Saydamlık (Opaklık)",
            value = (cfg.bubbleIdleAlpha * 100f).roundToInt(),
            rangeMin = 10,
            rangeMax = 100,
            unit = "%",
            fineStep = 5,
            onChange = { settings.setBubbleIdleAlpha(it / 100f) }
        )
        ValueSliderRow(
            label = "Otomatik Kapanma Süresi",
            value = cfg.autoCollapseSec,
            rangeMin = 2,
            rangeMax = 10,
            unit = "sn",
            fineStep = 1,
            onChange = { settings.setAutoCollapseSec(it) }
        )
    }
}

// ============================================================================ ortak davranış ve görünüm

@Composable
private fun BehaviorCard(cfg: AppConfig, settings: AppSettings) {
    SectionCard(
        title = "Touchpad Hassasiyeti (Ortak)",
        subtitle = "Test alanında anında deneyebilirsiniz."
    ) {
        ValueSliderRow(
            label = "Kaydırma Tepki Süresi (Cooldown)",
            value = cfg.scrollCooldownMs.toInt(),
            rangeMin = 10,
            rangeMax = 300,
            unit = "ms",
            fineStep = 5,
            onChange = { settings.setScrollCooldownMs(it.toLong()) }
        )
        Text(
            "Düşük değer = hızlı tepki, yüksek değer = daha kontrollü değişim.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SwitchRow(
            title = "Kaydırma Yönünü Ters Çevir",
            description = "Açıkken iki parmakla aşağı kaydırmak artırır, yukarı kaydırmak azaltır.",
            checked = cfg.invertScroll,
            onCheckedChange = { settings.setInvertScroll(it) }
        )
        Text(
            "Cihazın kendi ses/parlaklık göstergesi her zaman gizlidir; geri bildirimi yalnızca topçuk veya mini HUD verir.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceCard(cfg: AppConfig, settings: AppSettings) {
    var showResetDialog by remember { mutableStateOf(false) }
    val themeLabels = listOf("Sistem", "Açık", "Koyu")

    SectionCard(title = "Uygulama") {
        Text("Tema", style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            themeLabels.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = cfg.themeMode == index,
                    onClick = { settings.setThemeMode(index) },
                    shape = SegmentedButtonDefaults.itemShape(index, themeLabels.size)
                ) {
                    Text(label)
                }
            }
        }
        OutlinedButton(
            onClick = { showResetDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Tüm Konum ve Ayarları Varsayılana Döndür")
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Varsayılana dönülsün mü?") },
            text = {
                Text("Şerit ve topçuk konumları, boyutlar ve touchpad ayarları sıfırlanır. Tema ve servis durumu korunur.")
            },
            confirmButton = {
                TextButton(onClick = {
                    settings.resetToDefaults()
                    showResetDialog = false
                }) { Text("Sıfırla") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Vazgeç") }
            }
        )
    }
}

// ============================================================================ ses tanılama

/**
 * Ses değişmiyorsa nedenini bulmak için: aynı doğrulamalı motoru doğrudan uygulamadan çalıştırır ve
 * sonucu (hangi yöntemle, kaç kademe, ya da neden başarısız) gösterir.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VolumeDiagnosticsCard() {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    var report by remember { mutableStateOf(VolumeEngine.describe(audio, context)) }
    var message by remember { mutableStateOf("") }

    SectionCard(
        title = "Ses Tanılama",
        subtitle = "Ses değişmiyorsa buradan test edin; sonuç nedeni gösterir."
    ) {
        Text(
            report,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = {
                message = VolumeEngine.step(audio, false).summary()
                report = VolumeEngine.describe(audio, context)
            }) { Text("Sesi \u22121") }
            OutlinedButton(onClick = {
                message = VolumeEngine.step(audio, true).summary()
                report = VolumeEngine.describe(audio, context)
            }) { Text("Sesi +1") }
            OutlinedButton(onClick = {
                message = VolumeEngine.toggleMute(audio).summary()
                report = VolumeEngine.describe(audio, context)
            }) { Text("Sessize Al / Aç") }
            TextButton(onClick = {
                report = VolumeEngine.describe(audio, context)
            }) { Text("Yenile") }
        }
        if (message.isNotEmpty()) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ============================================================================ ortak bileşenler

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description.isNotEmpty()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(it)
            }
        )
    }
}

/** Başlık + anlık değer + (−/+) ince ayar düğmeleri + kaydırıcı. */
@Composable
private fun ValueSliderRow(
    label: String,
    value: Int,
    rangeMin: Int,
    rangeMax: Int,
    unit: String = "px",
    fineStep: Int = 10,
    onChange: (Int) -> Unit
) {
    val hi = if (rangeMax > rangeMin) rangeMax else rangeMin + 1
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            OutlinedButton(
                onClick = { onChange(min(max(value - fineStep, rangeMin), hi)) },
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) { Text("\u2212$fineStep") }
            Text(
                "$value $unit",
                modifier = Modifier.widthIn(min = 96.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            OutlinedButton(
                onClick = { onChange(min(max(value + fineStep, rangeMin), hi)) },
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) { Text("+$fineStep") }
        }
        Slider(
            value = value.toFloat().coerceIn(rangeMin.toFloat(), hi.toFloat()),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = rangeMin.toFloat()..hi.toFloat()
        )
    }
}
