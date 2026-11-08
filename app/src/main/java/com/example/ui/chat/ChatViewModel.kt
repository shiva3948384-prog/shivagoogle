package com.example.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.livedata.LiveDataFetchOutcome
import com.example.data.livedata.LiveDataIntelligenceService
import com.example.data.livedata.LiveIntentDetectionResult
import com.example.data.livedata.LiveMarketDataRegistry
import com.example.data.local.GeneratedImageBinaryCache
import com.example.domain.models.ActiveImageGenerationSession
import com.example.domain.models.AiErrorType
import com.example.domain.models.AiGatewayException
import com.example.domain.models.AiModelCatalog
import com.example.domain.models.AiModelDefinition
import com.example.domain.models.AppPreferences
import com.example.domain.models.AvailabilityStatus
import com.example.domain.models.ChatMessage
import com.example.domain.models.Conversation
import com.example.domain.models.ConversationSearchResult
import com.example.domain.models.ConversationTimeGroup
import com.example.domain.models.GeneratedImageResult
import com.example.domain.models.GenerationState
import com.example.domain.models.ImageAnalysisState
import com.example.domain.models.ImageGenerationStage
import com.example.domain.models.LiveMarketDataPayload
import com.example.domain.models.MemoryCategory
import com.example.domain.models.MemorySourceType
import com.example.domain.models.MessageAttachment
import com.example.domain.models.MessageRole
import com.example.domain.models.MessageStatus
import com.example.domain.models.ModelCategory
import com.example.domain.models.PersonalIntelligenceSnapshot
import com.example.domain.models.ProjectContextMemory
import com.example.domain.models.RequestLifecycleState
import com.example.domain.models.RequestTimingMetrics
import com.example.domain.models.StreamChunk
import com.example.domain.models.ThemeMode
import com.example.domain.models.UserMemoryItem
import com.example.domain.repositories.AuthRepository
import com.example.domain.repositories.ChatRepository
import com.example.domain.repositories.ModelCatalogRepository
import com.example.domain.repositories.PreferencesRepository
import com.example.domain.usecases.ChatImageGenerationParams
import com.example.domain.usecases.ChatRequestRoute
import com.example.domain.usecases.ConversationGroupingUseCase
import com.example.domain.usecases.ImageAttachmentProcessor
import com.example.domain.usecases.ImageGenerationIntentDetector
import com.example.domain.usecases.PersonalIntelligenceEngine
import com.example.domain.usecases.SmartModelRouter
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatUiState(
    val activeUserId: String? = null,
    val activeUserEmail: String = "",
    val activeUserDisplayName: String = "",
    val isLiveCloudConfigured: Boolean = false,
    val conversations: List<Conversation> = emptyList(),
    val groupedConversations: Map<ConversationTimeGroup, List<Conversation>> = emptyMap(),
    val activeConversationId: String? = null,
    val activeConversation: Conversation? = null,
    val messages: List<ChatMessage> = emptyList(),
    val selectedModelId: String = AiModelCatalog.DEFAULT_MODEL_ID,
    val selectedModel: AiModelDefinition = AiModelCatalog.findById(AiModelCatalog.DEFAULT_MODEL_ID),
    val modelCatalog: List<AiModelDefinition> = AiModelCatalog.INITIAL_DISCOVERY_MODELS,
    val isRefreshingCatalog: Boolean = false,
    val modelSearchQuery: String = "",
    val selectedModelCategory: ModelCategory? = null,
    val composerText: String = "",
    val pendingAttachments: List<MessageAttachment> = emptyList(),
    val editingMessageId: String? = null,
    val generationState: GenerationState = GenerationState.IDLE,
    val requestLifecycleState: RequestLifecycleState = RequestLifecycleState.IDLE,
    val activeRequestId: String? = null,
    val imageAnalysisState: ImageAnalysisState = ImageAnalysisState.IDLE,
    val isGenerating: Boolean = false,
    val isAnalyzingImage: Boolean = false,
    val activeLiveDataActivityStatus: String? = null,
    val liveMarketDataByMessageId: Map<String, LiveMarketDataPayload> = emptyMap(),
    val streamingMessageText: String = "",
    val lastTimingMetrics: RequestTimingMetrics? = null,
    val fallbackBannerNotice: String? = null,
    val errorBannerMessage: String? = null,
    val canRetryLastPrompt: Boolean = false,
    val lastFailedPromptText: String? = null,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<ConversationSearchResult> = emptyList(),
    val preferences: AppPreferences = AppPreferences(),
    val isModelSelectorSheetOpen: Boolean = false,
    val userSendScrollTrigger: Int = 0,
    val activeImageGenerationSession: ActiveImageGenerationSession? = null,
    val userMemories: List<UserMemoryItem> = emptyList(),
    val activeProjects: List<ProjectContextMemory> = emptyList(),
    val activePersonalIntelligenceSnapshot: PersonalIntelligenceSnapshot? = null
) {
    val isFetchingLiveData: Boolean
        get() = activeLiveDataActivityStatus != null
}

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val authRepository: AuthRepository,
    private val chatRepository: ChatRepository,
    private val modelCatalogRepository: ModelCatalogRepository,
    private val preferencesRepository: PreferencesRepository,
    private val liveDataService: LiveDataIntelligenceService = LiveDataIntelligenceService()
) : ViewModel() {

    private val initialPrefs = preferencesRepository.getInitialPreferences()
    private val _uiState = MutableStateFlow(
        ChatUiState(
            isLiveCloudConfigured = authRepository.isConfiguredInBuild,
            preferences = initialPrefs,
            selectedModelId = initialPrefs.defaultModelId,
            selectedModel = modelCatalogRepository.getModelById(initialPrefs.defaultModelId)
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val searchQueryFlow = MutableStateFlow("")
    private val activeConversationIdFlow = MutableStateFlow<String?>(null)
    private var activeGenerationJob: Job? = null
    private var activeImageGenerationJob: Job? = null
    private var activeImageTimerJob: Job? = null
    private var lastImageGenerationParams: ChatImageGenerationParams? = null
    private var lastLiveDetectedIntentByMessageId = mutableMapOf<String, LiveIntentDetectionResult.Detected>()
    var identityThinkingDelayMs: Long = DEFAULT_IDENTITY_THINKING_DELAY_MS

    init {
        observePreferences()
        observeModelCatalog()
        observeAuthenticatedUserAndConversations()
        observePersonalIntelligenceMemory()
        observeActiveConversationMessages()
        observeDebouncedSearch()
    }

    private fun observePersonalIntelligenceMemory() {
        viewModelScope.launch {
            authRepository.sessionFlow
                .flatMapLatest { session ->
                    if (session == null) {
                        _uiState.update {
                            it.copy(
                                userMemories = emptyList(),
                                activeProjects = emptyList(),
                                activePersonalIntelligenceSnapshot = null
                            )
                        }
                        flowOf(emptyList())
                    } else {
                        chatRepository.observeUserMemories(session.user.id)
                    }
                }
                .collect { memories ->
                    _uiState.update { it.copy(userMemories = memories) }
                }
        }
        viewModelScope.launch {
            authRepository.sessionFlow
                .flatMapLatest { session ->
                    if (session == null) {
                        flowOf(emptyList())
                    } else {
                        chatRepository.observeProjectContexts(session.user.id)
                    }
                }
                .collect { projects ->
                    _uiState.update { it.copy(activeProjects = projects) }
                }
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            preferencesRepository.preferencesFlow.collect { prefs ->
                _uiState.update { current ->
                    val modelIdToUse = if (current.activeConversationId == null) {
                        prefs.defaultModelId
                    } else {
                        current.selectedModelId
                    }
                    current.copy(
                        preferences = prefs,
                        selectedModelId = modelIdToUse,
                        selectedModel = modelCatalogRepository.getModelById(modelIdToUse)
                    )
                }
            }
        }
    }

    private fun observeModelCatalog() {
        viewModelScope.launch {
            modelCatalogRepository.catalogFlow.collect { catalog ->
                _uiState.update { current ->
                    current.copy(
                        modelCatalog = catalog,
                        selectedModel = AiModelCatalog.findById(current.selectedModelId, catalog)
                    )
                }
            }
        }
        viewModelScope.launch {
            modelCatalogRepository.isRefreshingFlow.collect { refreshing ->
                _uiState.update { it.copy(isRefreshingCatalog = refreshing) }
            }
        }
    }

    private fun observeAuthenticatedUserAndConversations() {
        viewModelScope.launch {
            authRepository.sessionFlow
                .flatMapLatest { session ->
                    if (session == null) {
                        _uiState.update {
                            it.copy(
                                activeUserId = null,
                                activeUserEmail = "",
                                activeUserDisplayName = "",
                                conversations = emptyList(),
                                groupedConversations = emptyMap(),
                                activeConversationId = null,
                                activeConversation = null,
                                messages = emptyList()
                            )
                        }
                        activeConversationIdFlow.value = null
                        flowOf(emptyList())
                    } else {
                        _uiState.update {
                            val verificationBanner = if (!session.user.isEmailVerified) {
                                "Please verify your email address (${session.user.email}) with your 6-digit verification code to enable cloud sync across devices."
                            } else {
                                it.fallbackBannerNotice
                            }
                            it.copy(
                                activeUserId = session.user.id,
                                activeUserEmail = session.user.email,
                                activeUserDisplayName = session.user.displayName,
                                isLiveCloudConfigured = authRepository.isConfiguredInBuild,
                                fallbackBannerNotice = verificationBanner
                            )
                        }
                        launch {
                            chatRepository.syncConversationsFromCloud(session.user.id)
                            modelCatalogRepository.refreshModelCatalog()
                        }
                        chatRepository.observeConversations(session.user.id)
                    }
                }
                .collect { conversations ->
                    val grouped = ConversationGroupingUseCase.groupByLocalTime(conversations)
                    _uiState.update { current ->
                        val activeConv = conversations.firstOrNull { it.id == current.activeConversationId }
                        val rawConvModelId = activeConv?.selectedModelId
                        val newSelectedModelId = if (!rawConvModelId.isNullOrBlank() && AiModelCatalog.isSupportedApinexModel(rawConvModelId)) {
                            rawConvModelId
                        } else {
                            current.selectedModelId.takeIf { AiModelCatalog.isSupportedApinexModel(it) }
                                ?: current.preferences.defaultModelId
                        }
                        current.copy(
                            conversations = conversations,
                            groupedConversations = grouped,
                            activeConversation = activeConv,
                            selectedModelId = newSelectedModelId,
                            selectedModel = modelCatalogRepository.getModelById(newSelectedModelId)
                        )
                    }
                }
        }
    }

    private fun observeActiveConversationMessages() {
        viewModelScope.launch {
            activeConversationIdFlow
                .flatMapLatest { convId ->
                    val userId = authRepository.sessionFlow.value?.user?.id
                    if (convId == null || userId == null) {
                        flowOf(emptyList())
                    } else {
                        if (!_uiState.value.isGenerating) {
                            launch {
                                chatRepository.syncMessagesForConversation(convId, userId)
                            }
                        }
                        chatRepository.observeMessages(convId, userId)
                    }
                }
                .collect { dbMessages ->
                    _uiState.update { current ->
                        // Guard against out-of-order Room emissions overwriting a just-finalized
                        // in-memory message that has longer completed content.
                        val currentById = current.messages.associateBy { it.id }
                        val mergedMessages = dbMessages.map { dbMsg ->
                            val inMem = currentById[dbMsg.id]
                            if (inMem != null &&
                                inMem.role == MessageRole.ASSISTANT &&
                                inMem.status == MessageStatus.COMPLETED &&
                                dbMsg.status == MessageStatus.STREAMING &&
                                inMem.content.length > dbMsg.content.length
                            ) {
                                inMem
                            } else {
                                dbMsg
                            }
                        }
                        current.copy(messages = mergedMessages)
                    }
                }
        }
    }

    private fun observeDebouncedSearch() {
        viewModelScope.launch {
            searchQueryFlow
                .debounce(220L)
                .distinctUntilChanged()
                .collectLatest { query ->
                    val userId = _uiState.value.activeUserId ?: return@collectLatest
                    _uiState.update { it.copy(isSearching = true) }
                    val result = chatRepository.searchConversations(userId, query)
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            searchResults = result.getOrDefault(emptyList())
                        )
                    }
                }
        }
    }

    // ========================================================================
    // UI Actions: Conversations & Navigation
    // ========================================================================

    fun startNewChat() {
        stopGeneration()
        val defaultModelId = _uiState.value.preferences.defaultModelId
        activeConversationIdFlow.value = null
        _uiState.update {
            it.copy(
                activeConversationId = null,
                activeConversation = null,
                messages = emptyList(),
                selectedModelId = defaultModelId,
                selectedModel = modelCatalogRepository.getModelById(defaultModelId),
                composerText = "",
                pendingAttachments = emptyList(),
                editingMessageId = null,
                generationState = GenerationState.IDLE,
                requestLifecycleState = RequestLifecycleState.IDLE,
                activeRequestId = null,
                imageAnalysisState = ImageAnalysisState.IDLE,
                isGenerating = false,
                isAnalyzingImage = false,
                activeLiveDataActivityStatus = null,
                streamingMessageText = "",
                errorBannerMessage = null,
                fallbackBannerNotice = null
            )
        }
    }

    fun selectConversation(conversationId: String) {
        if (activeConversationIdFlow.value != null && activeConversationIdFlow.value != conversationId) {
            stopGeneration()
        }
        val conv = _uiState.value.conversations.firstOrNull { it.id == conversationId }
        activeConversationIdFlow.value = conversationId
        _uiState.update {
            val rawConvModel = conv?.selectedModelId
            val modelId = if (!rawConvModel.isNullOrBlank() && AiModelCatalog.isSupportedApinexModel(rawConvModel)) {
                rawConvModel
            } else {
                it.selectedModelId.takeIf { id -> AiModelCatalog.isSupportedApinexModel(id) }
                    ?: it.preferences.defaultModelId
            }
            it.copy(
                activeConversationId = conversationId,
                activeConversation = conv,
                selectedModelId = modelId,
                selectedModel = modelCatalogRepository.getModelById(modelId),
                editingMessageId = null,
                errorBannerMessage = null,
                fallbackBannerNotice = null
            )
        }
    }

    fun renameConversation(conversationId: String, newTitle: String) {
        val userId = _uiState.value.activeUserId ?: return
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            chatRepository.renameConversation(conversationId, userId, newTitle.trim())
        }
    }

    fun deleteConversation(conversationId: String) {
        val userId = _uiState.value.activeUserId ?: return
        viewModelScope.launch {
            if (_uiState.value.activeConversationId == conversationId) {
                startNewChat()
            }
            chatRepository.deleteConversation(conversationId, userId)
            // Refresh search results if active
            if (_uiState.value.searchQuery.isNotBlank()) {
                val refreshed = chatRepository.searchConversations(userId, _uiState.value.searchQuery)
                _uiState.update { it.copy(searchResults = refreshed.getOrDefault(emptyList())) }
            }
        }
    }

    fun clearAllHistory() {
        val userId = _uiState.value.activeUserId ?: return
        viewModelScope.launch {
            startNewChat()
            chatRepository.clearAllUserConversations(userId)
        }
    }

    // ========================================================================
    // UI Actions: Model Catalog & Switching
    // ========================================================================

    fun openModelSelectorSheet() {
        _uiState.update { it.copy(isModelSelectorSheetOpen = true) }
    }

    fun closeModelSelectorSheet() {
        _uiState.update { it.copy(isModelSelectorSheetOpen = false) }
    }

    fun updateModelSearchQuery(query: String) {
        _uiState.update { it.copy(modelSearchQuery = query) }
    }

    fun selectModelCategoryFilter(category: ModelCategory?) {
        _uiState.update { it.copy(selectedModelCategory = category) }
    }

    fun refreshModelCatalog() {
        viewModelScope.launch {
            modelCatalogRepository.refreshModelCatalog()
        }
    }

    fun selectModel(providerModelId: String) {
        val modelDef = modelCatalogRepository.getModelById(providerModelId)
        if (modelDef.availabilityStatus == AvailabilityStatus.UNAVAILABLE) {
            _uiState.update {
                it.copy(
                    errorBannerMessage = "Model '${modelDef.displayName}' is temporarily unavailable. Please choose another model."
                )
            }
            return
        }

        val userId = _uiState.value.activeUserId
        val activeConvId = _uiState.value.activeConversationId

        _uiState.update {
            it.copy(
                selectedModelId = providerModelId,
                selectedModel = modelDef,
                isModelSelectorSheetOpen = false,
                errorBannerMessage = null,
                // Clear pending image attachments if switching to a non-vision model
                pendingAttachments = if (modelDef.supportsVisionInput) it.pendingAttachments else emptyList()
            )
        }

        viewModelScope.launch {
            preferencesRepository.setDefaultModelId(providerModelId)
            if (userId != null && activeConvId != null) {
                chatRepository.updateConversationModel(activeConvId, userId, providerModelId)
            }
        }
    }

    // ========================================================================
    // UI Actions: Composer, Sending, Streaming, Editing, Regenerating
    // ========================================================================

    fun updateComposerText(text: String) {
        _uiState.update { it.copy(composerText = text, errorBannerMessage = null) }
    }

    fun onComposerTextChanged(text: String) {
        updateComposerText(text)
    }

    fun applyPromptSuggestion(prompt: String) {
        _uiState.update { it.copy(composerText = prompt, errorBannerMessage = null) }
    }

    fun addImageAttachment(
        uriString: String,
        mimeType: String,
        fileName: String,
        base64DataUrl: String? = null
    ) {
        addAttachment(
            MessageAttachment(
                id = UUID.randomUUID().toString(),
                uriString = uriString,
                mimeType = mimeType,
                fileName = fileName,
                base64DataUrl = base64DataUrl
            )
        )
    }

    fun addAttachment(attachment: MessageAttachment) {
        val currentModel = _uiState.value.selectedModel
        if (!currentModel.supportsVisionInput) {
            _uiState.update {
                it.copy(
                    errorBannerMessage = "${currentModel.displayName} does not support image attachments. Switch to Gemini 3.8 Flash for vision analysis."
                )
            }
            return
        }

        val validation = ImageAttachmentProcessor.validateAndPrepareAttachment(attachment)
        val validatedAtt = validation.preparedAttachment
        val prepared: MessageAttachment = if (validation.isValid && validatedAtt != null) {
            validatedAtt
        } else if (!attachment.base64DataUrl.isNullOrBlank() || attachment.uriString.isNotBlank()) {
            // Preserve caller-provided attachment in case it is a test URI that will be resolved at send time
            attachment
        } else {
            _uiState.update {
                it.copy(
                    errorBannerMessage = validation.errorMessage
                        ?: "This image file could not be read. Please attach a valid PNG, JPEG, WebP, or GIF image."
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                pendingAttachments = (it.pendingAttachments + prepared).distinctBy { a -> a.uriString.ifBlank { a.id } },
                errorBannerMessage = null
            )
        }
    }

    fun switchToVisionModelForImageAnalysis(visionModelId: String = "free/gemini-3.8-flash") {
        val targetId = if (AiModelCatalog.isVisionCapableModel(visionModelId)) {
            visionModelId
        } else {
            "free/gemini-3.8-flash"
        }
        selectModel(targetId)
    }

    fun removeAttachment(attachmentId: String) {
        _uiState.update {
            it.copy(pendingAttachments = it.pendingAttachments.filterNot { a -> a.id == attachmentId })
        }
    }

    fun startEditingUserMessage(message: ChatMessage) {
        _uiState.update {
            it.copy(
                editingMessageId = message.id,
                composerText = message.content,
                errorBannerMessage = null
            )
        }
    }

    fun cancelEditingUserMessage() {
        _uiState.update {
            it.copy(
                editingMessageId = null,
                composerText = ""
            )
        }
    }

    fun sendMessage() {
        val sendPressedEpochMs = System.currentTimeMillis()
        val state = _uiState.value
        val userId = state.activeUserId ?: return
        val promptText = state.composerText.trim()
        val rawAttachments = state.pendingAttachments

        if ((promptText.isEmpty() && rawAttachments.isEmpty()) || state.isGenerating) return

        // Validate & normalize any pending image attachments
        val validatedAttachments = if (rawAttachments.isNotEmpty()) {
            val preparedList = mutableListOf<MessageAttachment>()
            for (att in rawAttachments) {
                val res = ImageAttachmentProcessor.validateAndPrepareAttachment(att)
                val preparedAtt = res.preparedAttachment
                if (res.isValid && preparedAtt != null) {
                    preparedList.add(preparedAtt)
                } else if (!att.base64DataUrl.isNullOrBlank()) {
                    preparedList.add(att)
                } else {
                    _uiState.update {
                        it.copy(
                            generationState = GenerationState.FAILED,
                            requestLifecycleState = RequestLifecycleState.FAILED,
                            imageAnalysisState = ImageAnalysisState.FAILED,
                            isGenerating = false,
                            isAnalyzingImage = false,
                            errorBannerMessage = res.errorMessage ?: "Invalid or corrupted image attachment. Please select a valid image."
                        )
                    }
                    return
                }
            }
            preparedList
        } else {
            emptyList()
        }

        val hasImageAttachments = validatedAttachments.isNotEmpty()
        val route = ImageGenerationIntentDetector.detectRoute(
            promptText = promptText,
            hasImageAttachments = hasImageAttachments
        )

        if (route is ChatRequestRoute.ImageAnalysis && !state.selectedModel.supportsVisionInput) {
            val visionCheck = ImageAttachmentProcessor.checkModelVisionCompatibility(
                selectedModelId = state.selectedModelId,
                catalog = state.modelCatalog
            )
            _uiState.update {
                it.copy(
                    generationState = GenerationState.FAILED,
                    requestLifecycleState = RequestLifecycleState.FAILED,
                    imageAnalysisState = ImageAnalysisState.FAILED,
                    isGenerating = false,
                    isAnalyzingImage = false,
                    errorBannerMessage = visionCheck.errorMessage
                        ?: "${state.selectedModel.displayName} does not support image analysis. Switch to Gemini 3.8 Flash for vision analysis."
                )
            }
            return
        }

        val editingMsgId = state.editingMessageId
        val selectedModelId = state.selectedModelId

        // Route 1: Automatic Image Generation via Pollinations + Dedicated Image Generation Screen
        if (route is ChatRequestRoute.ImageGeneration) {
            startChatImageGenerationFlow(
                userId = userId,
                params = route.params,
                editingMsgId = editingMsgId,
                selectedModelId = selectedModelId
            )
            return
        }

        // Route 2: Ambiguous visual request -> ask short clarification without generating unwanted image
        if (route is ChatRequestRoute.AmbiguousImageIntent) {
            handleAmbiguousImageIntent(
                userId = userId,
                route = route,
                editingMsgId = editingMsgId,
                selectedModelId = selectedModelId
            )
            return
        }

        // Route 3: Normal Chat, Live Real-Time Data Intelligence, or Multimodal Image Analysis via Apinex
        val effectivePromptText = if (promptText.isEmpty() && hasImageAttachments) {
            "Analyze and describe this image."
        } else {
            promptText
        }

        // Route 3a: Deterministic Smarter AI 1.0 Identity & Developer Privacy Reply with natural ~2s thinking indicator
        val deterministicIdentityReply = if (!hasImageAttachments) {
            SmartModelRouter.resolveDeterministicIdentityReply(effectivePromptText)
        } else {
            null
        }
        if (deterministicIdentityReply != null) {
            handleIdentityResponseWithThinking(
                userId = userId,
                originalPrompt = effectivePromptText,
                identityResponse = deterministicIdentityReply,
                editingMsgId = editingMsgId,
                selectedModelId = selectedModelId
            )
            return
        }

        // Smart Model Routing: Automatically choose the optimal Apinex engine when using default routing
        val routedSelection = SmartModelRouter.routeRequest(
            promptText = effectivePromptText,
            hasImageAttachments = hasImageAttachments,
            conversationHistoryCount = state.messages.size,
            catalog = state.modelCatalog,
            preferredModelId = if (selectedModelId != AiModelCatalog.DEFAULT_MODEL_ID) selectedModelId else null,
            recentHistory = state.messages
        )
        val routedModelId = routedSelection.primaryModelId
        val routedFallbackModelId = routedSelection.fallbackModelIds.firstOrNull() ?: state.preferences.fallbackModelId

        // Check for Live Real-Time Data Intent when no image attachments are present
        val liveIntent = if (!hasImageAttachments) {
            liveDataService.detectLiveIntent(effectivePromptText)
        } else {
            LiveIntentDetectionResult.None
        }

        if (liveIntent is LiveIntentDetectionResult.ClarificationNeeded) {
            handleLiveDataClarificationIntent(
                userId = userId,
                originalPrompt = effectivePromptText,
                clarificationResponse = liveIntent.clarificationMessage,
                editingMsgId = editingMsgId,
                selectedModelId = routedModelId
            )
            return
        }

        val clientRequestId = UUID.randomUUID().toString()
        val initialImageState = if (hasImageAttachments) {
            ImageAnalysisState.IMAGE_ANALYSIS_STARTED
        } else {
            ImageAnalysisState.IDLE
        }
        val initialLiveActivity = (liveIntent as? LiveIntentDetectionResult.Detected)?.initialActivityIndicator

        _uiState.update {
            it.copy(
                generationState = GenerationState.REQUEST_SENDING,
                requestLifecycleState = RequestLifecycleState.PREPARING,
                activeRequestId = clientRequestId,
                imageAnalysisState = initialImageState,
                isGenerating = true,
                isAnalyzingImage = hasImageAttachments,
                activeLiveDataActivityStatus = initialLiveActivity,
                streamingMessageText = "",
                composerText = "",
                pendingAttachments = emptyList(),
                editingMessageId = null,
                errorBannerMessage = null,
                fallbackBannerNotice = null,
                canRetryLastPrompt = false,
                lastFailedPromptText = null,
                userSendScrollTrigger = it.userSendScrollTrigger + 1
            )
        }

        activeGenerationJob?.cancel()
        activeGenerationJob = viewModelScope.launch {
            var convId = state.activeConversationId
            if (convId == null) {
                val derivedTitle = ConversationGroupingUseCase.deriveTitleFromFirstPrompt(
                    effectivePromptText
                )
                val convResult = chatRepository.createConversation(
                    userId = userId,
                    title = derivedTitle,
                    selectedModelId = selectedModelId
                )
                val created = convResult.getOrNull()
                if (created == null) {
                    _uiState.update {
                        it.copy(
                            generationState = GenerationState.FAILED,
                            requestLifecycleState = RequestLifecycleState.FAILED,
                            activeRequestId = null,
                            imageAnalysisState = if (hasImageAttachments) ImageAnalysisState.FAILED else ImageAnalysisState.IDLE,
                            isGenerating = false,
                            isAnalyzingImage = false,
                            activeLiveDataActivityStatus = null,
                            errorBannerMessage = convResult.exceptionOrNull()?.message ?: "Could not create conversation."
                        )
                    }
                    return@launch
                }
                convId = created.id
                activeConversationIdFlow.value = convId
                _uiState.update {
                    it.copy(
                        activeConversationId = convId,
                        activeConversation = created
                    )
                }
            }

            val savedUserMessage: ChatMessage? = if (editingMsgId != null) {
                chatRepository.editUserMessageAndTruncateSubsequent(
                    conversationId = convId,
                    userId = userId,
                    messageId = editingMsgId,
                    newContent = effectivePromptText
                ).getOrNull()
            } else {
                chatRepository.saveUserMessage(
                    conversationId = convId,
                    userId = userId,
                    content = effectivePromptText,
                    modelId = selectedModelId,
                    attachments = validatedAttachments,
                    clientRequestId = clientRequestId
                ).getOrNull()
            }

            // Optimistically ensure the user message is in uiState.messages immediately
            if (savedUserMessage != null) {
                _uiState.update { current ->
                    val updatedList = if (current.messages.none { m -> m.id == savedUserMessage.id }) {
                        current.messages + savedUserMessage
                    } else {
                        current.messages.map { m -> if (m.id == savedUserMessage.id) savedUserMessage else m }
                    }
                    current.copy(messages = updatedList)
                }
            }

            var verifiedLiveContextPrompt: String? = null
            var verifiedLiveMarketPayload: LiveMarketDataPayload? = null

            if (liveIntent is LiveIntentDetectionResult.Detected) {
                val liveOutcome = liveDataService.fetchVerifiedLiveData(
                    intent = liveIntent,
                    onActivityStageChanged = { stageStatus ->
                        _uiState.update { current ->
                            if (current.activeRequestId == clientRequestId) {
                                current.copy(activeLiveDataActivityStatus = stageStatus)
                            } else {
                                current
                            }
                        }
                    }
                )
                when (liveOutcome) {
                    is LiveDataFetchOutcome.Success -> {
                        verifiedLiveMarketPayload = liveOutcome.payload
                        verifiedLiveContextPrompt = liveOutcome.payload.verifiedContextForAiPrompt
                        LiveMarketDataRegistry.putForRequest(clientRequestId, liveOutcome.payload)
                        LiveMarketDataRegistry.putForRequest("${clientRequestId}_assistant", liveOutcome.payload)
                    }
                    is LiveDataFetchOutcome.Failure -> {
                        if (liveIntent.category in setOf(
                                LiveDataCategory.FOREIGN_EXCHANGE,
                                LiveDataCategory.CURRENCY_CONVERSION,
                                LiveDataCategory.CRYPTOCURRENCY
                            ) ||
                            liveIntent.subType in setOf("FOREIGN_EXCHANGE", "CURRENCY_CONVERSION", "CRYPTO_PRICE", "CRYPTO_CONVERSION", "CRYPTO_24H_CHANGE", "CRYPTO_TOP_MARKET")
                        ) {
                            val assistantClientReqId = "${clientRequestId}_assistant"
                            val failureReply = liveOutcome.userFriendlyReason
                            val savedAssistant = chatRepository.saveAssistantMessage(
                                conversationId = convId,
                                userId = userId,
                                content = failureReply,
                                modelId = routedModelId,
                                attachments = emptyList(),
                                clientRequestId = assistantClientReqId
                            ).getOrNull()

                            _uiState.update { current ->
                                var list = current.messages
                                if (savedUserMessage != null && list.none { it.id == savedUserMessage.id }) {
                                    list = list + savedUserMessage
                                }
                                if (savedAssistant != null &&
                                    list.none { it.id == savedAssistant.id || it.clientRequestId == assistantClientReqId }
                                ) {
                                    list = list + savedAssistant
                                }
                                current.copy(
                                    messages = list,
                                    generationState = GenerationState.COMPLETED,
                                    requestLifecycleState = RequestLifecycleState.COMPLETED,
                                    activeRequestId = null,
                                    isGenerating = false,
                                    activeLiveDataActivityStatus = null,
                                    streamingMessageText = ""
                                )
                            }
                            return@launch
                        }
                        verifiedLiveContextPrompt = buildString {
                            appendLine("[VERIFIED LIVE DATA STATUS — SMARTER AI REAL-TIME ENGINE]")
                            appendLine("Live provider status: ${liveOutcome.userFriendlyReason}")
                            appendLine("CRITICAL RULE: Do NOT fabricate or guess current live prices or exchange rates. Explicitly state to the user: \"${liveOutcome.userFriendlyReason}\" and offer general educational context if helpful.")
                        }
                    }
                }

                // If the user explicitly asked whether Smarter AI has live data / real-time web access,
                // respond with the verified runtime capability status after the live probe completes.
                if (liveIntent.subType == "LIVE_CAPABILITY_STATUS") {
                    val effectiveDelayMs = if (_uiState.value.preferences.reducedAnimations) {
                        minOf(identityThinkingDelayMs, REDUCED_MOTION_IDENTITY_DELAY_MS)
                    } else {
                        identityThinkingDelayMs
                    }
                    if (effectiveDelayMs > 0L) {
                        delay(effectiveDelayMs)
                    }
                    val capabilityReply = if (verifiedLiveMarketPayload != null) {
                        SmartModelRouter.LIVE_DATA_CAPABILITY_RESPONSE
                    } else {
                        "Smarter AI includes live-data retrieval services, but the live providers could not be reached right now due to network unavailability. Please check your connection and try again."
                    }
                    val assistantClientReqId = "${clientRequestId}_assistant"
                    val savedAssistant = chatRepository.saveAssistantMessage(
                        conversationId = convId,
                        userId = userId,
                        content = capabilityReply,
                        modelId = routedModelId,
                        attachments = emptyList(),
                        clientRequestId = assistantClientReqId
                    ).getOrNull()

                    _uiState.update { current ->
                        var list = current.messages
                        if (savedUserMessage != null && list.none { it.id == savedUserMessage.id }) {
                            list = list + savedUserMessage
                        }
                        if (savedAssistant != null &&
                            list.none { it.id == savedAssistant.id || it.clientRequestId == assistantClientReqId }
                        ) {
                            list = list + savedAssistant
                        }
                        val updatedLiveMap = if (verifiedLiveMarketPayload != null && savedAssistant != null) {
                            LiveMarketDataRegistry.put(savedAssistant.id, verifiedLiveMarketPayload)
                            LiveMarketDataRegistry.bindToMessageId(savedAssistant.id, assistantClientReqId, verifiedLiveMarketPayload)
                            lastLiveDetectedIntentByMessageId[savedAssistant.id] = liveIntent
                            current.liveMarketDataByMessageId + (savedAssistant.id to verifiedLiveMarketPayload)
                        } else {
                            current.liveMarketDataByMessageId
                        }
                        current.copy(
                            messages = list,
                            generationState = GenerationState.COMPLETED,
                            requestLifecycleState = RequestLifecycleState.COMPLETED,
                            activeRequestId = null,
                            isGenerating = false,
                            activeLiveDataActivityStatus = null,
                            liveMarketDataByMessageId = updatedLiveMap,
                            streamingMessageText = ""
                        )
                    }
                    return@launch
                }
            }

            // Personal Intelligence Engine: Extract user-approved preferences & ongoing project context
            var currentMemories = _uiState.value.userMemories
            var currentProjects = _uiState.value.activeProjects
            if (_uiState.value.preferences.personalIntelligenceEnabled) {
                val extracted = PersonalIntelligenceEngine.extractMemoriesFromTurn(
                    userId = userId,
                    conversationId = convId,
                    userPrompt = effectivePromptText,
                    existingMemories = currentMemories,
                    existingProjects = currentProjects
                )
                if (extracted.topicsToForget.isNotEmpty()) {
                    val toDelete = currentMemories.filter { mem ->
                        extracted.topicsToForget.any { topic ->
                            mem.content.contains(topic, ignoreCase = true) ||
                                mem.memoryKey.contains(topic, ignoreCase = true)
                        }
                    }
                    toDelete.forEach { mem ->
                        chatRepository.deleteUserMemory(mem.id, userId)
                    }
                    currentMemories = currentMemories.filterNot { m -> toDelete.any { it.id == m.id } }
                }
                if (extracted.memoriesToUpsert.isNotEmpty()) {
                    extracted.memoriesToUpsert.forEach { item ->
                        chatRepository.saveUserMemory(item)
                    }
                    val mergedMap = (currentMemories + extracted.memoriesToUpsert).associateBy { "${it.category.name}:${it.memoryKey}" }
                    currentMemories = mergedMap.values.sortedByDescending { it.updatedAtEpochMs }
                }
                if (extracted.projectsToUpsert.isNotEmpty()) {
                    extracted.projectsToUpsert.forEach { proj ->
                        chatRepository.saveProjectContext(proj)
                    }
                    val mergedProjMap = (currentProjects + extracted.projectsToUpsert).associateBy { it.projectName.lowercase() }
                    currentProjects = mergedProjMap.values.sortedByDescending { it.updatedAtEpochMs }
                }
                val existingSummary = chatRepository.getConversationSummariesSnapshot(userId)
                    .firstOrNull { it.conversationId == convId }
                val updatedSummary = PersonalIntelligenceEngine.buildConversationSummaryFromTurn(
                    userId = userId,
                    conversationId = convId,
                    userPrompt = effectivePromptText,
                    assistantReply = "",
                    existingSummary = existingSummary
                )
                if (updatedSummary != null) {
                    chatRepository.saveConversationSummary(updatedSummary)
                }
                _uiState.update {
                    it.copy(
                        userMemories = currentMemories,
                        activeProjects = currentProjects
                    )
                }
            }

            val personalSnapshot = PersonalIntelligenceEngine.buildPersonalIntelligenceSnapshot(
                userDisplayName = _uiState.value.activeUserDisplayName,
                personalIntelligenceEnabled = _uiState.value.preferences.personalIntelligenceEnabled,
                userPrompt = effectivePromptText,
                allMemories = currentMemories,
                allProjects = currentProjects,
                recentConversations = _uiState.value.conversations
            )
            _uiState.update { it.copy(activePersonalIntelligenceSnapshot = personalSnapshot) }

            val personalContextPrompt = personalSnapshot.formattedSystemContext.takeIf { it.isNotBlank() }
            val liveContextPrompt = verifiedLiveContextPrompt?.takeIf { it.isNotBlank() }

            triggerAssistantStream(
                conversationId = convId,
                userId = userId,
                requestedModelId = routedModelId,
                routedFallbackModelId = routedFallbackModelId,
                clientRequestId = clientRequestId,
                lastPromptForRetry = effectivePromptText,
                latestUserMessage = savedUserMessage,
                sendPressedEpochMs = sendPressedEpochMs,
                liveDataContextPrompt = liveContextPrompt,
                personalIntelligenceContextPrompt = personalContextPrompt,
                liveMarketPayload = verifiedLiveMarketPayload,
                detectedLiveIntent = liveIntent as? LiveIntentDetectionResult.Detected
            )
        }
    }

    private fun handleIdentityResponseWithThinking(
        userId: String,
        originalPrompt: String,
        identityResponse: String,
        editingMsgId: String?,
        selectedModelId: String
    ) {
        activeGenerationJob?.cancel()
        val state = _uiState.value
        val reqId = UUID.randomUUID().toString()
        _uiState.update {
            it.copy(
                composerText = "",
                pendingAttachments = emptyList(),
                editingMessageId = null,
                errorBannerMessage = null,
                fallbackBannerNotice = null,
                generationState = GenerationState.WAITING_FOR_FIRST_TOKEN,
                requestLifecycleState = RequestLifecycleState.PROCESSING,
                activeRequestId = reqId,
                imageAnalysisState = ImageAnalysisState.IDLE,
                isGenerating = true,
                isAnalyzingImage = false,
                activeLiveDataActivityStatus = null,
                streamingMessageText = "",
                userSendScrollTrigger = it.userSendScrollTrigger + 1
            )
        }

        activeGenerationJob = viewModelScope.launch {
            try {
                var convId = state.activeConversationId
                if (convId == null) {
                    val created = chatRepository.createConversation(
                        userId = userId,
                        title = ConversationGroupingUseCase.deriveTitleFromFirstPrompt(originalPrompt),
                        selectedModelId = selectedModelId
                    ).getOrNull()
                    if (created == null) {
                        _uiState.update {
                            it.copy(
                                generationState = GenerationState.IDLE,
                                requestLifecycleState = RequestLifecycleState.IDLE,
                                activeRequestId = null,
                                isGenerating = false
                            )
                        }
                        return@launch
                    }
                    convId = created.id
                    activeConversationIdFlow.value = convId
                    _uiState.update { it.copy(activeConversationId = convId, activeConversation = created) }
                }

                val savedUser = if (editingMsgId != null) {
                    chatRepository.editUserMessageAndTruncateSubsequent(convId, userId, editingMsgId, originalPrompt).getOrNull()
                } else {
                    chatRepository.saveUserMessage(convId, userId, originalPrompt, selectedModelId, emptyList(), reqId).getOrNull()
                }

                if (savedUser != null) {
                    _uiState.update { current ->
                        val updatedList = if (current.messages.none { it.id == savedUser.id }) {
                            current.messages + savedUser
                        } else {
                            current.messages
                        }
                        current.copy(
                            messages = updatedList,
                            generationState = GenerationState.WAITING_FOR_FIRST_TOKEN,
                            requestLifecycleState = RequestLifecycleState.PROCESSING,
                            activeRequestId = reqId,
                            isGenerating = true
                        )
                    }
                }

                val effectiveDelayMs = if (_uiState.value.preferences.reducedAnimations) {
                    minOf(identityThinkingDelayMs, REDUCED_MOTION_IDENTITY_DELAY_MS)
                } else {
                    identityThinkingDelayMs
                }
                if (effectiveDelayMs > 0L) {
                    delay(effectiveDelayMs)
                }
                if (!isActive) return@launch

                val assistantClientReqId = "${reqId}_identity"
                val savedAssistant = chatRepository.saveAssistantMessage(
                    conversationId = convId,
                    userId = userId,
                    content = identityResponse,
                    modelId = selectedModelId,
                    attachments = emptyList(),
                    clientRequestId = assistantClientReqId
                ).getOrNull()

                if (!isActive) return@launch

                _uiState.update { current ->
                    var list = current.messages
                    if (savedUser != null && list.none { it.id == savedUser.id }) {
                        list = list + savedUser
                    }
                    if (savedAssistant != null &&
                        list.none { it.id == savedAssistant.id || (it.clientRequestId == assistantClientReqId && assistantClientReqId.isNotBlank()) }
                    ) {
                        list = list + savedAssistant
                    }
                    current.copy(
                        messages = list,
                        generationState = GenerationState.COMPLETED,
                        requestLifecycleState = RequestLifecycleState.COMPLETED,
                        activeRequestId = null,
                        isGenerating = false,
                        activeLiveDataActivityStatus = null,
                        streamingMessageText = ""
                    )
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                _uiState.update { current ->
                    current.copy(
                        generationState = GenerationState.CANCELLED,
                        requestLifecycleState = RequestLifecycleState.CANCELLED,
                        activeRequestId = null,
                        isGenerating = false,
                        activeLiveDataActivityStatus = null,
                        streamingMessageText = ""
                    )
                }
            }
        }
    }

    private fun handleLiveDataClarificationIntent(
        userId: String,
        originalPrompt: String,
        clarificationResponse: String,
        editingMsgId: String?,
        selectedModelId: String
    ) {
        val state = _uiState.value
        _uiState.update {
            it.copy(
                composerText = "",
                pendingAttachments = emptyList(),
                editingMessageId = null,
                errorBannerMessage = null,
                userSendScrollTrigger = it.userSendScrollTrigger + 1
            )
        }
        viewModelScope.launch {
            var convId = state.activeConversationId
            if (convId == null) {
                val created = chatRepository.createConversation(
                    userId = userId,
                    title = ConversationGroupingUseCase.deriveTitleFromFirstPrompt(originalPrompt),
                    selectedModelId = selectedModelId
                ).getOrNull() ?: return@launch
                convId = created.id
                activeConversationIdFlow.value = convId
                _uiState.update { it.copy(activeConversationId = convId, activeConversation = created) }
            }
            val reqId = UUID.randomUUID().toString()
            val savedUser = if (editingMsgId != null) {
                chatRepository.editUserMessageAndTruncateSubsequent(convId, userId, editingMsgId, originalPrompt).getOrNull()
            } else {
                chatRepository.saveUserMessage(convId, userId, originalPrompt, selectedModelId, emptyList(), reqId).getOrNull()
            }
            val savedAssistant = chatRepository.saveAssistantMessage(
                conversationId = convId,
                userId = userId,
                content = clarificationResponse,
                modelId = selectedModelId,
                attachments = emptyList(),
                clientRequestId = "${reqId}_live_clarify"
            ).getOrNull()
            _uiState.update { current ->
                var list = current.messages
                if (savedUser != null && list.none { it.id == savedUser.id }) list = list + savedUser
                if (savedAssistant != null && list.none { it.id == savedAssistant.id }) list = list + savedAssistant
                current.copy(
                    messages = list,
                    generationState = GenerationState.COMPLETED,
                    requestLifecycleState = RequestLifecycleState.COMPLETED,
                    activeRequestId = null,
                    isGenerating = false,
                    activeLiveDataActivityStatus = null
                )
            }
        }
    }

    private fun handleAmbiguousImageIntent(
        userId: String,
        route: ChatRequestRoute.AmbiguousImageIntent,
        editingMsgId: String?,
        selectedModelId: String
    ) {
        val state = _uiState.value
        _uiState.update {
            it.copy(
                composerText = "",
                pendingAttachments = emptyList(),
                editingMessageId = null,
                errorBannerMessage = null,
                userSendScrollTrigger = it.userSendScrollTrigger + 1
            )
        }
        viewModelScope.launch {
            var convId = state.activeConversationId
            if (convId == null) {
                val created = chatRepository.createConversation(
                    userId = userId,
                    title = ConversationGroupingUseCase.deriveTitleFromFirstPrompt(route.originalPrompt),
                    selectedModelId = selectedModelId
                ).getOrNull() ?: return@launch
                convId = created.id
                activeConversationIdFlow.value = convId
                _uiState.update { it.copy(activeConversationId = convId, activeConversation = created) }
            }
            val reqId = UUID.randomUUID().toString()
            val savedUser = if (editingMsgId != null) {
                chatRepository.editUserMessageAndTruncateSubsequent(convId, userId, editingMsgId, route.originalPrompt).getOrNull()
            } else {
                chatRepository.saveUserMessage(convId, userId, route.originalPrompt, selectedModelId, emptyList(), reqId).getOrNull()
            }
            val savedAssistant = chatRepository.saveAssistantMessage(
                conversationId = convId,
                userId = userId,
                content = route.clarificationResponse,
                modelId = selectedModelId,
                attachments = emptyList(),
                clientRequestId = "${reqId}_clarify"
            ).getOrNull()
            _uiState.update { current ->
                var list = current.messages
                if (savedUser != null && list.none { it.id == savedUser.id }) list = list + savedUser
                if (savedAssistant != null && list.none { it.id == savedAssistant.id }) list = list + savedAssistant
                current.copy(messages = list, generationState = GenerationState.COMPLETED, isGenerating = false)
            }
        }
    }

    private fun startChatImageGenerationFlow(
        userId: String,
        params: ChatImageGenerationParams,
        editingMsgId: String?,
        selectedModelId: String,
        saveUserTurnToChat: Boolean = true
    ) {
        activeImageGenerationJob?.cancel()
        activeImageTimerJob?.cancel()
        lastImageGenerationParams = params

        val sessionId = UUID.randomUUID().toString()
        val startEpochMs = System.currentTimeMillis()
        val modelDisplayName = AiModelCatalog.getDisplayName(params.modelId)

        val initialSession = ActiveImageGenerationSession(
            sessionId = sessionId,
            conversationId = _uiState.value.activeConversationId,
            originalUserPrompt = params.originalUserMessage,
            extractedPrompt = params.extractedPrompt,
            modelId = params.modelId,
            modelDisplayName = modelDisplayName,
            width = params.width,
            height = params.height,
            aspectRatioLabel = params.aspectRatioLabel,
            stage = ImageGenerationStage.PREPARING_PROMPT,
            startedAtEpochMs = startEpochMs,
            elapsedTimeMs = 0L,
            progressPercentage = null, // Honest progress: null until 100% verified completion
            result = null,
            errorMessage = null,
            canRetry = false,
            isScreenVisible = true
        )

        _uiState.update {
            it.copy(
                generationState = GenerationState.REQUEST_SENDING,
                requestLifecycleState = RequestLifecycleState.PREPARING,
                activeRequestId = sessionId,
                isGenerating = true,
                isAnalyzingImage = false,
                activeLiveDataActivityStatus = null,
                composerText = if (saveUserTurnToChat) "" else it.composerText,
                pendingAttachments = emptyList(),
                editingMessageId = null,
                errorBannerMessage = null,
                fallbackBannerNotice = null,
                activeImageGenerationSession = initialSession,
                userSendScrollTrigger = it.userSendScrollTrigger + 1
            )
        }

        activeImageTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(250L)
                val currentSession = _uiState.value.activeImageGenerationSession ?: break
                if (!currentSession.isGenerating) break
                val elapsed = (System.currentTimeMillis() - startEpochMs).coerceAtLeast(0L)
                _uiState.update { state ->
                    val s = state.activeImageGenerationSession ?: return@update state
                    if (!s.isGenerating) return@update state
                    state.copy(activeImageGenerationSession = s.copy(elapsedTimeMs = elapsed))
                }
            }
        }

        activeImageGenerationJob = viewModelScope.launch {
            try {
                var convId = _uiState.value.activeConversationId
                if (convId == null) {
                    val derivedTitle = ConversationGroupingUseCase.deriveTitleFromFirstPrompt(params.originalUserMessage)
                    val convResult = chatRepository.createConversation(
                        userId = userId,
                        title = derivedTitle,
                        selectedModelId = selectedModelId
                    )
                    val created = convResult.getOrNull()
                    if (created == null) {
                        activeImageTimerJob?.cancel()
                        val err = convResult.exceptionOrNull()?.message ?: "Could not create conversation."
                        _uiState.update { state ->
                            state.copy(
                                generationState = GenerationState.FAILED,
                                requestLifecycleState = RequestLifecycleState.FAILED,
                                activeRequestId = null,
                                isGenerating = false,
                                errorBannerMessage = err,
                                activeImageGenerationSession = state.activeImageGenerationSession?.copy(
                                    stage = ImageGenerationStage.FAILED,
                                    progressPercentage = null,
                                    errorMessage = err,
                                    canRetry = true
                                )
                            )
                        }
                        return@launch
                    }
                    convId = created.id
                    activeConversationIdFlow.value = convId
                    _uiState.update { state ->
                        state.copy(
                            activeConversationId = convId,
                            activeConversation = created,
                            activeImageGenerationSession = state.activeImageGenerationSession?.copy(conversationId = convId)
                        )
                    }
                }

                val clientRequestId = UUID.randomUUID().toString()
                if (saveUserTurnToChat) {
                    val savedUserMessage = if (editingMsgId != null) {
                        chatRepository.editUserMessageAndTruncateSubsequent(
                            conversationId = convId,
                            userId = userId,
                            messageId = editingMsgId,
                            newContent = params.originalUserMessage
                        ).getOrNull()
                    } else {
                        chatRepository.saveUserMessage(
                            conversationId = convId,
                            userId = userId,
                            content = params.originalUserMessage,
                            modelId = selectedModelId,
                            attachments = emptyList(),
                            clientRequestId = clientRequestId
                        ).getOrNull()
                    }

                    if (savedUserMessage != null) {
                        _uiState.update { current ->
                            val updatedList = if (current.messages.none { m -> m.id == savedUserMessage.id }) {
                                current.messages + savedUserMessage
                            } else {
                                current.messages.map { m -> if (m.id == savedUserMessage.id) savedUserMessage else m }
                            }
                            current.copy(messages = updatedList)
                        }
                    }
                }

                _uiState.update { current ->
                    current.copy(requestLifecycleState = RequestLifecycleState.SENDING)
                }

                val imgResult = chatRepository.generateChatImage(
                    prompt = params.extractedPrompt,
                    modelId = params.modelId,
                    width = params.width,
                    height = params.height,
                    enhance = params.enhance,
                    onStageChanged = { newStage ->
                        _uiState.update { current ->
                            val session = current.activeImageGenerationSession ?: return@update current
                            // Never set 100% until final GeneratedImageResult is validated below
                            val honestProgress = if (newStage == ImageGenerationStage.COMPLETED) 100 else null
                            current.copy(
                                requestLifecycleState = RequestLifecycleState.PROCESSING,
                                activeImageGenerationSession = session.copy(
                                    stage = if (newStage == ImageGenerationStage.COMPLETED) {
                                        ImageGenerationStage.PROCESSING_FINAL_IMAGE
                                    } else {
                                        newStage
                                    },
                                    progressPercentage = honestProgress
                                )
                            )
                        }
                    }
                )

                activeImageTimerJob?.cancel()
                val finalElapsed = (System.currentTimeMillis() - startEpochMs).coerceAtLeast(0L)

                imgResult.fold(
                    onSuccess = { generated ->
                        val localUri = generated.localFilePath
                            ?: GeneratedImageBinaryCache.putGeneratedImage(
                                attachmentId = generated.id,
                                imageBytes = generated.imageBytes,
                                mimeType = generated.mimeType,
                                remoteUrl = generated.imageUrl
                            )
                        // Avoid storing huge multi-hundred-KB Base64 strings inside SQLite rows
                        // when GeneratedImageBinaryCache already stores the binary payload by ID/URI.
                        val base64DataUrl = if (generated.imageBytes.size <= 48 * 1024) {
                            runCatching {
                                "data:${generated.mimeType};base64," +
                                    java.util.Base64.getEncoder().encodeToString(generated.imageBytes)
                            }.getOrNull()
                        } else {
                            null
                        }

                        val imageAttachment = MessageAttachment(
                            id = generated.id,
                            uriString = localUri,
                            mimeType = generated.mimeType,
                            fileName = "smarter_ai_${generated.seed}.png",
                            base64DataUrl = base64DataUrl
                        )

                        val assistantSummary = "Generated image for **\"${params.extractedPrompt}\"** (${generated.width}×${generated.height} • $modelDisplayName)"
                        val savedAssistantMsg = chatRepository.saveAssistantMessage(
                            conversationId = convId,
                            userId = userId,
                            content = assistantSummary,
                            modelId = params.modelId,
                            attachments = listOf(imageAttachment),
                            clientRequestId = "${clientRequestId}_pollinations_image"
                        ).getOrNull()

                        _uiState.update { current ->
                            val updatedMessages = if (savedAssistantMsg != null && current.messages.none { it.id == savedAssistantMsg.id }) {
                                current.messages + savedAssistantMsg
                            } else {
                                current.messages
                            }
                            current.copy(
                                messages = updatedMessages,
                                generationState = GenerationState.COMPLETED,
                                requestLifecycleState = RequestLifecycleState.COMPLETED,
                                activeRequestId = null,
                                isGenerating = false,
                                isAnalyzingImage = false,
                                activeImageGenerationSession = current.activeImageGenerationSession?.copy(
                                    stage = ImageGenerationStage.COMPLETED,
                                    elapsedTimeMs = finalElapsed,
                                    progressPercentage = 100, // 100% ONLY after image is received and validated
                                    result = generated.copy(localFilePath = localUri),
                                    errorMessage = null,
                                    canRetry = true
                                )
                            )
                        }
                    },
                    onFailure = { err ->
                        val friendly = (err as? AiGatewayException)?.userFriendlyMessage
                            ?: err.message
                            ?: "Unable to generate image right now. Please try again."
                        val canRetry = (err as? AiGatewayException)?.canRetry ?: true
                        val isTimeout = (err as? AiGatewayException)?.errorType == AiErrorType.NETWORK_TIMEOUT
                        _uiState.update { current ->
                            current.copy(
                                generationState = GenerationState.FAILED,
                                requestLifecycleState = if (isTimeout) RequestLifecycleState.TIMED_OUT else RequestLifecycleState.FAILED,
                                activeRequestId = null,
                                isGenerating = false,
                                isAnalyzingImage = false,
                                errorBannerMessage = friendly,
                                canRetryLastPrompt = canRetry,
                                lastFailedPromptText = params.originalUserMessage,
                                activeImageGenerationSession = current.activeImageGenerationSession?.copy(
                                    stage = ImageGenerationStage.FAILED,
                                    elapsedTimeMs = finalElapsed,
                                    progressPercentage = null,
                                    errorMessage = friendly,
                                    canRetry = canRetry
                                )
                            )
                        }
                    }
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                activeImageTimerJob?.cancel()
                val finalElapsed = (System.currentTimeMillis() - startEpochMs).coerceAtLeast(0L)
                _uiState.update { current ->
                    current.copy(
                        generationState = GenerationState.CANCELLED,
                        requestLifecycleState = RequestLifecycleState.CANCELLED,
                        activeRequestId = null,
                        isGenerating = false,
                        isAnalyzingImage = false,
                        activeImageGenerationSession = current.activeImageGenerationSession?.copy(
                            stage = ImageGenerationStage.CANCELLED,
                            elapsedTimeMs = finalElapsed,
                            progressPercentage = null,
                            errorMessage = "Image generation cancelled.",
                            canRetry = true
                        )
                    )
                }
            } catch (t: Throwable) {
                activeImageTimerJob?.cancel()
                val finalElapsed = (System.currentTimeMillis() - startEpochMs).coerceAtLeast(0L)
                val friendly = t.message ?: "Image generation encountered an unexpected error."
                _uiState.update { current ->
                    current.copy(
                        generationState = GenerationState.FAILED,
                        requestLifecycleState = RequestLifecycleState.FAILED,
                        activeRequestId = null,
                        isGenerating = false,
                        isAnalyzingImage = false,
                        errorBannerMessage = friendly,
                        canRetryLastPrompt = true,
                        lastFailedPromptText = params.originalUserMessage,
                        activeImageGenerationSession = current.activeImageGenerationSession?.copy(
                            stage = ImageGenerationStage.FAILED,
                            elapsedTimeMs = finalElapsed,
                            progressPercentage = null,
                            errorMessage = friendly,
                            canRetry = true
                        )
                    )
                }
            } finally {
                activeImageTimerJob?.cancel()
                if (_uiState.value.activeRequestId == sessionId && _uiState.value.isGenerating) {
                    _uiState.update { current ->
                        current.copy(
                            isGenerating = false,
                            isAnalyzingImage = false,
                            activeRequestId = null,
                            requestLifecycleState = if (current.requestLifecycleState.isActive) {
                                RequestLifecycleState.IDLE
                            } else {
                                current.requestLifecycleState
                            }
                        )
                    }
                }
            }
        }
    }

    fun cancelChatImageGeneration() {
        val jobToCancel = activeImageGenerationJob
        activeImageGenerationJob = null
        activeImageTimerJob?.cancel()
        activeImageTimerJob = null
        _uiState.update { current ->
            val session = current.activeImageGenerationSession
            current.copy(
                generationState = GenerationState.CANCELLED,
                isGenerating = false,
                activeImageGenerationSession = session?.copy(
                    stage = ImageGenerationStage.CANCELLED,
                    progressPercentage = null,
                    errorMessage = "Image generation cancelled.",
                    canRetry = true
                )
            )
        }
        jobToCancel?.cancel()
    }

    fun retryChatImageGeneration() {
        val userId = _uiState.value.activeUserId ?: return
        val session = _uiState.value.activeImageGenerationSession
        val params = lastImageGenerationParams ?: session?.let {
            ChatImageGenerationParams(
                originalUserMessage = it.originalUserPrompt,
                extractedPrompt = it.extractedPrompt,
                modelId = it.modelId,
                width = it.width,
                height = it.height,
                aspectRatioLabel = it.aspectRatioLabel
            )
        } ?: return

        startChatImageGenerationFlow(
            userId = userId,
            params = params,
            editingMsgId = null,
            selectedModelId = _uiState.value.selectedModelId,
            saveUserTurnToChat = false
        )
    }

    fun dismissDedicatedImageGenerationScreen() {
        _uiState.update { current ->
            current.copy(
                activeImageGenerationSession = current.activeImageGenerationSession?.copy(isScreenVisible = false)
            )
        }
    }

    fun openGeneratedImageFromChat(message: ChatMessage, attachment: MessageAttachment) {
        val bytes = GeneratedImageBinaryCache.getBytes(
            attachmentId = attachment.id,
            uriString = attachment.uriString,
            base64DataUrl = attachment.base64DataUrl
        ) ?: return

        val promptFromContent = Regex("""\*\*"([^"]+)"\*\*""").find(message.content)?.groupValues?.getOrNull(1)
            ?: message.content
        val dimsMatch = Regex("""\((\d+)×(\d+)""").find(message.content)
        val w = dimsMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1024
        val h = dimsMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 1024
        val modelDisplayName = AiModelCatalog.getDisplayName(message.modelId)

        val reconstructedResult = GeneratedImageResult(
            id = attachment.id,
            prompt = promptFromContent,
            modelId = message.modelId,
            width = w,
            height = h,
            seed = 42L,
            imageBytes = bytes,
            mimeType = attachment.mimeType,
            imageUrl = attachment.uriString,
            localFilePath = attachment.uriString
        )

        _uiState.update { current ->
            current.copy(
                activeImageGenerationSession = ActiveImageGenerationSession(
                    sessionId = attachment.id,
                    conversationId = message.conversationId,
                    originalUserPrompt = promptFromContent,
                    extractedPrompt = promptFromContent,
                    modelId = message.modelId,
                    modelDisplayName = modelDisplayName,
                    width = w,
                    height = h,
                    aspectRatioLabel = "${w}×${h}",
                    stage = ImageGenerationStage.COMPLETED,
                    startedAtEpochMs = message.createdAtEpochMs,
                    elapsedTimeMs = 0L,
                    progressPercentage = 100,
                    result = reconstructedResult,
                    errorMessage = null,
                    canRetry = true,
                    isScreenVisible = true
                )
            )
        }
    }

    fun regenerateLastAssistantResponse() {
        val sendPressedEpochMs = System.currentTimeMillis()
        val state = _uiState.value
        val userId = state.activeUserId ?: return
        val convId = state.activeConversationId ?: return
        if (state.isGenerating) return

        val currentMessages = state.messages
        val lastAssistant = currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
        val lastUser = currentMessages.lastOrNull { it.role == MessageRole.USER } ?: return

        val hasImageAttachments = lastUser.attachments.isNotEmpty()
        if (hasImageAttachments && !state.selectedModel.supportsVisionInput) {
            _uiState.update {
                it.copy(
                    generationState = GenerationState.FAILED,
                    imageAnalysisState = ImageAnalysisState.FAILED,
                    isGenerating = false,
                    isAnalyzingImage = false,
                    errorBannerMessage = "${state.selectedModel.displayName} does not support image analysis. Switch to Gemini 3.8 Flash for vision analysis."
                )
            }
            return
        }

        val liveIntent = if (!hasImageAttachments) {
            liveDataService.detectLiveIntent(lastUser.content)
        } else {
            LiveIntentDetectionResult.None
        }
        val initialLiveIndicator = (liveIntent as? LiveIntentDetectionResult.Detected)?.initialActivityIndicator
        val regenRequestId = UUID.randomUUID().toString()

        _uiState.update {
            it.copy(
                generationState = GenerationState.REQUEST_SENDING,
                activeRequestId = regenRequestId,
                imageAnalysisState = if (hasImageAttachments) ImageAnalysisState.IMAGE_ANALYSIS_STARTED else ImageAnalysisState.IDLE,
                isGenerating = true,
                isAnalyzingImage = hasImageAttachments,
                activeLiveDataActivityStatus = initialLiveIndicator,
                streamingMessageText = "",
                errorBannerMessage = null,
                userSendScrollTrigger = it.userSendScrollTrigger + 1
            )
        }

        activeGenerationJob = viewModelScope.launch {
            if (lastAssistant != null) {
                chatRepository.deleteMessage(lastAssistant.id, userId)
                _uiState.update { current ->
                    current.copy(messages = current.messages.filterNot { m -> m.id == lastAssistant.id })
                }
            }

            var verifiedLiveContextPrompt: String? = null
            var verifiedLiveMarketPayload: LiveMarketDataPayload? = null
            if (liveIntent is LiveIntentDetectionResult.Detected) {
                val liveOutcome = liveDataService.fetchVerifiedLiveData(
                    intent = liveIntent,
                    forceRefresh = true
                )
                when (liveOutcome) {
                    is LiveDataFetchOutcome.Success -> {
                        verifiedLiveMarketPayload = liveOutcome.payload
                        verifiedLiveContextPrompt = liveOutcome.payload.verifiedContextForAiPrompt
                        LiveMarketDataRegistry.putForRequest(regenRequestId, liveOutcome.payload)
                        LiveMarketDataRegistry.putForRequest("${regenRequestId}_assistant", liveOutcome.payload)
                    }
                    is LiveDataFetchOutcome.Failure -> {
                        if (liveIntent.category in setOf(
                                LiveDataCategory.FOREIGN_EXCHANGE,
                                LiveDataCategory.CURRENCY_CONVERSION,
                                LiveDataCategory.CRYPTOCURRENCY
                            ) ||
                            liveIntent.subType in setOf("FOREIGN_EXCHANGE", "CURRENCY_CONVERSION", "CRYPTO_PRICE", "CRYPTO_CONVERSION", "CRYPTO_24H_CHANGE", "CRYPTO_TOP_MARKET")
                        ) {
                            val assistantClientReqId = "${regenRequestId}_assistant"
                            val savedAssistant = chatRepository.saveAssistantMessage(
                                conversationId = convId,
                                userId = userId,
                                content = liveOutcome.userFriendlyReason,
                                modelId = state.selectedModelId,
                                attachments = emptyList(),
                                clientRequestId = assistantClientReqId
                            ).getOrNull()
                            _uiState.update { current ->
                                val list = if (savedAssistant != null && current.messages.none { it.id == savedAssistant.id }) {
                                    current.messages + savedAssistant
                                } else {
                                    current.messages
                                }
                                current.copy(
                                    messages = list,
                                    generationState = GenerationState.COMPLETED,
                                    requestLifecycleState = RequestLifecycleState.COMPLETED,
                                    activeRequestId = null,
                                    isGenerating = false,
                                    activeLiveDataActivityStatus = null,
                                    streamingMessageText = ""
                                )
                            }
                            return@launch
                        }
                        verifiedLiveContextPrompt = buildString {
                            appendLine("[VERIFIED LIVE DATA STATUS — SMARTER AI REAL-TIME ENGINE]")
                            appendLine("Live provider status: ${liveOutcome.userFriendlyReason}")
                            appendLine("CRITICAL RULE: Do NOT fabricate or guess current live prices or exchange rates. Explicitly state to the user: \"${liveOutcome.userFriendlyReason}\" and offer general educational context if helpful.")
                        }
                    }
                }
            }

            triggerAssistantStream(
                conversationId = convId,
                userId = userId,
                requestedModelId = state.selectedModelId,
                clientRequestId = regenRequestId,
                lastPromptForRetry = lastUser.content,
                latestUserMessage = lastUser,
                sendPressedEpochMs = sendPressedEpochMs,
                liveDataContextPrompt = verifiedLiveContextPrompt,
                liveMarketPayload = verifiedLiveMarketPayload,
                detectedLiveIntent = liveIntent as? LiveIntentDetectionResult.Detected
            )
        }
    }

    fun retryFailedGeneration() {
        regenerateLastAssistantResponse()
    }

    private suspend fun triggerAssistantStream(
        conversationId: String,
        userId: String,
        requestedModelId: String,
        routedFallbackModelId: String? = null,
        clientRequestId: String,
        lastPromptForRetry: String,
        latestUserMessage: ChatMessage? = null,
        sendPressedEpochMs: Long = System.currentTimeMillis(),
        liveDataContextPrompt: String? = null,
        personalIntelligenceContextPrompt: String? = null,
        liveMarketPayload: LiveMarketDataPayload? = null,
        detectedLiveIntent: LiveIntentDetectionResult.Detected? = null
    ) {
        val prefs = _uiState.value.preferences
        val baseHistory = _uiState.value.messages.filter {
            it.conversationId == conversationId &&
                it.status != MessageStatus.ERROR &&
                (it.content.isNotBlank() || it.attachments.isNotEmpty())
        }
        val rawHistorySnapshot = if (latestUserMessage != null && baseHistory.none { it.id == latestUserMessage.id }) {
            baseHistory + latestUserMessage
        } else {
            baseHistory
        }

        // Keep the user's message 100% unchanged!
        // Inject both Personal Intelligence and Verified Live Data context strictly as MessageRole.SYSTEM
        // messages so they are passed in the system context to Apinex without polluting or mutating the user message.
        val systemContextMessages = buildList {
            if (!personalIntelligenceContextPrompt.isNullOrBlank()) {
                add(
                    ChatMessage(
                        id = "sys_pi_$clientRequestId",
                        conversationId = conversationId,
                        userId = userId,
                        role = MessageRole.SYSTEM,
                        content = personalIntelligenceContextPrompt,
                        modelId = requestedModelId
                    )
                )
            }
            if (!liveDataContextPrompt.isNullOrBlank()) {
                add(
                    ChatMessage(
                        id = "sys_live_$clientRequestId",
                        conversationId = conversationId,
                        userId = userId,
                        role = MessageRole.SYSTEM,
                        content = liveDataContextPrompt,
                        modelId = requestedModelId
                    )
                )
            }
        }

        val historySnapshot = if (systemContextMessages.isNotEmpty()) {
            systemContextMessages + rawHistorySnapshot
        } else {
            rawHistorySnapshot
        }

        val isImageTurn = (latestUserMessage ?: rawHistorySnapshot.lastOrNull { it.role == MessageRole.USER })
            ?.attachments
            ?.isNotEmpty() == true && AiModelCatalog.isVisionCapableModel(requestedModelId, _uiState.value.modelCatalog)

        _uiState.update {
            it.copy(
                generationState = GenerationState.WAITING_FOR_FIRST_TOKEN,
                requestLifecycleState = RequestLifecycleState.SENDING,
                activeRequestId = clientRequestId,
                imageAnalysisState = if (isImageTurn) ImageAnalysisState.IMAGE_ANALYSIS_STARTED else ImageAnalysisState.IDLE,
                isGenerating = true,
                isAnalyzingImage = isImageTurn,
                streamingMessageText = "",
                errorBannerMessage = null,
                fallbackBannerNotice = null
            )
        }

        try {
            chatRepository.streamAssistantResponse(
                conversationId = conversationId,
                userId = userId,
                requestedModelId = requestedModelId,
                fallbackModelId = prefs.fallbackModelId ?: routedFallbackModelId,
                allowFallback = prefs.allowModelFallback,
                history = historySnapshot,
                streamEnabled = prefs.streamResponses,
                clientRequestId = clientRequestId
            ).collect { chunk ->
                when (chunk) {
                    is StreamChunk.Meta -> {
                        val fallbackMsg = if (chunk.fallbackUsed) {
                            val reqName = AiModelCatalog.getDisplayName(chunk.requestedModelId)
                            val activeName = AiModelCatalog.getDisplayName(chunk.activeModelId)
                            "Requested model '$reqName' was unavailable; switched to fallback model '$activeName'."
                        } else null

                        _uiState.update { current ->
                            current.copy(
                                generationState = if (current.streamingMessageText.isEmpty()) {
                                    GenerationState.WAITING_FOR_FIRST_TOKEN
                                } else {
                                    GenerationState.STREAMING
                                },
                                requestLifecycleState = if (current.streamingMessageText.isEmpty()) {
                                    RequestLifecycleState.SENDING
                                } else {
                                    RequestLifecycleState.STREAMING
                                },
                                fallbackBannerNotice = fallbackMsg ?: current.fallbackBannerNotice
                            )
                        }
                    }
                    is StreamChunk.ReasoningDelta -> {
                        _uiState.update { current ->
                            if (current.streamingMessageText.isEmpty()) {
                                current.copy(
                                    generationState = GenerationState.WAITING_FOR_FIRST_TOKEN,
                                    requestLifecycleState = RequestLifecycleState.PROCESSING
                                )
                            } else {
                                current
                            }
                        }
                    }
                    is StreamChunk.Delta -> {
                        // As soon as the first visible response token arrives:
                        // - Transition imageAnalysisState from IMAGE_ANALYSIS_STARTED -> RESPONSE_GENERATION
                        // - Set isAnalyzingImage = false immediately so "Analyzing provided image" disappears right away!
                        // - Clear activeLiveDataActivityStatus so live indicator transitions smoothly into the response!
                        _uiState.update { current ->
                            val authoritativeFxText = liveMarketPayload?.authoritativeDirectAnswer?.takeIf { it.isNotBlank() }
                            val newStreamingText = authoritativeFxText ?: (current.streamingMessageText + chunk.textDelta)
                            val nextImageState = if (isImageTurn) {
                                ImageAnalysisState.RESPONSE_GENERATION
                            } else {
                                ImageAnalysisState.IDLE
                            }
                            current.copy(
                                generationState = GenerationState.STREAMING,
                                requestLifecycleState = RequestLifecycleState.STREAMING,
                                imageAnalysisState = nextImageState,
                                isGenerating = true,
                                isAnalyzingImage = false,
                                activeLiveDataActivityStatus = null,
                                streamingMessageText = newStreamingText
                            )
                        }
                    }
                    is StreamChunk.Completed -> {
                        val uiRenderedAtMs = System.currentTimeMillis()
                        val finalMetrics = chunk.timingMetrics?.copy(
                            sendPressedEpochMs = sendPressedEpochMs,
                            uiRenderedEpochMs = uiRenderedAtMs
                        )
                        _uiState.update { current ->
                            val authoritativeFxText = liveMarketPayload?.authoritativeDirectAnswer?.takeIf { it.isNotBlank() }
                            val finalFullText = authoritativeFxText ?: chunk.fullText.ifEmpty { current.streamingMessageText }
                            val updatedMessages = updateLastAssistantMessageInMemory(
                                messages = current.messages,
                                conversationId = conversationId,
                                userId = userId,
                                modelId = chunk.activeModelId,
                                requestedModelId = requestedModelId,
                                fallbackUsed = chunk.fallbackUsed,
                                content = finalFullText,
                                status = MessageStatus.COMPLETED,
                                errorMessage = null,
                                clientRequestId = "${clientRequestId}_assistant"
                            )
                            val assistantMsg = updatedMessages.lastOrNull {
                                it.role == MessageRole.ASSISTANT &&
                                    (it.clientRequestId == "${clientRequestId}_assistant" || it.conversationId == conversationId)
                            }
                            val newLiveDataMap = if (liveMarketPayload != null && assistantMsg != null) {
                                LiveMarketDataRegistry.put(assistantMsg.id, liveMarketPayload)
                                LiveMarketDataRegistry.bindToMessageId(assistantMsg.id, "${clientRequestId}_assistant", liveMarketPayload)
                                if (detectedLiveIntent != null) {
                                    lastLiveDetectedIntentByMessageId[assistantMsg.id] = detectedLiveIntent
                                }
                                current.liveMarketDataByMessageId + (assistantMsg.id to liveMarketPayload)
                            } else {
                                current.liveMarketDataByMessageId
                            }
                            current.copy(
                                messages = updatedMessages,
                                generationState = GenerationState.COMPLETED,
                                requestLifecycleState = RequestLifecycleState.COMPLETED,
                                activeRequestId = null,
                                imageAnalysisState = if (isImageTurn) ImageAnalysisState.COMPLETED else ImageAnalysisState.IDLE,
                                isGenerating = false,
                                isAnalyzingImage = false,
                                activeLiveDataActivityStatus = null,
                                liveMarketDataByMessageId = newLiveDataMap,
                                streamingMessageText = "",
                                lastTimingMetrics = finalMetrics ?: current.lastTimingMetrics
                            )
                        }
                        if (finalMetrics != null) {
                            logTimingMetrics("completed", finalMetrics)
                        }
                    }
                    is StreamChunk.Error -> {
                        val uiRenderedAtMs = System.currentTimeMillis()
                        val finalMetrics = chunk.timingMetrics?.copy(
                            sendPressedEpochMs = sendPressedEpochMs,
                            uiRenderedEpochMs = uiRenderedAtMs
                        )
                        val isTimeout = chunk.exception.errorType == AiErrorType.NETWORK_TIMEOUT
                        _uiState.update { current ->
                            val partial = chunk.partialText.ifEmpty { current.streamingMessageText }
                            val targetStatus = if (partial.isNotBlank()) MessageStatus.INTERRUPTED else MessageStatus.ERROR
                            val updatedMessages = updateLastAssistantMessageInMemory(
                                messages = current.messages,
                                conversationId = conversationId,
                                userId = userId,
                                modelId = requestedModelId,
                                requestedModelId = requestedModelId,
                                fallbackUsed = false,
                                content = partial,
                                status = targetStatus,
                                errorMessage = chunk.exception.userFriendlyMessage,
                                clientRequestId = "${clientRequestId}_assistant"
                            )
                            current.copy(
                                messages = updatedMessages,
                                generationState = GenerationState.FAILED,
                                requestLifecycleState = if (isTimeout) RequestLifecycleState.TIMED_OUT else RequestLifecycleState.FAILED,
                                activeRequestId = null,
                                imageAnalysisState = if (isImageTurn) ImageAnalysisState.FAILED else ImageAnalysisState.IDLE,
                                isGenerating = false,
                                isAnalyzingImage = false,
                                activeLiveDataActivityStatus = null,
                                streamingMessageText = "",
                                lastTimingMetrics = finalMetrics ?: current.lastTimingMetrics,
                                errorBannerMessage = chunk.exception.userFriendlyMessage,
                                canRetryLastPrompt = chunk.exception.canRetry,
                                lastFailedPromptText = lastPromptForRetry
                            )
                        }
                        if (finalMetrics != null) {
                            logTimingMetrics("error", finalMetrics)
                        }
                    }
                }
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            _uiState.update { current ->
                val partial = current.streamingMessageText
                val updatedMessages = if (partial.isNotBlank()) {
                    updateLastAssistantMessageInMemory(
                        messages = current.messages,
                        conversationId = conversationId,
                        userId = userId,
                        modelId = requestedModelId,
                        requestedModelId = requestedModelId,
                        fallbackUsed = false,
                        content = partial,
                        status = MessageStatus.INTERRUPTED,
                        errorMessage = "Generation stopped by user.",
                        clientRequestId = "${clientRequestId}_assistant"
                    )
                } else {
                    current.messages.filterNot {
                        it.role == MessageRole.ASSISTANT &&
                            it.status == MessageStatus.STREAMING &&
                            it.content.isBlank()
                    }
                }
                current.copy(
                    messages = updatedMessages,
                    generationState = GenerationState.CANCELLED,
                    requestLifecycleState = RequestLifecycleState.CANCELLED,
                    activeRequestId = null,
                    imageAnalysisState = if (isImageTurn) ImageAnalysisState.CANCELLED else ImageAnalysisState.IDLE,
                    isGenerating = false,
                    isAnalyzingImage = false,
                    activeLiveDataActivityStatus = null,
                    streamingMessageText = ""
                )
            }
        } catch (e: Throwable) {
            _uiState.update { current ->
                val partial = current.streamingMessageText
                val updatedMessages = updateLastAssistantMessageInMemory(
                    messages = current.messages,
                    conversationId = conversationId,
                    userId = userId,
                    modelId = requestedModelId,
                    requestedModelId = requestedModelId,
                    fallbackUsed = false,
                    content = partial,
                    status = if (partial.isNotBlank()) MessageStatus.INTERRUPTED else MessageStatus.ERROR,
                    errorMessage = e.message ?: "Unable to complete response. Please try again.",
                    clientRequestId = "${clientRequestId}_assistant"
                )
                current.copy(
                    messages = updatedMessages,
                    generationState = GenerationState.FAILED,
                    requestLifecycleState = RequestLifecycleState.FAILED,
                    activeRequestId = null,
                    imageAnalysisState = if (isImageTurn) ImageAnalysisState.FAILED else ImageAnalysisState.IDLE,
                    isGenerating = false,
                    isAnalyzingImage = false,
                    activeLiveDataActivityStatus = null,
                    streamingMessageText = "",
                    errorBannerMessage = e.message ?: "Unable to complete response. Please try again.",
                    canRetryLastPrompt = true,
                    lastFailedPromptText = lastPromptForRetry
                )
            }
        } finally {
            if (_uiState.value.isGenerating || _uiState.value.isAnalyzingImage || _uiState.value.activeLiveDataActivityStatus != null) {
                _uiState.update {
                    it.copy(
                        generationState = if (it.generationState in setOf(GenerationState.COMPLETED, GenerationState.FAILED, GenerationState.CANCELLED)) {
                            it.generationState
                        } else {
                            GenerationState.IDLE
                        },
                        requestLifecycleState = if (it.requestLifecycleState.isActive) {
                            RequestLifecycleState.IDLE
                        } else {
                            it.requestLifecycleState
                        },
                        activeRequestId = null,
                        imageAnalysisState = if (it.imageAnalysisState in setOf(ImageAnalysisState.COMPLETED, ImageAnalysisState.FAILED, ImageAnalysisState.CANCELLED)) {
                            it.imageAnalysisState
                        } else {
                            ImageAnalysisState.IDLE
                        },
                        isGenerating = false,
                        isAnalyzingImage = false,
                        activeLiveDataActivityStatus = null,
                        streamingMessageText = ""
                    )
                }
            }
        }
    }

    fun refreshLiveMarketDataForMessage(messageId: String) {
        val intent = lastLiveDetectedIntentByMessageId[messageId] ?: return
        viewModelScope.launch {
            val outcome = liveDataService.fetchVerifiedLiveData(intent)
            if (outcome is LiveDataFetchOutcome.Success) {
                LiveMarketDataRegistry.put(messageId, outcome.payload)
                _uiState.update { current ->
                    current.copy(
                        liveMarketDataByMessageId = current.liveMarketDataByMessageId + (messageId to outcome.payload)
                    )
                }
            }
        }
    }

    /**
     * Synchronously updates or appends the assistant message in the in-memory UI state list
     * so there is zero gap or flicker between `streamingMessageText` clearing and Room's Flow emitting.
     */
    private fun updateLastAssistantMessageInMemory(
        messages: List<ChatMessage>,
        conversationId: String,
        userId: String,
        modelId: String,
        requestedModelId: String,
        fallbackUsed: Boolean,
        content: String,
        status: MessageStatus,
        errorMessage: String?,
        clientRequestId: String
    ): List<ChatMessage> {
        val now = System.currentTimeMillis()
        val lastIdx = messages.indexOfLast {
            it.conversationId == conversationId &&
                it.role == MessageRole.ASSISTANT &&
                (it.status == MessageStatus.STREAMING || it.clientRequestId == clientRequestId)
        }
        return if (lastIdx >= 0) {
            messages.toMutableList().apply {
                val existing = this[lastIdx]
                this[lastIdx] = existing.copy(
                    content = content,
                    modelId = modelId,
                    requestedModelId = requestedModelId,
                    fallbackUsed = fallbackUsed,
                    status = status,
                    errorMessage = errorMessage,
                    updatedAtEpochMs = now
                )
            }
        } else if (content.isNotBlank() || status == MessageStatus.ERROR) {
            messages + ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                userId = userId,
                role = MessageRole.ASSISTANT,
                content = content,
                modelId = modelId,
                requestedModelId = requestedModelId,
                fallbackUsed = fallbackUsed,
                status = status,
                errorMessage = errorMessage,
                clientRequestId = clientRequestId,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
        } else {
            messages
        }
    }

    private fun logTimingMetrics(outcome: String, m: RequestTimingMetrics) {
        runCatching {
            android.util.Log.d(
                "SmarterAIPerf",
                "outcome=$outcome model=${m.modelId} image=${m.hasImageAttachment} " +
                    "ttfbMs=${m.ttfbMs} ttftMs=${m.ttftMs} genMs=${m.totalGenerationMs} " +
                    "dbMs=${m.dbPersistenceMs} totalMs=${m.totalRequestMs} " +
                    "events=${m.sseEventCount} chars=${m.characterCount} " +
                    "finishReason=${m.finishReason} sawDone=${m.sawDoneEvent}"
            )
        }
    }

    fun stopGeneration() {
        val jobToCancel = activeGenerationJob
        activeGenerationJob = null
        _uiState.update { current ->
            val convId = current.activeConversationId
            val userId = current.activeUserId
            val partial = current.streamingMessageText
            val updatedMessages = if (convId != null && userId != null) {
                if (partial.isNotBlank()) {
                    updateLastAssistantMessageInMemory(
                        messages = current.messages,
                        conversationId = convId,
                        userId = userId,
                        modelId = current.selectedModelId,
                        requestedModelId = current.selectedModelId,
                        fallbackUsed = false,
                        content = partial,
                        status = MessageStatus.INTERRUPTED,
                        errorMessage = "Generation stopped by user.",
                        clientRequestId = ""
                    )
                } else {
                    current.messages.filterNot {
                        it.role == MessageRole.ASSISTANT &&
                            it.status == MessageStatus.STREAMING &&
                            it.content.isBlank()
                    }
                }
            } else {
                current.messages
            }
            val wasImageTurn = current.isAnalyzingImage ||
                current.imageAnalysisState == ImageAnalysisState.IMAGE_ANALYSIS_STARTED ||
                current.imageAnalysisState == ImageAnalysisState.RESPONSE_GENERATION
            current.copy(
                messages = updatedMessages,
                generationState = GenerationState.CANCELLED,
                requestLifecycleState = RequestLifecycleState.CANCELLED,
                activeRequestId = null,
                imageAnalysisState = if (wasImageTurn) ImageAnalysisState.CANCELLED else ImageAnalysisState.IDLE,
                isGenerating = false,
                isAnalyzingImage = false,
                activeLiveDataActivityStatus = null,
                streamingMessageText = ""
            )
        }
        jobToCancel?.cancel()
    }

    fun dismissBanners() {
        _uiState.update {
            it.copy(
                errorBannerMessage = null,
                fallbackBannerNotice = null
            )
        }
    }

    // ========================================================================
    // UI Actions: Chat Search
    // ========================================================================

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchQueryFlow.value = query
    }

    // ========================================================================
    // UI Actions: Preferences & Settings
    // ========================================================================

    fun updateThemeMode(themeMode: ThemeMode) {
        _uiState.update { current ->
            current.copy(preferences = current.preferences.copy(themeMode = themeMode))
        }
        viewModelScope.launch {
            preferencesRepository.setThemeMode(themeMode)
        }
    }

    fun updateDefaultModel(modelId: String) {
        viewModelScope.launch {
            preferencesRepository.setDefaultModelId(modelId)
        }
    }

    fun updateFallbackConfiguration(fallbackModelId: String?, allowFallback: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setFallbackModel(fallbackModelId, allowFallback)
        }
    }

    fun updateEnterToSend(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setEnterToSend(enabled)
        }
    }

    fun updateReducedAnimations(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setReducedAnimations(enabled)
        }
    }

    fun updateStreamResponses(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setStreamResponses(enabled)
        }
    }

    fun updateRenderMarkdown(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setRenderMarkdown(enabled)
        }
    }

    fun updateUserDisplayName(newName: String) {
        viewModelScope.launch {
            authRepository.updateProfileName(newName)
        }
    }

    // ========================================================================
    // UI Actions: Personal Intelligence & Memory Privacy Controls
    // ========================================================================

    fun updatePersonalIntelligenceEnabled(enabled: Boolean) {
        _uiState.update { current ->
            current.copy(preferences = current.preferences.copy(personalIntelligenceEnabled = enabled))
        }
        viewModelScope.launch {
            preferencesRepository.setPersonalIntelligenceEnabled(enabled)
        }
    }

    fun addExplicitUserMemory(
        category: MemoryCategory = MemoryCategory.USER_PREFERENCE,
        content: String
    ) {
        val userId = _uiState.value.activeUserId ?: return
        val trimmed = content.trim()
        if (trimmed.isEmpty() || PersonalIntelligenceEngine.containsSensitivePersonalData(trimmed)) return
        val now = System.currentTimeMillis()
        val key = "manual_${trimmed.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(32)}"
        val item = UserMemoryItem(
            id = UUID.randomUUID().toString(),
            userId = userId,
            category = category,
            memoryKey = key,
            content = trimmed,
            sourceType = MemorySourceType.EXPLICIT_USER,
            confidence = 1.0,
            isUserApproved = true,
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
        _uiState.update { current ->
            current.copy(userMemories = listOf(item) + current.userMemories.filterNot { it.memoryKey == key })
        }
        viewModelScope.launch {
            chatRepository.saveUserMemory(item)
        }
    }

    fun deleteUserMemory(memoryId: String) {
        val userId = _uiState.value.activeUserId ?: return
        _uiState.update { current ->
            current.copy(userMemories = current.userMemories.filterNot { it.id == memoryId })
        }
        viewModelScope.launch {
            chatRepository.deleteUserMemory(memoryId, userId)
        }
    }

    fun deleteProjectContext(projectId: String) {
        val userId = _uiState.value.activeUserId ?: return
        _uiState.update { current ->
            current.copy(activeProjects = current.activeProjects.filterNot { it.id == projectId })
        }
        viewModelScope.launch {
            chatRepository.deleteProjectContext(projectId, userId)
        }
    }

    fun clearAllPersonalIntelligenceMemory() {
        val userId = _uiState.value.activeUserId ?: return
        _uiState.update { current ->
            current.copy(
                userMemories = emptyList(),
                activeProjects = emptyList(),
                activePersonalIntelligenceSnapshot = null
            )
        }
        viewModelScope.launch {
            chatRepository.clearAllUserMemories(userId)
        }
    }

    companion object {
        const val DEFAULT_IDENTITY_THINKING_DELAY_MS = 2000L
        const val REDUCED_MOTION_IDENTITY_DELAY_MS = 400L

        fun provideFactory(
            authRepository: AuthRepository,
            chatRepository: ChatRepository,
            modelCatalogRepository: ModelCatalogRepository,
            preferencesRepository: PreferencesRepository,
            liveDataService: LiveDataIntelligenceService = LiveDataIntelligenceService()
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(
                    authRepository = authRepository,
                    chatRepository = chatRepository,
                    modelCatalogRepository = modelCatalogRepository,
                    preferencesRepository = preferencesRepository,
                    liveDataService = liveDataService
                ) as T
            }
        }
    }
}
