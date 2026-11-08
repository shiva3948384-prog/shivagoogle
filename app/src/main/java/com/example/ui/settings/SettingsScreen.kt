package com.example.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.models.AiModelDefinition
import com.example.domain.models.AppPreferences
import com.example.domain.models.AvailabilityStatus
import com.example.domain.models.MemoryCategory
import com.example.domain.models.MemorySourceType
import com.example.domain.models.ProjectContextMemory
import com.example.domain.models.ThemeMode
import com.example.domain.models.UserMemoryItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    userDisplayName: String,
    userEmail: String,
    isLiveCloudConfigured: Boolean,
    preferences: AppPreferences,
    modelCatalog: List<AiModelDefinition>,
    userMemories: List<UserMemoryItem> = emptyList(),
    activeProjects: List<ProjectContextMemory> = emptyList(),
    onUpdateDisplayName: (String) -> Unit,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onDefaultModelChanged: (String) -> Unit,
    onFallbackConfigChanged: (String?, Boolean) -> Unit,
    onEnterToSendChanged: (Boolean) -> Unit,
    onStreamResponsesChanged: (Boolean) -> Unit,
    onRenderMarkdownChanged: (Boolean) -> Unit,
    onReducedAnimationsChanged: (Boolean) -> Unit,
    onPersonalIntelligenceEnabledChanged: (Boolean) -> Unit = {},
    onAddExplicitMemory: (MemoryCategory, String) -> Unit = { _, _ -> },
    onDeleteMemory: (String) -> Unit = {},
    onDeleteProjectContext: (String) -> Unit = {},
    onClearAllPersonalMemory: () -> Unit = {},
    onClearAllHistory: () -> Unit,
    onDeleteAccount: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    var showEditNameDialog by remember { mutableStateOf(false) }
    var editedName by remember(userDisplayName) { mutableStateOf(userDisplayName) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var newMemoryText by remember { mutableStateOf("") }

    val availableModels = remember(modelCatalog) {
        modelCatalog.filter { it.availabilityStatus != AvailabilityStatus.UNAVAILABLE }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 660.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // 1. Account & Profile Section
                SettingsSectionCard(title = "Account & Profile") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = userDisplayName.ifBlank { "Smarter AI User" },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = userEmail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = {
                                editedName = userDisplayName
                                showEditNameDialog = true
                            },
                            modifier = Modifier.testTag("edit_profile_name_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = "Edit display name"
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    Text(
                        text = if (isLiveCloudConfigured) {
                            "Cloud Sync: Connected & Active"
                        } else {
                            "Cloud Sync: Local Mode Active"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 2. Appearance & Theme Section
                SettingsSectionCard(title = "Appearance") {
                    Text(
                        text = "Theme Mode",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = preferences.themeMode == mode,
                                onClick = { onThemeModeChanged(mode) },
                                label = { Text(mode.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.testTag("theme_mode_chip_${mode.name}")
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    SettingsSwitchRow(
                        title = "Reduced Animations",
                        subtitle = "Minimize motion and transitions across screens.",
                        checked = preferences.reducedAnimations,
                        onCheckedChange = onReducedAnimationsChanged,
                        testTag = "toggle_reduced_animations"
                    )
                }

                // 3. Personal Intelligence & Memory Engine (Privacy-Conscious User Context)
                SettingsSectionCard(title = "Personal Intelligence & Memory") {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Psychology,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Smarter AI 1.0 remembers your approved preferences, communication style, and ongoing projects with clear provenance separation. Sensitive financial, medical, or credential data is never stored.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    SettingsSwitchRow(
                        title = "Enable Personal Intelligence",
                        subtitle = "Allow Smarter AI 1.0 to retrieve relevant preferences and active project context.",
                        checked = preferences.personalIntelligenceEnabled,
                        onCheckedChange = onPersonalIntelligenceEnabledChanged,
                        testTag = "toggle_personal_intelligence"
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    // Add Explicit Preference / Memory
                    Text(
                        text = "Add Explicit Preference or Context",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newMemoryText,
                            onValueChange = { newMemoryText = it },
                            placeholder = {
                                Text(
                                    text = "e.g., Prefer concise Kotlin examples with Clean Architecture",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("add_explicit_memory_input")
                        )
                        Button(
                            onClick = {
                                val trimmed = newMemoryText.trim()
                                if (trimmed.length >= 3) {
                                    onAddExplicitMemory(
                                        MemoryCategory.USER_PREFERENCE,
                                        trimmed
                                    )
                                    newMemoryText = ""
                                }
                            },
                            enabled = newMemoryText.trim().length >= 3,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("add_explicit_memory_button")
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = "Save preference", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.padding(horizontal = 2.dp))
                            Text("Save")
                        }
                    }

                    // Active Projects List
                    if (activeProjects.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Text(
                            text = "Active Project Context (${activeProjects.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            activeProjects.forEach { proj ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("project_context_card_${proj.id}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = proj.projectName,
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            if (proj.techStack.isNotBlank()) {
                                                Text(
                                                    text = "Stack: ${proj.techStack}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Text(
                                                text = proj.summary,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(
                                            onClick = { onDeleteProjectContext(proj.id) },
                                            modifier = Modifier
                                                .size(32.dp)
                                                .testTag("delete_project_btn_${proj.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Close,
                                                contentDescription = "Remove project context",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Saved User Memories List
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Text(
                        text = "Saved Memories & Preferences (${userMemories.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("saved_memories_header")
                    )

                    if (userMemories.isEmpty()) {
                        Text(
                            text = "No personal memories saved yet. Tell Smarter AI what to remember or add a preference above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("empty_memories_notice")
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            userMemories.forEach { memory ->
                                val badgeLabel = when (memory.sourceType) {
                                    MemorySourceType.EXPLICIT_USER -> "Explicitly Provided"
                                    MemorySourceType.PREVIOUS_CONVERSATION -> "Conversation Memory"
                                    MemorySourceType.INFERRED_CONTEXT -> "Contextual Inference"
                                    MemorySourceType.EXTERNAL_SOURCE -> "External Source"
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("memory_item_card_${memory.id}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = "$badgeLabel • ${memory.category.displayTitle}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = memory.content,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        IconButton(
                                            onClick = { onDeleteMemory(memory.id) },
                                            modifier = Modifier
                                                .size(32.dp)
                                                .testTag("delete_memory_btn_${memory.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Close,
                                                contentDescription = "Delete memory",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = onClearAllPersonalMemory,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("clear_all_memories_button")
                        ) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                            Text("Clear All Personal Intelligence Memory")
                        }
                    }
                }

                // 4. Smarter AI 1.0 Intelligent Routing & Resilience
                SettingsSectionCard(title = "Smarter AI 1.0 Intelligence") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .testTag("settings_default_model_selector")
                    ) {
                        Text(
                            text = "Active Intelligence Engine",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Smarter AI 1.0 • Automatic Task Routing (Coding, Reasoning, Vision & Creative)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    SettingsSwitchRow(
                        title = "Automatic High-Availability Fallback",
                        subtitle = "Automatically route to secondary Smarter AI 1.0 engines if the primary engine is busy or rate-limited.",
                        checked = preferences.allowModelFallback,
                        onCheckedChange = { enabled ->
                            val fallbackId = preferences.fallbackModelId
                                ?: availableModels.firstOrNull { it.providerModelId != preferences.defaultModelId }?.providerModelId
                            onFallbackConfigChanged(fallbackId, enabled)
                        },
                        testTag = "toggle_model_fallback"
                    )
                }

                // 5. Chat & Response Preferences
                SettingsSectionCard(title = "Chat & Rendering") {
                    SettingsSwitchRow(
                        title = "Stream AI Responses (SSE)",
                        subtitle = "Display tokens progressively for models that support streaming.",
                        checked = preferences.streamResponses,
                        onCheckedChange = onStreamResponsesChanged,
                        testTag = "toggle_stream_responses"
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    SettingsSwitchRow(
                        title = "Formatted Markdown & Code Blocks",
                        subtitle = "Render headings, tables, lists, and syntax-highlighted code.",
                        checked = preferences.renderMarkdown,
                        onCheckedChange = onRenderMarkdownChanged,
                        testTag = "toggle_render_markdown"
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    SettingsSwitchRow(
                        title = "Enter Key Sends Message",
                        subtitle = "Send immediately when pressing Enter on the keyboard.",
                        checked = preferences.enterToSend,
                        onCheckedChange = onEnterToSendChanged,
                        testTag = "toggle_enter_to_send"
                    )
                }

                // 6. Data Controls & Privacy
                SettingsSectionCard(title = "Data Controls & Privacy") {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Account-level security ensures only your authenticated account can read or modify your conversations. All provider credentials and verification secrets remain strictly on the Smarter AI backend and are never embedded in this APK.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    OutlinedButton(
                        onClick = { showClearHistoryDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_clear_history_button")
                    ) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        Text("Clear All Conversation History")
                    }

                    OutlinedButton(
                        onClick = onSignOut,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_sign_out_button")
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null)
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        Text("Sign Out")
                    }

                    Button(
                        onClick = { showDeleteAccountDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_delete_account_button")
                    ) {
                        Icon(Icons.Outlined.DeleteForever, contentDescription = null)
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        Text("Delete Account & Data")
                    }
                }

                // 7. About Section
                SettingsSectionCard(title = "About Smarter AI") {
                    Text(
                        text = "Smarter AI 1.0 • Personal Intelligence & Real-Time Data Engine",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Cloud-synced conversations, Personal Intelligence memory, real-time web/market intelligence, image studio, and voice read-aloud.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showEditNameDialog) {
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            title = { Text("Update Display Name") },
            text = {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editedName.trim().length >= 2) {
                            onUpdateDisplayName(editedName.trim())
                        }
                        showEditNameDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditNameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("Clear All Chat History?") },
            text = {
                Text("This will permanently delete all conversations and messages for your account.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearAllHistory()
                        showClearHistoryDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("confirm_clear_history_button")
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountDialog = false },
            title = { Text("Delete Account Permanently?") },
            text = {
                Text("Your account profile, conversation history, and cached data will be permanently deleted. This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAccountDialog = false
                        onDeleteAccount()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("confirm_delete_account_button")
                ) {
                    Text("Delete Account")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
