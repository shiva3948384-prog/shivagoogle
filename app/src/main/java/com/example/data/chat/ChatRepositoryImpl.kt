package com.example.data.chat

import com.example.data.local.ChatCacheDao
import com.example.data.local.ConversationEntity
import com.example.data.local.ConversationSummaryEntity
import com.example.data.local.GeneratedImageBinaryCache
import com.example.data.local.MessageEntity
import com.example.data.local.PendingMemorySyncEntity
import com.example.data.local.ProjectContextEntity
import com.example.data.local.UserMemoryEntity
import com.example.data.network.SupabaseAndGatewayClient
import com.example.domain.models.AiErrorType
import com.example.domain.models.AiGatewayException
import com.example.domain.models.AiModelCatalog
import com.example.domain.models.AvailabilityStatus
import com.example.domain.models.ChatMessage
import com.example.domain.models.Conversation
import com.example.domain.models.ConversationSearchResult
import com.example.domain.models.ConversationSummaryMemory
import com.example.domain.models.GeneratedImageResult
import com.example.domain.models.ImageGenerationStage
import com.example.domain.models.MemoryCategory
import com.example.domain.models.MemoryCloudSyncReport
import com.example.domain.models.MemorySourceType
import com.example.domain.models.MemorySyncEntityType
import com.example.domain.models.MemorySyncOperationType
import com.example.domain.models.MessageAttachment
import com.example.domain.models.MessageRole
import com.example.domain.models.MessageStatus
import com.example.domain.models.PendingMemorySyncOperation
import com.example.domain.models.ProjectContextMemory
import com.example.domain.models.StreamChunk
import com.example.domain.models.UserMemoryItem
import com.example.domain.repositories.AuthRepository
import com.example.domain.repositories.ChatRepository
import com.example.domain.repositories.ModelCatalogRepository
import com.example.domain.usecases.ConversationContextManager
import com.example.domain.usecases.PersonalIntelligenceEngine
import com.example.domain.usecases.SmartModelRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

class ChatRepositoryImpl(
    private val chatCacheDao: ChatCacheDao,
    private val networkClient: SupabaseAndGatewayClient,
    private val authRepository: AuthRepository,
    private val modelCatalogRepository: ModelCatalogRepository
) : ChatRepository {

    private val cloudSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun observeConversations(userId: String): Flow<List<Conversation>> {
        return chatCacheDao.observeConversationsForUser(userId)
            .map { list -> list.map { it.toDomain() } }
    }

    override fun observeMessages(conversationId: String, userId: String): Flow<List<ChatMessage>> {
        return chatCacheDao.observeMessagesForConversation(conversationId, userId)
            .map { list -> list.map { it.toDomain() } }
    }

    override suspend fun syncConversationsFromCloud(
        userId: String,
        limit: Int,
        offset: Int
    ): Result<List<Conversation>> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(
                AiGatewayException(AiErrorType.UNAUTHORIZED, "Unauthorized cross-user access blocked.")
            )
        }
        if (!networkClient.isLiveCloudConfigured || !session.hasValidCloudJwt) {
            return Result.success(emptyList())
        }
        return try {
            val remoteConversations = networkClient.fetchConversations(session.accessToken, userId, limit, offset)
            chatCacheDao.upsertConversations(
                remoteConversations.map { ConversationEntity.fromDomain(it, isSynced = true) }
            )
            Result.success(remoteConversations)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun syncMessagesForConversation(
        conversationId: String,
        userId: String,
        limit: Int,
        offset: Int
    ): Result<List<ChatMessage>> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(
                AiGatewayException(AiErrorType.UNAUTHORIZED, "Unauthorized cross-user access blocked.")
            )
        }
        if (!networkClient.isLiveCloudConfigured || !session.hasValidCloudJwt) {
            val local = chatCacheDao.getMessagesPaged(conversationId, userId, limit, offset).map { it.toDomain() }
            return Result.success(local)
        }
        return try {
            val existingLocalById = chatCacheDao.getMessagesPaged(conversationId, userId, limit, offset)
                .associateBy { it.id }

            val remoteMessages = networkClient.fetchMessages(session.accessToken, conversationId, userId, limit, offset)
                .mapNotNull { msg ->
                    val localExisting = existingLocalById[msg.id]
                    // Never overwrite an actively streaming local message or a local message with longer content
                    if (localExisting != null) {
                        if (localExisting.status == MessageStatus.STREAMING.dbValue) {
                            return@mapNotNull null
                        }
                        if (localExisting.content.length > msg.content.length) {
                            return@mapNotNull null
                        }
                    }
                    // Normalize any stale STREAMING row fetched from remote cloud so it never appears stuck
                    if (msg.status == MessageStatus.STREAMING) {
                        msg.copy(
                            status = if (msg.content.isNotBlank()) MessageStatus.COMPLETED else MessageStatus.INTERRUPTED,
                            errorMessage = if (msg.content.isBlank()) "Response generation was interrupted." else msg.errorMessage
                        )
                    } else {
                        msg
                    }
                }
            if (remoteMessages.isNotEmpty()) {
                chatCacheDao.upsertMessages(
                    remoteMessages.map { MessageEntity.fromDomain(it, isSynced = true) }
                )
            }
            val mergedLocal = chatCacheDao.getMessagesPaged(conversationId, userId, limit, offset).map { it.toDomain() }
            Result.success(mergedLocal.ifEmpty { remoteMessages })
        } catch (e: Exception) {
            val localFallback = chatCacheDao.getMessagesPaged(conversationId, userId, limit, offset).map { it.toDomain() }
            if (localFallback.isNotEmpty()) {
                Result.success(localFallback)
            } else {
                Result.failure(e)
            }
        }
    }

    override suspend fun createConversation(
        userId: String,
        title: String,
        selectedModelId: String
    ): Result<Conversation> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(
                AiGatewayException(AiErrorType.UNAUTHORIZED, "Cannot create conversation for unauthenticated user.")
            )
        }
        val nowMs = System.currentTimeMillis()
        val conversation = Conversation(
            id = UUID.randomUUID().toString(),
            userId = userId,
            title = title.trim().ifBlank { "New Conversation" },
            selectedModelId = selectedModelId,
            createdAtEpochMs = nowMs,
            updatedAtEpochMs = nowMs
        )
        val canSyncToCloud = networkClient.isLiveCloudConfigured && session.hasValidCloudJwt
        chatCacheDao.upsertConversation(
            ConversationEntity.fromDomain(conversation, isSynced = !canSyncToCloud)
        )

        if (canSyncToCloud) {
            val token = session.accessToken
            cloudSyncScope.launch {
                runCatching {
                    val synced = networkClient.insertConversation(token, conversation)
                    chatCacheDao.upsertConversation(ConversationEntity.fromDomain(synced, isSynced = true))
                }
            }
        }
        return Result.success(conversation)
    }

    override suspend fun renameConversation(
        conversationId: String,
        userId: String,
        newTitle: String
    ): Result<Conversation> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(AiGatewayException(AiErrorType.UNAUTHORIZED, "Unauthorized."))
        }
        val existing = chatCacheDao.getConversation(conversationId, userId)
            ?: return Result.failure(IllegalArgumentException("Conversation not found for this user."))
        val cleaned = newTitle.trim().ifBlank { existing.title }
        val updated = existing.copy(
            title = cleaned,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        chatCacheDao.upsertConversation(updated)

        if (networkClient.isLiveCloudConfigured && session.hasValidCloudJwt) {
            val token = session.accessToken
            cloudSyncScope.launch {
                runCatching {
                    networkClient.patchConversation(
                        accessToken = token,
                        conversationId = conversationId,
                        userId = userId,
                        title = cleaned
                    )
                }
            }
        }
        return Result.success(updated.toDomain())
    }

    override suspend fun updateConversationModel(
        conversationId: String,
        userId: String,
        newModelId: String
    ): Result<Conversation> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(AiGatewayException(AiErrorType.UNAUTHORIZED, "Unauthorized."))
        }
        val existing = chatCacheDao.getConversation(conversationId, userId)
            ?: return Result.failure(IllegalArgumentException("Conversation not found for this user."))
        val updated = existing.copy(
            selectedModelId = newModelId,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        chatCacheDao.upsertConversation(updated)

        if (networkClient.isLiveCloudConfigured && session.hasValidCloudJwt) {
            val token = session.accessToken
            cloudSyncScope.launch {
                runCatching {
                    networkClient.patchConversation(
                        accessToken = token,
                        conversationId = conversationId,
                        userId = userId,
                        selectedModelId = newModelId
                    )
                }
            }
        }
        return Result.success(updated.toDomain())
    }

    override suspend fun deleteConversation(conversationId: String, userId: String): Result<Unit> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(AiGatewayException(AiErrorType.UNAUTHORIZED, "Unauthorized."))
        }
        chatCacheDao.deleteMessagesForConversation(conversationId, userId)
        chatCacheDao.deleteConversation(conversationId, userId)
        ConversationContextManager.clearSummary(conversationId)
        if (networkClient.isLiveCloudConfigured && session.hasValidCloudJwt) {
            val token = session.accessToken
            cloudSyncScope.launch {
                runCatching {
                    networkClient.deleteConversation(token, conversationId, userId)
                }
            }
        }
        return Result.success(Unit)
    }

    override suspend fun clearAllUserConversations(userId: String): Result<Unit> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(AiGatewayException(AiErrorType.UNAUTHORIZED, "Unauthorized."))
        }
        chatCacheDao.deleteAllMessagesForUser(userId)
        chatCacheDao.deleteAllConversationsForUser(userId)
        ConversationContextManager.clearAllSummaries()
        return Result.success(Unit)
    }

    override suspend fun searchConversations(
        userId: String,
        query: String,
        limit: Int,
        offset: Int
    ): Result<List<ConversationSearchResult>> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(AiGatewayException(AiErrorType.UNAUTHORIZED, "Unauthorized search blocked."))
        }
        val trimmed = query.trim()

        if (networkClient.isLiveCloudConfigured && session.hasValidCloudJwt) {
            runCatching {
                val remoteResults = networkClient.searchChatsRpc(
                    accessToken = session.accessToken,
                    userId = userId,
                    query = trimmed,
                    limit = limit,
                    offset = offset
                )
                if (remoteResults.isNotEmpty()) {
                    return Result.success(remoteResults)
                }
            }
        }

        val matchingConvs = chatCacheDao.searchLocalConversations(userId, trimmed, limit, offset)
        val results = matchingConvs.map { convEntity ->
            val matchedMsg = if (trimmed.isNotEmpty()) {
                chatCacheDao.findMatchingMessageForSnippet(convEntity.id, userId, trimmed)
            } else {
                null
            }
            val latestMsg = matchedMsg ?: chatCacheDao.getLatestMessageForConversation(convEntity.id, userId)
            val snippet = when {
                matchedMsg != null -> buildHighlightedSnippet(matchedMsg.content, trimmed)
                latestMsg != null && latestMsg.content.isNotBlank() -> latestMsg.content.take(140)
                else -> "Model: ${com.example.domain.models.AiModelCatalog.getDisplayName(convEntity.selectedModelId)}"
            }
            ConversationSearchResult(
                conversation = convEntity.toDomain(),
                snippet = snippet,
                matchedInMessage = matchedMsg != null
            )
        }
        return Result.success(results)
    }

    override suspend fun saveUserMessage(
        conversationId: String,
        userId: String,
        content: String,
        modelId: String,
        attachments: List<MessageAttachment>,
        clientRequestId: String
    ): Result<ChatMessage> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(AiGatewayException(AiErrorType.UNAUTHORIZED, "Unauthorized."))
        }
        val conv = chatCacheDao.getConversation(conversationId, userId)
            ?: return Result.failure(IllegalArgumentException("Conversation does not belong to current user."))

        // Prevent duplicate message insertion for the same clientRequestId
        val existingDuplicate = chatCacheDao.findByClientRequestId(conversationId, clientRequestId, userId)
        if (existingDuplicate != null) {
            return Result.success(existingDuplicate.toDomain())
        }

        val nowMs = System.currentTimeMillis()
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            userId = userId,
            role = MessageRole.USER,
            content = content.trim(),
            modelId = modelId,
            status = MessageStatus.COMPLETED,
            attachments = attachments,
            clientRequestId = clientRequestId,
            createdAtEpochMs = nowMs,
            updatedAtEpochMs = nowMs
        )

        val canSyncToCloud = networkClient.isLiveCloudConfigured && session.hasValidCloudJwt
        chatCacheDao.upsertMessage(MessageEntity.fromDomain(message, isSynced = !canSyncToCloud))
        chatCacheDao.upsertConversation(conv.copy(updatedAtEpochMs = nowMs))

        if (canSyncToCloud) {
            val token = session.accessToken
            cloudSyncScope.launch {
                runCatching {
                    val synced = networkClient.insertMessage(token, message)
                    chatCacheDao.upsertMessage(MessageEntity.fromDomain(synced, isSynced = true))
                }
            }
        }
        return Result.success(message)
    }

    override suspend fun saveAssistantMessage(
        conversationId: String,
        userId: String,
        content: String,
        modelId: String,
        attachments: List<MessageAttachment>,
        clientRequestId: String
    ): Result<ChatMessage> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(AiGatewayException(AiErrorType.UNAUTHORIZED, "Unauthorized."))
        }
        val conv = chatCacheDao.getConversation(conversationId, userId)
            ?: return Result.failure(IllegalArgumentException("Conversation does not belong to current user."))

        val existingDuplicate = chatCacheDao.findByClientRequestId(conversationId, clientRequestId, userId)
        if (existingDuplicate != null) {
            return Result.success(existingDuplicate.toDomain())
        }

        val nowMs = System.currentTimeMillis()
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            userId = userId,
            role = MessageRole.ASSISTANT,
            content = content.trim(),
            modelId = modelId,
            status = MessageStatus.COMPLETED,
            attachments = attachments,
            clientRequestId = clientRequestId,
            createdAtEpochMs = nowMs,
            updatedAtEpochMs = nowMs
        )

        val canSyncToCloud = networkClient.isLiveCloudConfigured && session.hasValidCloudJwt
        chatCacheDao.upsertMessage(MessageEntity.fromDomain(message, isSynced = !canSyncToCloud))
        chatCacheDao.upsertConversation(conv.copy(updatedAtEpochMs = nowMs))

        if (canSyncToCloud) {
            val token = session.accessToken
            cloudSyncScope.launch {
                runCatching {
                    // Strip large base64 data URLs before syncing cloud row; preserve URI/metadata
                    val cloudSafeAttachments = attachments.map { att ->
                        if ((att.base64DataUrl?.length ?: 0) > 32_768 && att.uriString.isNotBlank()) {
                            att.copy(base64DataUrl = null)
                        } else {
                            att
                        }
                    }
                    val synced = networkClient.insertMessage(token, message.copy(attachments = cloudSafeAttachments))
                    chatCacheDao.upsertMessage(MessageEntity.fromDomain(synced.copy(attachments = attachments), isSynced = true))
                }
            }
        }
        return Result.success(message)
    }

    override suspend fun generateChatImage(
        prompt: String,
        modelId: String,
        width: Int,
        height: Int,
        enhance: Boolean,
        onStageChanged: ((ImageGenerationStage) -> Unit)?
    ): Result<GeneratedImageResult> {
        val session = authRepository.sessionFlow.value
        return try {
            val generated = networkClient.generatePollinationsImage(
                accessToken = session?.accessToken,
                prompt = prompt,
                modelId = modelId,
                width = width,
                height = height,
                enhance = enhance,
                onStageChanged = onStageChanged
            )
            val localUri = GeneratedImageBinaryCache.putGeneratedImage(
                attachmentId = generated.id,
                imageBytes = generated.imageBytes,
                mimeType = generated.mimeType,
                remoteUrl = generated.imageUrl
            )
            Result.success(generated.copy(localFilePath = localUri))
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun editUserMessageAndTruncateSubsequent(
        conversationId: String,
        userId: String,
        messageId: String,
        newContent: String
    ): Result<ChatMessage> {
        val session = authRepository.sessionFlow.value
        if (session == null || session.user.id != userId) {
            return Result.failure(AiGatewayException(AiErrorType.UNAUTHORIZED, "Unauthorized."))
        }
        val messages = chatCacheDao.getMessagesPaged(conversationId, userId, 500, 0)
        val target = messages.firstOrNull { it.id == messageId && it.userId == userId }
            ?: return Result.failure(IllegalArgumentException("Message not found."))

        chatCacheDao.deleteMessagesAfter(conversationId, userId, target.createdAtEpochMs)
        val updated = target.copy(
            content = newContent.trim(),
            updatedAtEpochMs = System.currentTimeMillis()
        )
        chatCacheDao.upsertMessage(updated)
        return Result.success(updated.toDomain())
    }

    override suspend fun deleteMessage(messageId: String, userId: String): Result<Unit> {
        chatCacheDao.deleteMessage(messageId, userId)
        return Result.success(Unit)
    }

    override fun streamAssistantResponse(
        conversationId: String,
        userId: String,
        requestedModelId: String,
        fallbackModelId: String?,
        allowFallback: Boolean,
        history: List<ChatMessage>,
        streamEnabled: Boolean,
        clientRequestId: String
    ): Flow<StreamChunk> = flow {
        val cachedSession = authRepository.sessionFlow.value
        val session = if (
            cachedSession != null &&
            cachedSession.user.id == userId &&
            (cachedSession.expiresAtEpochMs <= 0L || cachedSession.expiresAtEpochMs - System.currentTimeMillis() > 60_000L)
        ) {
            cachedSession
        } else {
            authRepository.refreshSessionIfNeeded().getOrNull()
        }
        if (session == null || session.user.id != userId) {
            emit(
                StreamChunk.Error(
                    AiGatewayException(
                        errorType = AiErrorType.UNAUTHORIZED,
                        userFriendlyMessage = "Your session has expired. Please sign in again.",
                        requestedModelId = requestedModelId,
                        canRetry = false
                    ),
                    partialText = ""
                )
            )
            return@flow
        }

        // Verify ownership of the conversation before making any provider call
        val convEntity = chatCacheDao.getConversation(conversationId, userId)
        if (convEntity == null) {
            emit(
                StreamChunk.Error(
                    AiGatewayException(
                        errorType = AiErrorType.UNAUTHORIZED,
                        userFriendlyMessage = "Access denied: Conversation does not belong to the active user.",
                        requestedModelId = requestedModelId,
                        canRetry = false
                    ),
                    partialText = ""
                )
            )
            return@flow
        }

        // Validate that a chat model is configured and available in the catalog
        if (requestedModelId.isBlank()) {
            emit(
                StreamChunk.Error(
                    AiGatewayException(
                        errorType = AiErrorType.UNSUPPORTED_MODEL,
                        userFriendlyMessage = "No chat AI provider or model is currently configured.",
                        requestedModelId = requestedModelId,
                        canRetry = false
                    ),
                    partialText = ""
                )
            )
            return@flow
        }

        val requestedModelDef = modelCatalogRepository.getModelById(requestedModelId)
        var effectiveModelId = requestedModelId
        var fallbackTriggered = false

        if (requestedModelDef.availabilityStatus == AvailabilityStatus.UNAVAILABLE) {
            if (allowFallback && !fallbackModelId.isNullOrBlank() && fallbackModelId != requestedModelId) {
                val fallbackDef = modelCatalogRepository.getModelById(fallbackModelId)
                if (fallbackDef.availabilityStatus != AvailabilityStatus.UNAVAILABLE) {
                    effectiveModelId = fallbackModelId
                    fallbackTriggered = true
                    emit(StreamChunk.Meta(effectiveModelId, requestedModelId, fallbackUsed = true))
                } else {
                    emit(
                        StreamChunk.Error(
                            AiGatewayException(
                                errorType = AiErrorType.UNSUPPORTED_MODEL,
                                userFriendlyMessage = "The selected model and fallback model are currently unavailable.",
                                requestedModelId = requestedModelId,
                                canRetry = false
                            ),
                            partialText = ""
                        )
                    )
                    return@flow
                }
            } else {
                emit(
                    StreamChunk.Error(
                        AiGatewayException(
                            errorType = AiErrorType.UNSUPPORTED_MODEL,
                            userFriendlyMessage = "The model '${requestedModelDef.displayName}' ($requestedModelId) is currently unavailable. Please select an available Smarter AI model.",
                            requestedModelId = requestedModelId,
                            canRetry = false
                        ),
                        partialText = ""
                    )
                )
                return@flow
            }
        }

        val assistantMsgId = UUID.randomUUID().toString()
        val assistantClientReqId = "${clientRequestId}_assistant"
        val startMs = System.currentTimeMillis()

        // Create initial streaming message placeholder in local cache
        val initialEntity = MessageEntity(
            id = assistantMsgId,
            conversationId = conversationId,
            userId = userId,
            role = MessageRole.ASSISTANT.apiValue,
            content = "",
            modelId = effectiveModelId,
            requestedModelId = requestedModelId,
            fallbackUsed = fallbackTriggered,
            status = MessageStatus.STREAMING.dbValue,
            clientRequestId = assistantClientReqId,
            createdAtEpochMs = startMs,
            updatedAtEpochMs = startMs,
            isSyncedWithCloud = false
        )
        chatCacheDao.upsertMessage(initialEntity)

        val effectiveStream = streamEnabled && requestedModelDef.supportsStreaming
        val partialBuilder = StringBuilder()

        val lastUserMsg = history.lastOrNull { it.role == MessageRole.USER }
        val hasImageAttachments = lastUserMsg?.attachments?.isNotEmpty() == true
        val routedFallbacks = if (allowFallback) {
            SmartModelRouter.routeRequest(
                promptText = lastUserMsg?.content.orEmpty(),
                hasImageAttachments = hasImageAttachments,
                conversationHistoryCount = history.size,
                catalog = modelCatalogRepository.catalogFlow.value,
                recentHistory = history
            ).fallbackModelIds
        } else {
            emptyList()
        }
        val candidateModels = buildList {
            add(effectiveModelId)
            if (allowFallback) {
                if (!fallbackModelId.isNullOrBlank()) add(fallbackModelId)
                addAll(routedFallbacks)
            }
        }
            .map { it.trim() }
            .filter { it.isNotEmpty() && AiModelCatalog.isSupportedApinexModel(it) }
            .distinct()
            .filter { candidateId ->
                candidateId == effectiveModelId ||
                    modelCatalogRepository.getModelById(candidateId).availabilityStatus != AvailabilityStatus.UNAVAILABLE
            }
            .take(3)

        try {
            var attemptIndex = 0
            var terminalErrorChunk: StreamChunk.Error? = null

            while (attemptIndex < candidateModels.size) {
                val attemptModelId = candidateModels[attemptIndex]
                val isFallbackAttempt = attemptIndex > 0 || fallbackTriggered
                if (attemptModelId != effectiveModelId || isFallbackAttempt != fallbackTriggered) {
                    effectiveModelId = attemptModelId
                    fallbackTriggered = isFallbackAttempt
                    chatCacheDao.upsertMessage(
                        initialEntity.copy(
                            modelId = effectiveModelId,
                            fallbackUsed = fallbackTriggered,
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                    emit(
                        StreamChunk.Meta(
                            activeModelId = effectiveModelId,
                            requestedModelId = requestedModelId,
                            fallbackUsed = fallbackTriggered
                        )
                    )
                }

                var shouldTryNextFallback = false
                val attemptReqId = if (attemptIndex == 0) clientRequestId else "${clientRequestId}_fb$attemptIndex"

                val upstreamFlow = networkClient.streamChatCompletion(
                    accessToken = session.accessToken,
                    conversationId = conversationId,
                    requestedModelId = effectiveModelId,
                    fallbackModelId = fallbackModelId,
                    allowFallback = allowFallback,
                    history = history,
                    streamEnabled = effectiveStream,
                    clientRequestId = attemptReqId
                )

                upstreamFlow.collect { chunk ->
                    when (chunk) {
                        is StreamChunk.Meta -> {
                            val newActiveModelId = chunk.activeModelId
                            val newFallback = chunk.fallbackUsed || fallbackTriggered
                            if (newActiveModelId != effectiveModelId || newFallback != fallbackTriggered) {
                                effectiveModelId = newActiveModelId
                                fallbackTriggered = newFallback
                                chatCacheDao.upsertMessage(
                                    initialEntity.copy(
                                        modelId = effectiveModelId,
                                        fallbackUsed = fallbackTriggered,
                                        updatedAtEpochMs = System.currentTimeMillis()
                                    )
                                )
                            }
                            emit(
                                chunk.copy(
                                    requestedModelId = requestedModelId,
                                    fallbackUsed = fallbackTriggered
                                )
                            )
                        }
                        is StreamChunk.ReasoningDelta -> {
                            emit(chunk)
                        }
                        is StreamChunk.Delta -> {
                            partialBuilder.append(chunk.textDelta)
                            effectiveModelId = chunk.activeModelId
                            fallbackTriggered = chunk.fallbackUsed || fallbackTriggered
                            emit(
                                chunk.copy(
                                    activeModelId = effectiveModelId,
                                    fallbackUsed = fallbackTriggered
                                )
                            )
                        }
                        is StreamChunk.Completed -> {
                            val authoritativeFxText = com.example.data.livedata.LiveMarketDataRegistry
                                .getForRequest(clientRequestId)
                                ?.authoritativeDirectAnswer
                                ?.takeIf { it.isNotBlank() }
                            val finalContent = authoritativeFxText ?: chunk.fullText.ifEmpty { partialBuilder.toString() }
                            val now = System.currentTimeMillis()
                            val completedEntity = initialEntity.copy(
                                content = finalContent,
                                modelId = chunk.activeModelId,
                                fallbackUsed = chunk.fallbackUsed || fallbackTriggered,
                                status = MessageStatus.COMPLETED.dbValue,
                                updatedAtEpochMs = now,
                                isSyncedWithCloud = true
                            )
                            chatCacheDao.upsertMessage(completedEntity)
                            chatCacheDao.upsertConversation(convEntity.copy(updatedAtEpochMs = now))
                            val dbPersistedAtMs = System.currentTimeMillis()

                            if (networkClient.isLiveCloudConfigured && session.hasValidCloudJwt) {
                                val token = session.accessToken
                                val domainMsg = completedEntity.toDomain()
                                cloudSyncScope.launch {
                                    runCatching {
                                        networkClient.insertMessage(token, domainMsg)
                                    }
                                }
                            }
                            emit(
                                chunk.copy(
                                    fullText = finalContent,
                                    fallbackUsed = chunk.fallbackUsed || fallbackTriggered,
                                    timingMetrics = chunk.timingMetrics?.copy(dbPersistedEpochMs = dbPersistedAtMs)
                                )
                            )
                        }
                        is StreamChunk.Error -> {
                            val savedText = chunk.partialText.ifEmpty { partialBuilder.toString() }
                            val canFailoverToNextModel = allowFallback &&
                                savedText.isBlank() &&
                                attemptIndex + 1 < candidateModels.size &&
                                chunk.exception.errorType != AiErrorType.UNAUTHORIZED

                            if (canFailoverToNextModel) {
                                shouldTryNextFallback = true
                            } else {
                                val now = System.currentTimeMillis()
                                val finalStatus = if (savedText.isNotBlank()) {
                                    MessageStatus.INTERRUPTED.dbValue
                                } else {
                                    MessageStatus.ERROR.dbValue
                                }
                                chatCacheDao.upsertMessage(
                                    initialEntity.copy(
                                        content = savedText,
                                        modelId = effectiveModelId,
                                        fallbackUsed = fallbackTriggered,
                                        status = finalStatus,
                                        errorMessage = chunk.exception.userFriendlyMessage,
                                        errorCode = chunk.exception.errorType.name,
                                        updatedAtEpochMs = now
                                    )
                                )
                                val dbPersistedAtMs = System.currentTimeMillis()
                                terminalErrorChunk = chunk.copy(
                                    partialText = savedText,
                                    timingMetrics = chunk.timingMetrics?.copy(dbPersistedEpochMs = dbPersistedAtMs)
                                )
                            }
                        }
                    }
                }

                if (shouldTryNextFallback) {
                    attemptIndex++
                } else {
                    break
                }
            }

            terminalErrorChunk?.let { emit(it) }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            withContext(NonCancellable) {
                val savedText = partialBuilder.toString()
                if (savedText.isNotBlank()) {
                    chatCacheDao.upsertMessage(
                        initialEntity.copy(
                            content = savedText,
                            status = MessageStatus.INTERRUPTED.dbValue,
                            errorMessage = "Generation stopped by user.",
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                } else {
                    chatCacheDao.deleteMessage(assistantMsgId, userId)
                }
            }
            throw ce
        }
    }

    private val memorySyncMutex = Mutex()

    // ========================================================================
    // Personal Intelligence Engine — Offline-First Room + Supabase Cloud Sync
    // ========================================================================

    override fun observeUserMemories(userId: String): Flow<List<UserMemoryItem>> {
        return chatCacheDao.observeUserMemories(userId)
            .map { entities -> entities.map { it.toDomain() } }
    }

    override fun observeProjectContexts(userId: String): Flow<List<ProjectContextMemory>> {
        return chatCacheDao.observeProjectContexts(userId)
            .map { entities -> entities.map { it.toDomain() } }
    }

    override fun observeConversationSummaries(userId: String): Flow<List<ConversationSummaryMemory>> {
        return chatCacheDao.observeConversationSummaries(userId)
            .map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun getUserMemoriesSnapshot(userId: String): List<UserMemoryItem> {
        return chatCacheDao.getUserMemories(userId).map { it.toDomain() }
    }

    override suspend fun getProjectContextsSnapshot(userId: String): List<ProjectContextMemory> {
        return chatCacheDao.getProjectContexts(userId).map { it.toDomain() }
    }

    override suspend fun getConversationSummariesSnapshot(userId: String): List<ConversationSummaryMemory> {
        return chatCacheDao.getConversationSummaries(userId).map { it.toDomain() }
    }

    override suspend fun getPendingMemorySyncOperations(userId: String): List<PendingMemorySyncOperation> {
        return chatCacheDao.getPendingMemorySyncOperations(userId).map { it.toDomain() }
    }

    private fun verifyMemoryUserOwnership(targetUserId: String): Result<Unit> {
        if (targetUserId.isBlank()) {
            return Result.failure(
                AiGatewayException(
                    errorType = AiErrorType.UNAUTHORIZED,
                    userFriendlyMessage = "User ID is required for memory operations.",
                    statusCode = 401,
                    canRetry = false
                )
            )
        }
        val session = authRepository.sessionFlow.value
        if (session != null && session.user.id != targetUserId) {
            return Result.failure(
                AiGatewayException(
                    errorType = AiErrorType.UNAUTHORIZED,
                    userFriendlyMessage = "Unauthorized cross-user memory operation blocked.",
                    statusCode = 403,
                    canRetry = false
                )
            )
        }
        return Result.success(Unit)
    }

    private fun userMemoryNaturalKey(category: MemoryCategory, memoryKey: String): String {
        return "user_memory:${category.name}:${memoryKey.trim().lowercase(Locale.ROOT)}"
    }

    private fun projectNaturalKey(projectName: String): String {
        return "project_context:${projectName.trim().lowercase(Locale.ROOT)}"
    }

    private fun conversationSummaryNaturalKey(conversationId: String): String {
        return "conversation_summary:${conversationId.trim()}"
    }

    private suspend fun resolveAuthenticatedCloudToken(expectedUserId: String): Result<String> {
        val currentSession = authRepository.sessionFlow.value
            ?: return Result.failure(
                AiGatewayException(
                    errorType = AiErrorType.UNAUTHORIZED,
                    userFriendlyMessage = "No authenticated session available for cloud memory sync.",
                    statusCode = 401,
                    canRetry = false
                )
            )
        if (currentSession.user.id != expectedUserId) {
            return Result.failure(
                AiGatewayException(
                    errorType = AiErrorType.UNAUTHORIZED,
                    userFriendlyMessage = "Session user_id mismatch ($expectedUserId vs ${currentSession.user.id}).",
                    statusCode = 403,
                    canRetry = false
                )
            )
        }
        if (SupabaseAndGatewayClient.isServiceRoleKeyOrJwt(currentSession.accessToken) ||
            SupabaseAndGatewayClient.isServiceRoleKeyOrJwt(networkClient.supabaseAnonKey)
        ) {
            return Result.failure(
                AiGatewayException(
                    errorType = AiErrorType.UNAUTHORIZED,
                    userFriendlyMessage = "Security violation: service_role credentials are prohibited in Android.",
                    statusCode = 403,
                    canRetry = false
                )
            )
        }
        if (!networkClient.isLiveCloudConfigured) {
            return Result.failure(
                AiGatewayException(
                    errorType = AiErrorType.NETWORK_UNAVAILABLE,
                    userFriendlyMessage = "Supabase cloud configuration is not active.",
                    statusCode = 503,
                    canRetry = true
                )
            )
        }
        val activeSession = if (currentSession.isExpired()) {
            authRepository.refreshSessionIfNeeded().getOrNull() ?: currentSession
        } else {
            currentSession
        }
        if (activeSession.user.id != expectedUserId || !activeSession.hasValidCloudJwt) {
            return Result.failure(
                AiGatewayException(
                    errorType = AiErrorType.UNAUTHORIZED,
                    userFriendlyMessage = "Authenticated Supabase JWT is required for cloud memory sync.",
                    statusCode = 401,
                    canRetry = false
                )
            )
        }
        return Result.success(activeSession.accessToken)
    }

    private suspend fun <T> executeMemoryCloudOpWithRefresh(
        userId: String,
        block: suspend (accessToken: String) -> T
    ): Result<T> {
        val tokenResult = resolveAuthenticatedCloudToken(userId)
        val initialToken = tokenResult.getOrElse { return Result.failure(it) }
        return try {
            Result.success(block(initialToken))
        } catch (e: AiGatewayException) {
            if (e.statusCode == 401) {
                val refreshed = authRepository.refreshSessionIfNeeded().getOrNull()
                if (refreshed != null &&
                    refreshed.user.id == userId &&
                    refreshed.hasValidCloudJwt &&
                    refreshed.accessToken != initialToken
                ) {
                    return runCatching { block(refreshed.accessToken) }
                }
            }
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun saveUserMemory(item: UserMemoryItem): Result<UserMemoryItem> {
        verifyMemoryUserOwnership(item.userId).onFailure { return Result.failure(it) }
        val cleanKey = item.memoryKey.trim()
        val cleanContent = item.content.trim()
        if (cleanKey.isEmpty() || cleanContent.isEmpty()) {
            return Result.failure(IllegalArgumentException("Memory key and content must not be blank."))
        }
        if (PersonalIntelligenceEngine.hasOptOutDirective(cleanContent) ||
            PersonalIntelligenceEngine.containsSensitivePersonalData(cleanContent) ||
            PersonalIntelligenceEngine.containsSensitivePersonalData(cleanKey)
        ) {
            return Result.failure(
                IllegalArgumentException("Memory contains opt-out or sensitive personal information and cannot be saved.")
            )
        }

        val normalizedInput = item.copy(memoryKey = cleanKey, content = cleanContent)
        val existingEntity = chatCacheDao.getUserMemoryByKey(
            userId = normalizedInput.userId,
            category = normalizedInput.category.name,
            memoryKey = normalizedInput.memoryKey
        )
        val canonicalItem = if (existingEntity != null) {
            val resolved = PersonalIntelligenceEngine.resolveMemoryConflict(
                local = existingEntity.toDomain(),
                remote = normalizedInput
            )
            if (existingEntity.id != resolved.id) {
                chatCacheDao.deleteUserMemoryById(existingEntity.id, normalizedInput.userId)
            }
            resolved.copy(id = existingEntity.id)
        } else {
            normalizedInput
        }

        val natKey = userMemoryNaturalKey(canonicalItem.category, canonicalItem.memoryKey)

        // 1. Offline-first immediate Room persistence (unsynced initially)
        chatCacheDao.upsertUserMemory(UserMemoryEntity.fromDomain(canonicalItem, isSynced = false))
        chatCacheDao.deletePendingMemorySyncByNaturalKey(
            userId = canonicalItem.userId,
            entityType = MemorySyncEntityType.USER_MEMORY.name,
            naturalKey = natKey
        )

        // Do not upload unapproved or sensitive memories to the cloud
        if (!PersonalIntelligenceEngine.isMemoryEligibleForCloudUpload(canonicalItem, canonicalItem.userId)) {
            return Result.success(canonicalItem)
        }

        // 2. Attempt immediate cloud upload if online & authenticated; otherwise queue for retry
        val uploadResult = executeMemoryCloudOpWithRefresh(canonicalItem.userId) { token ->
            networkClient.upsertUserMemoryCloud(token, canonicalItem)
        }
        uploadResult.fold(
            onSuccess = { remoteSaved ->
                val merged = PersonalIntelligenceEngine.resolveMemoryConflict(canonicalItem, remoteSaved)
                    .copy(id = canonicalItem.id)
                chatCacheDao.upsertUserMemory(UserMemoryEntity.fromDomain(merged, isSynced = true))
                return Result.success(merged)
            },
            onFailure = { err ->
                enqueueMemorySyncOperation(
                    userId = canonicalItem.userId,
                    entityType = MemorySyncEntityType.USER_MEMORY,
                    operationType = MemorySyncOperationType.UPSERT,
                    naturalKey = natKey,
                    targetId = canonicalItem.id,
                    payloadJson = encodeUserMemoryQueuePayload(canonicalItem),
                    updatedAtEpochMs = canonicalItem.updatedAtEpochMs,
                    lastError = err.message
                )
                return Result.success(canonicalItem)
            }
        )
    }

    override suspend fun deleteUserMemory(memoryId: String, userId: String): Result<Unit> {
        verifyMemoryUserOwnership(userId).onFailure { return Result.failure(it) }
        val existing = chatCacheDao.getUserMemories(userId).firstOrNull { it.id == memoryId }
        val natKey = if (existing != null) {
            userMemoryNaturalKey(MemoryCategory.fromDbValue(existing.category), existing.memoryKey)
        } else {
            "user_memory_id:$memoryId"
        }

        // 1. Immediate local deletion
        chatCacheDao.deleteUserMemoryById(memoryId, userId)
        chatCacheDao.deletePendingMemorySyncByNaturalKey(
            userId = userId,
            entityType = MemorySyncEntityType.USER_MEMORY.name,
            naturalKey = natKey
        )

        // 2. Attempt immediate cloud deletion; if offline/failed, persist delete tombstone in queue
        val payload = "${existing?.category.orEmpty()}|${existing?.memoryKey.orEmpty()}"
        val deleteResult = executeMemoryCloudOpWithRefresh(userId) { token ->
            networkClient.deleteUserMemoryCloud(
                accessToken = token,
                memoryId = memoryId,
                userId = userId,
                category = existing?.category,
                memoryKey = existing?.memoryKey
            )
        }
        if (deleteResult.isFailure) {
            enqueueMemorySyncOperation(
                userId = userId,
                entityType = MemorySyncEntityType.USER_MEMORY,
                operationType = MemorySyncOperationType.DELETE,
                naturalKey = natKey,
                targetId = memoryId,
                payloadJson = payload,
                updatedAtEpochMs = System.currentTimeMillis(),
                lastError = deleteResult.exceptionOrNull()?.message
            )
        }
        return Result.success(Unit)
    }

    override suspend fun saveProjectContext(context: ProjectContextMemory): Result<ProjectContextMemory> {
        verifyMemoryUserOwnership(context.userId).onFailure { return Result.failure(it) }
        val cleanName = context.projectName.trim()
        if (cleanName.isEmpty()) {
            return Result.failure(IllegalArgumentException("Project name must not be blank."))
        }
        if (!PersonalIntelligenceEngine.isProjectEligibleForCloudUpload(context, context.userId)) {
            return Result.failure(
                IllegalArgumentException("Project context contains opt-out or sensitive personal data and cannot be saved.")
            )
        }

        val normalizedInput = context.copy(projectName = cleanName)
        val existingEntity = chatCacheDao.getProjectContextByName(normalizedInput.userId, cleanName)
        val canonicalProject = if (existingEntity != null) {
            val resolved = PersonalIntelligenceEngine.resolveProjectContextConflict(
                local = existingEntity.toDomain(),
                remote = normalizedInput
            )
            if (existingEntity.id != resolved.id) {
                chatCacheDao.deleteProjectContextById(existingEntity.id, normalizedInput.userId)
            }
            resolved.copy(id = existingEntity.id)
        } else {
            normalizedInput
        }

        val natKey = projectNaturalKey(canonicalProject.projectName)
        chatCacheDao.upsertProjectContext(ProjectContextEntity.fromDomain(canonicalProject, isSynced = false))
        chatCacheDao.deletePendingMemorySyncByNaturalKey(
            userId = canonicalProject.userId,
            entityType = MemorySyncEntityType.PROJECT_CONTEXT.name,
            naturalKey = natKey
        )

        val uploadResult = executeMemoryCloudOpWithRefresh(canonicalProject.userId) { token ->
            networkClient.upsertProjectContextCloud(token, canonicalProject)
        }
        uploadResult.fold(
            onSuccess = { remoteSaved ->
                val merged = PersonalIntelligenceEngine.resolveProjectContextConflict(canonicalProject, remoteSaved)
                    .copy(id = canonicalProject.id)
                chatCacheDao.upsertProjectContext(ProjectContextEntity.fromDomain(merged, isSynced = true))
                return Result.success(merged)
            },
            onFailure = { err ->
                enqueueMemorySyncOperation(
                    userId = canonicalProject.userId,
                    entityType = MemorySyncEntityType.PROJECT_CONTEXT,
                    operationType = MemorySyncOperationType.UPSERT,
                    naturalKey = natKey,
                    targetId = canonicalProject.id,
                    payloadJson = encodeProjectContextQueuePayload(canonicalProject),
                    updatedAtEpochMs = canonicalProject.updatedAtEpochMs,
                    lastError = err.message
                )
                return Result.success(canonicalProject)
            }
        )
    }

    override suspend fun deleteProjectContext(projectId: String, userId: String): Result<Unit> {
        verifyMemoryUserOwnership(userId).onFailure { return Result.failure(it) }
        val existing = chatCacheDao.getProjectContexts(userId).firstOrNull { it.id == projectId }
        val natKey = if (existing != null) {
            projectNaturalKey(existing.projectName)
        } else {
            "project_context_id:$projectId"
        }

        chatCacheDao.deleteProjectContextById(projectId, userId)
        chatCacheDao.deletePendingMemorySyncByNaturalKey(
            userId = userId,
            entityType = MemorySyncEntityType.PROJECT_CONTEXT.name,
            naturalKey = natKey
        )

        val deleteResult = executeMemoryCloudOpWithRefresh(userId) { token ->
            networkClient.deleteProjectContextCloud(
                accessToken = token,
                projectId = projectId,
                userId = userId,
                projectName = existing?.projectName
            )
        }
        if (deleteResult.isFailure) {
            enqueueMemorySyncOperation(
                userId = userId,
                entityType = MemorySyncEntityType.PROJECT_CONTEXT,
                operationType = MemorySyncOperationType.DELETE,
                naturalKey = natKey,
                targetId = projectId,
                payloadJson = existing?.projectName.orEmpty(),
                updatedAtEpochMs = System.currentTimeMillis(),
                lastError = deleteResult.exceptionOrNull()?.message
            )
        }
        return Result.success(Unit)
    }

    override suspend fun saveConversationSummary(summary: ConversationSummaryMemory): Result<ConversationSummaryMemory> {
        verifyMemoryUserOwnership(summary.userId).onFailure { return Result.failure(it) }
        if (!PersonalIntelligenceEngine.isConversationSummaryEligibleForCloudUpload(summary, summary.userId)) {
            return Result.failure(
                IllegalArgumentException("Conversation summary is blank or contains opt-out/sensitive data.")
            )
        }

        val existingEntity = chatCacheDao.getConversationSummaryByConversationId(
            userId = summary.userId,
            conversationId = summary.conversationId
        )
        val canonicalSummary = if (existingEntity != null) {
            PersonalIntelligenceEngine.resolveConversationSummaryConflict(
                local = existingEntity.toDomain(),
                remote = summary
            ).copy(id = existingEntity.id)
        } else {
            summary
        }

        val natKey = conversationSummaryNaturalKey(canonicalSummary.conversationId)
        chatCacheDao.upsertConversationSummary(ConversationSummaryEntity.fromDomain(canonicalSummary, isSynced = false))
        chatCacheDao.deletePendingMemorySyncByNaturalKey(
            userId = canonicalSummary.userId,
            entityType = MemorySyncEntityType.CONVERSATION_SUMMARY.name,
            naturalKey = natKey
        )

        val uploadResult = executeMemoryCloudOpWithRefresh(canonicalSummary.userId) { token ->
            networkClient.upsertConversationSummaryCloud(token, canonicalSummary)
        }
        uploadResult.fold(
            onSuccess = { remoteSaved ->
                val merged = PersonalIntelligenceEngine.resolveConversationSummaryConflict(canonicalSummary, remoteSaved)
                    .copy(id = canonicalSummary.id)
                chatCacheDao.upsertConversationSummary(ConversationSummaryEntity.fromDomain(merged, isSynced = true))
                return Result.success(merged)
            },
            onFailure = { err ->
                enqueueMemorySyncOperation(
                    userId = canonicalSummary.userId,
                    entityType = MemorySyncEntityType.CONVERSATION_SUMMARY,
                    operationType = MemorySyncOperationType.UPSERT,
                    naturalKey = natKey,
                    targetId = canonicalSummary.id,
                    payloadJson = encodeConversationSummaryQueuePayload(canonicalSummary),
                    updatedAtEpochMs = canonicalSummary.updatedAtEpochMs,
                    lastError = err.message
                )
                return Result.success(canonicalSummary)
            }
        )
    }

    override suspend fun deleteConversationSummary(summaryId: String, userId: String): Result<Unit> {
        verifyMemoryUserOwnership(userId).onFailure { return Result.failure(it) }
        val existing = chatCacheDao.getConversationSummaries(userId).firstOrNull { it.id == summaryId }
        val natKey = if (existing != null) {
            conversationSummaryNaturalKey(existing.conversationId)
        } else {
            "conversation_summary_id:$summaryId"
        }

        chatCacheDao.deleteConversationSummaryById(summaryId, userId)
        chatCacheDao.deletePendingMemorySyncByNaturalKey(
            userId = userId,
            entityType = MemorySyncEntityType.CONVERSATION_SUMMARY.name,
            naturalKey = natKey
        )

        val deleteResult = executeMemoryCloudOpWithRefresh(userId) { token ->
            networkClient.deleteConversationSummaryCloud(
                accessToken = token,
                summaryId = summaryId,
                userId = userId,
                conversationId = existing?.conversationId
            )
        }
        if (deleteResult.isFailure) {
            enqueueMemorySyncOperation(
                userId = userId,
                entityType = MemorySyncEntityType.CONVERSATION_SUMMARY,
                operationType = MemorySyncOperationType.DELETE,
                naturalKey = natKey,
                targetId = summaryId,
                payloadJson = existing?.conversationId.orEmpty(),
                updatedAtEpochMs = System.currentTimeMillis(),
                lastError = deleteResult.exceptionOrNull()?.message
            )
        }
        return Result.success(Unit)
    }

    override suspend fun clearAllUserMemories(userId: String): Result<Unit> {
        verifyMemoryUserOwnership(userId).onFailure { return Result.failure(it) }

        // 1. Delete all local memories, projects, conversation summaries, and obsolete pending upserts for this user
        chatCacheDao.deleteAllMemoriesForUser(userId)
        chatCacheDao.deleteAllProjectContextsForUser(userId)
        chatCacheDao.deleteAllConversationSummariesForUser(userId)
        chatCacheDao.deleteAllPendingMemorySyncForUser(userId)

        // 2. Attempt immediate cloud clear across all three tables; queue CLEAR_ALL tombstones if offline
        val now = System.currentTimeMillis()
        val memClear = executeMemoryCloudOpWithRefresh(userId) { token ->
            networkClient.deleteAllUserMemoriesCloud(token, userId)
        }
        if (memClear.isFailure) {
            enqueueMemorySyncOperation(
                userId = userId,
                entityType = MemorySyncEntityType.USER_MEMORY,
                operationType = MemorySyncOperationType.CLEAR_ALL,
                naturalKey = "clear_all:user_memory",
                targetId = userId,
                payloadJson = "",
                updatedAtEpochMs = now,
                lastError = memClear.exceptionOrNull()?.message
            )
        }

        val projClear = executeMemoryCloudOpWithRefresh(userId) { token ->
            networkClient.deleteAllProjectContextsCloud(token, userId)
        }
        if (projClear.isFailure) {
            enqueueMemorySyncOperation(
                userId = userId,
                entityType = MemorySyncEntityType.PROJECT_CONTEXT,
                operationType = MemorySyncOperationType.CLEAR_ALL,
                naturalKey = "clear_all:project_context",
                targetId = userId,
                payloadJson = "",
                updatedAtEpochMs = now,
                lastError = projClear.exceptionOrNull()?.message
            )
        }

        val sumClear = executeMemoryCloudOpWithRefresh(userId) { token ->
            networkClient.deleteAllConversationSummariesCloud(token, userId)
        }
        if (sumClear.isFailure) {
            enqueueMemorySyncOperation(
                userId = userId,
                entityType = MemorySyncEntityType.CONVERSATION_SUMMARY,
                operationType = MemorySyncOperationType.CLEAR_ALL,
                naturalKey = "clear_all:conversation_summaries",
                targetId = userId,
                payloadJson = "",
                updatedAtEpochMs = now,
                lastError = sumClear.exceptionOrNull()?.message
            )
        }

        return Result.success(Unit)
    }

    override suspend fun flushPendingMemorySyncQueue(userId: String): Result<MemoryCloudSyncReport> {
        verifyMemoryUserOwnership(userId).onFailure { return Result.failure(it) }
        val pendingList = chatCacheDao.getPendingMemorySyncOperations(userId)
        if (pendingList.isEmpty()) {
            return Result.success(MemoryCloudSyncReport(userId = userId, pendingQueueCount = 0))
        }

        val tokenResult = resolveAuthenticatedCloudToken(userId)
        if (tokenResult.isFailure) {
            val ex = tokenResult.exceptionOrNull()!!
            return Result.failure(ex)
        }

        var uploadedMemories = 0
        var uploadedProjects = 0
        var uploadedSummaries = 0
        var deletedCount = 0
        var firstFatalError: Throwable? = null

        for (op in pendingList) {
            val opType = MemorySyncOperationType.fromDbValue(op.operationType)
            val entityType = MemorySyncEntityType.fromDbValue(op.entityType)

            val stepResult: Result<Unit> = when (opType) {
                MemorySyncOperationType.CLEAR_ALL -> {
                    executeMemoryCloudOpWithRefresh(userId) { token ->
                        when (entityType) {
                            MemorySyncEntityType.USER_MEMORY -> networkClient.deleteAllUserMemoriesCloud(token, userId)
                            MemorySyncEntityType.PROJECT_CONTEXT -> networkClient.deleteAllProjectContextsCloud(token, userId)
                            MemorySyncEntityType.CONVERSATION_SUMMARY -> networkClient.deleteAllConversationSummariesCloud(token, userId)
                        }
                    }.onSuccess { deletedCount++ }
                }
                MemorySyncOperationType.DELETE -> {
                    executeMemoryCloudOpWithRefresh(userId) { token ->
                        when (entityType) {
                            MemorySyncEntityType.USER_MEMORY -> {
                                val parts = op.payloadJson.split("|", limit = 2)
                                val cat = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
                                val key = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                                networkClient.deleteUserMemoryCloud(token, op.targetId, userId, cat, key)
                            }
                            MemorySyncEntityType.PROJECT_CONTEXT -> {
                                val projName = op.payloadJson.takeIf { it.isNotBlank() }
                                networkClient.deleteProjectContextCloud(token, op.targetId, userId, projName)
                            }
                            MemorySyncEntityType.CONVERSATION_SUMMARY -> {
                                val convId = op.payloadJson.takeIf { it.isNotBlank() }
                                networkClient.deleteConversationSummaryCloud(token, op.targetId, userId, convId)
                            }
                        }
                    }.onSuccess { deletedCount++ }
                }
                MemorySyncOperationType.UPSERT -> {
                    when (entityType) {
                        MemorySyncEntityType.USER_MEMORY -> {
                            val localEntity = chatCacheDao.getUserMemories(userId).firstOrNull { it.id == op.targetId }
                            val item = localEntity?.toDomain() ?: decodeUserMemoryQueuePayload(op.payloadJson, userId)
                            if (item == null || !PersonalIntelligenceEngine.isMemoryEligibleForCloudUpload(item, userId)) {
                                Result.success(Unit)
                            } else {
                                executeMemoryCloudOpWithRefresh(userId) { token ->
                                    val saved = networkClient.upsertUserMemoryCloud(token, item)
                                    val merged = PersonalIntelligenceEngine.resolveMemoryConflict(item, saved).copy(id = item.id)
                                    chatCacheDao.upsertUserMemory(UserMemoryEntity.fromDomain(merged, isSynced = true))
                                }.onSuccess { uploadedMemories++ }
                            }
                        }
                        MemorySyncEntityType.PROJECT_CONTEXT -> {
                            val localEntity = chatCacheDao.getProjectContexts(userId).firstOrNull { it.id == op.targetId }
                            val proj = localEntity?.toDomain() ?: decodeProjectContextQueuePayload(op.payloadJson, userId)
                            if (proj == null || !PersonalIntelligenceEngine.isProjectEligibleForCloudUpload(proj, userId)) {
                                Result.success(Unit)
                            } else {
                                executeMemoryCloudOpWithRefresh(userId) { token ->
                                    val saved = networkClient.upsertProjectContextCloud(token, proj)
                                    val merged = PersonalIntelligenceEngine.resolveProjectContextConflict(proj, saved).copy(id = proj.id)
                                    chatCacheDao.upsertProjectContext(ProjectContextEntity.fromDomain(merged, isSynced = true))
                                }.onSuccess { uploadedProjects++ }
                            }
                        }
                        MemorySyncEntityType.CONVERSATION_SUMMARY -> {
                            val localEntity = chatCacheDao.getConversationSummaries(userId).firstOrNull { it.id == op.targetId }
                            val sum = localEntity?.toDomain() ?: decodeConversationSummaryQueuePayload(op.payloadJson, userId)
                            if (sum == null || !PersonalIntelligenceEngine.isConversationSummaryEligibleForCloudUpload(sum, userId)) {
                                Result.success(Unit)
                            } else {
                                executeMemoryCloudOpWithRefresh(userId) { token ->
                                    val saved = networkClient.upsertConversationSummaryCloud(token, sum)
                                    val merged = PersonalIntelligenceEngine.resolveConversationSummaryConflict(sum, saved).copy(id = sum.id)
                                    chatCacheDao.upsertConversationSummary(ConversationSummaryEntity.fromDomain(merged, isSynced = true))
                                }.onSuccess { uploadedSummaries++ }
                            }
                        }
                    }
                }
            }

            if (stepResult.isSuccess) {
                chatCacheDao.deletePendingMemorySyncById(op.id, userId)
            } else {
                val err = stepResult.exceptionOrNull()
                chatCacheDao.upsertPendingMemorySync(
                    op.copy(
                        retryCount = op.retryCount + 1,
                        lastError = err?.message
                    )
                )
                if (firstFatalError == null) {
                    firstFatalError = err
                }
                val gw = err as? AiGatewayException
                if (gw != null && (gw.statusCode == 401 || gw.statusCode == 403 || !gw.canRetry)) {
                    break
                }
            }
        }

        val remainingQueue = chatCacheDao.getPendingMemorySyncOperations(userId).size
        if (firstFatalError != null && remainingQueue > 0) {
            return Result.failure(firstFatalError)
        }
        return Result.success(
            MemoryCloudSyncReport(
                userId = userId,
                uploadedMemoriesCount = uploadedMemories,
                uploadedProjectsCount = uploadedProjects,
                uploadedSummariesCount = uploadedSummaries,
                deletedRecordsCount = deletedCount,
                pendingQueueCount = remainingQueue
            )
        )
    }

    override suspend fun syncPersonalIntelligenceWithCloud(userId: String): Result<MemoryCloudSyncReport> =
        memorySyncMutex.withLock {
            verifyMemoryUserOwnership(userId).onFailure { return@withLock Result.failure(it) }
            val session = authRepository.sessionFlow.value
                ?: return@withLock Result.failure(
                    AiGatewayException(
                        errorType = AiErrorType.UNAUTHORIZED,
                        userFriendlyMessage = "Sign in to synchronize Personal Intelligence memory with the cloud.",
                        statusCode = 401,
                        canRetry = false
                    )
                )
            if (session.user.id != userId) {
                return@withLock Result.failure(
                    AiGatewayException(
                        errorType = AiErrorType.UNAUTHORIZED,
                        userFriendlyMessage = "Unauthorized cross-user memory sync blocked.",
                        statusCode = 403,
                        canRetry = false
                    )
                )
            }

            // Step 1: Flush any queued offline mutations (CLEAR_ALL, DELETE tombstones, and queued UPSERTs)
            val flushResult = flushPendingMemorySyncQueue(userId)
            if (flushResult.isFailure) {
                return@withLock flushResult
            }
            val flushReport = flushResult.getOrThrow()

            var uploadedMemories = flushReport.uploadedMemoriesCount
            var downloadedMemories = 0
            var uploadedProjects = flushReport.uploadedProjectsCount
            var downloadedProjects = 0
            var uploadedSummaries = flushReport.uploadedSummariesCount
            var downloadedSummaries = 0
            var conflictsResolved = 0

            // Step 2: Pull remote records from all three PostgREST tables
            val remoteMemories = executeMemoryCloudOpWithRefresh(userId) { token ->
                networkClient.fetchUserMemories(token, userId, limit = 250)
            }.getOrElse { return@withLock Result.failure(it) }

            val remoteProjects = executeMemoryCloudOpWithRefresh(userId) { token ->
                networkClient.fetchProjectContexts(token, userId, limit = 100)
            }.getOrElse { return@withLock Result.failure(it) }

            val remoteSummaries = executeMemoryCloudOpWithRefresh(userId) { token ->
                networkClient.fetchConversationSummaries(token, userId, limit = 100)
            }.getOrElse { return@withLock Result.failure(it) }

            val pendingOps = chatCacheDao.getPendingMemorySyncOperations(userId)
            val tombstonedKeys = pendingOps
                .filter { it.operationType == MemorySyncOperationType.DELETE.name }
                .map { it.naturalKey }
                .toSet()

            // Step 3: Bidirectional merge for `user_memory` (natural key: category + memoryKey)
            val localMemoryEntities = chatCacheDao.getUserMemories(userId)
            val localMemoriesByNatKey = localMemoryEntities.associateBy {
                userMemoryNaturalKey(MemoryCategory.fromDbValue(it.category), it.memoryKey)
            }
            val remoteMemoriesByNatKey = remoteMemories
                .filter { it.userId == userId }
                .associateBy { userMemoryNaturalKey(it.category, it.memoryKey) }

            val allMemoryKeys = (localMemoriesByNatKey.keys + remoteMemoriesByNatKey.keys).distinct()
            for (natKey in allMemoryKeys) {
                if (natKey in tombstonedKeys) continue
                val localEntity = localMemoriesByNatKey[natKey]
                val localItem = localEntity?.toDomain()
                val remoteItem = remoteMemoriesByNatKey[natKey]

                when {
                    localItem != null && remoteItem == null -> {
                        if (!localEntity.isSyncedWithCloud &&
                            PersonalIntelligenceEngine.isMemoryEligibleForCloudUpload(localItem, userId)
                        ) {
                            val up = executeMemoryCloudOpWithRefresh(userId) { token ->
                                networkClient.upsertUserMemoryCloud(token, localItem)
                            }
                            if (up.isSuccess) {
                                val saved = up.getOrThrow().copy(id = localItem.id)
                                chatCacheDao.upsertUserMemory(UserMemoryEntity.fromDomain(saved, isSynced = true))
                                uploadedMemories++
                            }
                        }
                    }
                    localItem == null && remoteItem != null -> {
                        if (!PersonalIntelligenceEngine.containsSensitivePersonalData(remoteItem.content) &&
                            !PersonalIntelligenceEngine.hasOptOutDirective(remoteItem.content)
                        ) {
                            chatCacheDao.upsertUserMemory(UserMemoryEntity.fromDomain(remoteItem, isSynced = true))
                            downloadedMemories++
                        }
                    }
                    localItem != null && remoteItem != null -> {
                        val hadDifference = localItem.content != remoteItem.content ||
                            localItem.updatedAtEpochMs != remoteItem.updatedAtEpochMs ||
                            localItem.sourceType != remoteItem.sourceType
                        if (hadDifference) {
                            conflictsResolved++
                        }
                        val winner = PersonalIntelligenceEngine.resolveMemoryConflict(localItem, remoteItem)
                            .copy(id = localItem.id)
                        val localNeedsCloudPush = (winner.updatedAtEpochMs > remoteItem.updatedAtEpochMs ||
                            winner.content != remoteItem.content ||
                            !localEntity.isSyncedWithCloud) &&
                            PersonalIntelligenceEngine.isMemoryEligibleForCloudUpload(winner, userId)

                        if (localNeedsCloudPush) {
                            val up = executeMemoryCloudOpWithRefresh(userId) { token ->
                                networkClient.upsertUserMemoryCloud(token, winner)
                            }
                            if (up.isSuccess) {
                                chatCacheDao.upsertUserMemory(UserMemoryEntity.fromDomain(winner, isSynced = true))
                                uploadedMemories++
                            } else {
                                chatCacheDao.upsertUserMemory(UserMemoryEntity.fromDomain(winner, isSynced = false))
                            }
                        } else {
                            chatCacheDao.upsertUserMemory(UserMemoryEntity.fromDomain(winner, isSynced = true))
                            if (remoteItem.updatedAtEpochMs > localItem.updatedAtEpochMs) {
                                downloadedMemories++
                            }
                        }
                    }
                }
            }

            // Step 4: Bidirectional merge for `project_context` (natural key: projectName)
            val localProjectEntities = chatCacheDao.getProjectContexts(userId)
            val localProjectsByNatKey = localProjectEntities.associateBy { projectNaturalKey(it.projectName) }
            val remoteProjectsByNatKey = remoteProjects
                .filter { it.userId == userId }
                .associateBy { projectNaturalKey(it.projectName) }

            val allProjectKeys = (localProjectsByNatKey.keys + remoteProjectsByNatKey.keys).distinct()
            for (natKey in allProjectKeys) {
                if (natKey in tombstonedKeys) continue
                val localEntity = localProjectsByNatKey[natKey]
                val localProj = localEntity?.toDomain()
                val remoteProj = remoteProjectsByNatKey[natKey]

                when {
                    localProj != null && remoteProj == null -> {
                        if (!localEntity.isSyncedWithCloud &&
                            PersonalIntelligenceEngine.isProjectEligibleForCloudUpload(localProj, userId)
                        ) {
                            val up = executeMemoryCloudOpWithRefresh(userId) { token ->
                                networkClient.upsertProjectContextCloud(token, localProj)
                            }
                            if (up.isSuccess) {
                                val saved = up.getOrThrow().copy(id = localProj.id)
                                chatCacheDao.upsertProjectContext(ProjectContextEntity.fromDomain(saved, isSynced = true))
                                uploadedProjects++
                            }
                        }
                    }
                    localProj == null && remoteProj != null -> {
                        if (PersonalIntelligenceEngine.isProjectEligibleForCloudUpload(remoteProj, userId)) {
                            chatCacheDao.upsertProjectContext(ProjectContextEntity.fromDomain(remoteProj, isSynced = true))
                            downloadedProjects++
                        }
                    }
                    localProj != null && remoteProj != null -> {
                        val hadDifference = localProj.summary != remoteProj.summary ||
                            localProj.techStack != remoteProj.techStack ||
                            localProj.updatedAtEpochMs != remoteProj.updatedAtEpochMs
                        if (hadDifference) {
                            conflictsResolved++
                        }
                        val winner = PersonalIntelligenceEngine.resolveProjectContextConflict(localProj, remoteProj)
                            .copy(id = localProj.id)
                        val localNeedsCloudPush = (winner.updatedAtEpochMs > remoteProj.updatedAtEpochMs ||
                            winner.summary != remoteProj.summary ||
                            winner.techStack != remoteProj.techStack ||
                            !localEntity.isSyncedWithCloud) &&
                            PersonalIntelligenceEngine.isProjectEligibleForCloudUpload(winner, userId)

                        if (localNeedsCloudPush) {
                            val up = executeMemoryCloudOpWithRefresh(userId) { token ->
                                networkClient.upsertProjectContextCloud(token, winner)
                            }
                            if (up.isSuccess) {
                                chatCacheDao.upsertProjectContext(ProjectContextEntity.fromDomain(winner, isSynced = true))
                                uploadedProjects++
                            } else {
                                chatCacheDao.upsertProjectContext(ProjectContextEntity.fromDomain(winner, isSynced = false))
                            }
                        } else {
                            chatCacheDao.upsertProjectContext(ProjectContextEntity.fromDomain(winner, isSynced = true))
                            if (remoteProj.updatedAtEpochMs > localProj.updatedAtEpochMs) {
                                downloadedProjects++
                            }
                        }
                    }
                }
            }

            // Step 5: Bidirectional merge for `conversation_summaries` (natural key: conversationId)
            val localSummaryEntities = chatCacheDao.getConversationSummaries(userId)
            val localSummariesByNatKey = localSummaryEntities.associateBy {
                conversationSummaryNaturalKey(it.conversationId)
            }
            val remoteSummariesByNatKey = remoteSummaries
                .filter { it.userId == userId }
                .associateBy { conversationSummaryNaturalKey(it.conversationId) }

            val allSummaryKeys = (localSummariesByNatKey.keys + remoteSummariesByNatKey.keys).distinct()
            for (natKey in allSummaryKeys) {
                if (natKey in tombstonedKeys) continue
                val localEntity = localSummariesByNatKey[natKey]
                val localSum = localEntity?.toDomain()
                val remoteSum = remoteSummariesByNatKey[natKey]

                when {
                    localSum != null && remoteSum == null -> {
                        if (!localEntity.isSyncedWithCloud &&
                            PersonalIntelligenceEngine.isConversationSummaryEligibleForCloudUpload(localSum, userId)
                        ) {
                            val up = executeMemoryCloudOpWithRefresh(userId) { token ->
                                networkClient.upsertConversationSummaryCloud(token, localSum)
                            }
                            if (up.isSuccess) {
                                val saved = up.getOrThrow().copy(id = localSum.id)
                                chatCacheDao.upsertConversationSummary(ConversationSummaryEntity.fromDomain(saved, isSynced = true))
                                uploadedSummaries++
                            }
                        }
                    }
                    localSum == null && remoteSum != null -> {
                        if (PersonalIntelligenceEngine.isConversationSummaryEligibleForCloudUpload(remoteSum, userId)) {
                            chatCacheDao.upsertConversationSummary(ConversationSummaryEntity.fromDomain(remoteSum, isSynced = true))
                            downloadedSummaries++
                        }
                    }
                    localSum != null && remoteSum != null -> {
                        val hadDifference = localSum.summaryText != remoteSum.summaryText ||
                            localSum.updatedAtEpochMs != remoteSum.updatedAtEpochMs
                        if (hadDifference) {
                            conflictsResolved++
                        }
                        val winner = PersonalIntelligenceEngine.resolveConversationSummaryConflict(localSum, remoteSum)
                            .copy(id = localSum.id)
                        val localNeedsCloudPush = (winner.updatedAtEpochMs > remoteSum.updatedAtEpochMs ||
                            winner.summaryText != remoteSum.summaryText ||
                            !localEntity.isSyncedWithCloud) &&
                            PersonalIntelligenceEngine.isConversationSummaryEligibleForCloudUpload(winner, userId)

                        if (localNeedsCloudPush) {
                            val up = executeMemoryCloudOpWithRefresh(userId) { token ->
                                networkClient.upsertConversationSummaryCloud(token, winner)
                            }
                            if (up.isSuccess) {
                                chatCacheDao.upsertConversationSummary(ConversationSummaryEntity.fromDomain(winner, isSynced = true))
                                uploadedSummaries++
                            } else {
                                chatCacheDao.upsertConversationSummary(ConversationSummaryEntity.fromDomain(winner, isSynced = false))
                            }
                        } else {
                            chatCacheDao.upsertConversationSummary(ConversationSummaryEntity.fromDomain(winner, isSynced = true))
                            if (remoteSum.updatedAtEpochMs > localSum.updatedAtEpochMs) {
                                downloadedSummaries++
                            }
                        }
                    }
                }
            }

            val remainingPending = chatCacheDao.getPendingMemorySyncOperations(userId).size
            Result.success(
                MemoryCloudSyncReport(
                    userId = userId,
                    uploadedMemoriesCount = uploadedMemories,
                    downloadedMemoriesCount = downloadedMemories,
                    uploadedProjectsCount = uploadedProjects,
                    downloadedProjectsCount = downloadedProjects,
                    uploadedSummariesCount = uploadedSummaries,
                    downloadedSummariesCount = downloadedSummaries,
                    deletedRecordsCount = flushReport.deletedRecordsCount,
                    conflictsResolvedCount = conflictsResolved,
                    pendingQueueCount = remainingPending,
                    syncedAtEpochMs = System.currentTimeMillis()
                )
            )
        }

    private suspend fun enqueueMemorySyncOperation(
        userId: String,
        entityType: MemorySyncEntityType,
        operationType: MemorySyncOperationType,
        naturalKey: String,
        targetId: String,
        payloadJson: String,
        updatedAtEpochMs: Long,
        lastError: String?
    ) {
        val existing = chatCacheDao.getPendingMemorySyncByNaturalKey(
            userId = userId,
            entityType = entityType.name,
            naturalKey = naturalKey
        )
        val opEntity = PendingMemorySyncEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            userId = userId,
            entityType = entityType.name,
            operationType = operationType.name,
            naturalKey = naturalKey,
            targetId = targetId,
            payloadJson = payloadJson,
            updatedAtEpochMs = updatedAtEpochMs,
            retryCount = existing?.retryCount ?: 0,
            lastError = lastError,
            createdAtEpochMs = existing?.createdAtEpochMs ?: System.currentTimeMillis()
        )
        chatCacheDao.upsertPendingMemorySync(opEntity)
    }

    private fun encodeUserMemoryQueuePayload(item: UserMemoryItem): String {
        val parts = listOf(
            item.id,
            item.category.name,
            item.memoryKey,
            item.sourceType.name,
            item.confidence.toString(),
            item.conversationId.orEmpty(),
            item.isUserApproved.toString(),
            item.createdAtEpochMs.toString(),
            item.updatedAtEpochMs.toString(),
            item.content
        )
        return parts.joinToString("\u001F")
    }

    private fun decodeUserMemoryQueuePayload(payload: String, userId: String): UserMemoryItem? {
        val parts = payload.split("\u001F", limit = 10)
        if (parts.size < 10) return null
        return UserMemoryItem(
            id = parts[0],
            userId = userId,
            category = MemoryCategory.fromDbValue(parts[1]),
            memoryKey = parts[2],
            sourceType = MemorySourceType.fromDbValue(parts[3]),
            confidence = parts[4].toDoubleOrNull() ?: 1.0,
            conversationId = parts[5].takeIf { it.isNotBlank() },
            isUserApproved = parts[6].toBooleanStrictOrNull() ?: true,
            createdAtEpochMs = parts[7].toLongOrNull() ?: System.currentTimeMillis(),
            updatedAtEpochMs = parts[8].toLongOrNull() ?: System.currentTimeMillis(),
            content = parts[9]
        )
    }

    private fun encodeProjectContextQueuePayload(project: ProjectContextMemory): String {
        val parts = listOf(
            project.id,
            project.projectName,
            project.summary,
            project.techStack,
            project.recentDecisions,
            project.openTasks,
            project.lastConversationId.orEmpty(),
            project.updatedAtEpochMs.toString()
        )
        return parts.joinToString("\u001F")
    }

    private fun decodeProjectContextQueuePayload(payload: String, userId: String): ProjectContextMemory? {
        val parts = payload.split("\u001F", limit = 8)
        if (parts.size < 8) return null
        return ProjectContextMemory(
            id = parts[0],
            userId = userId,
            projectName = parts[1],
            summary = parts[2],
            techStack = parts[3],
            recentDecisions = parts[4],
            openTasks = parts[5],
            lastConversationId = parts[6].takeIf { it.isNotBlank() },
            updatedAtEpochMs = parts[7].toLongOrNull() ?: System.currentTimeMillis()
        )
    }

    private fun encodeConversationSummaryQueuePayload(summary: ConversationSummaryMemory): String {
        val parts = listOf(
            summary.id,
            summary.conversationId,
            summary.summaryText,
            summary.keyTopics,
            summary.updatedAtEpochMs.toString()
        )
        return parts.joinToString("\u001F")
    }

    private fun decodeConversationSummaryQueuePayload(payload: String, userId: String): ConversationSummaryMemory? {
        val parts = payload.split("\u001F", limit = 5)
        if (parts.size < 5) return null
        return ConversationSummaryMemory(
            id = parts[0],
            userId = userId,
            conversationId = parts[1],
            summaryText = parts[2],
            keyTopics = parts[3],
            updatedAtEpochMs = parts[4].toLongOrNull() ?: System.currentTimeMillis()
        )
    }

    private fun buildHighlightedSnippet(content: String, query: String): String {
        val clean = content.replace("\n", " ").trim()
        val index = clean.indexOf(query, ignoreCase = true)
        if (index < 0) return clean.take(140)
        val start = (index - 40).coerceAtLeast(0)
        val end = (index + query.length + 80).coerceAtMost(clean.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < clean.length) "…" else ""
        return prefix + clean.substring(start, end) + suffix
    }
}
