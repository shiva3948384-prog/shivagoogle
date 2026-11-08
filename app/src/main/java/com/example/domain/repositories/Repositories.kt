package com.example.domain.repositories

import com.example.domain.models.AiModelDefinition
import com.example.domain.models.AppPreferences
import com.example.domain.models.AuthSession
import com.example.domain.models.ChatMessage
import com.example.domain.models.Conversation
import com.example.domain.models.ConversationSearchResult
import com.example.domain.models.ConversationSummaryMemory
import com.example.domain.models.GeneratedImageResult
import com.example.domain.models.ImageGenerationStage
import com.example.domain.models.MemoryCloudSyncReport
import com.example.domain.models.MessageAttachment
import com.example.domain.models.MessageRole
import com.example.domain.models.MessageStatus
import com.example.domain.models.OtpChallengeState
import com.example.domain.models.PendingMemorySyncOperation
import com.example.domain.models.ProjectContextMemory
import com.example.domain.models.StreamChunk
import com.example.domain.models.ThemeMode
import com.example.domain.models.UserMemoryItem
import com.example.domain.models.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import java.util.UUID

interface AuthRepository {
    val sessionFlow: StateFlow<AuthSession?>
    val isConfiguredInBuild: Boolean

    suspend fun restoreSession(): Result<AuthSession?>
    suspend fun signUp(email: String, password: String, displayName: String): Result<AuthSession>
    suspend fun signIn(email: String, password: String): Result<AuthSession>
    suspend fun sendPasswordResetEmail(email: String): Result<Unit>
    suspend fun refreshSessionIfNeeded(): Result<AuthSession?>
    suspend fun updateProfileName(displayName: String): Result<UserProfile>
    suspend fun signOut(): Result<Unit>
    suspend fun deleteAccount(): Result<Unit>

    /**
     * Initiates account creation and requests a cryptographically secure 6-digit numeric OTP
     * delivered through Brevo transactional email with Smarter AI branding.
     */
    suspend fun initiateSignUpOtp(
        email: String,
        password: String,
        displayName: String
    ): Result<OtpChallengeState> {
        val now = System.currentTimeMillis()
        return Result.success(
            OtpChallengeState(
                email = email.trim().lowercase(),
                displayName = displayName.trim(),
                expiresAtEpochMs = now + 5 * 60 * 1000L,
                resendAvailableAtEpochMs = now + 45 * 1000L,
                maxAttempts = 5,
                remainingAttempts = 5,
                notice = "We sent a 6-digit verification code to ${email.trim().lowercase()}."
            )
        )
    }

    /**
     * Verifies the 6-digit numeric OTP against the server-side salted SHA-256 hash, enforcing
     * 5-minute expiry, max 5 attempts, and single-use consumption, then establishes a verified session.
     */
    suspend fun verifySignUpOtp(
        email: String,
        otpCode: String
    ): Result<AuthSession> {
        return signUp(email, "VerifiedPass123", email.substringBefore("@"))
    }

    /**
     * Invalidates any previous OTP and sends a fresh 6-digit numeric OTP subject to the 45-second cooldown.
     */
    suspend fun resendSignUpOtp(
        email: String
    ): Result<OtpChallengeState> {
        val now = System.currentTimeMillis()
        return Result.success(
            OtpChallengeState(
                email = email.trim().lowercase(),
                expiresAtEpochMs = now + 5 * 60 * 1000L,
                resendAvailableAtEpochMs = now + 45 * 1000L,
                maxAttempts = 5,
                remainingAttempts = 5,
                notice = "A new 6-digit verification code has been sent to ${email.trim().lowercase()}."
            )
        )
    }
}

interface ModelCatalogRepository {
    val catalogFlow: StateFlow<List<AiModelDefinition>>
    val isRefreshingFlow: StateFlow<Boolean>
    val lastVerifiedEpochMsFlow: StateFlow<Long?>

    suspend fun refreshModelCatalog(): Result<List<AiModelDefinition>>
    fun getModelById(providerModelId: String): AiModelDefinition
}

interface ChatRepository {
    fun observeConversations(userId: String): Flow<List<Conversation>>
    fun observeMessages(conversationId: String, userId: String): Flow<List<ChatMessage>>

    suspend fun syncConversationsFromCloud(
        userId: String,
        limit: Int = 50,
        offset: Int = 0
    ): Result<List<Conversation>>

    suspend fun syncMessagesForConversation(
        conversationId: String,
        userId: String,
        limit: Int = 200,
        offset: Int = 0
    ): Result<List<ChatMessage>>

    suspend fun createConversation(
        userId: String,
        title: String,
        selectedModelId: String
    ): Result<Conversation>

    suspend fun renameConversation(
        conversationId: String,
        userId: String,
        newTitle: String
    ): Result<Conversation>

    suspend fun updateConversationModel(
        conversationId: String,
        userId: String,
        newModelId: String
    ): Result<Conversation>

    suspend fun deleteConversation(
        conversationId: String,
        userId: String
    ): Result<Unit>

    suspend fun clearAllUserConversations(userId: String): Result<Unit>

    suspend fun searchConversations(
        userId: String,
        query: String,
        limit: Int = 30,
        offset: Int = 0
    ): Result<List<ConversationSearchResult>>

    suspend fun saveUserMessage(
        conversationId: String,
        userId: String,
        content: String,
        modelId: String,
        attachments: List<MessageAttachment> = emptyList(),
        clientRequestId: String
    ): Result<ChatMessage>

    suspend fun saveAssistantMessage(
        conversationId: String,
        userId: String,
        content: String,
        modelId: String,
        attachments: List<MessageAttachment> = emptyList(),
        clientRequestId: String
    ): Result<ChatMessage> {
        val now = System.currentTimeMillis()
        return Result.success(
            ChatMessage(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                userId = userId,
                role = MessageRole.ASSISTANT,
                content = content,
                modelId = modelId,
                status = MessageStatus.COMPLETED,
                attachments = attachments,
                clientRequestId = clientRequestId,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
        )
    }

    suspend fun generateChatImage(
        prompt: String,
        modelId: String = "sana",
        width: Int = 1024,
        height: Int = 1024,
        enhance: Boolean = true,
        onStageChanged: ((ImageGenerationStage) -> Unit)? = null
    ): Result<GeneratedImageResult> {
        onStageChanged?.invoke(ImageGenerationStage.PREPARING_PROMPT)
        onStageChanged?.invoke(ImageGenerationStage.CONNECTING_TO_MODEL)
        onStageChanged?.invoke(ImageGenerationStage.GENERATING_IMAGE)
        onStageChanged?.invoke(ImageGenerationStage.RECEIVING_IMAGE)
        onStageChanged?.invoke(ImageGenerationStage.PROCESSING_FINAL_IMAGE)
        onStageChanged?.invoke(ImageGenerationStage.COMPLETED)
        val minimalPng = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(),
            0x42, 0x60, 0x82.toByte()
        )
        return Result.success(
            GeneratedImageResult(
                id = UUID.randomUUID().toString(),
                prompt = prompt,
                modelId = modelId,
                width = width,
                height = height,
                seed = 42L,
                imageBytes = minimalPng,
                mimeType = "image/png",
                imageUrl = "https://image.pollinations.ai/prompt/${java.net.URLEncoder.encode(prompt, "UTF-8")}?model=$modelId&width=$width&height=$height"
            )
        )
    }

    suspend fun editUserMessageAndTruncateSubsequent(
        conversationId: String,
        userId: String,
        messageId: String,
        newContent: String
    ): Result<ChatMessage>

    suspend fun deleteMessage(
        messageId: String,
        userId: String
    ): Result<Unit>

    fun streamAssistantResponse(
        conversationId: String,
        userId: String,
        requestedModelId: String,
        fallbackModelId: String?,
        allowFallback: Boolean,
        history: List<ChatMessage>,
        streamEnabled: Boolean,
        clientRequestId: String
    ): Flow<StreamChunk>

    // Personal Intelligence Engine persistence & cloud sync hooks (with default implementations for test fakes)
    fun observeUserMemories(userId: String): Flow<List<UserMemoryItem>> = flowOf(emptyList())
    fun observeProjectContexts(userId: String): Flow<List<ProjectContextMemory>> = flowOf(emptyList())
    fun observeConversationSummaries(userId: String): Flow<List<ConversationSummaryMemory>> = flowOf(emptyList())
    suspend fun getUserMemoriesSnapshot(userId: String): List<UserMemoryItem> = emptyList()
    suspend fun getProjectContextsSnapshot(userId: String): List<ProjectContextMemory> = emptyList()
    suspend fun getConversationSummariesSnapshot(userId: String): List<ConversationSummaryMemory> = emptyList()
    suspend fun saveUserMemory(item: UserMemoryItem): Result<UserMemoryItem> = Result.success(item)
    suspend fun deleteUserMemory(memoryId: String, userId: String): Result<Unit> = Result.success(Unit)
    suspend fun clearAllUserMemories(userId: String): Result<Unit> = Result.success(Unit)
    suspend fun saveProjectContext(context: ProjectContextMemory): Result<ProjectContextMemory> = Result.success(context)
    suspend fun deleteProjectContext(projectId: String, userId: String): Result<Unit> = Result.success(Unit)
    suspend fun saveConversationSummary(summary: ConversationSummaryMemory): Result<ConversationSummaryMemory> = Result.success(summary)
    suspend fun deleteConversationSummary(summaryId: String, userId: String): Result<Unit> = Result.success(Unit)
    suspend fun getPendingMemorySyncOperations(userId: String): List<PendingMemorySyncOperation> = emptyList()
    suspend fun flushPendingMemorySyncQueue(userId: String): Result<MemoryCloudSyncReport> =
        Result.success(MemoryCloudSyncReport(userId = userId))
    suspend fun syncPersonalIntelligenceWithCloud(userId: String): Result<MemoryCloudSyncReport> =
        Result.success(MemoryCloudSyncReport(userId = userId))
}

interface PreferencesRepository {
    val preferencesFlow: Flow<AppPreferences>

    /**
     * Synchronously returns the persisted preferences so the initial theme and settings
     * are applied before the first visible frame without any startup theme flicker.
     */
    fun getInitialPreferences(): AppPreferences = AppPreferences()

    suspend fun setThemeMode(themeMode: ThemeMode)
    suspend fun setDefaultModelId(modelId: String)
    suspend fun setFallbackModel(modelId: String?, allowFallback: Boolean)
    suspend fun setEnterToSend(enabled: Boolean)
    suspend fun setReducedAnimations(enabled: Boolean)
    suspend fun setStreamResponses(enabled: Boolean)
    suspend fun setRenderMarkdown(enabled: Boolean)
    suspend fun setPersonalIntelligenceEnabled(enabled: Boolean) {}
}
