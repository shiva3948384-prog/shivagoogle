package com.example.ui.chat

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.livedata.LiveMarketDataRegistry
import com.example.data.local.GeneratedImageBinaryCache
import com.example.domain.models.ChatMessage
import com.example.domain.models.GeneratedImageResult
import com.example.domain.models.LiveMarketDataPayload
import com.example.domain.models.MessageAttachment
import com.example.domain.models.MessageRole
import com.example.domain.models.MessageStatus
import com.example.domain.models.ProjectContextMemory
import com.example.domain.models.ThemeMode
import com.example.domain.models.UserMemoryItem
import com.example.domain.usecases.SmartModelRouter
import com.example.ui.components.LiveMarketChartCard
import com.example.ui.components.MarkdownMessageContent
import com.example.ui.history.ConversationDrawerContent
import com.example.ui.imagegen.DedicatedChatImageGenerationScreen
import com.example.ui.imagegen.saveGeneratedImageToGallery
import com.example.ui.imagegen.shareGeneratedImage
import com.example.ui.theme.StatusAvailableGreen
import com.example.ui.theme.StatusErrorRed
import com.example.ui.theme.StatusWarningAmber
import com.example.ui.theme.smarterAiAmbientHeaderBrush
import com.example.ui.theme.smarterAiSignatureGradient
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class PromptSuggestionItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val promptText: String,
    val icon: ImageVector,
    val requiresImageAttachment: Boolean = false
)

val WELCOME_SUGGESTIONS = listOf(
    PromptSuggestionItem(
        id = "help_me_understand_something",
        title = "Help me understand something.",
        subtitle = "Clear analogies & technical breakdowns",
        promptText = "Explain how zero-knowledge proofs work using a clear everyday analogy, then show a concise technical breakdown.",
        icon = Icons.Outlined.Lightbulb
    ),
    PromptSuggestionItem(
        id = "write_something_for_me",
        title = "Write something for me.",
        subtitle = "Polished drafts, memos & creative copy",
        promptText = "Draft a crisp, high-impact product launch announcement introducing an intelligent assistant with strict privacy controls.",
        icon = Icons.Outlined.EditNote
    ),
    PromptSuggestionItem(
        id = "analyze_an_image",
        title = "Analyze an image.",
        subtitle = "Extract insights from photos & diagrams",
        promptText = "Analyze this image in detail and highlight the most important visual elements, text, or architecture patterns.",
        icon = Icons.Outlined.Image,
        requiresImageAttachment = true
    ),
    PromptSuggestionItem(
        id = "help_me_with_coding",
        title = "Help me with coding.",
        subtitle = "Clean architecture, debugging & algorithms",
        promptText = "Write an idiomatic Kotlin Coroutines Flow retry-with-exponential-backoff operator and explain how to test it.",
        icon = Icons.Outlined.Code
    ),
    PromptSuggestionItem(
        id = "research_the_latest_information",
        title = "Research the latest information.",
        subtitle = "Live news, markets & technology updates",
        promptText = "What are the latest technology news and breakthroughs today?",
        icon = Icons.Outlined.Public
    ),
    PromptSuggestionItem(
        id = "generate_creative_ideas",
        title = "Generate creative ideas.",
        subtitle = "Brainstorm concepts, designs & strategies",
        promptText = "Generate 5 distinctive product concepts that combine on-device privacy with personal workflow automation.",
        icon = Icons.Outlined.Psychology
    )
)

/**
 * Formats the personalized home screen greeting strictly from authenticated/local user profile data.
 * Never invents a user's name if unavailable.
 */
fun formatPersonalizedHomeGreeting(userDisplayName: String?): String {
    val cleaned = userDisplayName?.trim().orEmpty()
    val isGenericPlaceholder = cleaned.isEmpty() ||
        cleaned.equals("User", ignoreCase = true) ||
        cleaned.equals("Smarter AI User", ignoreCase = true) ||
        cleaned.equals("Authenticated User", ignoreCase = true)
    return if (!isGenericPlaceholder) {
        "Hey, $cleaned"
    } else {
        "Hey there"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onStartNewChat: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onOpenSearchScreen: () -> Unit,
    onOpenSettingsScreen: () -> Unit,
    onSignOut: () -> Unit,
    onOpenModelSelector: () -> Unit,
    onCloseModelSelector: () -> Unit,
    onModelSearchQueryChanged: (String) -> Unit,
    onModelCategorySelected: (com.example.domain.models.ModelCategory?) -> Unit,
    onRefreshModelCatalog: () -> Unit,
    onSelectModel: (String) -> Unit,
    onComposerTextChanged: (String) -> Unit,
    onApplyPromptSuggestion: (String) -> Unit,
    onAddAttachment: (MessageAttachment) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSendMessage: () -> Unit,
    onStopGeneration: () -> Unit,
    onRegenerateResponse: () -> Unit,
    onRetryFailedGeneration: () -> Unit,
    onStartEditingMessage: (ChatMessage) -> Unit,
    onCancelEditingMessage: () -> Unit,
    onDismissBanners: () -> Unit,
    onOpenImageStudio: () -> Unit = {},
    onOpenAudioStudio: () -> Unit = {},
    onOpenVoiceChat: () -> Unit = {},
    onRefreshLiveMarketData: (String) -> Unit = {},
    onCancelImageGeneration: () -> Unit = {},
    onRetryImageGeneration: () -> Unit = {},
    onDismissImageGenerationScreen: () -> Unit = {},
    onOpenGeneratedImageFromChat: (ChatMessage, MessageAttachment) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val activeImageSession = uiState.activeImageGenerationSession
    if (activeImageSession != null && activeImageSession.isScreenVisible) {
        DedicatedChatImageGenerationScreen(
            session = activeImageSession,
            onCancelGeneration = onCancelImageGeneration,
            onRetryGeneration = onRetryImageGeneration,
            onContinueInChat = onDismissImageGenerationScreen,
            modifier = modifier
        )
        return
    }

    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val isDark = when (uiState.preferences.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    // Photo Picker for Vision-capable models (Zero-permission Android Photo Picker)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val encodedDataUrl = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val rawBytes = stream.readBytes()
                            val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                            val finalBytes = if (decoded != null && decoded.width > 0 && decoded.height > 0) {
                                val maxDim = 896
                                val scale = minOf(1f, maxDim.toFloat() / maxOf(decoded.width, decoded.height))
                                val scaled = if (scale < 1f) {
                                    android.graphics.Bitmap.createScaledBitmap(
                                        decoded,
                                        (decoded.width * scale).toInt().coerceAtLeast(1),
                                        (decoded.height * scale).toInt().coerceAtLeast(1),
                                        true
                                    )
                                } else {
                                    decoded
                                }
                                val out = java.io.ByteArrayOutputStream()
                                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, out)
                                out.toByteArray()
                            } else {
                                rawBytes
                            }
                            val b64 = android.util.Base64.encodeToString(finalBytes, android.util.Base64.NO_WRAP)
                            "data:image/jpeg;base64,$b64"
                        }
                    }.getOrNull()
                }
                onAddAttachment(
                    MessageAttachment(
                        id = UUID.randomUUID().toString(),
                        uriString = uri.toString(),
                        mimeType = "image/jpeg",
                        fileName = "image_${System.currentTimeMillis()}.jpg",
                        base64DataUrl = encodedDataUrl
                    )
                )
            }
        }
    }

    // Pixel-accurate bottom detection that accounts for tall messages exceeding viewport height
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) {
                true
            } else {
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
                if (lastVisible == null) {
                    true
                } else {
                    val viewportBottom = layoutInfo.viewportEndOffset
                    val itemBottom = lastVisible.offset + lastVisible.size
                    lastVisible.index == totalItems - 1 && (itemBottom - viewportBottom) <= 96
                }
            }
        }
    }

    var isAutoScrollLockedToBottom by rememberSaveable { mutableStateOf(true) }
    var isProgrammaticScroll by remember { mutableStateOf(false) }

    // Respect user touch gestures: if the user manually scrolls upward, pause auto-scroll; resume when back at bottom
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to isAtBottom }
            .collect { (inProgress, atBottom) ->
                if (inProgress && !isProgrammaticScroll) {
                    isAutoScrollLockedToBottom = atBottom
                } else if (atBottom) {
                    isAutoScrollLockedToBottom = true
                }
            }
    }

    // Re-lock and scroll to bottom when the user sends a message or switches conversations
    LaunchedEffect(uiState.userSendScrollTrigger, uiState.activeConversationId) {
        isAutoScrollLockedToBottom = true
        if (uiState.messages.isNotEmpty()) {
            isProgrammaticScroll = true
            try {
                listState.scrollToBottomEdge(itemCount = uiState.messages.size, animate = false)
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    // Follow new messages and streaming tokens (coalesced to avoid excessive layout passes on every token)
    val lastMessageContentLength = uiState.messages.lastOrNull()?.content?.length ?: 0
    val streamingScrollBucket = if (uiState.streamingMessageText.isEmpty()) 0 else (uiState.streamingMessageText.length / 24) + 1
    LaunchedEffect(uiState.messages.size, streamingScrollBucket, lastMessageContentLength, uiState.isGenerating) {
        if (uiState.messages.isNotEmpty() && isAutoScrollLockedToBottom && !listState.isScrollInProgress) {
            isProgrammaticScroll = true
            try {
                listState.scrollToBottomEdge(itemCount = uiState.messages.size, animate = false)
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawerContent(
                groupedConversations = uiState.groupedConversations,
                activeConversationId = uiState.activeConversationId,
                userDisplayName = uiState.activeUserDisplayName,
                userEmail = uiState.activeUserEmail,
                onNewChatClick = {
                    coroutineScope.launch { drawerState.close() }
                    onStartNewChat()
                },
                onSearchChatsClick = {
                    coroutineScope.launch { drawerState.close() }
                    onOpenSearchScreen()
                },
                onSelectConversation = { id ->
                    coroutineScope.launch { drawerState.close() }
                    onSelectConversation(id)
                },
                onRenameConversation = onRenameConversation,
                onDeleteConversation = onDeleteConversation,
                onOpenSettings = {
                    coroutineScope.launch { drawerState.close() }
                    onOpenSettingsScreen()
                },
                onOpenImageStudio = {
                    coroutineScope.launch { drawerState.close() }
                    onOpenImageStudio()
                },
                onOpenAudioStudio = {
                    coroutineScope.launch { drawerState.close() }
                    onOpenAudioStudio()
                },
                onSignOut = {
                    coroutineScope.launch { drawerState.close() }
                    onSignOut()
                }
            )
        }
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.systemBars,
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = { coroutineScope.launch { drawerState.open() } },
                            modifier = Modifier.testTag("open_drawer_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Menu,
                                contentDescription = "Open conversation sidebar"
                            )
                        }
                    },
                    title = {
                        Column(
                            horizontalAlignment = Alignment.Start
                        ) {
                            // Unified Smarter AI 1.0 Identity Pill
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                                modifier = Modifier.testTag("top_bar_model_selector_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = StatusAvailableGreen,
                                        modifier = Modifier.size(7.dp)
                                    ) {}
                                    Text(
                                        text = SmartModelRouter.PUBLIC_MODEL_IDENTITY,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (uiState.activeConversation != null) {
                                Text(
                                    text = uiState.activeConversation.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .padding(start = 6.dp, top = 2.dp)
                                        .testTag("active_conversation_title")
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onOpenAudioStudio,
                            modifier = Modifier.testTag("top_bar_audio_studio_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.GraphicEq,
                                contentDescription = "Open Audio Studio & Read Aloud"
                            )
                        }
                        IconButton(
                            onClick = onStartNewChat,
                            modifier = Modifier.testTag("top_bar_new_chat_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = "Start New Chat"
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(smarterAiAmbientHeaderBrush(isDark))
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding()
            ) {
                // Fallback Model Notification Banner
                AnimatedVisibility(
                    visible = !uiState.fallbackBannerNotice.isNullOrBlank(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        color = StatusWarningAmber.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, StatusWarningAmber.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .testTag("fallback_model_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.WarningAmber,
                                contentDescription = null,
                                tint = StatusWarningAmber
                            )
                            Text(
                                text = uiState.fallbackBannerNotice.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = onDismissBanners,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // Error Banner with Retry
                AnimatedVisibility(
                    visible = !uiState.errorBannerMessage.isNullOrBlank(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        color = StatusErrorRed.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, StatusErrorRed.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .testTag("chat_error_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = uiState.errorBannerMessage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusErrorRed,
                                modifier = Modifier.weight(1f)
                            )
                            if (uiState.canRetryLastPrompt) {
                                TextButton(
                                    onClick = onRetryFailedGeneration,
                                    modifier = Modifier.testTag("error_banner_retry_button")
                                ) {
                                    Text("Retry", color = StatusErrorRed, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            IconButton(
                                onClick = onDismissBanners,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Dismiss error",
                                    tint = StatusErrorRed,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Main Content Area: Either Gemini-Inspired Welcome Home Screen or Redesigned Chat Stream
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.TopCenter
                ) {
                    if (uiState.activeConversationId == null && uiState.messages.isEmpty() && !uiState.isGenerating) {
                        NewChatWelcomeView(
                            userDisplayName = uiState.activeUserDisplayName,
                            isDark = isDark,
                            personalIntelligenceEnabled = uiState.preferences.personalIntelligenceEnabled,
                            reducedAnimations = uiState.preferences.reducedAnimations,
                            activeProjects = uiState.activeProjects,
                            userMemories = uiState.userMemories,
                            onSuggestionClicked = { suggestion ->
                                if (suggestion.requiresImageAttachment) {
                                    onApplyPromptSuggestion(suggestion.promptText)
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                } else {
                                    onApplyPromptSuggestion(suggestion.promptText)
                                }
                            },
                            onContinueProjectClicked = { project ->
                                onApplyPromptSuggestion("Continue working on my ${project.projectName} project.")
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 720.dp)
                        )
                    } else {
                        val lastAssistantId = remember(uiState.messages) {
                            uiState.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
                        }
                        val hasTrailingAssistantMessage = remember(uiState.messages) {
                            uiState.messages.lastOrNull()?.role == MessageRole.ASSISTANT
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 760.dp)
                                .testTag("chat_messages_list"),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            items(
                                items = uiState.messages,
                                key = { it.id }
                            ) { message ->
                                val isLastAssistant = message.role == MessageRole.ASSISTANT &&
                                    message.id == lastAssistantId
                                val displayMessage = if (
                                    isLastAssistant &&
                                    uiState.isGenerating &&
                                    uiState.streamingMessageText.isNotEmpty()
                                ) {
                                    message.copy(content = uiState.streamingMessageText)
                                } else {
                                    message
                                }

                                val showImageAnalysisForBubble = isLastAssistant &&
                                    uiState.isGenerating &&
                                    uiState.isAnalyzingImage &&
                                    uiState.imageAnalysisState != com.example.domain.models.ImageAnalysisState.RESPONSE_GENERATION &&
                                    uiState.imageAnalysisState != com.example.domain.models.ImageAnalysisState.COMPLETED &&
                                    uiState.imageAnalysisState != com.example.domain.models.ImageAnalysisState.FAILED &&
                                    uiState.imageAnalysisState != com.example.domain.models.ImageAnalysisState.CANCELLED &&
                                    uiState.streamingMessageText.isEmpty() &&
                                    displayMessage.content.isBlank()

                                val liveMarketPayload = uiState.liveMarketDataByMessageId[message.id]
                                    ?: LiveMarketDataRegistry.getForMessage(message.id, message.clientRequestId)

                                MessageBubbleItem(
                                    message = displayMessage,
                                    renderMarkdown = uiState.preferences.renderMarkdown,
                                    reducedAnimations = uiState.preferences.reducedAnimations,
                                    isGenerating = uiState.isGenerating && isLastAssistant,
                                    isAnalyzingImage = showImageAnalysisForBubble,
                                    liveActivityStatus = if (isLastAssistant && uiState.isGenerating) uiState.activeLiveDataActivityStatus else null,
                                    liveMarketPayload = liveMarketPayload,
                                    canRegenerate = isLastAssistant && !uiState.isGenerating,
                                    onEditUserMessage = { onStartEditingMessage(message) },
                                    onRegenerate = onRegenerateResponse,
                                    onRefreshLiveMarketData = { onRefreshLiveMarketData(message.id) },
                                    onOpenGeneratedImage = { att -> onOpenGeneratedImageFromChat(displayMessage, att) }
                                )
                            }

                            if (uiState.isGenerating && !hasTrailingAssistantMessage) {
                                item(key = "pending_assistant_generation") {
                                    val showPendingImageAnalysis = uiState.isAnalyzingImage &&
                                        uiState.imageAnalysisState != com.example.domain.models.ImageAnalysisState.RESPONSE_GENERATION &&
                                        uiState.imageAnalysisState != com.example.domain.models.ImageAnalysisState.COMPLETED &&
                                        uiState.imageAnalysisState != com.example.domain.models.ImageAnalysisState.FAILED &&
                                        uiState.imageAnalysisState != com.example.domain.models.ImageAnalysisState.CANCELLED &&
                                        uiState.streamingMessageText.isEmpty()
                                    MessageBubbleItem(
                                        message = ChatMessage(
                                            id = "pending_assistant",
                                            conversationId = uiState.activeConversationId.orEmpty(),
                                            userId = uiState.activeUserId.orEmpty(),
                                            role = MessageRole.ASSISTANT,
                                            content = uiState.streamingMessageText,
                                            modelId = uiState.selectedModelId,
                                            status = MessageStatus.STREAMING
                                        ),
                                        renderMarkdown = uiState.preferences.renderMarkdown,
                                        reducedAnimations = uiState.preferences.reducedAnimations,
                                        isGenerating = true,
                                        isAnalyzingImage = showPendingImageAnalysis,
                                        liveActivityStatus = uiState.activeLiveDataActivityStatus,
                                        liveMarketPayload = null,
                                        canRegenerate = false,
                                        onEditUserMessage = {},
                                        onRegenerate = onRegenerateResponse
                                    )
                                }
                            }
                        }

                        // Floating "Jump to Latest" button when user has scrolled upward
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !isAtBottom && uiState.messages.isNotEmpty(),
                            enter = fadeIn(animationSpec = tween(durationMillis = if (uiState.preferences.reducedAnimations) 0 else 180)),
                            exit = fadeOut(animationSpec = tween(durationMillis = if (uiState.preferences.reducedAnimations) 0 else 150)),
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp)
                        ) {
                            Surface(
                                onClick = {
                                    isAutoScrollLockedToBottom = true
                                    coroutineScope.launch {
                                        isProgrammaticScroll = true
                                        try {
                                            listState.scrollToBottomEdge(
                                                itemCount = uiState.messages.size,
                                                animate = !uiState.preferences.reducedAnimations
                                            )
                                        } finally {
                                            isProgrammaticScroll = false
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                shadowElevation = 4.dp,
                                modifier = Modifier.testTag("scroll_to_bottom_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.KeyboardArrowDown,
                                        contentDescription = "Scroll to latest message",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "Latest",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                // Premium Bottom-Anchored Chat Composer Bar
                ChatComposerBar(
                    composerText = uiState.composerText,
                    selectedModel = uiState.selectedModel,
                    pendingAttachments = uiState.pendingAttachments,
                    editingMessageId = uiState.editingMessageId,
                    isGenerating = uiState.isGenerating,
                    enterToSend = uiState.preferences.enterToSend,
                    reducedAnimations = uiState.preferences.reducedAnimations,
                    showToolShortcuts = uiState.activeConversationId == null && uiState.messages.isEmpty(),
                    onTextChanged = onComposerTextChanged,
                    onPickImage = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onQuickToolPrompt = onApplyPromptSuggestion,
                    onRemoveAttachment = onRemoveAttachment,
                    onCancelEditing = onCancelEditingMessage,
                    onSendMessage = onSendMessage,
                    onStopGeneration = onStopGeneration,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewChatWelcomeView(
    userDisplayName: String,
    isDark: Boolean,
    personalIntelligenceEnabled: Boolean,
    reducedAnimations: Boolean = false,
    activeProjects: List<ProjectContextMemory>,
    userMemories: List<UserMemoryItem>,
    onSuggestionClicked: (PromptSuggestionItem) -> Unit,
    onContinueProjectClicked: (ProjectContextMemory) -> Unit,
    modifier: Modifier = Modifier
) {
    val personalizedGreeting = remember(userDisplayName) {
        formatPersonalizedHomeGreeting(userDisplayName)
    }
    val signatureBrush = remember(isDark) {
        smarterAiSignatureGradient(isDark)
    }

    var hasEntered by remember { mutableStateOf(reducedAnimations) }
    LaunchedEffect(Unit) {
        if (!hasEntered) {
            hasEntered = true
        }
    }
    val headerTranslationY by animateFloatAsState(
        targetValue = if (hasEntered || reducedAnimations) 0f else 14f,
        animationSpec = tween(durationMillis = if (reducedAnimations) 0 else 280, easing = FastOutSlowInEasing),
        label = "welcomeHeaderTranslationY"
    )
    val headerAlpha by animateFloatAsState(
        targetValue = if (hasEntered || reducedAnimations) 1f else 0.88f,
        animationSpec = tween(durationMillis = if (reducedAnimations) 0 else 280, easing = FastOutSlowInEasing),
        label = "welcomeHeaderAlpha"
    )

    LazyColumn(
        modifier = modifier.testTag("new_chat_welcome_view"),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(26.dp)
    ) {
        // 1. Spacious Personalized Greeting Header (Gemini-inspired layout, original Smarter AI 1.0 identity)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .graphicsLayer {
                        translationY = headerTranslationY
                        alpha = headerAlpha
                    },
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Text(
                        text = SmartModelRouter.PUBLIC_MODEL_IDENTITY,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = personalizedGreeting,
                    style = MaterialTheme.typography.displayMedium.merge(
                        TextStyle(brush = signatureBrush)
                    ),
                    modifier = Modifier.testTag("home_welcome_greeting")
                )

                Text(
                    text = "How can I help you today?",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("home_welcome_subtitle")
                )
            }
        }

        // 2. Contextual Personal Intelligence Continuation Pill (when the user has an active project)
        if (personalIntelligenceEnabled && activeProjects.isNotEmpty()) {
            val latestProject = activeProjects.first()
            item {
                Surface(
                    onClick = { onContinueProjectClicked(latestProject) },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                        .testTag("home_continue_project_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Continue working on ${latestProject.projectName}",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = latestProject.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // 3. Contextual Prompt Suggestion Cards (Primary Home Experience with staggered entrance & press feedback)
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                WELCOME_SUGGESTIONS.chunked(2).forEachIndexed { rowIdx, rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowItems.forEachIndexed { colIdx, item ->
                            val cardIdx = rowIdx * 2 + colIdx
                            val interactionSource = remember { MutableInteractionSource() }
                            val isPressed by interactionSource.collectIsPressedAsState()
                            val pressScale by animateFloatAsState(
                                targetValue = if (isPressed && !reducedAnimations) 0.97f else 1f,
                                animationSpec = spring(stiffness = 500f),
                                label = "cardPressScale_$cardIdx"
                            )
                            val cardOffsetY by animateFloatAsState(
                                targetValue = if (hasEntered || reducedAnimations) 0f else (12f + cardIdx * 4f),
                                animationSpec = tween(
                                    durationMillis = if (reducedAnimations) 0 else 260,
                                    delayMillis = if (reducedAnimations) 0 else cardIdx * 35,
                                    easing = FastOutSlowInEasing
                                ),
                                label = "cardEntranceY_$cardIdx"
                            )
                            val borderColor by animateColorAsState(
                                targetValue = if (isPressed) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                } else {
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                                },
                                animationSpec = tween(durationMillis = 150),
                                label = "cardBorderColor_$cardIdx"
                            )

                            Surface(
                                onClick = { onSuggestionClicked(item) },
                                interactionSource = interactionSource,
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, borderColor),
                                modifier = Modifier
                                    .weight(1f)
                                    .graphicsLayer {
                                        scaleX = pressScale
                                        scaleY = pressScale
                                        translationY = cardOffsetY
                                    }
                                    .testTag("prompt_suggestion_${item.id}")
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = item.icon,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = item.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Subtle Personal Intelligence & Real-Time Engine capability footer
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = if (personalIntelligenceEnabled) {
                            if (userMemories.isNotEmpty()) {
                                "Personal Intelligence Active (${userMemories.size} memories)"
                            } else {
                                "Personal Intelligence Active"
                            }
                        } else {
                            "Personal Intelligence Paused"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "Real-Time Data & Web Research",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubbleItem(
    message: ChatMessage,
    renderMarkdown: Boolean,
    reducedAnimations: Boolean = false,
    isGenerating: Boolean,
    isAnalyzingImage: Boolean = false,
    liveActivityStatus: String? = null,
    liveMarketPayload: LiveMarketDataPayload? = null,
    canRegenerate: Boolean,
    onEditUserMessage: () -> Unit,
    onRegenerate: () -> Unit,
    onRefreshLiveMarketData: () -> Unit = {},
    onOpenGeneratedImage: (MessageAttachment) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val timeFormatter = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    if (message.role == MessageRole.USER) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("user_message_item_${message.id}"),
            horizontalAlignment = Alignment.End
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 24.dp,
                    topEnd = 24.dp,
                    bottomStart = 24.dp,
                    bottomEnd = 6.dp
                ),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)),
                modifier = Modifier.widthIn(max = 540.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp)) {
                    if (message.attachments.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Image,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Attached ${message.attachments.size} image(s)",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    SelectionContainer(modifier = Modifier.testTag("selectable_user_message_${message.id}")) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 4.dp, end = 4.dp)
            ) {
                Text(
                    text = timeFormatter.format(Date(message.createdAtEpochMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = onEditUserMessage,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("edit_user_message_btn_${message.id}")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "Edit and resubmit message",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    } else {
        // Assistant Message — Spacious, Uncluttered Smarter AI 1.0 Layout
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("assistant_message_item_${message.id}"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Unified Smarter AI 1.0 Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier.size(26.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Text(
                    text = SmartModelRouter.PUBLIC_MODEL_IDENTITY,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!isAnalyzingImage && (message.status == MessageStatus.STREAMING || isGenerating)) {
                    StreamingPulseIndicator()
                }
            }

            if (!liveActivityStatus.isNullOrBlank() && message.content.isBlank()) {
                LiveDataActivityStatusIndicator(
                    statusText = liveActivityStatus,
                    modifier = Modifier.padding(start = 34.dp)
                )
            } else if (isAnalyzingImage && message.content.isBlank()) {
                ImageAnalysisStatusIndicator(
                    modifier = Modifier.padding(start = 34.dp)
                )
            }

            // Interactive Live Market Data, Web Research & Citation Card
            if (liveMarketPayload != null) {
                LiveMarketChartCard(
                    payload = liveMarketPayload,
                    onRefreshRequested = onRefreshLiveMarketData,
                    modifier = Modifier.padding(start = 34.dp, bottom = 4.dp)
                )
            }

            // Message Body or Animated Thinking Indicator
            if (message.content.isNotBlank()) {
                MarkdownMessageContent(
                    content = message.content,
                    renderMarkdown = renderMarkdown,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 34.dp)
                )
            } else if (!isAnalyzingImage && liveActivityStatus.isNullOrBlank() && (message.status == MessageStatus.STREAMING || isGenerating)) {
                AnimatedThinkingIndicator(
                    reducedAnimations = reducedAnimations,
                    modifier = Modifier.padding(start = 34.dp)
                )
            }

            // Generated Image Attachments inside Assistant Chat Bubble
            if (message.attachments.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 34.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    message.attachments.forEach { attachment ->
                        val imageBytes = remember(attachment.id, attachment.uriString, attachment.base64DataUrl) {
                            GeneratedImageBinaryCache.getBytes(
                                attachmentId = attachment.id,
                                uriString = attachment.uriString,
                                base64DataUrl = attachment.base64DataUrl
                            )
                        }
                        val bitmap = remember(imageBytes) {
                            imageBytes?.let {
                                BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenGeneratedImage(attachment) }
                                .testTag("chat_generated_image_card_${attachment.id}")
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (bitmap != null) {
                                    val ratio = if (bitmap.width > 0 && bitmap.height > 0) {
                                        bitmap.width.toFloat() / bitmap.height.toFloat()
                                    } else {
                                        1f
                                    }
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = message.content,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(ratio.coerceIn(0.55f, 1.8f))
                                            .clip(RoundedCornerShape(14.dp))
                                            .testTag("chat_generated_image_preview")
                                    )
                                }
                                if (imageBytes != null) {
                                    val imgObj = remember(attachment.id, imageBytes) {
                                        GeneratedImageResult(
                                            id = attachment.id,
                                            prompt = message.content,
                                            modelId = message.modelId,
                                            width = bitmap?.width ?: 1024,
                                            height = bitmap?.height ?: 1024,
                                            seed = 42L,
                                            imageBytes = imageBytes,
                                            mimeType = attachment.mimeType,
                                            imageUrl = attachment.uriString,
                                            localFilePath = attachment.uriString
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    saveGeneratedImageToGallery(context, imgObj)
                                                }
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("chat_save_generated_image_button")
                                        ) {
                                            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Save")
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    shareGeneratedImage(context, imgObj)
                                                }
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("chat_share_generated_image_button")
                                        ) {
                                            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Share")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Interrupted or Error State Notice inside the message
            if (message.status == MessageStatus.INTERRUPTED || message.status == MessageStatus.ERROR) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = StatusErrorRed.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, StatusErrorRed.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .padding(start = 34.dp)
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = message.errorMessage ?: if (message.status == MessageStatus.INTERRUPTED) {
                                "Response generation was interrupted."
                            } else {
                                "Failed to generate response."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusErrorRed,
                            modifier = Modifier.weight(1f)
                        )
                        if (canRegenerate) {
                            TextButton(onClick = onRegenerate) {
                                Text("Retry", color = StatusErrorRed)
                            }
                        }
                    }
                }
            }

            // Action Toolbar (Regenerate & Timestamp)
            if (message.status != MessageStatus.STREAMING) {
                Row(
                    modifier = Modifier.padding(start = 34.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (canRegenerate) {
                        IconButton(
                            onClick = onRegenerate,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("regenerate_assistant_response_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Regenerate response",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Text(
                        text = timeFormatter.format(Date(message.createdAtEpochMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AnimatedThinkingIndicator(
    reducedAnimations: Boolean = false,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "assistant_thinking_transition")
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = modifier.testTag("assistant_thinking_indicator")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(3) { index ->
                val dotScale by transition.animateFloat(
                    initialValue = if (reducedAnimations) 1.0f else 0.65f,
                    targetValue = if (reducedAnimations) 1.0f else 1.2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 520, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                        initialStartOffset = StartOffset(index * 150)
                    ),
                    label = "thinking_dot_scale_$index"
                )
                val dotAlpha by transition.animateFloat(
                    initialValue = if (reducedAnimations) 0.85f else 0.35f,
                    targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 520, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                        initialStartOffset = StartOffset(index * 150)
                    ),
                    label = "thinking_dot_alpha_$index"
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(7.dp)
                        .scale(dotScale)
                        .alpha(dotAlpha)
                        .testTag("thinking_dot_$index")
                ) {}
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Thinking…",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StreamingPulseIndicator() {
    val transition = rememberInfiniteTransition(label = "streaming_pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .alpha(alpha)
            .testTag("streaming_response_indicator")
    ) {
        Surface(
            shape = CircleShape,
            color = StatusAvailableGreen,
            modifier = Modifier.size(6.dp)
        ) {}
        Text(
            text = "Streaming",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ImageAnalysisStatusIndicator(
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "image_analysis_pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "image_analysis_alpha"
    )
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        modifier = modifier.testTag("image_analysis_status_indicator")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(16.dp)
                    .alpha(alpha)
            )
            Text(
                text = "Analyzing Provided Image",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun LiveDataActivityStatusIndicator(
    statusText: String,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "live_data_pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "live_data_alpha"
    )
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        modifier = modifier.testTag("live_data_activity_indicator")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(8.dp)
                    .alpha(alpha)
            ) {}
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ChatComposerBar(
    composerText: String,
    selectedModel: com.example.domain.models.AiModelDefinition,
    pendingAttachments: List<MessageAttachment>,
    editingMessageId: String?,
    isGenerating: Boolean,
    enterToSend: Boolean,
    reducedAnimations: Boolean = false,
    showToolShortcuts: Boolean,
    onTextChanged: (String) -> Unit,
    onPickImage: () -> Unit,
    onQuickToolPrompt: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onCancelEditing: () -> Unit,
    onSendMessage: () -> Unit,
    onStopGeneration: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.8f)),
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .animateContentSize(
                        animationSpec = tween(
                            durationMillis = if (reducedAnimations) 0 else 180,
                            easing = FastOutSlowInEasing
                        )
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    // Tool Shortcuts Row on Home / Empty Composer
                    if (showToolShortcuts && editingMessageId == null && pendingAttachments.isEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                .testTag("composer_tool_shortcuts_row"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ComposerToolShortcutChip(
                                icon = Icons.Outlined.Image,
                                label = "Upload Image",
                                onClick = onPickImage,
                                testTag = "composer_shortcut_upload_image"
                            )
                            ComposerToolShortcutChip(
                                icon = Icons.Outlined.Public,
                                label = "Live Research",
                                onClick = { onQuickToolPrompt("What are the latest technology news and breakthroughs today?") },
                                testTag = "composer_shortcut_live_research"
                            )
                            ComposerToolShortcutChip(
                                icon = Icons.Outlined.AutoAwesome,
                                label = "Create Image",
                                onClick = { onQuickToolPrompt("Generate an image of a futuristic architectural sanctuary at golden hour.") },
                                testTag = "composer_shortcut_create_image"
                            )
                            ComposerToolShortcutChip(
                                icon = Icons.Outlined.Code,
                                label = "Code Assistant",
                                onClick = { onQuickToolPrompt("Help me architect a clean, scalable Kotlin module.") },
                                testTag = "composer_shortcut_code_assistant"
                            )
                        }
                    }

                    // Editing Message Banner
                    if (editingMessageId != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Editing message (subsequent replies will be regenerated)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(
                                onClick = onCancelEditing,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Cancel editing",
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }

                    // Pending Attachments Preview Row
                    if (pendingAttachments.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            pendingAttachments.forEach { att ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Image,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = att.fileName,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1
                                        )
                                        IconButton(
                                            onClick = { onRemoveAttachment(att.id) },
                                            modifier = Modifier.size(18.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Close,
                                                contentDescription = "Remove attachment",
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        // Attachment / Image Upload Action Button
                        if (selectedModel.supportsVisionInput) {
                            IconButton(
                                onClick = onPickImage,
                                enabled = !isGenerating,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("attach_image_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AttachFile,
                                    contentDescription = "Attach or upload image",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        // Multiline Message Input
                        OutlinedTextField(
                            value = composerText,
                            onValueChange = { newText ->
                                if (enterToSend && newText.endsWith("\n") && !isGenerating) {
                                    onTextChanged(newText.trimEnd('\n'))
                                    onSendMessage()
                                } else {
                                    onTextChanged(newText)
                                }
                            },
                            placeholder = {
                                Text(
                                    text = "Ask ${SmartModelRouter.PUBLIC_ASSISTANT_NAME}…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(
                                imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (!isGenerating) onSendMessage()
                                }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                disabledBorderColor = androidx.compose.ui.graphics.Color.Transparent
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_composer_input")
                        )

                        // Send or Stop Generation Button
                        if (isGenerating) {
                            IconButton(
                                onClick = onStopGeneration,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("stop_generation_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.StopCircle,
                                    contentDescription = "Stop generating",
                                    tint = StatusErrorRed,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        } else {
                            val canSend = composerText.isNotBlank() || pendingAttachments.isNotEmpty()
                            val sendInteractionSource = remember { MutableInteractionSource() }
                            val isSendPressed by sendInteractionSource.collectIsPressedAsState()
                            val sendScale by animateFloatAsState(
                                targetValue = if (isSendPressed && canSend && !reducedAnimations) 0.92f else 1f,
                                animationSpec = spring(stiffness = 600f),
                                label = "sendButtonScale"
                            )
                            val sendBgColor by animateColorAsState(
                                targetValue = if (canSend) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                animationSpec = tween(durationMillis = if (reducedAnimations) 0 else 180),
                                label = "sendButtonBgColor"
                            )
                            Button(
                                onClick = onSendMessage,
                                enabled = canSend,
                                interactionSource = sendInteractionSource,
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = sendBgColor,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier
                                    .size(46.dp)
                                    .scale(sendScale)
                                    .testTag("send_message_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send message",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerToolShortcutChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier.testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private suspend fun LazyListState.scrollToBottomEdge(itemCount: Int, animate: Boolean) {
    val total = maxOf(layoutInfo.totalItemsCount, itemCount)
    if (total <= 0) return
    val lastIndex = total - 1
    val lastItemInfo = layoutInfo.visibleItemsInfo.lastOrNull { it.index == lastIndex }
    val viewportHeight = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset

    if (lastItemInfo != null && viewportHeight > 0) {
        val targetOffset = (lastItemInfo.size - viewportHeight + 48).coerceAtLeast(0)
        if (animate) {
            animateScrollToItem(lastIndex, targetOffset)
        } else {
            scrollToItem(lastIndex, targetOffset)
        }
    } else {
        if (animate) {
            animateScrollToItem(lastIndex)
        } else {
            scrollToItem(lastIndex)
        }
        val updatedLast = layoutInfo.visibleItemsInfo.lastOrNull { it.index == lastIndex }
        val updatedViewport = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
        if (updatedLast != null && updatedViewport > 0 && updatedLast.size > updatedViewport) {
            val offset = (updatedLast.size - updatedViewport + 48).coerceAtLeast(0)
            scrollToItem(lastIndex, offset)
        }
    }
}
