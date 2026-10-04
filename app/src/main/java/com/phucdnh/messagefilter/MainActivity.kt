package com.phucdnh.messagefilter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.phucdnh.messagefilter.data.local.ForwardType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Switch
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.rememberCoroutineScope
import com.phucdnh.messagefilter.ai.AiSummarizerManager
import com.phucdnh.messagefilter.ai.ModelDownloadState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.phucdnh.messagefilter.data.CapturedMessage
import com.phucdnh.messagefilter.data.MessageRepository
import com.phucdnh.messagefilter.data.local.AppAction
import com.phucdnh.messagefilter.data.local.AppFilterPreferences
import com.phucdnh.messagefilter.ui.theme.MessageFilterTheme
import com.phucdnh.messagefilter.util.NotificationHelper
import com.phucdnh.messagefilter.util.OtpExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationHelper.createNotificationChannel(this)
        MessageRepository.initialize(this)
        AppFilterPreferences.initialize(this)
        AiSummarizerManager.initialize(this)

        setContent {
            MessageFilterTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isListenerGranted by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    var isPostNotificationGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showNotificationDisclosureDialog by remember { mutableStateOf(false) }
    var showPrivacyPolicyDialog by remember { mutableStateOf(false) }

    val requestPostNotificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isPostNotificationGranted = isGranted
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isListenerGranted = isNotificationListenerEnabled(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    isPostNotificationGranted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Message Filter & Forwarder",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = { showPrivacyPolicyDialog = true }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_info),
                            contentDescription = "Privacy Policy & Info"
                        )
                    }
                },
                modifier = Modifier.statusBarsPadding(),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Permission Banner if missing
            if (!isListenerGranted || !isPostNotificationGranted) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (!isListenerGranted) {
                        PermissionCard(
                            title = "Notification Listener Access",
                            description = "Enable access to read messages.",
                            isGranted = false,
                            actionLabel = "Grant",
                            onActionClick = {
                                showNotificationDisclosureDialog = true
                            }
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    if (!isPostNotificationGranted) {
                        PermissionCard(
                            title = "Post Notifications",
                            description = "Allow app to display forwarded messages.",
                            isGranted = false,
                            actionLabel = "Grant",
                            onActionClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    requestPostNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        )
                    }
                }
            }

            if (showNotificationDisclosureDialog) {
                AlertDialog(
                    onDismissRequest = { showNotificationDisclosureDialog = false },
                    title = {
                        Text(
                            text = "Yêu cầu quyền truy cập thông báo",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                text = "Message Filter & Forwarder cần quyền Truy cập thông báo (Notification Listener) để thực hiện các tính năng cốt lõi:\n\n" +
                                        "• Tự động trích xuất mã OTP và mã xác thực để hiển thị nút sao chép nhanh.\n" +
                                        "• Lọc và chặn thông báo quảng cáo, tin rác theo từ khóa bạn cấu hình.\n" +
                                        "• Gom nhóm và chuyển tiếp thông báo từ các ứng dụng được chọn.\n\n" +
                                        "Cam kết bảo mật & quyền riêng tư:\n" +
                                        "• Toàn bộ dữ liệu thông báo được xử lý 100% cục bộ trên thiết bị của bạn.\n" +
                                        "• Ứng dụng hoạt động hoàn toàn ngoại tuyến (offline), KHÔNG thu thập, KHÔNG lưu trữ trên máy chủ và KHÔNG chia sẻ dữ liệu ra bên ngoài.",
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showNotificationDisclosureDialog = false
                                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                context.startActivity(intent)
                            }
                        ) {
                            Text("Đồng ý & Mở cài đặt")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showNotificationDisclosureDialog = false }) {
                            Text("Để sau")
                        }
                    }
                )
            }

            if (showPrivacyPolicyDialog) {
                AlertDialog(
                    onDismissRequest = { showPrivacyPolicyDialog = false },
                    title = {
                        Text(
                            text = "Chính sách quyền riêng tư",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                text = "Message Filter & Forwarder cam kết bảo vệ quyền riêng tư của bạn:\n\n" +
                                        "1. Không thu thập dữ liệu:\n" +
                                        "Ứng dụng không thu thập, không theo dõi, không gửi bất kỳ thông tin cá nhân hay nội dung tin nhắn nào của bạn lên mạng internet.\n\n" +
                                        "2. Xử lý cục bộ 100%:\n" +
                                        "Mọi quy trình phân tích tin nhắn, trích xuất OTP và lọc từ khóa đều chạy trực tiếp trên thiết bị của bạn.\n\n" +
                                        "3. Quyền hạn ứng dụng:\n" +
                                        "• Truy cập thông báo: Đọc tin nhắn đến để lọc và hiển thị mã OTP.\n" +
                                        "• Đăng thông báo: Hiển thị các thông báo chuyển tiếp và nút hành động nhanh.\n" +
                                        "• Quét danh sách ứng dụng: Cho phép bạn lựa chọn các ứng dụng cần lọc thông báo.\n\n" +
                                        "Bạn có thể xem chi tiết tài liệu tại file PRIVACY_POLICY.md trong mã nguồn.",
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = { showPrivacyPolicyDialog = false }) {
                            Text("Đã hiểu")
                        }
                    }
                )
            }

            // Tabs: 4 Tabs (App Filters, AI Digest, History & Logs, Test & Simulator)
            PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("App Filters", fontSize = 12.sp) }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text("AI Digest", fontSize = 12.sp) }
                )
                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    text = { Text("History & Logs", fontSize = 12.sp) }
                )
                Tab(
                    selected = selectedTabIndex == 3,
                    onClick = { selectedTabIndex = 3 },
                    text = { Text("Test Alert", fontSize = 12.sp) }
                )
            }

            when (selectedTabIndex) {
                0 -> AppFilterTabContent()
                1 -> AiDigestTabContent()
                2 -> HistoryTabContent()
                3 -> TestTabContent()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppFilterTabContent() {
    val context = LocalContext.current
    val forwardPackages by AppFilterPreferences.forwardFlow.collectAsState()
    val forwardOtpOnlyPackages by AppFilterPreferences.forwardOtpOnlyFlow.collectAsState()
    val filterPackages by AppFilterPreferences.filterFlow.collectAsState()
    val blockedPackages by AppFilterPreferences.blockedFlow.collectAsState()
    val filterKeywords by AppFilterPreferences.keywordsFlow.collectAsState()
    val autoDismissOriginal by AppFilterPreferences.autoDismissFlow.collectAsState()

    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var filterTabSelection by remember { mutableIntStateOf(0) } // 0: All, 1: Forward, 2: Filter, 3: Blocked

    var newKeywordInput by remember { mutableStateOf("") }
    var isKeywordSectionExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val currentForward = AppFilterPreferences.forwardFlow.value
            val currentFilter = AppFilterPreferences.filterFlow.value
            val currentBlocked = AppFilterPreferences.blockedFlow.value

            val list = packages.map { appInfo ->
                InstalledAppInfo(
                    packageName = appInfo.packageName,
                    appName = pm.getApplicationLabel(appInfo).toString(),
                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }.sortedWith(
                compareBy<InstalledAppInfo> { app ->
                    when {
                        currentForward.contains(app.packageName) -> 0
                        currentFilter.contains(app.packageName) -> 1
                        currentBlocked.contains(app.packageName) -> 2
                        else -> 3
                    }
                }.thenBy { it.appName.lowercase() }
            )
            withContext(Dispatchers.Main) {
                installedApps = list
            }
        }
    }

    val presets = AppFilterPreferences.POPULAR_MESSAGING_APPS
    val presetPackageSet = remember { presets.map { it.first }.toSet() }

    // Keep app list order stable while user is toggling buttons on screen
    val filteredApps = remember(installedApps, searchQuery, forwardPackages, filterPackages, blockedPackages, filterTabSelection) {
        val isShowingPresets = searchQuery.isBlank() && filterTabSelection == 0
        installedApps.filter { app ->
            if (isShowingPresets && presetPackageSet.contains(app.packageName)) {
                return@filter false
            }

            val matchesQuery = searchQuery.isBlank() ||
                    app.appName.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)

            val isForward = forwardPackages.contains(app.packageName)
            val isFilter = filterPackages.contains(app.packageName)
            val isBlocked = blockedPackages.contains(app.packageName)
            val isNormal = !isForward && !isFilter && !isBlocked

            val matchesTab = when (filterTabSelection) {
                1 -> isForward
                2 -> isFilter
                3 -> isBlocked
                4 -> isNormal
                else -> true
            }

            matchesQuery && matchesTab
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Dismiss Original App Notification",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Hủy thông báo gốc để popup & đồng hồ chỉ nhận thông báo sạch từ app mình.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    androidx.compose.material3.Switch(
                        checked = autoDismissOriginal,
                        onCheckedChange = { enabled ->
                            AppFilterPreferences.setAutoDismissOriginal(context, enabled)
                        }
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE3F2FD).copy(alpha = 0.6f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isKeywordSectionExpanded = !isKeywordSectionExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Từ khóa lọc tin rác (${filterKeywords.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0D47A1)
                        )
                        Text(
                            text = if (isKeywordSectionExpanded) "Thu gọn ▲" else "Mở rộng ▼",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF1976D2),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (isKeywordSectionExpanded) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Thông báo chứa từ khóa này sẽ tự hủy (chặn popup, rung, chuông) nếu app có bật tính năng Filter.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF1565C0)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newKeywordInput,
                                onValueChange = { newKeywordInput = it },
                                placeholder = { Text("Nhập từ khóa cần lọc (vd: sale, cước)...", fontSize = 13.sp) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp),
                                textStyle = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (newKeywordInput.isNotBlank()) {
                                        AppFilterPreferences.addFilterKeyword(context, newKeywordInput)
                                        newKeywordInput = ""
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                            ) {
                                Text("Thêm")
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            filterKeywords.forEach { kw ->
                                Surface(
                                    color = Color.White,
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, Color(0xFF90CAF9))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(text = kw, fontSize = 11.sp, color = Color(0xFF0D47A1), fontWeight = FontWeight.Medium)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "✕",
                                            fontSize = 11.sp,
                                            color = Color.Gray,
                                            modifier = Modifier
                                                .clickable { AppFilterPreferences.removeFilterKeyword(context, kw) }
                                                .padding(2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Forward: ${forwardPackages.size} | Filter: ${filterPackages.size} | Block: ${blockedPackages.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        item {
            val normalCount = installedApps.count {
                !forwardPackages.contains(it.packageName) &&
                        !filterPackages.contains(it.packageName) &&
                        !blockedPackages.contains(it.packageName)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val tabs = listOf(
                    "All" to 0,
                    "Forward (${forwardPackages.size})" to 1,
                    "Filter (${filterPackages.size})" to 2,
                    "Block (${blockedPackages.size})" to 3,
                    "Normal ($normalCount)" to 4
                )
                tabs.forEach { (label, index) ->
                    val isSelected = filterTabSelection == index
                    FilterChip(
                        selected = isSelected,
                        onClick = { filterTabSelection = index },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search apps (e.g. WhatsApp, Messenger)...") },
                singleLine = true,
                maxLines = 1,
                shape = RoundedCornerShape(10.dp)
            )
        }

        if (searchQuery.isBlank() && filterTabSelection == 0) {
            item {
                Text(
                    text = "Quick Presets (Messaging Apps)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }

            items(presets, key = { "preset_${it.first}" }) { (pkg, name) ->
                val forwardType = if (forwardOtpOnlyPackages.contains(pkg)) ForwardType.OTP_ONLY else ForwardType.ALL
                AppActionItemCard(
                    appName = name,
                    packageName = pkg,
                    isForward = forwardPackages.contains(pkg),
                    forwardType = forwardType,
                    isFilter = filterPackages.contains(pkg),
                    isBlocked = blockedPackages.contains(pkg),
                    onToggleForward = { AppFilterPreferences.toggleForward(context, pkg) },
                    onSelectForwardType = { newType -> AppFilterPreferences.setForwardType(context, pkg, newType) },
                    onToggleFilter = { AppFilterPreferences.toggleFilter(context, pkg) },
                    onToggleBlock = { AppFilterPreferences.toggleBlock(context, pkg) }
                )
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "All Device Apps",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }

        items(filteredApps, key = { "app_${it.packageName}" }) { app ->
            val forwardType = if (forwardOtpOnlyPackages.contains(app.packageName)) ForwardType.OTP_ONLY else ForwardType.ALL
            AppActionItemCard(
                appName = app.appName,
                packageName = app.packageName,
                isForward = forwardPackages.contains(app.packageName),
                forwardType = forwardType,
                isFilter = filterPackages.contains(app.packageName),
                isBlocked = blockedPackages.contains(app.packageName),
                onToggleForward = { AppFilterPreferences.toggleForward(context, app.packageName) },
                onSelectForwardType = { newType -> AppFilterPreferences.setForwardType(context, app.packageName, newType) },
                onToggleFilter = { AppFilterPreferences.toggleFilter(context, app.packageName) },
                onToggleBlock = { AppFilterPreferences.toggleBlock(context, app.packageName) }
            )
        }
    }
}

@Composable
fun AppActionItemCard(
    appName: String,
    packageName: String,
    isForward: Boolean,
    forwardType: ForwardType,
    isFilter: Boolean,
    isBlocked: Boolean,
    onToggleForward: () -> Unit,
    onSelectForwardType: (ForwardType) -> Unit,
    onToggleFilter: () -> Unit,
    onToggleBlock: () -> Unit
) {
    var showForwardMenu by remember { mutableStateOf(false) }

    val cardBgColor = when {
        isBlocked -> Color(0xFFFFEBEE)
        isForward || isFilter -> Color(0xFFF1F8E9)
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appName,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (isBlocked) {
                        Surface(
                            color = Color(0xFFC62828).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "BLOCK",
                                color = Color(0xFFC62828),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else if (!isForward && !isFilter) {
                        Surface(
                            color = Color.Gray.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "NORMAL",
                                color = Color.Gray,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        if (isForward) {
                            val forwardBadgeText = if (forwardType == ForwardType.OTP_ONLY) "FORWARD (OTP)" else "FORWARD"
                            Surface(
                                color = Color(0xFF2E7D32).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = forwardBadgeText,
                                    color = Color(0xFF2E7D32),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (isFilter) {
                            Surface(
                                color = Color(0xFF1976D2).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "FILTER",
                                    color = Color(0xFF1976D2),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    ActionButtonItem(
                        title = "Forward",
                        subText = if (forwardType == ForwardType.OTP_ONLY && isForward) "(OTP)" else null,
                        isSelected = isForward,
                        activeColor = Color(0xFF2E7D32),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onToggleForward,
                        onLongClick = { showForwardMenu = true }
                    )

                    DropdownMenu(
                        expanded = showForwardMenu,
                        onDismissRequest = { showForwardMenu = false },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = "Forward All Messages",
                                        fontWeight = if (forwardType == ForwardType.ALL) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "Chuyển tiếp tất cả tin nhắn",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            },
                            trailingIcon = {
                                if (forwardType == ForwardType.ALL) {
                                    Text("✓", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                                }
                            },
                            onClick = {
                                onSelectForwardType(ForwardType.ALL)
                                showForwardMenu = false
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = "Forward OTP Only",
                                        fontWeight = if (forwardType == ForwardType.OTP_ONLY) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = "Chỉ chuyển tiếp khi có mã OTP",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            },
                            trailingIcon = {
                                if (forwardType == ForwardType.OTP_ONLY) {
                                    Text("✓", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                                }
                            },
                            onClick = {
                                onSelectForwardType(ForwardType.OTP_ONLY)
                                showForwardMenu = false
                            }
                        )
                    }
                }

                ActionButtonItem(
                    title = "Filter",
                    isSelected = isFilter,
                    activeColor = Color(0xFF1976D2),
                    modifier = Modifier.weight(1f),
                    onClick = onToggleFilter
                )

                ActionButtonItem(
                    title = "Block",
                    isSelected = isBlocked,
                    activeColor = Color(0xFFC62828),
                    modifier = Modifier.weight(1f),
                    onClick = onToggleBlock
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActionButtonItem(
    title: String,
    isSelected: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier,
    subText: String? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        color = if (isSelected) activeColor else Color.White.copy(alpha = 0.9f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            1.dp,
            if (isSelected) activeColor else Color.LightGray.copy(alpha = 0.5f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) Color.White else Color.DarkGray
                )
                if (!subText.isNullOrBlank() && isSelected) {
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = subText,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiDigestTabContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val aiEnabled by AppFilterPreferences.aiSummarizeGroupsFlow.collectAsState()
    val userNicknames by AppFilterPreferences.userNicknamesFlow.collectAsState()
    val debounceSeconds by AppFilterPreferences.debounceSecondsFlow.collectAsState()
    val silentDigest by AppFilterPreferences.aiSilentDigestFlow.collectAsState()
    val downloadState by AiSummarizerManager.downloadState.collectAsState()
    val isInferring by AiSummarizerManager.isInferring.collectAsState()

    var nicknamesInput by remember(userNicknames) { mutableStateOf(userNicknames) }
    var testResultText by remember { mutableStateOf<String?>(null) }
    var isRunningSampleTest by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Master Switch Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Tóm tắt thông minh nhóm chat",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tự động gom tin nhắn nhóm dồn dập, dùng AI On-Device tóm tắt gọn gàng để smartwatch không bị bom rung.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = aiEnabled,
                        onCheckedChange = { AppFilterPreferences.setAiSummarizeGroupsEnabled(context, it) }
                    )
                }
            }
        }

        // 2. On-Device Model Management Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Mô hình AI trên máy (On-Device)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "SmolLM2-135M-Instruct (Quantized Q4_K_M ~100MB). Xử lý hoàn toàn ngoại tuyến qua llama.cpp, không gửi dữ liệu ra mạng internet và tự động giải phóng RAM sau 2 phút.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                when (val state = downloadState) {
                    is ModelDownloadState.NotDownloaded -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Chưa tải mô hình",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "Dung lượng tải: ~100MB (Hugging Face CDN)",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Button(
                                    onClick = { AiSummarizerManager.startDownload(context) },
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text("Tải mô hình", fontSize = 13.sp)
                                }
                            }
                        }
                    }

                    is ModelDownloadState.Downloading -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Đang tải: ${(state.progress * 100).toInt()}%",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "${state.downloadedBytes / (1024 * 1024)}MB / ${state.totalBytes / (1024 * 1024)}MB",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { AiSummarizerManager.cancelDownload(context) }
                                ) {
                                    Text("Hủy tải", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    is ModelDownloadState.Ready -> {
                        Surface(
                            color = Color(0xFFE8F5E9),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Đã sẵn sàng hoạt động cục bộ",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = Color(0xFF2E7D32)
                                        )
                                        Text(
                                            text = "Dung lượng file: ${"%.1f".format(state.fileSizeMb)} MB",
                                            fontSize = 12.sp,
                                            color = Color(0xFF388E3C)
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { AiSummarizerManager.deleteModel(context) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Text("Xóa file", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    is ModelDownloadState.Error -> {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Tải mô hình thất bại",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Text(
                                        text = state.error,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                Button(
                                    onClick = { AiSummarizerManager.startDownload(context) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text("Thử lại", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. User Identity & Nicknames Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Tên nhận diện trong nhóm chat",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Khi có người gọi tên hoặc tag bạn trong nhóm (ví dụ: '@Phúc', 'Phúc ơi', 'anh Phúc'), app sẽ BỎ QUA gom nhóm và gửi thông báo khẩn cấp ngay lập tức đến smartwatch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = nicknamesInput,
                    onValueChange = { nicknamesInput = it },
                    label = { Text("Tên/biệt danh (ngăn cách bằng dấu phẩy)") },
                    placeholder = { Text("Phúc, phucdnh, anh Phúc") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    FilledTonalButton(
                        onClick = {
                            AppFilterPreferences.setUserNicknames(context, nicknamesInput)
                            Toast.makeText(context, "Đã lưu tên nhận diện", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text("Lưu tên", fontSize = 13.sp)
                    }
                }
            }
        }

        // 4. Debounce & Silent Options Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Tùy chọn gom nhóm & Rung",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Thời gian gom tin nhắn dồn dập:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(20, 40, 60).forEach { sec ->
                        FilterChip(
                            selected = debounceSeconds == sec,
                            onClick = { AppFilterPreferences.setDebounceSeconds(context, sec) },
                            label = { Text("$sec giây") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Thông báo tóm tắt im lặng",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Thông báo tóm tắt chỉ hiển thị trên màn hình đồng hồ mà không làm rung tay.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = silentDigest,
                        onCheckedChange = { AppFilterPreferences.setAiSilentDigest(context, it) }
                    )
                }
            }
        }

        // 5. Live Test & Demonstration Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Chạy thử suy luận AI trực tiếp",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Thử nghiệm tóm tắt 4 tin nhắn nhóm mẫu về việc ăn trưa và xem bóng đá tối nay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        isRunningSampleTest = true
                        testResultText = null
                        scope.launch {
                            val sampleMessages = listOf(
                                "Tuấn" to "Trưa nay ăn gì mấy ông ơi?",
                                "Huy" to "Cơm tấm sườn bì chả góc ngã tư đi, nay đang thèm.",
                                "Tuấn" to "Ok chốt, 11h45 đi chung nha.",
                                "Bảo" to "Tối nay nhớ coi chung kết cúp C1 lúc 2h nhé anh em."
                            )
                            val userNames = AppFilterPreferences.getUserNicknames(context)
                            val res = AiSummarizerManager.summarizeGroupMessages(
                                context = context,
                                groupTitle = "Hội Anh Em IT",
                                messages = sampleMessages,
                                userNicknames = userNames
                            )
                            testResultText = res.summary
                            isRunningSampleTest = false

                            // Also trigger actual notification so user can see it on smartwatch!
                            NotificationHelper.showGroupDigestNotification(
                                context = context,
                                notificationId = 88888,
                                packageName = "com.whatsapp",
                                groupTitle = "Hội Anh Em IT",
                                summaryText = res.summary,
                                messageCount = sampleMessages.size
                            )
                        }
                    },
                    enabled = !isRunningSampleTest && !isInferring,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isRunningSampleTest || isInferring) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Đang suy luận AI...")
                    } else {
                        Text("Chạy thử tóm tắt & Gửi thông báo mẫu")
                    }
                }

                if (!testResultText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.background,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Kết quả tóm tắt AI:",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = testResultText ?: "",
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TestTabContent() {
    val context = LocalContext.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    var selectedPackage by remember { mutableStateOf("com.whatsapp") }
    var senderInput by remember { mutableStateOf("SSO") }
    var messageInput by remember { mutableStateOf("886778 is your SSO's OTP code.") }
    var testFeedbackMessage by remember { mutableStateOf<String?>(null) }

    val presetApps = listOf(
        "com.phucdnh.messagefilter" to "MessageFilter (App Này)",
        "com.whatsapp" to "WhatsApp",
        "com.google.android.apps.messaging" to "Google Messages",
        "com.facebook.orca" to "Messenger",
        "org.telegram.messenger" to "Telegram",
        "com.zing.zalo" to "Zalo"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Test Notification Simulator",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        // Preset App Selector
        Text(text = "1. Chọn Ứng dụng gửi (Package):", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            presetApps.forEach { (pkg, name) ->
                FilterChip(
                    selected = selectedPackage == pkg,
                    onClick = {
                        selectedPackage = pkg
                        focusManager.clearFocus()
                    },
                    label = { Text(name, fontSize = 11.sp) }
                )
            }
        }

        // Quick Templates
        Text(text = "2. Mẫu tin nhắn thử nghiệm nhanh:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Surface(
                color = Color(0xFFE8F5E9),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFFA5D6A7)),
                modifier = Modifier.clickable {
                    focusManager.clearFocus()
                    selectedPackage = "com.google.android.apps.messaging"
                    senderInput = "SSO"
                    val randomOtp = (100000..999999).random()
                    messageInput = "$randomOtp is your SSO's OTP code. Valid for 5 minutes."
                }
            ) {
                Text(
                    text = "Mã OTP (SMS)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2E7D32),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }

            Surface(
                color = Color(0xFFEDE7F6),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFFD1C4E9)),
                modifier = Modifier.clickable {
                    focusManager.clearFocus()
                    selectedPackage = "com.whatsapp"
                    senderInput = "Group Dev Team"
                    messageInput = "@PhucDNH check gấp giúp t hợp đồng vừa gửi nha!"
                }
            ) {
                Text(
                    text = "Tag @PhucDNH",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF512DA8),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }

            Surface(
                color = Color(0xFFE3F2FD),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFF90CAF9)),
                modifier = Modifier.clickable {
                    focusManager.clearFocus()
                    selectedPackage = "com.whatsapp"
                    senderInput = "Quỳnh Anh"
                    val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                    messageInput = "Alo m ơi, tin nhắn lúc $timeStr nè!"
                }
            ) {
                Text(
                    text = "Chat Thường",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1565C0),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }

            Surface(
                color = Color(0xFFFFEBEE),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFFFFCDD2)),
                modifier = Modifier.clickable {
                    focusManager.clearFocus()
                    selectedPackage = "com.zing.zalo"
                    AppFilterPreferences.setPackageAction(context, "com.zing.zalo", AppAction.FILTER)
                    senderInput = "Shopee Khuyến Mãi"
                    messageInput = "Tri ân khách hàng: voucher giảm giá 50% tiết kiệm ngay hôm nay!"
                }
            ) {
                Text(
                    text = "Tin Rác (Filter)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFC62828),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
        }

        // Sender / Title Input
        Text(text = "3. Người gửi / Tiêu đề (Sender):", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        OutlinedTextField(
            value = senderInput,
            onValueChange = { senderInput = it },
            placeholder = { Text("Nhập người gửi (vd: SSO, Tuấn, Group A)...", fontSize = 12.sp) },
            singleLine = true,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )

        // Message Content Input
        Text(text = "4. Nội dung tin nhắn (Message Content):", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        OutlinedTextField(
            value = messageInput,
            onValueChange = { messageInput = it },
            placeholder = { Text("Nhập nội dung tin nhắn...", fontSize = 12.sp) },
            minLines = 2,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )

        // Status Card
        val currentAppAction = remember(selectedPackage, AppFilterPreferences.forwardFlow.collectAsState().value, AppFilterPreferences.filterFlow.collectAsState().value, AppFilterPreferences.blockedFlow.collectAsState().value) {
            AppFilterPreferences.getPackageAction(context, selectedPackage)
        }

        Surface(
            color = when (currentAppAction) {
                AppAction.FORWARD -> Color(0xFFE8F5E9)
                AppAction.FILTER -> Color(0xFFE3F2FD)
                AppAction.BLOCK -> Color(0xFFFFEBEE)
                AppAction.NONE -> MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Trạng thái app đã chọn:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = currentAppAction.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (currentAppAction) {
                        AppAction.FORWARD -> Color(0xFF2E7D32)
                        AppAction.FILTER -> Color(0xFF1976D2)
                        AppAction.BLOCK -> Color(0xFFC62828)
                        AppAction.NONE -> Color.Gray
                    }
                )
            }
        }

        Button(
            onClick = {
                focusManager.clearFocus()
                if (senderInput.isNotBlank() && messageInput.isNotBlank()) {
                    val cleanSender = senderInput.trim()
                    val cleanMsg = messageInput.trim()
                    val conversationId = (selectedPackage.hashCode() xor cleanSender.hashCode()) and 0x7FFFFFFF
                    val now = System.currentTimeMillis()

                    val lowerMsg = cleanMsg.lowercase()
                    val lowerSender = cleanSender.lowercase()
                    val filterKeywords = AppFilterPreferences.getFilterKeywords(context)
                    val matchedKw = filterKeywords.firstOrNull { kw ->
                        kw.isNotBlank() && (lowerMsg.contains(kw.lowercase()) || lowerSender.contains(kw.lowercase()))
                    }

                    val isBlocked = AppFilterPreferences.isBlocked(context, selectedPackage)
                    val isFilter = AppFilterPreferences.isFilterEnabled(context, selectedPackage)
                    val isForward = AppFilterPreferences.isForwardEnabled(context, selectedPackage)

                    when {
                        isBlocked -> {
                            testFeedbackMessage = "Đã chặn: App này đang ở chế độ Block (Không rung & không hiện thông báo)."
                            NotificationHelper.incrementFilteredCount(context, cleanSender, "Blocked")
                            MessageRepository.addMessage(
                                context = context,
                                message = CapturedMessage(
                                    packageName = selectedPackage,
                                    senderOrTitle = cleanSender,
                                    messageContent = cleanMsg,
                                    timestamp = now,
                                    isForwarded = false,
                                    status = "BLOCK"
                                )
                            )
                        }
                        isFilter && matchedKw != null -> {
                            testFeedbackMessage = "Đã lọc tin rác: Chặn từ khóa '$matchedKw' thành công (Không rung & không hiện thông báo)."
                            NotificationHelper.incrementFilteredCount(context, cleanSender, matchedKw)
                            MessageRepository.addMessage(
                                context = context,
                                message = CapturedMessage(
                                    packageName = selectedPackage,
                                    senderOrTitle = cleanSender,
                                    messageContent = "[Đã lọc: '$matchedKw'] $cleanMsg",
                                    timestamp = now,
                                    isForwarded = false,
                                    status = "FILTER"
                                )
                            )
                        }
                        isForward -> {
                            val forwardType = AppFilterPreferences.getForwardType(context, selectedPackage)
                            val detectedOtp = OtpExtractor.extractOtp(cleanMsg)

                            if (forwardType == ForwardType.OTP_ONLY && detectedOtp == null) {
                                testFeedbackMessage = "Bỏ qua: Chế độ Forward OTP Only nhưng tin nhắn không có mã OTP."
                                MessageRepository.addMessage(
                                    context = context,
                                    message = CapturedMessage(
                                        packageName = selectedPackage,
                                        senderOrTitle = cleanSender,
                                        messageContent = cleanMsg,
                                        timestamp = now,
                                        isForwarded = false,
                                        status = "NORMAL"
                                    )
                                )
                            } else {
                                val effectiveMsg = if (detectedOtp != null) {
                                    "[OTP: $detectedOtp]\n───\n$cleanMsg"
                                } else {
                                    cleanMsg
                                }

                                NotificationHelper.showForwardedMessageNotification(
                                    context = context,
                                    notificationId = conversationId,
                                    packageName = selectedPackage,
                                    sender = cleanSender,
                                    latestText = effectiveMsg,
                                    timestamp = now
                                )

                                MessageRepository.addMessage(
                                    context = context,
                                    message = CapturedMessage(
                                        packageName = selectedPackage,
                                        senderOrTitle = cleanSender,
                                        messageContent = effectiveMsg,
                                        timestamp = now,
                                        isForwarded = true,
                                        status = "FORWARD"
                                    )
                                )
                                testFeedbackMessage = if (isFilter) {
                                    "Đã lọc sạch (không dính từ khóa) và chuyển tiếp (FORWARD) thành công!"
                                } else {
                                    "Đã chuyển tiếp (FORWARD) thông báo sạch thành công!"
                                }
                            }
                        }
                        else -> {
                            MessageRepository.addMessage(
                                context = context,
                                message = CapturedMessage(
                                    packageName = selectedPackage,
                                    senderOrTitle = cleanSender,
                                    messageContent = cleanMsg,
                                    timestamp = now,
                                    isForwarded = false,
                                    status = "NORMAL"
                                )
                            )
                            testFeedbackMessage = "Tin sạch / Chế độ Normal: Không forward, ghi nhận log NORMAL."
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(text = "Gửi thông báo Test ngay", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }

        if (testFeedbackMessage != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = testFeedbackMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        OutlinedButton(
            onClick = {
                val key = "$selectedPackage:${senderInput.trim()}"
                NotificationHelper.clearConversation(key)
                testFeedbackMessage = "Đã xóa lịch sử bộ nhớ đệm của đoạn chat '$key'."
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(text = "Reset Lịch Sử Đoạn Chat Này", fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
fun HistoryTabContent() {
    val context = LocalContext.current
    val capturedMessages by MessageRepository.messages.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var statusFilterSelection by remember { mutableStateOf("ALL") } // ALL, FORWARD, FILTER, BLOCK, NORMAL
    var showClearDialog by remember { mutableStateOf(false) }

    val filteredMessages = remember(capturedMessages, searchQuery, statusFilterSelection) {
        capturedMessages.filter { msg ->
            val matchesQuery = searchQuery.isBlank() ||
                    msg.senderOrTitle.contains(searchQuery, ignoreCase = true) ||
                    msg.messageContent.contains(searchQuery, ignoreCase = true) ||
                    msg.packageName.contains(searchQuery, ignoreCase = true)

            val matchesStatus = when (statusFilterSelection) {
                "FORWARD" -> msg.status == "FORWARD"
                "AI_DIGEST" -> msg.status == "AI_DIGEST"
                "FILTER" -> msg.status == "FILTER"
                "BLOCK" -> msg.status == "BLOCK"
                "NORMAL" -> msg.status == "NORMAL"
                else -> true
            }

            matchesQuery && matchesStatus
        }
    }

    val forwardCount = remember(capturedMessages) { capturedMessages.count { it.status == "FORWARD" } }
    val aiDigestCount = remember(capturedMessages) { capturedMessages.count { it.status == "AI_DIGEST" } }
    val filterCount = remember(capturedMessages) { capturedMessages.count { it.status == "FILTER" } }
    val blockCount = remember(capturedMessages) { capturedMessages.count { it.status == "BLOCK" } }
    val normalCount = remember(capturedMessages) { capturedMessages.count { it.status == "NORMAL" } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Quick Actions / Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${filteredMessages.size} logs",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (capturedMessages.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = { exportMessagesToMarkdown(context, filteredMessages) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(text = "Export (.md)", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { showClearDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(text = "Clear History", fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Status Filter Chips
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = statusFilterSelection == "ALL",
                onClick = { statusFilterSelection = "ALL" },
                label = { Text("All (${capturedMessages.size})", fontSize = 11.sp) }
            )
            FilterChip(
                selected = statusFilterSelection == "FORWARD",
                onClick = { statusFilterSelection = "FORWARD" },
                label = { Text("Forward ($forwardCount)", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF2E7D32), selectedLabelColor = Color.White)
            )
            FilterChip(
                selected = statusFilterSelection == "AI_DIGEST",
                onClick = { statusFilterSelection = "AI_DIGEST" },
                label = { Text("AI Digest ($aiDigestCount)", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF6A1B9A), selectedLabelColor = Color.White)
            )
            FilterChip(
                selected = statusFilterSelection == "FILTER",
                onClick = { statusFilterSelection = "FILTER" },
                label = { Text("Filter ($filterCount)", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF1976D2), selectedLabelColor = Color.White)
            )
            FilterChip(
                selected = statusFilterSelection == "BLOCK",
                onClick = { statusFilterSelection = "BLOCK" },
                label = { Text("Block ($blockCount)", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFC62828), selectedLabelColor = Color.White)
            )
            FilterChip(
                selected = statusFilterSelection == "NORMAL",
                onClick = { statusFilterSelection = "NORMAL" },
                label = { Text("Normal ($normalCount)", fontSize = 11.sp) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Search Filter
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search sender or message content...", fontSize = 12.sp) },
            singleLine = true,
            maxLines = 1,
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(6.dp))

        if (filteredMessages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (searchQuery.isNotBlank()) "No matching logs found." else "No notification history yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "All notifications from device apps will be recorded here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(filteredMessages, key = { it.id }) { msg ->
                    MessageItemCard(
                        message = msg,
                        onDelete = { MessageRepository.deleteMessage(msg.id) }
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear All History") },
            text = { Text("Are you sure you want to permanently delete all saved notification history?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        MessageRepository.clearAllHistory()
                        NotificationHelper.clearAllConversations()
                        showClearDialog = false
                    }
                ) {
                    Text("Delete All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

fun exportMessagesToMarkdown(context: Context, messages: List<CapturedMessage>) {
    if (messages.isEmpty()) {
        Toast.makeText(context, "Không có tin nhắn nào để xuất", Toast.LENGTH_SHORT).show()
        return
    }
    val sdf = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault())
    val fileDateSdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    val now = Date()
    val filename = "message_history_${fileDateSdf.format(now)}.md"

    val sb = StringBuilder()
    sb.append("# Lịch sử thông báo tin nhắn\n\n")
    sb.append("> Xuất lúc: ${sdf.format(now)} - Tổng cộng: ${messages.size} tin nhắn\n\n")
    sb.append("---\n\n")
    sb.append("| Thời gian | Ứng dụng | Người gửi / Nhóm | Trạng thái | Nội dung tin nhắn |\n")
    sb.append("| :--- | :--- | :--- | :---: | :--- |\n")

    for (msg in messages) {
        val timeStr = sdf.format(Date(msg.timestamp))
        val appName = NotificationHelper.getAppLabel(context, msg.packageName)
        val cleanContent = msg.messageContent.replace("|", "\\|").replace("\n", "<br>")
        val cleanSender = msg.senderOrTitle.replace("|", "\\|")
        sb.append("| $timeStr | $appName | $cleanSender | ${msg.status} | $cleanContent |\n")
    }

    val markdownContent = sb.toString()

    // 1. Save directly to public Downloads folder
    var savedUri: Uri? = null
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            savedUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (savedUri != null) {
                context.contentResolver.openOutputStream(savedUri)?.use { os ->
                    os.write(markdownContent.toByteArray(Charsets.UTF_8))
                }
            }
        } else {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists()) downloadDir.mkdirs()
            val file = File(downloadDir, filename)
            file.writeText(markdownContent, Charsets.UTF_8)
        }
        Toast.makeText(context, "Đã lưu vào thư mục Download:\n$filename", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Log.e("Export", "Lỗi lưu file: ${e.message}")
    }

    // 2. Open Share Sheet so user can send to Telegram, Zalo, Drive, etc.
    try {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, filename)
            putExtra(Intent.EXTRA_TEXT, markdownContent)
            if (savedUri != null) {
                putExtra(Intent.EXTRA_STREAM, savedUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        context.startActivity(Intent.createChooser(shareIntent, "Xuất lịch sử tin nhắn"))
    } catch (e: Exception) {
        Log.e("Export", "Lỗi mở share sheet: ${e.message}")
    }
}

@Composable
fun PermissionCard(
    title: String,
    description: String,
    isGranted: Boolean,
    actionLabel: String,
    onActionClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isGranted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!isGranted) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onActionClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(text = actionLabel, fontSize = 12.sp)
                }
            } else {
                Text(
                    text = "Granted",
                    color = Color(0xFF2E7D32),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
        }
    }
}

@Composable
fun MessageItemCard(
    message: CapturedMessage,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val cardBgColor = when (message.status) {
        "FORWARD" -> Color(0xFFE8F5E9) // Light Green
        "FILTER" -> Color(0xFFE3F2FD)  // Light Blue
        "BLOCK" -> Color(0xFFFFEBEE)   // Light Red
        else -> MaterialTheme.colorScheme.surface
    }

    val statusBadgeColor = when (message.status) {
        "FORWARD" -> Color(0xFF2E7D32)
        "FILTER" -> Color(0xFF1976D2)
        "BLOCK" -> Color(0xFFC62828)
        else -> Color.Gray
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                try {
                    // Clear conversation memory so future notifications start fresh
                    NotificationHelper.clearConversation("${message.packageName}:${message.senderOrTitle}")

                    // 1. Try to open the exact conversation using the cached PendingIntent from the original notification
                    val conversationIntent = NotificationHelper.getConversationIntent(message.packageName, message.senderOrTitle)
                    if (conversationIntent != null) {
                        try {
                            conversationIntent.send()
                            return@clickable
                        } catch (e: Exception) {
                            // If PendingIntent was invalidated, proceed to fallback
                        }
                    }

                    // 2. Fallback: Launch the app directly
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(message.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                    } else {
                        android.widget.Toast.makeText(context, "Không thể mở app: ${message.packageName}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "Lỗi khi mở app: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = statusBadgeColor,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = message.status,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = message.senderOrTitle,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = message.formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = message.messageContent,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                TextButton(
                    onClick = onDelete,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = "Delete",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
    return enabledPackages.contains(context.packageName)
}