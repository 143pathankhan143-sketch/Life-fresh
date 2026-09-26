package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.screens.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.border
import kotlinx.coroutines.delay
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.CRMViewModel
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.data.AppLanguageManager
import com.example.data.LocalAppLanguage
import com.example.data.LocalActiveLanguageMetadata
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavGraph.Companion.findStartDestination
import android.widget.Toast
import com.example.voice.VocalDestination
import com.example.voice.VoiceNavigator
import com.example.voice.VoiceTextKeys
import com.example.voice.android.AndroidVoiceTexts
import com.example.voice.android.VoiceAppController
import com.example.voice.android.VoiceConfirmBanner
import com.example.voice.android.VoiceEffectHandler
import com.example.voice.android.VoiceHandoff
import com.example.voice.android.VoiceMicButton

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        try {
            com.google.android.play.core.splitcompat.SplitCompat.installActivity(this)
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "SplitCompat.installActivity failed or skipped", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialMeta = AppLanguageManager.getActiveLanguageMetadata(this)
        AppLanguageManager.applyLocale(this, initialMeta)

        com.example.audio.ReminderScheduler.startChecking(applicationContext)

        handleIntent(intent)

        setContent {
            val viewModel: CRMViewModel = viewModel()
            val authViewModel: com.example.ui.viewmodel.AuthViewModel = viewModel()
            val isDark by viewModel.isDarkMode.collectAsStateWithLifecycle()
            val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
            val activeLanguageMeta by viewModel.activeLanguageMetadata.collectAsStateWithLifecycle()
            val currentUser by authViewModel.currentUser.collectAsStateWithLifecycle()
            val deletionNotice by authViewModel.deletionNotice.collectAsStateWithLifecycle()

            // Modern Android 13+ (API 33+) POST_NOTIFICATIONS runtime permission flow
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                android.util.Log.d("MainActivity", "POST_NOTIFICATIONS granted: $isGranted")
            }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            // Language change: recreate the activity so Compose re-resolves
            // EVERY string (and RTL) in the new locale from the first frame.
            // The old approach (applyLocale -> resources.updateConfiguration)
            // was invisible to Compose: screens kept the old language until an
            // unrelated touch/typing forced a recomposition, and the bottom
            // nav lagged one switch behind. recreate() is the same pattern
            // already used for Play language-pack installs below.
            var appliedLanguageMeta by remember { mutableStateOf(activeLanguageMeta) }
            LaunchedEffect(activeLanguageMeta) {
                if (appliedLanguageMeta != activeLanguageMeta) {
                    appliedLanguageMeta = activeLanguageMeta
                    this@MainActivity.recreate()
                }
            }

            val playInstallState by viewModel.playLanguageInstallState.collectAsStateWithLifecycle()
            LaunchedEffect(playInstallState) {
                if (playInstallState is com.example.data.PlayLanguageInstallState.Installed) {
                    viewModel.playLanguageDeliveryManager.resetState()
                    try {
                        com.google.android.play.core.splitcompat.SplitCompat.installActivity(this@MainActivity)
                    } catch (e: Throwable) {
                        android.util.Log.w("MainActivity", "SplitCompat.installActivity failed", e)
                    }
                    this@MainActivity.recreate()
                }
            }

            LaunchedEffect(deletionNotice) {
                val notice = deletionNotice
                if (!notice.isNullOrBlank()) {
                    android.widget.Toast.makeText(this@MainActivity, notice, android.widget.Toast.LENGTH_LONG).show()
                    authViewModel.clearDeletionNotice()
                }
            }

            CompositionLocalProvider(
                LocalAppLanguage provides appLanguage,
                LocalActiveLanguageMetadata provides activeLanguageMeta,
                LocalLayoutDirection provides if (activeLanguageMeta.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                MyApplicationTheme(darkTheme = isDark) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        var showSplash by rememberSaveable { mutableStateOf(true) }

                        if (showSplash) {
                            SplashScreen(onTimeout = { showSplash = false })
                        } else {
                            if (currentUser == null) {
                                val navController = androidx.navigation.compose.rememberNavController()
                                androidx.navigation.compose.NavHost(
                                    navController = navController,
                                    startDestination = "welcome"
                                ) {
                                    composable("welcome") {
                                        WelcomeScreen(
                                            authViewModel = authViewModel,
                                            onNavigateToLogin = { navController.navigate("login") },
                                            onNavigateToRegister = { navController.navigate("register") },
                                            onAuthSuccess = {}
                                        )
                                    }
                                    composable("login") {
                                        LoginScreen(
                                            authViewModel = authViewModel,
                                            onNavigateBack = { navController.popBackStack() },
                                            onNavigateToForgotPassword = { navController.navigate("forgot_password") },
                                            onAuthSuccess = {}
                                        )
                                    }
                                    composable("register") {
                                        RegisterScreen(
                                            authViewModel = authViewModel,
                                            onNavigateBack = { navController.popBackStack() },
                                            onAuthSuccess = {}
                                        )
                                    }
                                    composable("forgot_password") {
                                        ForgotPasswordScreen(
                                            authViewModel = authViewModel,
                                            onNavigateBack = { navController.popBackStack() }
                                        )
                                    }
                                }
                            } else {
                                MainScreen(viewModel = viewModel, authViewModel = authViewModel)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        val leadId = intent?.getStringExtra("ringing_lead_id")
        val ownerUid = intent?.getStringExtra("owner_uid")
        if (!leadId.isNullOrEmpty()) {
            lifecycleScope.launch {
                try {
                    val db = com.example.data.database.AppDatabase.getDatabase(this@MainActivity)
                    val activeUid = com.example.data.ActiveAccountStore.getActiveUid(this@MainActivity)
                    val targetUid = if (!ownerUid.isNullOrBlank()) ownerUid else activeUid

                    if (targetUid.isNotBlank() && targetUid == activeUid) {
                        val currentAuthUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                        if (currentAuthUid == null || currentAuthUid == activeUid) {
                            val lead = db.leadDao.getLeadById(leadId, activeUid)
                            if (lead != null && lead.ownerUid == activeUid) {
                                com.example.audio.ReminderScheduler.activeRingingLead.value = lead
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Error handling intent for lead $leadId", e)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        android.util.Log.d("APP_LIFECYCLE", "App moved to foreground")
    }

    override fun onStop() {
        super.onStop()
        android.util.Log.d("APP_LIFECYCLE", "App moved to background")
    }
}

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    var startAnimation by remember { mutableStateOf(false) }
    
    val alphaAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 1200, easing = LinearOutSlowInEasing),
        label = "alpha"
    )
    
    val scaleAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.85f,
        animationSpec = tween(durationMillis = 1200, easing = LinearOutSlowInEasing),
        label = "scale"
    )

    LaunchedEffect(Unit) {
        startAnimation = true
        kotlinx.coroutines.delay(2500)
        onTimeout()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF021204)) // Matching dark green background
            .testTag("splash_screen"),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.splash_screen_pro_v3),
            contentDescription = "LifeFresh Pro Splash Screen",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    alpha = alphaAnim
                ),
            contentScale = ContentScale.FillBounds
        )

        // Loading animation at the bottom
        androidx.compose.material3.CircularProgressIndicator(
            color = Color(0xFF4CAF50), // Premium LifeFresh green
            strokeWidth = 3.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp)
                .graphicsLayer(alpha = alphaAnim)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: CRMViewModel, authViewModel: com.example.ui.viewmodel.AuthViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val exactAlarmGranted by viewModel.isExactAlarmGrantedState.collectAsStateWithLifecycle()
    val showExactAlarmPrompt by viewModel.showExactAlarmPrompt.collectAsStateWithLifecycle()

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.updateExactAlarmStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val currentUser by authViewModel.currentUser.collectAsStateWithLifecycle()
    val pendingGuestOwnerUid by authViewModel.pendingGuestOwnerUid.collectAsStateWithLifecycle()
    var showGuestDataChoice by rememberSaveable { mutableStateOf(false) }
    var guestDataLeadCount by rememberSaveable { mutableStateOf(0) }
    var guestDataTransferBusy by rememberSaveable { mutableStateOf(false) }
    var guestDataTransferError by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(currentUser?.uid, currentUser?.isAnonymous, pendingGuestOwnerUid) {
        val user = currentUser
        when {
            user == null -> {
                showGuestDataChoice = false
                viewModel.resetCloudRestoreCheck()
            }
            user.isAnonymous -> {
                // If the user returned to Guest Mode, reconnect any guest workspace that was
                // intentionally kept separate or was left during a sign-in transition.
                viewModel.restorePreservedGuestDataIfNeeded(
                    newGuestOwnerUid = user.uid,
                    transitionGuestOwnerUid = pendingGuestOwnerUid
                ) { _, _ ->
                    if (!pendingGuestOwnerUid.isNullOrBlank()) {
                        authViewModel.clearPendingGuestTransition()
                    }
                }
                viewModel.resetCloudRestoreCheck()
            }
            !pendingGuestOwnerUid.isNullOrBlank() -> {
                val guestUid = pendingGuestOwnerUid ?: return@LaunchedEffect
                viewModel.getGuestLeadCount(guestUid) { count ->
                    guestDataLeadCount = count
                    guestDataTransferError = null
                    if (count > 0) {
                        showGuestDataChoice = true
                    } else {
                        showGuestDataChoice = false
                        authViewModel.clearPendingGuestTransition()
                        viewModel.checkForCloudBackup(user.uid)
                    }
                }
            }
            else -> {
                showGuestDataChoice = false
                viewModel.checkForCloudBackup(user.uid)
            }
        }
    }

    // Tab and Navigation controller using official Jetpack Navigation Compose APIs
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    var activeSettingsSubScreen by rememberSaveable { mutableStateOf<String?>(null) }

    val isMainTab = remember(currentRoute, activeSettingsSubScreen) {
        (currentRoute == "dashboard" || currentRoute == "leads" || currentRoute == "reports" || currentRoute == "settings" || currentRoute == "ai") && activeSettingsSubScreen == null
    }

    // Hide the bottom nav while the keyboard is up: the IME insets
    // (imePadding in chat/form screens) already push input fields above the
    // keyboard, and a visible nav bar would leave a dead gap between the
    // input field and the keyboard.
    // (Uses WindowInsets.ime.getBottom — WindowInsets.isImeVisible needs
    // Compose 1.9+, this project is on 1.7.)
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    val activeTab = remember(currentRoute) {
        when {
            currentRoute?.startsWith("dashboard") == true -> "dashboard"
            currentRoute?.startsWith("leads") == true ||
            currentRoute?.startsWith("profile") == true ||
            currentRoute?.startsWith("edit_lead") == true ||
            currentRoute?.startsWith("add_lead") == true -> "leads"
            currentRoute?.startsWith("ai") == true -> "ai"
            currentRoute?.startsWith("reports") == true -> "reports"
            currentRoute?.startsWith("settings") == true ||
            currentRoute?.startsWith("policy") == true -> "settings"
            else -> "dashboard"
        }
    }

    val navigateToTab = { tabRoute: String ->
        navController.navigate(tabRoute) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    // ---------------------------------------------------------------------
    //  Voice full-app control (M2): one mic button on every main tab.
    //  The rules live in com.example.voice (pure Kotlin, tested); this block
    //  only plays the script the loop returns and reports back what was heard.
    // ---------------------------------------------------------------------
    var voiceListening by remember { mutableStateOf(false) }
    var voiceSessionOn by remember { mutableStateOf(false) }
    var voiceConfirmPrompt by remember { mutableStateOf<String?>(null) }

    // Composables cannot see Activity members (applicationContext/lifecycleScope);
    // use the Compose-friendly equivalents already used elsewhere in this screen.
    val voiceScope = rememberCoroutineScope()
    val voiceLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()

    val voiceTexts = remember { AndroidVoiceTexts(context.applicationContext) { voiceLanguage.code } }
    val voiceHandler = remember {
        object : VoiceEffectHandler {
            override fun navigate(destination: VocalDestination) {
                navController.navigate(VoiceNavigator.routeFor(destination)) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }

            override fun back() {
                navController.popBackStack()
            }

            override fun askAI(prompt: String) {
                VoiceHandoff.post(prompt)
                navController.navigate(VoiceNavigator.ROUTE_AI) { launchSingleTop = true }
            }

            override fun showConfirmCard(prompt: String?) {
                voiceConfirmPrompt = prompt
            }

            override fun navAnnouncement(destination: VocalDestination): String? {
                if (!VoiceNavigator.announcesCounts(destination)) return null
                val activeLeads = viewModel.allLeadsList.value.filter { !it.archived && !it.isDraft }
                val pending = activeLeads.count { it.status.equals("Pending", ignoreCase = true) }
                return voiceTexts.get(
                    VoiceTextKeys.NAV_DONE,
                    voiceTexts.destinationName(destination)
                ) + " " + voiceTexts.get(VoiceTextKeys.NAV_COUNTS, pending, activeLeads.size)
            }

            override fun onSessionChanged(active: Boolean) {
                voiceSessionOn = active
                if (!active) voiceConfirmPrompt = null
            }

            override fun onListeningChanged(listening: Boolean) {
                voiceListening = listening
            }

            override fun onSpeakingChanged(speaking: Boolean) {
                // The mic button shows listening state only; speaking state is
                // reported by AiVoicePlayer itself.
            }
        }
    }

    val voiceController = remember {
        VoiceAppController(
            context = context.applicationContext,
            texts = voiceTexts,
            handler = voiceHandler,
            scope = voiceScope
        )
    }

    DisposableEffect(Unit) {
        onDispose { voiceController.shutdown() }
    }

    // The chat screen has its own mic and Bolo mode, and sub-screens/dialogs
    // should never sit under an open voice session.
    LaunchedEffect(isMainTab, activeTab) {
        if (!isMainTab || activeTab == "ai") voiceController.cancelSession()
    }

    val voiceMicPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceController.toggleMic()
        } else {
            Toast.makeText(
                context,
                "Mic permission chahiye - Settings me 'Record audio' allow karo.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val onVoiceMicTap: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) voiceController.toggleMic()
        else voiceMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val allLeads by viewModel.allLeadsList.collectAsStateWithLifecycle()

    // Alarm triggers observed from background poll routines
    val ringingLead by viewModel.ringingLead.collectAsStateWithLifecycle()
    val alarmTitle by viewModel.alarmModalTitle.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = if (isMainTab) {
            {
                TopAppBar(
                    title = {
                        val gradientBrush = remember {
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = listOf(
                                    androidx.compose.ui.graphics.Color(0xFF2E7D32),
                                    androidx.compose.ui.graphics.Color(0xFF4CAF50)
                                )
                            )
                        }

                        Text(
                            text = "LifeFresh QuickNote Pro",
                            fontWeight = FontWeight.Black,
                            style = MaterialTheme.typography.titleLarge.copy(
                                letterSpacing = (-0.5).sp,
                                fontSize = 22.sp,
                                brush = gradientBrush
                            )
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.testTag("app_header")
                )
            }
        } else {
            {}
        },
        bottomBar = if (isMainTab && !imeVisible) {
            {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.testTag("bottom_nav_bar")
                ) {
                    NavigationBarItem(
                        selected = (activeTab == "dashboard"),
                        onClick = { navigateToTab("dashboard") },
                        label = {
                            Text(
                                text = stringResource(R.string.nav_dashboard),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                softWrap = false,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.wrapContentWidth(unbounded = true)
                            )
                        },
                        icon = { Icon(Icons.Default.Analytics, contentDescription = stringResource(R.string.nav_dashboard), modifier = Modifier.size(20.dp)) },
                        modifier = Modifier.testTag("nav_item_dashboard").padding(horizontal = 1.dp)
                    )
                    NavigationBarItem(
                        selected = (activeTab == "leads"),
                        onClick = { navigateToTab("leads") },
                        label = {
                            Text(
                                text = stringResource(R.string.nav_leads),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                softWrap = false,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.wrapContentWidth(unbounded = true)
                            )
                        },
                        icon = { Icon(Icons.Default.People, contentDescription = stringResource(R.string.nav_leads), modifier = Modifier.size(20.dp)) },
                        modifier = Modifier.testTag("nav_item_leads").padding(horizontal = 1.dp)
                    )
                    NavigationBarItem(
                        selected = (activeTab == "ai"),
                        onClick = { navigateToTab("ai") },
                        label = {
                            Text(
                                text = stringResource(R.string.nav_ai),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                softWrap = false,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.wrapContentWidth(unbounded = true)
                            )
                        },
                        icon = { Icon(Icons.Default.AutoAwesome, contentDescription = stringResource(R.string.nav_ai), modifier = Modifier.size(20.dp)) },
                        modifier = Modifier.testTag("nav_item_ai").padding(horizontal = 1.dp)
                    )
                    NavigationBarItem(
                        selected = (activeTab == "reports"),
                        onClick = { navigateToTab("reports") },
                        label = {
                            Text(
                                text = stringResource(R.string.nav_reports),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                softWrap = false,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.wrapContentWidth(unbounded = true)
                            )
                        },
                        icon = { Icon(Icons.Default.Assessment, contentDescription = stringResource(R.string.nav_reports), modifier = Modifier.size(20.dp)) },
                        modifier = Modifier.testTag("nav_item_reports").padding(horizontal = 1.dp)
                    )
                    NavigationBarItem(
                        selected = (activeTab == "settings"),
                        onClick = { navigateToTab("settings") },
                        label = {
                            Text(
                                text = stringResource(R.string.nav_settings),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                softWrap = false,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.wrapContentWidth(unbounded = true)
                            )
                        },
                        icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings), modifier = Modifier.size(20.dp)) },
                        modifier = Modifier.testTag("nav_item_settings").padding(horizontal = 1.dp)
                    )
                }
            }
        } else {
            {}
        },
        snackbarHost = {
            // Spoken confirmations are echoed here: above the nav bar, in front
            // of the screen content, and gone the moment the gate closes.
            VoiceConfirmBanner(prompt = voiceConfirmPrompt)
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Add-lead FAB stays a Leads-panel affordance.
                if (isMainTab && activeTab == "leads") {
                    FloatingActionButton(
                        onClick = { navController.navigate("add_lead") },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White,
                        modifier = Modifier.testTag("btn_fab_add_lead")
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(R.string.cd_add_customer))
                    }
                }
                // Global voice entry (M2): every main tab except the AI chat,
                // which already has its own mic + Bolo mode.
                if (isMainTab && activeTab != "ai") {
                    VoiceMicButton(
                        listening = voiceListening,
                        sessionActive = voiceSessionOn,
                        onTap = onVoiceMicTap
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            NavHost(
                navController = navController,
                startDestination = "dashboard"
            ) {
                composable("dashboard") {
                    DashboardTab(
                        viewModel = viewModel,
                        authViewModel = authViewModel,
                        onViewLeadProfile = { lead ->
                            navController.navigate("profile/${lead.id}")
                        }
                    )
                }
                composable("leads") {
                    LeadsTab(
                        viewModel = viewModel,
                        onEditLead = { lead ->
                            navController.navigate("edit_lead/${lead.id}")
                        },
                        onAddLeadTrigger = {
                            navController.navigate("add_lead")
                        },
                        onViewLeadProfile = { lead ->
                            navController.navigate("profile/${lead.id}")
                        }
                    )
                }

                composable("ai") {
                    AIScreen(
                        viewModel = viewModel,
                        onExit = {
                            navController.popBackStack()
                        },
                        onAddLeadTrigger = {
                            navController.navigate("add_lead")
                        },
                        onNavigateToSettings = {
                            navController.navigate("settings")
                        }
                    )
                }

                composable("reports") {
                    ReportsTab(viewModel = viewModel)
                }
                composable("settings") {
                    SettingsTab(
                        viewModel = viewModel,
                        authViewModel = authViewModel,
                        onOpenPolicy = { policyType ->
                            navController.navigate("policy/$policyType")
                        },
                        onRequestAuthentication = {
                            activeSettingsSubScreen = null
                            authViewModel.beginAccountSignInFromGuest()
                        },
                        activeSubScreenParam = activeSettingsSubScreen,
                        onActiveSubScreenChange = { activeSettingsSubScreen = it }
                    )
                }
                dialog("profile/{leadId}") { backStackEntry ->
                    val leadId = backStackEntry.arguments?.getString("leadId")
                    val lead = allLeads.find { it.id == leadId }
                    if (lead != null) {
                        ClientProfileDialog(
                            lead = lead,
                            viewModel = viewModel,
                            onDismiss = { navController.popBackStack() },
                            onEditClick = {
                                navController.navigate("edit_lead/${lead.id}")
                            }
                        )
                    }
                }
                dialog("edit_lead/{leadId}") { backStackEntry ->
                    val leadId = backStackEntry.arguments?.getString("leadId")
                    val lead = allLeads.find { it.id == leadId }
                    if (lead != null) {
                        LeadFormDialog(
                            lead = lead,
                            viewModel = viewModel,
                            onDismiss = { navController.popBackStack() }
                        )
                    }
                }
                dialog("add_lead") {
                    LeadFormDialog(
                        lead = null,
                        viewModel = viewModel,
                        onDismiss = { navController.popBackStack() }
                    )
                }
                dialog("policy/{type}") { backStackEntry ->
                    val type = backStackEntry.arguments?.getString("type") ?: ""
                    PolicyDialog(
                        type = type,
                        onDismiss = { navController.popBackStack() }
                    )
                }
            }
        }
    }

    // --- DIALOG MODAL LAYOUT OVERLAYS ---

    // Guest-to-account transition dialog. Guest data is never merged silently.
    if (showGuestDataChoice && currentUser != null && currentUser?.isAnonymous == false) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    text = stringResource(R.string.main_guest_data_found),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.main_guest_data_count, guestDataLeadCount)
                    )
                    Text(
                        text = stringResource(R.string.main_guest_data_choose),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!guestDataTransferError.isNullOrBlank()) {
                        Text(
                            text = guestDataTransferError ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (guestDataTransferBusy) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val guestUid = pendingGuestOwnerUid ?: return@Button
                        val accountUid = currentUser?.uid ?: return@Button
                        guestDataTransferBusy = true
                        guestDataTransferError = null
                        viewModel.moveGuestDataToAccount(guestUid, accountUid) { success, message ->
                            guestDataTransferBusy = false
                            if (success) {
                                showGuestDataChoice = false
                                authViewModel.clearPendingGuestTransition()
                                viewModel.checkForCloudBackup(accountUid)
                                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                guestDataTransferError = message
                            }
                        }
                    },
                    enabled = !guestDataTransferBusy,
                    modifier = Modifier.testTag("btn_move_guest_data")
                ) {
                    Text(stringResource(R.string.main_move_sync))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        val guestUid = pendingGuestOwnerUid ?: return@OutlinedButton
                        val accountUid = currentUser?.uid ?: return@OutlinedButton
                        viewModel.keepGuestDataSeparate(guestUid)
                        showGuestDataChoice = false
                        guestDataTransferError = null
                        authViewModel.clearPendingGuestTransition()
                        viewModel.checkForCloudBackup(accountUid)
                    },
                    enabled = !guestDataTransferBusy,
                    modifier = Modifier.testTag("btn_keep_guest_data_separate")
                ) {
                    Text(stringResource(R.string.main_keep_guest))
                }
            },
            modifier = Modifier.testTag("guest_data_transition_dialog")
        )
    }

    // Cloud Restore Dialog overlay
    val showCloudRestoreDialog by viewModel.showCloudRestoreDialog.collectAsStateWithLifecycle()
    if (showCloudRestoreDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.skipCloudRestore() },
            title = {
                Text(text = stringResource(R.string.main_backup_found), fontWeight = FontWeight.Bold)
            },
            text = {
                Text(text = stringResource(R.string.main_backup_restore_prompt))
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.performCloudRestore() },
                    modifier = Modifier.testTag("btn_restore_confirm")
                ) {
                    Text(stringResource(R.string.main_restore))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.skipCloudRestore() },
                    modifier = Modifier.testTag("btn_restore_skip")
                ) {
                    Text(stringResource(R.string.main_skip))
                }
            },
            modifier = Modifier.testTag("cloud_restore_dialog")
        )
    }

    // 1. Alarm Ringing full modal overlay
    val rLead = ringingLead
    if (rLead != null) {
        AlarmRingingDialog(lead = rLead, title = alarmTitle, viewModel = viewModel)
    }

    // 5. Exact Alarm Permission onboarding Dialog (shown only when requested at point of need)
    if (showExactAlarmPrompt && !exactAlarmGranted) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissExactAlarmPrompt() },
            title = {
                Text(text = stringResource(R.string.main_alarms_disabled), fontWeight = FontWeight.Bold)
            },
            text = {
                Text(text = stringResource(R.string.main_alarms_disabled_sub))
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.dismissExactAlarmPrompt()
                        android.util.Log.d("EXACT_ALARM_SETTINGS_OPENED", "User clicked Enable Exact Alarms, opening system settings")
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                    data = android.net.Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                try {
                                    val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                                    context.startActivity(intent)
                                } catch (ex: Exception) {
                                    android.util.Log.e("EXACT_ALARM", "Failed to open settings", ex)
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("btn_enable_exact_alarm")
                ) {
                    Text(stringResource(R.string.main_enable_exact))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissExactAlarmPrompt() },
                    modifier = Modifier.testTag("btn_dismiss_exact_alarm")
                ) {
                    Text(stringResource(R.string.main_remind_later))
                }
            },
            modifier = Modifier.testTag("exact_alarm_onboarding_dialog")
        )
    }

    // 6. Changelog / What's New Dialog
    val showChangelog by viewModel.showChangelogDialog.collectAsStateWithLifecycle()
    if (showChangelog) {
        ChangelogDialog(viewModel = viewModel)
    }
}

@Composable
fun ChangelogDialog(viewModel: com.example.ui.viewmodel.CRMViewModel) {
    AlertDialog(
        onDismissRequest = { viewModel.dismissChangelog() },
        title = {
            Column {
                Text(
                    text = stringResource(R.string.what_new_heading),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val features = listOf(
                    "Refined premium design across Dashboard, Leads, Reports and Settings.",
                    "Added secure Cloud Backup, Cloud Restore and Automatic Sync controls.",
                    "Improved Lead management with cleaner quick actions, Archive/Restore and safer Delete confirmation.",
                    "Enhanced Client Profiles with compact reminders, coaching notes and clearer activity information.",
                    "Added professional CRM PDF report download, sharing and an easy report guide.",
                    "Improved Alarm Settings with instant save feedback and clearer permission status.",
                    "Improved navigation, Light/Dark theme readability, accessibility and overall stability."
                )
                
                features.forEach { feature ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "✓",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = feature,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { viewModel.dismissChangelog() },
                modifier = Modifier.testTag("btn_close_changelog")
            ) {
                Text(stringResource(R.string.common_close))
            }
        },
        modifier = Modifier.testTag("changelog_dialog")
    )
}
