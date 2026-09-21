package com.example.ui.viewmodel

import com.example.BuildConfig
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.example.audio.AlarmSynthesizer
import com.example.audio.ReminderScheduler
import com.example.data.database.AppDatabase
import com.example.data.database.LeadEntity
import com.example.data.database.AIChatSessionEntity
import com.example.data.database.AIChatMessageEntity
import com.example.data.repository.LeadRepository
import com.example.data.repository.AIChatRepository
import com.example.ui.screens.Sender
import com.example.ui.screens.MockMessage
import com.example.ui.screens.ChatSession
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

class CRMViewModel(application: Application, private val savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("CRMViewModel", "Caught unhandled coroutine exception: ${throwable.message}", throwable)
    }

    private val sharedPrefs: SharedPreferences =
        application.getSharedPreferences("lifefresh_prefs", Context.MODE_PRIVATE)
    private val preservedGuestOwnerKey = "preserved_guest_owner_uid"

    private val database = AppDatabase.getDatabase(application)
    private val leadSyncMutationCoordinator = com.example.sync.LeadSyncMutationCoordinator(
        context = application,
        database = database,
        leadDao = database.leadDao,
        metadataDao = database.leadSyncMetadataDao,
        syncDao = database.syncDao
    )
    private val repository = LeadRepository(database.leadDao, leadSyncMutationCoordinator)
    val aiChatRepository = AIChatRepository(database.aiChatDao)

    private val _currentUidFlow = MutableStateFlow<String?>(FirebaseAuth.getInstance().currentUser?.uid)
    val currentUidFlow: StateFlow<String?> = _currentUidFlow.asStateFlow()

    init {
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            val newUid = auth.currentUser?.uid
            if (newUid != _currentUidFlow.value) {
                _currentUidFlow.value = newUid
                if (newUid == null) {
                    clearInMemoryStateOnSignOut()
                }
            }
        }
    }

    private suspend fun claimLegacyLeadsIfNecessary(uid: String) {
        if (uid.isBlank()) return
        val isClaimed = sharedPrefs.getBoolean("legacy_unowned_leads_claimed", false)
        if (!isClaimed) {
            try {
                repository.claimUnownedLeads(uid)
                sharedPrefs.edit().putBoolean("legacy_unowned_leads_claimed", true).apply()
            } catch (e: Exception) {
                android.util.Log.e("CRMViewModel", "Error claiming legacy unowned leads", e)
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val accountScopedLeadsFlow: Flow<List<LeadEntity>> = _currentUidFlow.flatMapLatest { uid ->
        if (uid.isNullOrBlank()) {
            flowOf(emptyList())
        } else {
            flow {
                claimLegacyLeadsIfNecessary(uid)
                emitAll(repository.getAllLeads(uid))
            }
        }
    }

    fun getGuestLeadCount(guestOwnerUid: String, onComplete: (Int) -> Unit) {
        if (guestOwnerUid.isBlank()) {
            onComplete(0)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val count = runCatching { database.leadDao.getAllLeadsList(guestOwnerUid).size }.getOrDefault(0)
            withContext(Dispatchers.Main) { onComplete(count) }
        }
    }

    fun keepGuestDataSeparate(guestOwnerUid: String) {
        if (guestOwnerUid.isBlank()) return
        sharedPrefs.edit().putString(preservedGuestOwnerKey, guestOwnerUid).apply()
    }

    fun moveGuestDataToAccount(
        guestOwnerUid: String,
        targetOwnerUid: String,
        onComplete: (Boolean, String) -> Unit
    ) {
        if (guestOwnerUid.isBlank() || targetOwnerUid.isBlank() || guestOwnerUid == targetOwnerUid) {
            onComplete(false, "Guest or account identity is invalid.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val guestLeads = database.leadDao.getAllLeadsList(guestOwnerUid)
                if (guestLeads.isEmpty()) {
                    sharedPrefs.edit().remove(preservedGuestOwnerKey).apply()
                    withContext(Dispatchers.Main) { onComplete(true, "No guest data needed to be moved.") }
                    return@launch
                }

                ReminderScheduler.cancelAllRemindersForUser(getApplication(), guestOwnerUid)

                val movedLeads = guestLeads.map { lead ->
                    val existing = database.leadDao.getLeadById(lead.id, targetOwnerUid)
                    if (existing == null) {
                        lead.copy(ownerUid = targetOwnerUid)
                    } else {
                        lead.copy(
                            ownerUid = targetOwnerUid,
                            id = java.util.UUID.randomUUID().toString()
                        )
                    }
                }

                // Route through the repository so authenticated writes enter the normal outbox/sync path.
                repository.insertLeads(movedLeads)
                database.leadDao.clearLeadsForUser(guestOwnerUid)
                ReminderScheduler.rescheduleAllReminders(getApplication(), movedLeads)
                sharedPrefs.edit().remove(preservedGuestOwnerKey).apply()

                withContext(Dispatchers.Main) {
                    onComplete(true, "Moved ${movedLeads.size} guest lead${if (movedLeads.size == 1) "" else "s"} to your account.")
                }
            } catch (e: Exception) {
                android.util.Log.e("GuestDataTransfer", "Failed to move guest data", e)
                withContext(Dispatchers.Main) {
                    onComplete(false, e.localizedMessage ?: "Failed to move guest data to the signed-in account.")
                }
            }
        }
    }

    fun restorePreservedGuestDataIfNeeded(
        newGuestOwnerUid: String,
        transitionGuestOwnerUid: String? = null,
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        if (newGuestOwnerUid.isBlank()) {
            onComplete?.invoke(false, "Guest identity is invalid.")
            return
        }

        val sourceOwnerUid = transitionGuestOwnerUid?.takeIf { it.isNotBlank() }
            ?: sharedPrefs.getString(preservedGuestOwnerKey, null)

        if (sourceOwnerUid.isNullOrBlank() || sourceOwnerUid == newGuestOwnerUid) {
            onComplete?.invoke(true, "Guest workspace is ready.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val oldGuestLeads = database.leadDao.getAllLeadsList(sourceOwnerUid)
                if (oldGuestLeads.isEmpty()) {
                    sharedPrefs.edit().remove(preservedGuestOwnerKey).apply()
                    withContext(Dispatchers.Main) { onComplete?.invoke(true, "Guest workspace is ready.") }
                    return@launch
                }

                ReminderScheduler.cancelAllRemindersForUser(getApplication(), sourceOwnerUid)
                val restoredLeads = oldGuestLeads.map { it.copy(ownerUid = newGuestOwnerUid) }
                // Guest sessions stay local-only, so write directly to Room and do not enqueue cloud mutations.
                database.leadDao.insertLeads(restoredLeads)
                database.leadDao.clearLeadsForUser(sourceOwnerUid)
                ReminderScheduler.rescheduleAllReminders(getApplication(), restoredLeads)
                sharedPrefs.edit().remove(preservedGuestOwnerKey).apply()

                withContext(Dispatchers.Main) {
                    onComplete?.invoke(true, "Restored ${restoredLeads.size} guest lead${if (restoredLeads.size == 1) "" else "s"} on this device.")
                }
            } catch (e: Exception) {
                android.util.Log.e("GuestDataTransfer", "Failed to restore preserved guest data", e)
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false, e.localizedMessage ?: "Failed to restore guest data.")
                }
            }
        }
    }

    fun clearInMemoryStateOnSignOut() {
        _showCloudRestoreDialog.value = false
        pendingCloudLeads = emptyList()
        hasCheckedCloudBackupForUser = null
        lastUserId = null
        _lastSyncTime.value = null
        _totalCloudCustomers.value = null
        ringingLead.value = null
        searchQuery.value = ""
        currentFilter.value = "all"
        setActiveSession(null)
        dismissActiveAlarm()
    }

    private val leadOperationService by lazy {
        com.example.leads.operation.LeadOperationService(
            context = application,
            repository = repository
        )
    }

    val activeSessionId = savedStateHandle.getStateFlow<String?>("active_session_id", null)

    fun setActiveSession(sessionId: String?) {
        savedStateHandle["active_session_id"] = sessionId
    }

    /**
     * One-shot, deterministic read of the active session's messages from Room.
     *
     * Goes straight to the database (no shared flow, no WhileSubscribed caching),
     * so it always returns the current rows even right after process death.
     * Used by the AI chat view model to restore the last conversation.
     */
    suspend fun loadActiveSessionMessagesOnce(): List<MockMessage> {
        val uid = _currentUidFlow.value ?: FirebaseAuth.getInstance().currentUser?.uid
        val sessionId = activeSessionId.value
        if (uid.isNullOrBlank() || sessionId.isNullOrBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            aiChatRepository.getMessagesForSessionList(uid, sessionId)
        }.map { msg ->
            MockMessage(
                id = msg.id,
                text = msg.text,
                sender = if (msg.sender == "USER") Sender.USER else Sender.AI,
                timestamp = msg.timestamp,
                isError = msg.isError,
                isOfflineWarning = msg.isOfflineWarning,
                isConfirmation = msg.isConfirmation,
                actionCardType = msg.actionCardType
            )
        }.sortedBy { it.timestamp }
    }

    /**
     * Returns the active chat session id, creating a new Room session first if
     * none is active. Safe to call on every message; returns null when the user
     * is not signed in or AI features are disabled (nothing is persisted then).
     *
     * The session insert happens synchronously (on IO) before the id is
     * returned, so callers can immediately write messages without a foreign-key
     * race against the sessions table.
     */
    suspend fun ensureActiveSession(titleHint: String? = null): String? {
        if (!BuildConfig.AI_FEATURES_ENABLED) return null
        val uid = _currentUidFlow.value ?: FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) return null
        val existing = activeSessionId.value
        if (!existing.isNullOrBlank()) return existing
        val newId = "session_${System.currentTimeMillis()}"
        val hint = titleHint?.trim()?.takeIf { it.isNotEmpty() }
        val title = if (hint != null) {
            if (hint.length > 25) hint.substring(0, 22) + "..." else hint
        } else {
            "New Chat"
        }
        withContext(Dispatchers.IO) {
            aiChatRepository.insertSession(
                AIChatSessionEntity(
                    ownerUid = uid,
                    id = newId,
                    title = title,
                    createdTimestamp = System.currentTimeMillis(),
                    updatedTimestamp = System.currentTimeMillis(),
                    isPinned = false
                )
            )
        }
        setActiveSession(newId)
        return newId
    }

    init {
        if (BuildConfig.AI_FEATURES_ENABLED) {
            viewModelScope.launch {
                val uid = _currentUidFlow.value ?: FirebaseAuth.getInstance().currentUser?.uid
                val sessionId = activeSessionId.value
                if (uid != null && sessionId != null) {
                    val exists = aiChatRepository.getSessionById(uid, sessionId)
                    if (exists == null) {
                        setActiveSession(null)
                    }
                } else if (uid == null) {
                    setActiveSession(null)
                }
            }
        } else {
            setActiveSession(null)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val dbChatSessions: StateFlow<List<ChatSession>> = _currentUidFlow
        .flatMapLatest { uid ->
            if (uid.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                aiChatRepository.getAllSessionsWithMessages(uid).map { list ->
                    list.map { swm ->
                        ChatSession(
                            id = swm.session.id,
                            title = swm.session.title,
                            messages = swm.messages.map { msg ->
                                MockMessage(
                                    id = msg.id,
                                    text = msg.text,
                                    sender = if (msg.sender == "USER") Sender.USER else Sender.AI,
                                    timestamp = msg.timestamp,
                                    isError = msg.isError,
                                    isOfflineWarning = msg.isOfflineWarning,
                                    isConfirmation = msg.isConfirmation,
                                    actionCardType = msg.actionCardType
                                )
                            }.sortedBy { it.timestamp },
                            timestamp = swm.session.updatedTimestamp,
                            isPinned = swm.session.isPinned
                        )
                    }
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun createSession(sessionId: String, title: String) {
        if (!BuildConfig.AI_FEATURES_ENABLED) return
        val uid = _currentUidFlow.value ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (uid.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val session = AIChatSessionEntity(
                ownerUid = uid,
                id = sessionId,
                title = title,
                createdTimestamp = System.currentTimeMillis(),
                updatedTimestamp = System.currentTimeMillis(),
                isPinned = false
            )
            aiChatRepository.insertSession(session)
        }
    }

    fun saveMessage(sessionId: String, message: MockMessage) {
        if (!BuildConfig.AI_FEATURES_ENABLED) return
        val uid = _currentUidFlow.value ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (uid.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val msgEntity = AIChatMessageEntity(
                ownerUid = uid,
                id = message.id,
                sessionId = sessionId,
                text = message.text,
                sender = message.sender.name,
                timestamp = message.timestamp,
                isError = message.isError,
                isOfflineWarning = message.isOfflineWarning,
                isConfirmation = message.isConfirmation,
                actionCardType = message.actionCardType
            )
            aiChatRepository.insertMessage(msgEntity)
            aiChatRepository.updateSessionTimestamp(uid, sessionId)
        }
    }

    fun updateSessionPin(sessionId: String, isPinned: Boolean) {
        if (!BuildConfig.AI_FEATURES_ENABLED) return
        val uid = _currentUidFlow.value ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (uid.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            aiChatRepository.updateSessionPinStatus(uid, sessionId, isPinned)
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        if (!BuildConfig.AI_FEATURES_ENABLED) return
        val uid = _currentUidFlow.value ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (uid.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            aiChatRepository.updateSessionTitle(uid, sessionId, newTitle)
        }
    }

    fun deleteSession(sessionId: String) {
        if (!BuildConfig.AI_FEATURES_ENABLED) return
        val uid = _currentUidFlow.value ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (uid.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            aiChatRepository.deleteSessionById(uid, sessionId)
        }
    }

    // Application state
    val searchQuery = MutableStateFlow("")
    val currentFilter = MutableStateFlow("all") // "all", "drafts", "pending", "complete", "archived", "rem-today", "rem-upcoming", "rem-overdue", "rem-all"

    // Theme state
    val isDarkMode = MutableStateFlow(sharedPrefs.getBoolean("lifefresh_theme", false))

    // App Language state
    val appLanguage = MutableStateFlow(com.example.data.AppLanguageManager.getLanguage(application))
    val activeLanguageMetadata = MutableStateFlow(com.example.data.AppLanguageManager.getActiveLanguageMetadata(application))
    val languagePacks: StateFlow<List<com.example.data.LanguagePackMetadata>> = com.example.data.LanguagePackManager.languagePacksFlow

    val playLanguageDeliveryManager = com.example.data.PlayLanguageDeliveryManager.getInstance(application)
    val playLanguageInstallState: StateFlow<com.example.data.PlayLanguageInstallState> = playLanguageDeliveryManager.installState

    fun setAppLanguage(language: com.example.data.AppLanguage) {
        com.example.data.AppLanguageManager.setLanguage(getApplication(), language)
        appLanguage.value = language
        activeLanguageMetadata.value = language.toMetadata()
    }

    fun selectOrInstallLanguage(
        language: com.example.data.AppLanguage,
        onInstallInitiated: ((Int) -> Unit)? = null,
        onError: ((Exception) -> Unit)? = null
    ) {
        if (playLanguageDeliveryManager.isLanguageAvailable(language)) {
            setAppLanguage(language)
        } else {
            playLanguageDeliveryManager.requestInstallLanguage(
                language = language,
                onSuccess = { sessionId -> onInstallInitiated?.invoke(sessionId) },
                onError = { ex -> onError?.invoke(ex) }
            )
        }
    }

    fun cancelLanguageInstall(sessionId: Int) {
        playLanguageDeliveryManager.cancelInstall(sessionId)
    }

    fun resetPlayLanguageInstallState() {
        playLanguageDeliveryManager.resetState()
    }

    fun setAppLanguage(metadata: com.example.data.LanguagePackMetadata) {
        com.example.data.AppLanguageManager.setLanguage(getApplication(), metadata)
        appLanguage.value = com.example.data.AppLanguage.fromCode(metadata.code)
        activeLanguageMetadata.value = metadata
    }

    fun downloadLanguagePack(
        metadata: com.example.data.LanguagePackMetadata,
        onResult: (Boolean, String?) -> Unit
    ) {
        com.example.data.LanguagePackManager.downloadPack(
            context = getApplication(),
            packMetadata = metadata,
            onResult = onResult
        )
    }

    fun removeLanguagePack(metadata: com.example.data.LanguagePackMetadata): Boolean {
        val success = com.example.data.LanguagePackManager.removePack(getApplication(), metadata.code)
        if (success) {
            val currentActiveCode = com.example.data.AppLanguageManager.getLanguageCode(getApplication())
            appLanguage.value = com.example.data.AppLanguage.fromCode(currentActiveCode)
            activeLanguageMetadata.value = com.example.data.AppLanguageManager.getActiveLanguageMetadata(getApplication())
        }
        return success
    }

    // Alarm settings states
    val alarmUseCustom = MutableStateFlow(sharedPrefs.getBoolean("alarm_use_custom", false))
    val alarmSound = MutableStateFlow(
        sharedPrefs.getString("alarm_sound", null)?.let { saved ->
            if (saved in listOf("classic", "bell", "notification", "digital", "gentle")) "holiday" else saved
        } ?: "holiday"
    )
    val alarmVolume = MutableStateFlow(sharedPrefs.getString("alarm_volume", "medium") ?: "medium")
    val customAudioFilename = MutableStateFlow(sharedPrefs.getString("alarm_custom_filename", "No file selected") ?: "No file selected")
    val reminderRingMode = MutableStateFlow(sharedPrefs.getString("reminder_ring_mode", "continuous") ?: "continuous")

    // Alarm ringing state
    val ringingLead = MutableStateFlow<LeadEntity?>(null)
    val isExactAlarmGrantedState = MutableStateFlow(false)
    val showExactAlarmPrompt = MutableStateFlow(false)
    val alarmModalTitle = MutableStateFlow("")
    private val triggeredOverdueIds = mutableSetOf<String>()

    // Authoritative reactive account-scoped leads list
    val allLeadsList: StateFlow<List<LeadEntity>> = accountScopedLeadsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Testing alarm state in settings
    val isTestingAlarm = MutableStateFlow(false)

    // Changelog state
    val showChangelogDialog = MutableStateFlow(false)

    fun checkAndShowChangelog() {
        val lastViewedVersion = sharedPrefs.getString("last_viewed_version", null)
        val currentVersion = com.example.BuildConfig.VERSION_NAME
        if (lastViewedVersion != currentVersion) {
            showChangelogDialog.value = true
        }
    }

    fun forceShowChangelog() {
        showChangelogDialog.value = true
    }

    fun dismissChangelog() {
        showChangelogDialog.value = false
        sharedPrefs.edit().putString("last_viewed_version", com.example.BuildConfig.VERSION_NAME).apply()
    }

    // Cloud Restore Dialog State
    private val _showCloudRestoreDialog = MutableStateFlow(false)
    val showCloudRestoreDialog = _showCloudRestoreDialog.asStateFlow()

    private val _lastSyncTime = MutableStateFlow<String?>(sharedPrefs.getString("cloud_last_sync_time", null))
    val lastSyncTime = _lastSyncTime.asStateFlow()

    private val _totalCloudCustomers = MutableStateFlow<Int?>(if (sharedPrefs.contains("cloud_total_customers")) sharedPrefs.getInt("cloud_total_customers", 0) else null)
    val totalCloudCustomers = _totalCloudCustomers.asStateFlow()

    fun setLastSyncTime(time: String?) {
        if (time != null) {
            sharedPrefs.edit().putString("cloud_last_sync_time", time).apply()
        } else {
            sharedPrefs.edit().remove("cloud_last_sync_time").apply()
        }
        _lastSyncTime.value = time
    }

    fun setTotalCloudCustomers(count: Int?) {
        if (count != null) {
            sharedPrefs.edit().putInt("cloud_total_customers", count).apply()
        } else {
            sharedPrefs.edit().remove("cloud_total_customers").apply()
        }
        _totalCloudCustomers.value = count
    }

    fun backupAllToCloud(onComplete: (Boolean, String) -> Unit) {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null || currentUser.isAnonymous || currentUser.uid.isBlank()) {
            onComplete(false, "Cloud backup is available for authenticated non-anonymous users only.")
            return
        }
        val uid = currentUser.uid
        val syncRepo = com.example.sync.SyncRuntimeFactory.getSyncRepository(getApplication())

        viewModelScope.launch(Dispatchers.IO) {
            val leads = database.leadDao.getAllLeadsList(uid)
            if (leads.isEmpty()) {
                withContext(Dispatchers.Main) {
                    onComplete(true, "No leads to backup.")
                }
                return@launch
            }

            for (lead in leads) {
                val meta = database.leadSyncMetadataDao.getByLeadId(lead.id, uid)
                val pending = database.syncDao.getPendingMutationsForEntity(uid, "LEAD", lead.id)
                if (meta == null || pending.isEmpty()) {
                    leadSyncMutationCoordinator.upsertLead(lead, com.example.sync.LeadWriteOrigin.LOCAL_USER)
                }
            }

            val result = syncRepo.sync(com.example.sync.model.SyncTrigger.MANUAL, uid)
            withContext(Dispatchers.Main) {
                when (result) {
                    is com.example.sync.model.SyncRunResult.Success -> {
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        val nowStr = sdf.format(java.util.Date())
                        setLastSyncTime(nowStr)
                        queryCloudBackupStatus { _, _, count ->
                            setTotalCloudCustomers(count ?: leads.size)
                        }
                        onComplete(true, "Successfully uploaded ${result.summary.pushed} leads to cloud.")
                    }
                    is com.example.sync.model.SyncRunResult.Partial -> {
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        val nowStr = sdf.format(java.util.Date())
                        setLastSyncTime(nowStr)
                        onComplete(true, "Backup completed with warnings: ${result.message}")
                    }
                    is com.example.sync.model.SyncRunResult.AuthRequired -> {
                        onComplete(false, "Backup failed: ${result.message}")
                    }
                    is com.example.sync.model.SyncRunResult.AutomaticSyncDisabled -> {
                        onComplete(false, "Backup failed: ${result.message}")
                    }
                    is com.example.sync.model.SyncRunResult.RetryableFailure -> {
                        onComplete(false, "Backup failed: ${result.message}")
                    }
                    is com.example.sync.model.SyncRunResult.PermanentFailure -> {
                        onComplete(false, "Backup failed: ${result.message}")
                    }
                }
            }
        }
    }

    fun deleteCloudBackup(onComplete: (Boolean, String) -> Unit) {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null || currentUser.isAnonymous || currentUser.uid.isBlank()) {
            onComplete(false, "Authenticated non-anonymous user required.")
            return
        }
        val uid = currentUser.uid
        val db = FirebaseFirestore.getInstance()
        val syncPrefs = com.example.sync.SyncPreferences(getApplication())

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val userDocument = db.collection("users").document(uid)
                val leadsCollection = userDocument.collection("leads")

                // Step 1: Delete all documents in cloud leads collection from Firestore
                while (true) {
                    val snapshot = com.google.android.gms.tasks.Tasks.await(
                        leadsCollection.limit(400).get(com.google.firebase.firestore.Source.SERVER)
                    )
                    if (snapshot.isEmpty) break
                    val batch = db.batch()
                    snapshot.documents.forEach { doc -> batch.delete(doc.reference) }
                    com.google.android.gms.tasks.Tasks.await(batch.commit())
                    com.google.android.gms.tasks.Tasks.await(db.waitForPendingWrites())
                }

                // Step 2: Delete parent user document if present
                com.google.android.gms.tasks.Tasks.await(userDocument.delete())
                com.google.android.gms.tasks.Tasks.await(db.waitForPendingWrites())

                // Step 3: Clear local sync outbox, conflicts, and checkpoint so local leads are not automatically re-uploaded
                database.syncDao.clearOutboxForUser(uid)
                database.syncDao.clearConflictsForUser(uid)
                database.syncDao.clearCheckpointByScope("leads:$uid")

                // Step 4: Mark local lead metadata as SYNCED without deleting any local leads
                val localLeads = database.leadDao.getAllLeadsList(uid)
                val now = System.currentTimeMillis()
                for (lead in localLeads) {
                    database.leadSyncMetadataDao.markSynced(lead.id, uid, now)
                }

                // Step 5: Reset sync preferences timestamps and error status
                syncPrefs.setLastSuccessfulSyncAt(0L)
                syncPrefs.setLastSyncAttemptAt(0L)
                syncPrefs.setLastSyncError(null)

                withContext(Dispatchers.Main) {
                    setLastSyncTime(null)
                    setTotalCloudCustomers(0)
                    queryCloudBackupStatus { _, _, _ -> }
                    onComplete(true, "Cloud backup data has been permanently deleted.")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onComplete(false, "Failed to delete cloud backup: ${e.localizedMessage}")
                }
            }
        }
    }

    fun queryCloudBackupStatus(onComplete: (Boolean, String, Int?) -> Unit) {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null || currentUser.isAnonymous || currentUser.uid.isBlank()) {
            onComplete(false, "Authenticated non-anonymous user required.", null)
            return
        }
        val uid = currentUser.uid
        val db = FirebaseFirestore.getInstance()
        db.collection("users").document(uid).collection("leads")
            .get()
            .addOnSuccessListener { querySnapshot ->
                if (querySnapshot != null && !querySnapshot.isEmpty) {
                    val count = querySnapshot.size()
                    setTotalCloudCustomers(count)
                    onComplete(true, "Found $count cloud customers.", count)
                } else {
                    setTotalCloudCustomers(0)
                    onComplete(true, "No cloud backup found.", 0)
                }
            }
            .addOnFailureListener { e ->
                onComplete(false, "Failed to query cloud backup: ${e.localizedMessage}", null)
            }
    }

    fun manualRestoreFromCloud(onComplete: (Boolean, String) -> Unit) {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null || currentUser.isAnonymous || currentUser.uid.isBlank()) {
            onComplete(false, "Authenticated non-anonymous user required.")
            return
        }
        val uid = currentUser.uid
        val db = FirebaseFirestore.getInstance()
        db.collection("users").document(uid).collection("leads")
            .get()
            .addOnSuccessListener { querySnapshot ->
                if (querySnapshot != null && !querySnapshot.isEmpty) {
                    val leads = mutableListOf<LeadEntity>()
                    for (doc in querySnapshot.documents) {
                        try {
                            val id = doc.getString("id") ?: doc.id
                            val name = doc.getString("name") ?: ""
                            val mobile = doc.getString("mobile") ?: ""
                            val diseases = doc.getString("diseases") ?: "[]"
                            val otherDisease = doc.getString("otherDisease") ?: ""
                            val relation = doc.getString("relation") ?: ""
                            val otherRelation = doc.getString("otherRelation") ?: ""
                            val status = doc.getString("status") ?: "Pending"
                            val reminderDate = doc.getString("reminderDate") ?: ""
                            val reminderTime = doc.getString("reminderTime") ?: ""
                            val reminderNote = doc.getString("reminderNote") ?: ""
                            val reminderStatus = doc.getString("reminderStatus") ?: "Pending"
                            val notes = doc.getString("notes") ?: ""
                            val archived = doc.getBoolean("archived") ?: false
                            val lastCall = doc.getString("lastCall")
                            val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()

                            leads.add(
                                LeadEntity(
                                    id = id,
                                    name = name,
                                    mobile = mobile,
                                    diseases = diseases,
                                    otherDisease = otherDisease,
                                    relation = relation,
                                    otherRelation = otherRelation,
                                    status = status,
                                    reminderDate = reminderDate,
                                    reminderTime = reminderTime,
                                    reminderNote = reminderNote,
                                    reminderStatus = reminderStatus,
                                    notes = notes,
                                    archived = archived,
                                    lastCall = lastCall,
                                    timestamp = timestamp,
                                    ownerUid = uid
                                )
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("FirestoreSync", "Error parsing doc ${doc.id}", e)
                        }
                    }
                    if (leads.isNotEmpty()) {
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val activeUser = FirebaseAuth.getInstance().currentUser
                                if (activeUser == null || activeUser.isAnonymous || activeUser.uid != uid) {
                                    withContext(Dispatchers.Main) {
                                        onComplete(false, "Restore aborted due to user sign-out or account change.")
                                    }
                                    return@launch
                                }
                                val filteredLeads = leads.filterNot { lead ->
                                    val meta = database.leadSyncMetadataDao.getByLeadId(lead.id, uid)
                                    val pending = database.syncDao.getPendingMutationsForEntity(uid, "LEAD", lead.id)
                                    (meta != null && meta.deleted) || pending.any { it.operation == "DELETE" }
                                }
                                if (filteredLeads.isNotEmpty()) {
                                    repository.insertLeads(filteredLeads, com.example.sync.LeadWriteOrigin.REMOTE_RESTORE)
                                    ReminderScheduler.rescheduleAllReminders(getApplication(), filteredLeads)
                                }
                                withContext(Dispatchers.Main) {
                                    _showCloudRestoreDialog.value = false
                                    pendingCloudLeads = emptyList()
                                    hasCheckedCloudBackupForUser = uid
                                    sharedPrefs.edit().putBoolean("cloud_restore_completed_$uid", true).apply()
                                    onComplete(true, "Successfully restored ${filteredLeads.size} records from cloud backup.")
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("FirestoreSync", "Failed to insert restored leads", e)
                                withContext(Dispatchers.Main) {
                                    onComplete(false, "Failed to restore cloud records: ${e.localizedMessage}")
                                }
                            }
                        }
                    } else {
                        onComplete(false, "No cloud backup found.")
                    }
                } else {
                    onComplete(false, "No cloud backup found.")
                }
            }
            .addOnFailureListener { e ->
                onComplete(false, "Failed to read cloud backup: ${e.localizedMessage}")
            }
    }

    private var hasCheckedCloudBackupForUser: String? = null
    private var pendingCloudLeads: List<LeadEntity> = emptyList()
    private var lastUserId: String? = null

    fun checkForCloudBackup(userId: String) {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null || currentUser.isAnonymous || currentUser.uid != userId) {
            return
        }
        lastUserId = userId
        val isRestored = sharedPrefs.getBoolean("cloud_restore_completed_$userId", false)
        if (isRestored || hasCheckedCloudBackupForUser == userId) {
            hasCheckedCloudBackupForUser = userId
            return
        }
        
        val db = FirebaseFirestore.getInstance()
        db.collection("users").document(userId).collection("leads")
            .get()
            .addOnSuccessListener { querySnapshot ->
                if (querySnapshot != null && !querySnapshot.isEmpty) {
                    val leads = mutableListOf<LeadEntity>()
                    for (doc in querySnapshot.documents) {
                        try {
                            val id = doc.getString("id") ?: doc.id
                            val name = doc.getString("name") ?: ""
                            val mobile = doc.getString("mobile") ?: ""
                            val diseases = doc.getString("diseases") ?: "[]"
                            val otherDisease = doc.getString("otherDisease") ?: ""
                            val relation = doc.getString("relation") ?: ""
                            val otherRelation = doc.getString("otherRelation") ?: ""
                            val status = doc.getString("status") ?: "Pending"
                            val reminderDate = doc.getString("reminderDate") ?: ""
                            val reminderTime = doc.getString("reminderTime") ?: ""
                            val reminderNote = doc.getString("reminderNote") ?: ""
                            val reminderStatus = doc.getString("reminderStatus") ?: "Pending"
                            val notes = doc.getString("notes") ?: ""
                            val archived = doc.getBoolean("archived") ?: false
                            val lastCall = doc.getString("lastCall")
                            val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()

                            leads.add(
                                LeadEntity(
                                    id = id,
                                    name = name,
                                    mobile = mobile,
                                    diseases = diseases,
                                    otherDisease = otherDisease,
                                    relation = relation,
                                    otherRelation = otherRelation,
                                    status = status,
                                    reminderDate = reminderDate,
                                    reminderTime = reminderTime,
                                    reminderNote = reminderNote,
                                    reminderStatus = reminderStatus,
                                    notes = notes,
                                    archived = archived,
                                    lastCall = lastCall,
                                    timestamp = timestamp,
                                    ownerUid = userId
                                )
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("FirestoreSync", "Error parsing doc ${doc.id}", e)
                        }
                    }
                    if (leads.isNotEmpty()) {
                        pendingCloudLeads = leads
                        hasCheckedCloudBackupForUser = userId
                        _showCloudRestoreDialog.value = true
                    } else {
                        hasCheckedCloudBackupForUser = userId
                        android.util.Log.d("FirestoreSync", "No cloud backup found for $userId")
                    }
                } else {
                    hasCheckedCloudBackupForUser = userId
                    android.util.Log.d("FirestoreSync", "No cloud backup found for $userId")
                }
            }
            .addOnFailureListener { e ->
                android.util.Log.e("FirestoreSync", "Error checking cloud backup", e)
            }
    }

    fun performCloudRestore() {
        val leadsToRestore = pendingCloudLeads
        val currentUser = FirebaseAuth.getInstance().currentUser
        val uid = lastUserId ?: currentUser?.uid ?: ""
        if (currentUser == null || currentUser.isAnonymous || currentUser.uid != uid) {
            _showCloudRestoreDialog.value = false
            pendingCloudLeads = emptyList()
            return
        }

        if (leadsToRestore.isNotEmpty() && uid.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val activeUser = FirebaseAuth.getInstance().currentUser
                    if (activeUser == null || activeUser.isAnonymous || activeUser.uid != uid) {
                        return@launch
                    }
                    val filteredLeads = leadsToRestore.filterNot { lead ->
                        val meta = database.leadSyncMetadataDao.getByLeadId(lead.id, uid)
                        val pending = database.syncDao.getPendingMutationsForEntity(uid, "LEAD", lead.id)
                        (meta != null && meta.deleted) || pending.any { it.operation == "DELETE" }
                    }
                    if (filteredLeads.isNotEmpty()) {
                        repository.insertLeads(filteredLeads, com.example.sync.LeadWriteOrigin.REMOTE_RESTORE)
                        ReminderScheduler.rescheduleAllReminders(getApplication(), filteredLeads)
                    }
                    withContext(Dispatchers.Main) {
                        android.util.Log.d("FirestoreSync", "Cloud restore completed successfully. Restored ${filteredLeads.size} leads.")
                        android.widget.Toast.makeText(getApplication(), "Restore Completed Successfully", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("FirestoreSync", "Failed to insert restored leads", e)
                } finally {
                    withContext(Dispatchers.Main) {
                        _showCloudRestoreDialog.value = false
                        pendingCloudLeads = emptyList()
                        lastUserId?.let { userId ->
                            sharedPrefs.edit().putBoolean("cloud_restore_completed_$userId", true).apply()
                        }
                    }
                }
            }
        } else {
            _showCloudRestoreDialog.value = false
        }
    }

    fun skipCloudRestore() {
        _showCloudRestoreDialog.value = false
        pendingCloudLeads = emptyList()
        val userId = lastUserId ?: FirebaseAuth.getInstance().currentUser?.uid
        userId?.let { uid ->
            hasCheckedCloudBackupForUser = uid
            sharedPrefs.edit().putBoolean("cloud_restore_completed_$uid", true).apply()
        }
    }

    fun resetCloudRestoreCheck() {
        sharedPrefs.all.keys.filter { it.startsWith("cloud_restore_completed_") || it.startsWith("cloud_restore_handled_") }.forEach { key ->
            sharedPrefs.edit().remove(key).apply()
        }
        hasCheckedCloudBackupForUser = null
        pendingCloudLeads = emptyList()
        _showCloudRestoreDialog.value = false
    }

    // Combined filtered leads list
    val filteredLeads: StateFlow<List<LeadEntity>> = combine(
        allLeadsList,
        searchQuery,
        currentFilter
    ) { leads, query, filter ->
        val todayStr = getSystemTodayDateStr()

        leads.filter { lead ->
            // Drafts (incomplete leads from the AI chat) only appear under
            // the Drafts filter - they are hidden from every other view.
            if (filter == "drafts") {
                if (!lead.isDraft) return@filter false
            } else {
                if (lead.isDraft) return@filter false
            }

            // Filter out archived unless explicitly viewing archive
            if (filter == "archived") {
                if (!lead.archived) return@filter false
            } else {
                if (lead.archived) return@filter false
            }

            // General status filtering
            when (filter) {
                "pending" -> if (lead.status != "Pending") return@filter false
                "complete" -> if (lead.status != "Complete") return@filter false
            }

            // Reminders filtering
            val reminderStatus = lead.reminderStatus
            val cat = getReminderCategory(lead, todayStr)

            when (filter) {
                "rem-today" -> if (cat != "today" || reminderStatus != "Pending") return@filter false
                "rem-upcoming" -> if (cat != "upcoming" || reminderStatus != "Pending") return@filter false
                "rem-overdue" -> {
                    if (reminderStatus != "Overdue" && (cat != "overdue" || reminderStatus != "Pending")) return@filter false
                }
                "rem-all" -> if (lead.reminderDate.isEmpty()) return@filter false
            }

            // Search query filter
            if (query.isNotEmpty()) {
                val q = query.lowercase(Locale.getDefault())
                val diseasesStr = lead.diseases.lowercase(Locale.getDefault())
                val match = lead.name.lowercase(Locale.getDefault()).contains(q) ||
                        lead.mobile.contains(q) ||
                        lead.relation.lowercase(Locale.getDefault()).contains(q) ||
                        lead.otherRelation.lowercase(Locale.getDefault()).contains(q) ||
                        diseasesStr.contains(q) ||
                        lead.otherDisease.lowercase(Locale.getDefault()).contains(q) ||
                        lead.notes.lowercase(Locale.getDefault()).contains(q) ||
                        lead.reminderNote.lowercase(Locale.getDefault()).contains(q)
                if (!match) return@filter false
            }

            true
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active polling thread job for checking reminders
    private var reminderCheckJob: Job? = null

    init {
        com.example.data.LanguagePackManager.init(getApplication())
        viewModelScope.launch {
            playLanguageDeliveryManager.installState.collect { state ->
                if (state is com.example.data.PlayLanguageInstallState.Installed) {
                    setAppLanguage(state.language)
                }
            }
        }
        ReminderScheduler.startChecking(getApplication())
        checkAndShowChangelog()
        viewModelScope.launch {
            ReminderScheduler.activeRingingLead.collect { lead ->
                if (lead != null) {
                    ringingLead.value = lead
                    alarmModalTitle.value = "Reminder Due Today"
                } else {
                    ringingLead.value = null
                }
            }
        }
    }

    /* --- BASIC ACTIONS & CRUD --- */

    enum class SaveLeadResult {
        SUCCESS,
        DUPLICATE_MOBILE,
        DUPLICATE_REMINDER,
        VALIDATION_FAILED,
        LEAD_NOT_FOUND,
        SAVE_FAILED
    }

    fun hasDuplicateReminder(
        excludeId: String?,
        date: String,
        time: String
    ): Boolean {
        return leadOperationService.hasDuplicateReminder(
            excludeId = excludeId,
            date = date,
            time = time,
            currentLeads = allLeadsList.value
        )
    }

    suspend fun saveLead(
        id: String?,
        name: String,
        mobile: String,
        diseases: List<String>,
        otherDisease: String,
        relation: String,
        otherRelation: String,
        status: String,
        reminderDate: String,
        reminderTime: String,
        reminderNote: String,
        notes: String
    ): SaveLeadResult {
        val draft = com.example.leads.domain.LeadDraft(
            id = id,
            name = name,
            mobile = mobile,
            diseases = diseases,
            otherDisease = otherDisease,
            relation = relation,
            otherRelation = otherRelation,
            status = status,
            reminderDate = reminderDate,
            reminderTime = reminderTime,
            reminderNote = reminderNote,
            notes = notes
        )

        val result = leadOperationService.saveLead(
            draft = draft,
            currentLeads = allLeadsList.value
        )

        if (result.isSuccess) {
            result.entity?.let { entity ->
                triggeredOverdueIds.remove(entity.id)
            }
        }

        return when (result.status) {
            com.example.leads.operation.LeadSaveStatus.SUCCESS ->
                SaveLeadResult.SUCCESS

            com.example.leads.operation.LeadSaveStatus.DUPLICATE_MOBILE ->
                SaveLeadResult.DUPLICATE_MOBILE

            com.example.leads.operation.LeadSaveStatus.DUPLICATE_REMINDER ->
                SaveLeadResult.DUPLICATE_REMINDER

            com.example.leads.operation.LeadSaveStatus.VALIDATION_FAILED ->
                SaveLeadResult.VALIDATION_FAILED

            com.example.leads.operation.LeadSaveStatus.LEAD_NOT_FOUND ->
                SaveLeadResult.LEAD_NOT_FOUND

            com.example.leads.operation.LeadSaveStatus.DATABASE_ERROR ->
                SaveLeadResult.SAVE_FAILED
        }
    }

    /**
     * Builds the compact read-only CRM snapshot that is appended to the AI
     * system instruction before every request. In-memory only (StateFlow
     * snapshot) - no database access, so it is safe to call per message.
     */
    fun buildCrmSnapshot(): String {
        val leads = allLeadsList.value
        val full = leads.filter { !it.isDraft }
        val active = full.filter { !it.archived }
        val drafts = leads.filter { it.isDraft }
        val pending = active.count { it.status.equals("Pending", ignoreCase = true) }
        val complete = active.count { it.status.equals("Complete", ignoreCase = true) }
        val todayStr = getSystemTodayDateStr()
        val remToday = active.count { it.reminderDate == todayStr && it.reminderStatus == "Pending" }
        val remOverdue = active.count {
            it.reminderDate.isNotEmpty() && it.reminderDate < todayStr && it.reminderStatus == "Pending"
        }

        val sb = StringBuilder()
        sb.append("CRM DATA SNAPSHOT (read-only context about the user's current leads, refreshed for every message. Answer questions from it; it never changes data):\n")
        sb.append("Counts: total clients=").append(active.size)
            .append(", pending=").append(pending)
            .append(", complete=").append(complete)
            .append(", archived=").append(full.size - active.size)
            .append(", drafts=").append(drafts.size)
            .append(", reminders due today=").append(remToday)
            .append(", overdue reminders=").append(remOverdue)
            .append('\n')

        val recent = full.sortedByDescending { it.timestamp }.take(15)
        if (recent.isNotEmpty()) {
            sb.append("Recent clients (name | phone | status | reminderDate-time | wellness | lastCall):\n")
            recent.forEach { lead ->
                sb.append(lead.name)
                    .append(if (lead.archived) " [ARCHIVED]" else "")
                    .append(" | ").append(lead.mobile.ifEmpty { "-" })
                    .append(" | ").append(lead.status)
                    .append(" | ").append(
                        when {
                            lead.reminderDate.isEmpty() -> "-"
                            lead.reminderTime.isNotEmpty() -> lead.reminderDate + " " + lead.reminderTime
                            else -> lead.reminderDate
                        }
                    )
                val wellness = diseasesCompact(lead.diseases)
                if (wellness.isNotEmpty()) sb.append(" | ").append(wellness)
                sb.append(" | lastCall=").append((lead.lastCall ?: "").take(10).ifEmpty { "-" })
                sb.append('\n')
            }
        }

        if (drafts.isNotEmpty()) {
            sb.append("Incomplete drafts (from AI chat, not yet saved as real clients):\n")
            drafts.sortedByDescending { it.timestamp }.take(10).forEach { lead ->
                sb.append("- ").append(lead.name)
                    .append(" | ").append(lead.mobile.ifEmpty { "no phone yet" })
                val wellness = diseasesCompact(lead.diseases)
                if (wellness.isNotEmpty()) sb.append(" | ").append(wellness)
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    private fun diseasesCompact(diseasesJson: String): String {
        if (diseasesJson.isBlank()) return ""
        return try {
            val arr = JSONArray(diseasesJson)
            val items = (0 until arr.length()).map { arr.optString(it, "") }
                .filter { it.isNotBlank() }
                .distinct()
                .take(4)
            items.joinToString(", ").take(60)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Saves a lead collected through the AI chat (LEAD-COLLECT protocol).
     *
     * The AI never saves on its own: this is only called after the user taps a
     * button on the chat confirmation card. Drafts are device-local and never
     * sync to cloud (see LeadRepository); drafts never schedule alarms either.
     * Full leads reuse the normal insert path with the LOCAL_AI origin.
     *
     * Returns the chat message to show after the save attempt
     * (success, success-with-warning, or error).
     */
    suspend fun saveLeadFromAIChat(action: com.example.ai.chat.lead.LeadAction): String {
        val uid = _currentUidFlow.value
            ?: FirebaseAuth.getInstance().currentUser?.uid
            ?: ""
        if (uid.isBlank()) return "Lead save ke liye pehle login karo."

        val isDraft = action.kind == com.example.ai.chat.lead.LeadAction.Kind.DRAFT
        val name = action.name.trim().ifBlank { "Unknown" }
        val mobile = action.mobile.filter(Char::isDigit)

        if (!isDraft && mobile.length !in 10..15) {
            return "Number sahi nahi lag raha (10-15 digits chahiye). Lead save nahi hua."
        }

        // Drafts do not block a real lead with the same number - the user
        // may be completing one of them right now.
        if (!isDraft && mobile.isNotEmpty() &&
            allLeadsList.value.any { !it.isDraft && it.mobile.filter(Char::isDigit) == mobile }
        ) {
            return "Yeh number pehle se maujood hai, isliye naya lead save nahi hua."
        }

        val diseases = action.diseases
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.getDefault()) }
            .take(5)

        val note = action.note.trim()

        // Reminder: validate strictly before saving. Invalid/past/duplicate
        // reminders are skipped (the lead still saves) with a warning message.
        var reminderDate = ""
        var reminderTime = ""
        var reminderWarning = ""
        if (!isDraft && action.reminderDate.isNotBlank()) {
            val canonicalDate = parseStrictYMD(action.reminderDate)
            when {
                canonicalDate == null ->
                    reminderWarning = " Reminder date samajh nahi aayi, isliye alarm set nahi hua."
                else -> {
                    val time = normalizeReminderTime(action.reminderTime)
                    val triggerMillis = reminderTriggerMillis(canonicalDate, time)
                    if (triggerMillis != null && triggerMillis <= System.currentTimeMillis()) {
                        reminderWarning = " Reminder time past me hai, isliye alarm set nahi hua."
                    } else if (leadOperationService.hasDuplicateReminder(
                            null, canonicalDate, time, allLeadsList.value
                        )
                    ) {
                        reminderWarning = " Isi date-time pe doosra reminder pehle se hai, isliye yeh alarm set nahi hua."
                    } else {
                        reminderDate = canonicalDate
                        reminderTime = time
                    }
                }
            }
        }

        // Draft completion: when this save matches an existing draft (same
        // mobile, or same name when saving a draft without a mobile), reuse
        // that draft's row instead of creating a new one.
        val matchingDraft = when {
            mobile.isNotEmpty() ->
                allLeadsList.value.firstOrNull { it.isDraft && it.mobile.filter(Char::isDigit) == mobile }
            isDraft && name.isNotBlank() ->
                allLeadsList.value.firstOrNull { it.isDraft && it.name.equals(name, ignoreCase = true) }
            else -> null
        }

        val now = System.currentTimeMillis()
        val entity = LeadEntity(
            id = matchingDraft?.id ?: UUID.randomUUID().toString(),
            name = name,
            mobile = mobile,
            diseases = JSONArray(diseases).toString(),
            otherDisease = "",
            relation = "",
            otherRelation = "",
            status = "Pending",
            reminderDate = reminderDate,
            reminderTime = reminderTime,
            reminderNote = if (reminderDate.isNotEmpty()) note else "",
            reminderStatus = "Pending",
            notes = note,
            archived = false,
            lastCall = null,
            timestamp = matchingDraft?.timestamp ?: now,
            notesUpdatedAt = if (note.isNotEmpty()) now else 0L,
            reminderUpdatedAt = if (reminderDate.isNotEmpty()) now else 0L,
            isDraft = isDraft,
            ownerUid = uid
        )

        return try {
            repository.insertLead(entity, com.example.sync.LeadWriteOrigin.LOCAL_AI)

            var message = when {
                isDraft && matchingDraft != null ->
                    "📝 Draft '$name' update ho gaya. Leads tab me 'Drafts' chip se kholo aur complete karo."
                isDraft ->
                    "📝 '$name' Drafts me save ho gaya. Leads tab me 'Drafts' chip se kholo aur complete karo."
                matchingDraft != null ->
                    "✅ Draft '$name' complete ho gaya - ab proper lead ban gaya."
                else ->
                    "✅ Lead '$name' save ho gaya. Leads tab me dikhega."
            }

            if (reminderDate.isNotEmpty()) {
                try {
                    com.example.audio.ReminderScheduler.scheduleReminder(getApplication(), entity)
                    message += " Reminder set hua: ${formatReminderForDisplay(reminderDate, reminderTime)}."
                } catch (error: Exception) {
                    message += " Reminder alarm set nahi ho saka."
                }
            }

            message + reminderWarning
        } catch (error: Exception) {
            "Lead save nahi ho saka: ${error.message.orEmpty()}"
        }
    }

    /**
     * Applies a status change (Pending/Complete) proposed by the AI chat.
     * Only callable from the chat confirmation card. The lead is matched by
     * mobile first (exact), then by name - ambiguous names are rejected so
     * the AI can ask the user for the phone number.
     *
     * Returns the chat message to show after the update attempt.
     */
    suspend fun updateLeadStatusFromAIChat(action: com.example.ai.chat.lead.LeadAction): String {
        val uid = _currentUidFlow.value
            ?: FirebaseAuth.getInstance().currentUser?.uid
            ?: ""
        if (uid.isBlank()) return "Lead update ke liye pehle login karo."

        val newStatus = if (action.status.equals("Complete", ignoreCase = true)) "Complete" else "Pending"
        val mobile = action.mobile.filter(Char::isDigit)
        val leads = allLeadsList.value.filter { !it.isDraft }

        val nameMatches = if (action.name.isNotBlank()) {
            leads.filter { it.name.equals(action.name, ignoreCase = true) }
        } else {
            emptyList()
        }

        val lead = when {
            mobile.isNotEmpty() -> leads.firstOrNull { it.mobile.filter(Char::isDigit) == mobile }
            nameMatches.size == 1 -> nameMatches.first()
            nameMatches.size > 1 ->
                return "'${action.name}' ke naam se multiple leads hain. Phone number batayein taaki sahi lead update ho."
            else -> null
        }

        if (lead == null) {
            return "Lead nahi mila. Naam ya number dobara check karke try karo."
        }

        val updated = lead.copy(
            status = newStatus,
            reminderStatus = when {
                newStatus == "Complete" -> "Completed"
                lead.reminderDate.isNotEmpty() -> "Pending"
                else -> lead.reminderStatus
            }
        )

        return try {
            repository.insertLead(updated, com.example.sync.LeadWriteOrigin.LOCAL_AI)
            if (newStatus == "Complete") {
                com.example.audio.ReminderScheduler.cancelReminder(getApplication(), updated.ownerUid, updated.id)
            } else if (lead.reminderDate.isNotEmpty()) {
                com.example.audio.ReminderScheduler.scheduleReminder(getApplication(), updated)
            }
            "✅ '${lead.name}' ka status ab $newStatus hai."
        } catch (error: Exception) {
            "Status update nahi ho saka: ${error.message.orEmpty()}"
        }
    }

    /**
     * Matches an AI chat action (UPDATE/ARCHIVE/DELETE/WHATSAPP) to an
     * existing lead: mobile first (exact digits), then a unique exact name.
     * Returns Pair(lead, message) - the message is set when the user must be
     * asked to clarify; Pair(null, "") means no match found.
     */
    private suspend fun findLeadForAI(
        action: com.example.ai.chat.lead.LeadAction,
        includeArchived: Boolean
    ): Pair<LeadEntity?, String> {
        val mobile = action.mobile.filter(Char::isDigit)
        val leads = allLeadsList.value.filter { !it.isDraft && (includeArchived || !it.archived) }

        val nameMatches = if (action.name.isNotBlank()) {
            leads.filter { it.name.equals(action.name, ignoreCase = true) }
        } else {
            emptyList()
        }

        val lead = when {
            mobile.isNotEmpty() -> leads.firstOrNull { it.mobile.filter(Char::isDigit) == mobile }
            nameMatches.size == 1 -> nameMatches.first()
            nameMatches.size > 1 ->
                return Pair(
                    null,
                    "'${action.name}' ke naam se multiple leads hain. Phone number batayein taaki sahi lead par action ho."
                )
            else -> null
        }
        return Pair(lead, "")
    }

    /** Parses the stored diseases JSON array into a clean, de-duplicated list. */
    private fun parseDiseaseList(diseasesJson: String): List<String> {
        if (diseasesJson.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(diseasesJson)
            (0 until arr.length()).map { arr.optString(it, "") }
                .map { it.trim().replace(Regex("\\s+"), " ") }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase(Locale.getDefault()) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Applies a change set proposed by the AI chat (LEAD_UPDATE). Called only
     * from the chat confirmation card after the user taps "Update karo".
     * Invalid values (bad number, duplicate number, past/duplicate reminder)
     * are skipped with a warning; the valid ones are still applied.
     * Returns the chat message to show after the update attempt.
     */
    suspend fun updateLeadFromAIChat(action: com.example.ai.chat.lead.LeadAction): String {
        val uid = _currentUidFlow.value
            ?: FirebaseAuth.getInstance().currentUser?.uid
            ?: ""
        if (uid.isBlank()) return "Lead update ke liye pehle login karo."

        val (lead, clarification) = findLeadForAI(action, includeArchived = false)
        if (clarification.isNotEmpty()) return clarification
        if (lead == null) return "Lead nahi mila. Naam ya number dobara check karke try karo."

        var updated = lead
        var reminderChanged = false
        val warnings = mutableListOf<String>()

        // 1) Mobile number change (strict validation + duplicate check).
        val newMobile = action.setMobile.filter(Char::isDigit)
        if (newMobile.isNotEmpty()) {
            when {
                newMobile.length !in 10..15 ->
                    warnings += " Number sahi nahi lag raha (10-15 digits), number waisa hi rakha."
                allLeadsList.value.any {
                    !it.isDraft && it.id != lead.id && it.mobile.filter(Char::isDigit) == newMobile
                } -> warnings += " Yeh number doosre client ka hai, number waisa hi rakha."
                else -> updated = updated.copy(mobile = newMobile)
            }
        }

        // 2) Name change.
        val newName = action.setName.trim()
        if (newName.isNotEmpty()) {
            updated = updated.copy(name = newName)
        }

        // 3) Append new diseases (skipping ones already present).
        val toAdd = action.addDiseases
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.getDefault()) }
            .filter { candidate ->
                parseDiseaseList(updated.diseases).none { it.equals(candidate, ignoreCase = true) }
            }
        if (toAdd.isNotEmpty()) {
            val merged = (parseDiseaseList(updated.diseases) + toAdd).take(10)
            updated = updated.copy(diseases = JSONArray(merged).toString())
        }

        // 4) Append a note (capped so the stored field never overflows).
        val newNote = action.note.trim()
        if (newNote.isNotEmpty()) {
            val base = updated.notes.trimEnd()
            val appended = if (base.isEmpty()) newNote else "$base\n$newNote"
            updated = updated.copy(notes = appended.take(1000))
        }

        // 5) Reminder: remove it, or set/change it (strict validation).
        if (action.removeReminder) {
            if (lead.reminderDate.isNotEmpty()) {
                updated = updated.copy(
                    reminderDate = "",
                    reminderTime = "",
                    reminderStatus = "Completed",
                    reminderUpdatedAt = System.currentTimeMillis()
                )
            }
        } else if (action.setReminderDate.isNotBlank()) {
            val canonicalDate = parseStrictYMD(action.setReminderDate)
            when {
                canonicalDate == null ->
                    warnings += " Reminder date samajh nahi aayi, reminder waisa hi rakha."
                else -> {
                    val time = normalizeReminderTime(action.setReminderTime.ifBlank { lead.reminderTime })
                    val triggerMillis = reminderTriggerMillis(canonicalDate, time)
                    if (triggerMillis != null && triggerMillis <= System.currentTimeMillis()) {
                        warnings += " Reminder time past me hai, reminder waisa hi rakha."
                    } else if (leadOperationService.hasDuplicateReminder(
                            lead.id, canonicalDate, time, allLeadsList.value
                        )
                    ) {
                        warnings += " Isi date-time pe doosra reminder pehle se hai, reminder waisa hi rakha."
                    } else {
                        updated = updated.copy(
                            reminderDate = canonicalDate,
                            reminderTime = time,
                            reminderStatus = "Pending",
                            reminderUpdatedAt = System.currentTimeMillis()
                        )
                        reminderChanged = true
                    }
                }
            }
        }

        if (updated == lead) {
            // Nothing valid was applied - report the warnings (or say so).
            return if (warnings.isNotEmpty()) warnings.joinToString(" ")
            else "Koi change nahi mila apply karne ke liye."
        }

        return try {
            repository.insertLead(updated, com.example.sync.LeadWriteOrigin.LOCAL_AI)
            if (updated.reminderDate.isEmpty() && lead.reminderDate.isNotEmpty()) {
                com.example.audio.ReminderScheduler.cancelReminder(
                    getApplication(), updated.ownerUid, updated.id
                )
            } else if (reminderChanged) {
                com.example.audio.ReminderScheduler.scheduleReminder(getApplication(), updated)
            }
            "✅ '${updated.name}' update ho gaya." + warnings.joinToString(" ")
        } catch (error: Exception) {
            "Update nahi ho saka: ${error.message.orEmpty()}"
        }
    }

    /**
     * Moves a lead to Archived (soft delete) - the direct LEAD_ARCHIVE action,
     * which runs without a confirmation card by design. The alarm is
     * cancelled; the lead keeps all its fields and can be restored from the
     * Archived chip in the Leads tab.
     */
    suspend fun archiveLeadFromAIChat(action: com.example.ai.chat.lead.LeadAction): String {
        val uid = _currentUidFlow.value
            ?: FirebaseAuth.getInstance().currentUser?.uid
            ?: ""
        if (uid.isBlank()) return "Lead archive karne ke liye pehle login karo."

        val (lead, clarification) = findLeadForAI(action, includeArchived = false)
        if (clarification.isNotEmpty()) return clarification
        if (lead == null) {
            // Not found among active leads - it may already be archived.
            val mobile = action.mobile.filter(Char::isDigit)
            val alreadyArchived = allLeadsList.value.firstOrNull {
                it.archived && !it.isDraft &&
                    (
                        (mobile.isNotEmpty() && it.mobile.filter(Char::isDigit) == mobile) ||
                            (action.name.isNotBlank() && it.name.equals(action.name, ignoreCase = true))
                        )
            }
            return if (alreadyArchived != null) {
                "'${alreadyArchived.name}' pehle se archived me hai. Wapas chahiye ho to Leads tab me Archived chip se restore karo."
            } else {
                "Lead nahi mila. Naam ya number dobara check karke try karo."
            }
        }

        return try {
            val updated = lead.copy(archived = true)
            repository.insertLead(updated, com.example.sync.LeadWriteOrigin.LOCAL_AI)
            com.example.audio.ReminderScheduler.cancelReminder(
                getApplication(), updated.ownerUid, updated.id
            )
            "📦 '${lead.name}' archived me chala gaya. Permanent delete sirf 'archived se bhi delete karo' se hoga; wapas chahiye ho to Leads tab me Archived chip se restore karo."
        } catch (error: Exception) {
            "Archive nahi ho saka: ${error.message.orEmpty()}"
        }
    }

    /**
     * Permanently deletes a lead proposed by the AI chat (LEAD_DELETE), shown
     * in the chat only as a light confirmation. Safety guard: if the matched
     * lead is NOT archived, it is archived instead (soft delete), so a
     * permanent deletion through chat can never touch a live lead.
     */
    suspend fun deleteLeadFromAIChat(action: com.example.ai.chat.lead.LeadAction): String {
        val uid = _currentUidFlow.value
            ?: FirebaseAuth.getInstance().currentUser?.uid
            ?: ""
        if (uid.isBlank()) return "Lead delete karne ke liye pehle login karo."

        val (lead, clarification) = findLeadForAI(action, includeArchived = true)
        if (clarification.isNotEmpty()) return clarification
        if (lead == null) {
            return "Lead nahi mila (active aur archived dono me). Naam ya number dobara check karke try karo."
        }

        if (!lead.archived) {
            // Guard: chat never permanently deletes a live lead - archive it.
            return try {
                val updated = lead.copy(archived = true)
                repository.insertLead(updated, com.example.sync.LeadWriteOrigin.LOCAL_AI)
                com.example.audio.ReminderScheduler.cancelReminder(
                    getApplication(), updated.ownerUid, updated.id
                )
                "📦 '$(lead.name)' abhi archived nahi tha, isliye permanent delete ki jagah archived kar diya. Wapas chahiye ho to Leads tab me Archived chip se restore karo."
            } catch (error: Exception) {
                "Archive nahi ho saka: ${error.message.orEmpty()}"
            }
        }

        return try {
            repository.deleteLeadById(lead.id, lead.ownerUid.ifBlank { uid })
            com.example.audio.ReminderScheduler.cancelReminder(
                getApplication(), lead.ownerUid, lead.id
            )
            if (ringingLead.value?.id == lead.id) {
                dismissActiveAlarm()
            }
            "🗑️ '${lead.name}' hamesha ke liye delete ho gaya."
        } catch (error: Exception) {
            "Delete nahi ho saka: ${error.message.orEmpty()}"
        }
    }

    /**
     * Opens WhatsApp for the lead's number - the direct LEAD_WHATSAPP action,
     * which runs without a confirmation card by design. 10-digit numbers get
     * the Indian country code; other lengths (11-15 digits) are used as-is.
     */
    fun openWhatsAppForLeadFromAIChat(action: com.example.ai.chat.lead.LeadAction) {
        val mobile = action.mobile.filter(Char::isDigit).take(15)
        if (mobile.length < 10) {
            android.widget.Toast.makeText(
                getApplication(),
                "WhatsApp ke liye number nahi mila",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        val withCountryCode = if (mobile.length == 10) "91$mobile" else mobile
        try {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://wa.me/$withCountryCode")
            )
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        } catch (error: Exception) {
            // WhatsApp not installed - at least show the number.
            android.widget.Toast.makeText(
                getApplication(),
                "WhatsApp khol nahi saka. Number: $withCountryCode",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Strict yyyy-MM-dd parse; returns the canonical date string or null. */
    private fun parseStrictYMD(value: String): String? {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
        val position = java.text.ParsePosition(0)
        val parsed = formatter.parse(value, position) ?: return null
        if (position.index != value.length) return null
        return formatter.format(parsed)
    }

    /** Accepts HH:mm (24h); falls back to 09:00 when missing or invalid. */
    private fun normalizeReminderTime(value: String): String {
        val clean = value.trim()
        if (
            clean.length == 5 &&
            clean[2] == ':' &&
            clean.substring(0, 2).all(Char::isDigit) &&
            clean.substring(3).all(Char::isDigit)
        ) {
            val hour = clean.substring(0, 2).toInt()
            val minute = clean.substring(3).toInt()
            if (hour in 0..23 && minute in 0..59) return clean
        }
        return "09:00"
    }

    private fun reminderTriggerMillis(date: String, time: String): Long? {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }
        return try {
            formatter.parse("$date $time")?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun formatReminderForDisplay(date: String, time: String): String {
        val pretty = try {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)
            if (parsed != null) SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(parsed) else date
        } catch (e: Exception) {
            date
        }
        return if (time.isNotEmpty()) "$pretty, $time" else pretty
    }

    fun deleteLead(lead: LeadEntity) {
        val uid = _currentUidFlow.value ?: FirebaseAuth.getInstance().currentUser?.uid ?: lead.ownerUid
        if (uid.isBlank()) return
        viewModelScope.launch {
            repository.deleteLeadById(lead.id, uid)
            ReminderScheduler.cancelReminder(getApplication(), uid, lead.id)
            if (ringingLead.value?.id == lead.id) {
                dismissActiveAlarm()
            }
        }
    }

    fun toggleArchive(lead: LeadEntity) {
        viewModelScope.launch {
            val updated = lead.copy(archived = !lead.archived)
            repository.insertLead(updated)
            if (updated.archived) {
                ReminderScheduler.cancelReminder(getApplication(), updated.ownerUid, updated.id)
            } else {
                ReminderScheduler.scheduleReminder(getApplication(), updated)
            }
        }
    }

    fun markCallInitiated(lead: LeadEntity) {
        viewModelScope.launch {
            val updated = lead.copy(lastCall = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date()))
            repository.insertLead(updated)
        }
    }

    fun reactivateReminder(leadId: String, newDate: String, newTime: String): Boolean {
        android.util.Log.d("DUPLICATE_REMINDER_CHECK", "Checking duplicate reminder in reactivateReminder for leadId: $leadId, date: $newDate, time: $newTime")
        if (hasDuplicateReminder(leadId, newDate, newTime)) {
            android.util.Log.d("DUPLICATE_REMINDER_BLOCKED", "Reactivation blocked due to duplicate reminder at $newDate $newTime")
            return false
        }
        android.util.Log.d("DUPLICATE_REMINDER_ALLOWED", "Reactivation allowed for leadId: $leadId, no blocking duplicate reminder.")

        viewModelScope.launch {
            val list = allLeadsList.value
            val item = list.find { it.id == leadId }
            if (item != null) {
                val updated = item.copy(
                    reminderDate = newDate,
                    reminderTime = newTime,
                    reminderStatus = "Pending",
                    status = "Pending"
                )
                triggeredOverdueIds.remove(leadId)
                repository.insertLead(updated)
                ReminderScheduler.scheduleReminder(getApplication(), updated)
            }
        }
        return true
    }

    fun resetAllData(onResult: ((Boolean, String) -> Unit)? = null) {
        val uid = _currentUidFlow.value ?: FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (uid.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val pendingOutboxCount = database.syncDao.getUnsyncedOutboxCount(uid)
            val unresolvedConflictCount = database.syncDao.countUnresolvedConflicts(uid)
            val pendingMetadataItems = database.leadSyncMetadataDao.getPendingSyncItems(uid)

            if (pendingOutboxCount > 0 || unresolvedConflictCount > 0 || pendingMetadataItems.isNotEmpty()) {
                val msg = "Local data cannot be cleared while cloud changes are pending. Sync or resolve them first."
                withContext(Dispatchers.Main) {
                    onResult?.invoke(false, msg)
                }
                return@launch
            }

            val userLeads = repository.getAllLeadsList(uid)
            for (lead in userLeads) {
                ReminderScheduler.cancelReminder(getApplication(), uid, lead.id)
            }
            ReminderScheduler.cancelAllRemindersForUser(getApplication(), uid)
            repository.clearLeadsForUser(uid)
            aiChatRepository.clearChatHistoryForUser(uid)

            withContext(Dispatchers.Main) {
                dismissActiveAlarm()
                triggeredOverdueIds.clear()
                setActiveSession(null)
                onResult?.invoke(true, "All local client records and AI chats have been wiped successfully.")
            }
        }
    }

    /* --- THEME CONTROLLER --- */

    fun toggleTheme(enabled: Boolean) {
        isDarkMode.value = enabled
        sharedPrefs.edit().putBoolean("lifefresh_theme", enabled).apply()
    }

    /* --- ALARM ENGINE CONTROLLER --- */

    fun setAlarmUseCustom(useCustom: Boolean) {
        alarmUseCustom.value = useCustom
        sharedPrefs.edit().putBoolean("alarm_use_custom", useCustom).apply()
        stopAlarmAndTesting()
    }

    fun setAlarmSound(sound: String) {
        alarmSound.value = sound
        sharedPrefs.edit().putString("alarm_sound", sound).apply()
    }

    fun setAlarmVolume(volume: String) {
        alarmVolume.value = volume
        sharedPrefs.edit().putString("alarm_volume", volume).apply()
    }

    fun setReminderRingMode(mode: String) {
        reminderRingMode.value = mode
        sharedPrefs.edit().putString("reminder_ring_mode", mode).apply()
    }

    fun updateExactAlarmStatus() {
        android.util.Log.d("EXACT_ALARM_CHECK", "Checking exact alarm permission status")
        val isGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val alarmManager = getApplication<Application>().getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            alarmManager?.canScheduleExactAlarms() == true
        } else {
            true
        }
        isExactAlarmGrantedState.value = isGranted
        if (isGranted) {
            android.util.Log.d("EXACT_ALARM_GRANTED", "Exact alarm permission is granted")
        } else {
            android.util.Log.d("EXACT_ALARM_DENIED", "Exact alarm permission is denied")
        }
        android.util.Log.d("EXACT_ALARM_STATUS_UPDATED", "Exact alarm permission status updated")
    }

    fun triggerExactAlarmPrompt() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            updateExactAlarmStatus()
            if (!isExactAlarmGrantedState.value) {
                showExactAlarmPrompt.value = true
            }
        }
    }

    fun dismissExactAlarmPrompt() {
        showExactAlarmPrompt.value = false
    }

    fun registerCustomAudioFile(context: Context, uri: Uri): Boolean {
        // Copy audio file safely to Sandbox to guarantee proper offline plays!
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            var name = "custom_alarm.wav"
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = it.getString(nameIndex)
                    }
                }
            }

            val sandboxFile = File(context.filesDir, "custom_alarm.audio")
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val outputStream: OutputStream = sandboxFile.outputStream()
            
            inputStream?.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            customAudioFilename.value = name
            sharedPrefs.edit()
                .putString("alarm_custom_filename", name)
                .putString("alarm_custom_path", sandboxFile.absolutePath)
                .apply()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun removeCustomAudio(context: Context) {
        val sandboxFile = File(context.filesDir, "custom_alarm.audio")
        if (sandboxFile.exists()) {
            sandboxFile.delete()
        }
        customAudioFilename.value = "No file selected"
        sharedPrefs.edit()
            .remove("alarm_custom_filename")
            .remove("alarm_custom_path")
            .apply()
        stopAlarmAndTesting()
    }

    fun playAlarmSound(soundName: String, volumeLevel: String, loop: Boolean) {
        android.util.Log.d("CRMViewModel", "playAlarmSound called: soundName=$soundName, volumeLevel=$volumeLevel, loop=$loop")
        stopAlarmSound()

        val assetName = com.example.audio.AlarmSoundResolver.getSoundAssetPath(soundName)

        try {
            val fd = getApplication<Application>().assets.openFd(assetName)
            customMediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                fd.close()

                setAudioStreamType(AudioManager.STREAM_MUSIC)
                isLooping = loop
                val volumeFactor = com.example.audio.AlarmSoundResolver.getVolumeFactor(volumeLevel)
                setVolume(volumeFactor, volumeFactor)
                prepare()
                start()
            }
            android.util.Log.d("CRMViewModel", "MediaPlayer playing asset ringtone: $assetName")
        } catch (e: Exception) {
            android.util.Log.e("CRMViewModel", "Failed to play asset ringtone: $assetName, falling back to legacy synthesizers.", e)
            try {
                AlarmSynthesizer.playAlarmSound(soundName, volumeLevel, loop)
            } catch (ex: Exception) {
                android.util.Log.e("CRMViewModel", "Legacy synthesizer fallback failed too", ex)
            }
        }
    }

    fun stopAlarmSound() {
        android.util.Log.d("CRMViewModel", "stopAlarmSound called")
        try {
            AlarmSynthesizer.stopAlarmSound()
        } catch (e: Exception) {
            android.util.Log.e("CRMViewModel", "Error in stopAlarmSound: ${e.message}", e)
        }

        try {
            customMediaPlayer?.stop()
            customMediaPlayer?.release()
        } catch (e: Exception) {}
        customMediaPlayer = null

        try {
            com.example.audio.ReminderScheduler.stopRingingSound()
        } catch (e: Exception) {}
    }

    fun toggleTestAlarm(context: Context) {
        if (isTestingAlarm.value) {
            stopAlarmAndTesting()
        } else {
            isTestingAlarm.value = true
            triggerSoundOutput(context, isLoop = true)
        }
    }

    private fun stopAlarmAndTesting() {
        isTestingAlarm.value = false
        stopAlarmSound()
        try {
            com.example.audio.AlarmService.stopService(getApplication())
        } catch (e: Exception) {
            android.util.Log.e("CRMViewModel", "Failed to stop AlarmService in stopAlarmAndTesting", e)
        }
    }

    private var customMediaPlayer: android.media.MediaPlayer? = null

    private fun triggerSoundOutput(context: Context, isLoop: Boolean) {
        android.util.Log.d("CRMViewModel", "triggerSoundOutput: useCustom=${alarmUseCustom.value}, isLoop=$isLoop")
        if (alarmUseCustom.value) {
            val customPath = sharedPrefs.getString("alarm_custom_path", null)
            if (customPath != null && File(customPath).exists()) {
                try {
                    customMediaPlayer?.stop()
                    customMediaPlayer?.release()
                } catch (e: Exception) {}

                try {
                    customMediaPlayer = android.media.MediaPlayer().apply {
                        setDataSource(customPath)
                        setAudioStreamType(AudioManager.STREAM_MUSIC)
                        isLooping = isLoop
                        val volumeFactor = AlarmSynthesizer.getVolumeFactor(alarmVolume.value)
                        setVolume(volumeFactor, volumeFactor)
                        prepare()
                        start()
                    }
                    android.util.Log.d("CRMViewModel", "MediaPlayer playing custom sound: $customPath")
                } catch (e: Exception) {
                    android.util.Log.e("CRMViewModel", "MediaPlayer failed playing custom audio, falling back.", e)
                    // Fall back to synthesizers if media player fails to load file structures
                    playAlarmSound(alarmSound.value, alarmVolume.value, isLoop)
                }
            } else {
                android.util.Log.w("CRMViewModel", "Custom file path missing, falling back to synthesizers.")
                // Fall back if file missing
                playAlarmSound(alarmSound.value, alarmVolume.value, isLoop)
            }
        } else {
            playAlarmSound(alarmSound.value, alarmVolume.value, isLoop)
        }
    }

    /* --- IN-APP ACTIVE ALARM SCHEDULING ENGINE --- */

    fun snoozeActiveAlarm(minutes: Int) {
        val lead = ringingLead.value ?: return
        stopAlarmAndTesting()

        viewModelScope.launch {
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.MINUTE, minutes)

            val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val sdfTime = SimpleDateFormat("HH:mm", Locale.US)

            val snoozeDate = sdfDate.format(calendar.time)
            val snoozeTime = sdfTime.format(calendar.time)

            val updated = lead.copy(
                reminderDate = snoozeDate,
                reminderTime = snoozeTime,
                reminderStatus = "Pending"
            )
            triggeredOverdueIds.remove(lead.id)
            repository.insertLead(updated)
            ReminderScheduler.scheduleReminder(getApplication(), updated)
            ringingLead.value = null
        }
    }

    fun dismissActiveAlarm() {
        val lead = ringingLead.value ?: return
        stopAlarmAndTesting()

        viewModelScope.launch {
            val updated = lead.copy(reminderStatus = "Dismissed")
            repository.insertLead(updated)
            ReminderScheduler.scheduleReminder(getApplication(), updated)
            ringingLead.value = null
        }
    }

    fun markActiveAlarmComplete() {
        val lead = ringingLead.value ?: return
        stopAlarmAndTesting()

        viewModelScope.launch {
            val updated = lead.copy(
                status = "Complete",
                reminderStatus = "Completed"
            )
            repository.insertLead(updated)
            ReminderScheduler.scheduleReminder(getApplication(), updated)
            ringingLead.value = null
        }
    }

    fun closeActiveAlarmPopupOnly() {
        stopAlarmAndTesting()
        ringingLead.value = null
    }

    /* --- HELPERS & COMPILATION --- */

    fun getSystemTodayDateStr(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    fun getReminderCategory(lead: LeadEntity, todayStr: String): String? {
        if (lead.reminderDate.isEmpty()) return null
        return if (lead.reminderDate == todayStr) {
            if (lead.reminderTime.isNotEmpty()) {
                val currentTimeStr = SimpleDateFormat("HH:mm", Locale.US).format(Date())
                if (lead.reminderTime < currentTimeStr) {
                    "overdue"
                } else {
                    "today"
                }
            } else {
                "today"
            }
        } else if (lead.reminderDate < todayStr) {
            "overdue"
        } else {
            "upcoming"
        }
    }

    override fun onCleared() {
        super.onCleared()
        reminderCheckJob?.cancel()
        stopAlarmAndTesting()
    }

    /* --- BACKUP STRATEGY: PARSING JSON NATIVELY --- */

    fun exportBackupJson(): String {
        val array = JSONArray()
        val activeList = allLeadsList.value
        for (lead in activeList) {
            val obj = JSONObject().apply {
                put("id", lead.id)
                put("name", lead.name)
                put("mobile", lead.mobile)
                put("diseases", JSONArray(lead.diseases))
                put("otherDisease", lead.otherDisease)
                put("relation", lead.relation)
                put("otherRelation", lead.otherRelation)
                put("status", lead.status)
                put("reminderDate", lead.reminderDate)
                put("reminderTime", lead.reminderTime)
                put("reminderNote", lead.reminderNote)
                put("reminderStatus", lead.reminderStatus)
                put("notes", lead.notes)
                put("archived", lead.archived)
                put("lastCall", lead.lastCall ?: JSONObject.NULL)
                put("timestamp", lead.timestamp)
            }
            array.put(obj)
        }
        return array.toString(2)
    }

    fun importLeadsFromJson(jsonString: String): String {
        return try {
            val array = JSONArray(jsonString)
            var added = 0
            var skipped = 0

            val currentList = allLeadsList.value
            val listToInsert = mutableListOf<LeadEntity>()

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                val mobile = obj.optString("mobile", "").trim()
                val status = obj.optString("status", "Pending")

                if (name.isNotEmpty() && mobile.isNotEmpty()) {
                    val exists = currentList.any { it.mobile == mobile } || listToInsert.any { it.mobile == mobile }
                    if (!exists) {
                        val diseasesArray = obj.optJSONArray("diseases")
                        val diseasesList = mutableListOf<String>()
                        if (diseasesArray != null) {
                            for (j in 0 until diseasesArray.length()) {
                                val disease = diseasesArray.optString(j, "").trim()
                                if (disease.isNotEmpty()) {
                                    diseasesList.add(disease)
                                }
                            }
                        }

                        val activeUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
                        val lead = LeadEntity(
                            id = UUID.randomUUID().toString() + "_" + (1000..9999).random(),
                            name = name,
                            mobile = mobile,
                            diseases = JSONArray(diseasesList).toString(),
                            otherDisease = obj.optString("otherDisease", ""),
                            relation = obj.optString("relation", "Self"),
                            otherRelation = obj.optString("otherRelation", ""),
                            status = status,
                            reminderDate = obj.optString("reminderDate", ""),
                            reminderTime = obj.optString("reminderTime", ""),
                            reminderNote = obj.optString("reminderNote", ""),
                            reminderStatus = obj.optString("reminderStatus", "Pending"),
                            notes = obj.optString("notes", ""),
                            archived = obj.optBoolean("archived", false),
                            lastCall = if (obj.isNull("lastCall") || !obj.has("lastCall")) null else obj.optString("lastCall", "").takeIf { it.isNotEmpty() && it != "null" },
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            ownerUid = activeUid
                        )
                        listToInsert.add(lead)
                        added++
                    } else {
                        skipped++
                    }
                }
            }

            if (listToInsert.isNotEmpty()) {
                viewModelScope.launch {
                    runCatching {
                        repository.insertLeads(listToInsert, com.example.sync.LeadWriteOrigin.LOCAL_IMPORT)
                        ReminderScheduler.rescheduleAllReminders(getApplication(), listToInsert)
                    }.onSuccess {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                getApplication(),
                                "Imported $added lead(s) successfully",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }.onFailure { e ->
                        android.util.Log.e("CRMViewModel", "Failed to insert imported leads", e)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                getApplication(),
                                "Failed to save imported leads: ${e.localizedMessage ?: "Database error"}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }

            "Import Complete!\nAdded: $added records\nSkipped (Duplicates): $skipped records"
        } catch (e: Exception) {
            android.util.Log.e("CRMViewModel", "Error parsing import JSON payload", e)
            "Error parsing file structure payload."
        }
    }

    fun importBackupJson(jsonString: String): String = importLeadsFromJson(jsonString)
}
