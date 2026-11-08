package com.example.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.domain.models.ChatMessage
import com.example.domain.models.Conversation
import com.example.domain.models.ConversationSummaryMemory
import com.example.domain.models.MemoryCategory
import com.example.domain.models.MemorySourceType
import com.example.domain.models.MemorySyncEntityType
import com.example.domain.models.MemorySyncOperationType
import com.example.domain.models.MessageAttachment
import com.example.domain.models.MessageRole
import com.example.domain.models.MessageStatus
import com.example.domain.models.PendingMemorySyncOperation
import com.example.domain.models.ProjectContextMemory
import com.example.domain.models.UserMemoryItem
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * High-speed binary cache for generated and uploaded images so SQLite `messages.attachmentsJson`
 * never stores multi-megabyte Base64 payloads that could trigger Android `CursorWindow` overflow.
 */
object GeneratedImageBinaryCache {
    private val inMemoryById = ConcurrentHashMap<String, ByteArray>()
    private val inMemoryByUri = ConcurrentHashMap<String, ByteArray>()
    private val cacheRootDir: File by lazy {
        File(System.getProperty("java.io.tmpdir"), "smarter_ai_image_cache").apply { mkdirs() }
    }

    fun putGeneratedImage(
        attachmentId: String,
        imageBytes: ByteArray,
        mimeType: String = "image/png",
        remoteUrl: String = ""
    ): String {
        val ext = when {
            mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
            mimeType.contains("webp") -> "webp"
            mimeType.contains("gif") -> "gif"
            else -> "png"
        }
        val safeId = attachmentId.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "img_${System.currentTimeMillis()}" }
        val file = File(cacheRootDir, "$safeId.$ext")
        runCatching { file.writeBytes(imageBytes) }
        val fileUri = "file://${file.absolutePath}"
        if (attachmentId.isNotBlank()) {
            inMemoryById[attachmentId] = imageBytes
        }
        inMemoryByUri[fileUri] = imageBytes
        inMemoryByUri[file.absolutePath] = imageBytes
        if (remoteUrl.isNotBlank()) {
            inMemoryByUri[remoteUrl] = imageBytes
        }
        return fileUri
    }

    fun getBytes(
        attachmentId: String?,
        uriString: String?,
        base64DataUrl: String?
    ): ByteArray? {
        if (!attachmentId.isNullOrBlank()) {
            inMemoryById[attachmentId]?.let { return it }
        }
        if (!uriString.isNullOrBlank()) {
            inMemoryByUri[uriString]?.let { return it }
            val cleanPath = uriString.removePrefix("file://")
            inMemoryByUri[cleanPath]?.let { return it }
            val file = File(cleanPath)
            if (file.exists() && file.isFile) {
                runCatching { file.readBytes() }.getOrNull()?.let { bytes ->
                    if (!attachmentId.isNullOrBlank()) inMemoryById[attachmentId] = bytes
                    inMemoryByUri[uriString] = bytes
                    return bytes
                }
            }
        }
        if (!base64DataUrl.isNullOrBlank()) {
            val raw = if (base64DataUrl.contains("base64,")) {
                base64DataUrl.substringAfter("base64,").trim()
            } else {
                base64DataUrl.trim()
            }
            if (raw.isNotEmpty()) {
                val decoded = runCatching { Base64.getDecoder().decode(raw) }.getOrNull()
                    ?: runCatching { Base64.getMimeDecoder().decode(raw) }.getOrNull()
                if (decoded != null && decoded.isNotEmpty()) {
                    if (!attachmentId.isNullOrBlank()) inMemoryById[attachmentId] = decoded
                    return decoded
                }
            }
        }
        return null
    }
}

@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["userId", "updatedAtEpochMs"]),
        Index(value = ["userId", "title"])
    ]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val selectedModelId: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val archivedAtEpochMs: Long? = null,
    val isSyncedWithCloud: Boolean = false
) {
    fun toDomain(): Conversation = Conversation(
        id = id,
        userId = userId,
        title = title,
        selectedModelId = selectedModelId,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        archivedAtEpochMs = archivedAtEpochMs
    )

    companion object {
        fun fromDomain(domain: Conversation, isSynced: Boolean = false): ConversationEntity =
            ConversationEntity(
                id = domain.id,
                userId = domain.userId,
                title = domain.title,
                selectedModelId = domain.selectedModelId,
                createdAtEpochMs = domain.createdAtEpochMs,
                updatedAtEpochMs = domain.updatedAtEpochMs,
                archivedAtEpochMs = domain.archivedAtEpochMs,
                isSyncedWithCloud = isSynced
            )
    }
}

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId", "createdAtEpochMs"]),
        Index(value = ["userId", "conversationId"]),
        Index(value = ["clientRequestId"], unique = true)
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val userId: String,
    val role: String,
    val content: String,
    val modelId: String,
    val requestedModelId: String? = null,
    val fallbackUsed: Boolean = false,
    val status: String,
    val errorMessage: String? = null,
    val errorCode: String? = null,
    val attachmentsJson: String = "[]",
    val clientRequestId: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val isSyncedWithCloud: Boolean = false
) {
    fun toDomain(): ChatMessage = ChatMessage(
        id = id,
        conversationId = conversationId,
        userId = userId,
        role = MessageRole.fromApiValue(role),
        content = content,
        modelId = modelId,
        requestedModelId = requestedModelId,
        fallbackUsed = fallbackUsed,
        status = MessageStatus.fromDbValue(status),
        errorMessage = errorMessage,
        errorCode = errorCode,
        attachments = deserializeAttachments(attachmentsJson),
        clientRequestId = clientRequestId,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs
    )

    companion object {
        fun fromDomain(domain: ChatMessage, isSynced: Boolean = false): MessageEntity =
            MessageEntity(
                id = domain.id,
                conversationId = domain.conversationId,
                userId = domain.userId,
                role = domain.role.apiValue,
                content = domain.content,
                modelId = domain.modelId,
                requestedModelId = domain.requestedModelId,
                fallbackUsed = domain.fallbackUsed,
                status = domain.status.dbValue,
                errorMessage = domain.errorMessage,
                errorCode = domain.errorCode,
                attachmentsJson = serializeAttachments(domain.attachments),
                clientRequestId = domain.clientRequestId,
                createdAtEpochMs = domain.createdAtEpochMs,
                updatedAtEpochMs = domain.updatedAtEpochMs,
                isSyncedWithCloud = isSynced
            )

        fun serializeAttachments(attachments: List<MessageAttachment>): String {
            if (attachments.isEmpty()) return "[]"
            val arr = JSONArray()
            attachments.forEach { att ->
                var effectiveUri = att.uriString
                if (!att.base64DataUrl.isNullOrBlank()) {
                    val bytes = GeneratedImageBinaryCache.getBytes(att.id, att.uriString, att.base64DataUrl)
                    if (bytes != null && bytes.isNotEmpty()) {
                        val cachedFileUri = GeneratedImageBinaryCache.putGeneratedImage(
                            attachmentId = att.id,
                            imageBytes = bytes,
                            mimeType = att.mimeType,
                            remoteUrl = att.uriString
                        )
                        if (effectiveUri.isBlank()) {
                            effectiveUri = cachedFileUri
                        }
                    }
                }
                arr.put(
                    JSONObject().apply {
                        put("id", att.id)
                        put("uriString", effectiveUri)
                        put("mimeType", att.mimeType)
                        put("fileName", att.fileName)
                        // Never store raw base64 payloads inside SQLite attachmentsJson to prevent CursorWindow overflow
                    }
                )
            }
            return arr.toString()
        }

        fun deserializeAttachments(json: String): List<MessageAttachment> {
            if (json.isBlank() || json == "[]") return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                val list = mutableListOf<MessageAttachment>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    val uri = obj.optString("uriString")
                    val mime = obj.optString("mimeType", "image/jpeg")
                    val fileName = obj.optString("fileName", "image.jpg")
                    val inlineB64 = obj.optString("base64DataUrl").takeIf { it.isNotBlank() }
                    list.add(
                        MessageAttachment(
                            id = id,
                            uriString = uri,
                            mimeType = mime,
                            fileName = fileName,
                            base64DataUrl = inlineB64
                        )
                    )
                }
                list
            }.getOrDefault(emptyList())
        }
    }
}

@Entity(
    tableName = "user_memory",
    indices = [
        Index(value = ["userId", "updatedAtEpochMs"]),
        Index(value = ["userId", "category", "memoryKey"], unique = true)
    ]
)
data class UserMemoryEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val category: String,
    val memoryKey: String,
    val content: String,
    val sourceType: String,
    val confidence: Double,
    val conversationId: String? = null,
    val isUserApproved: Boolean = true,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val isSyncedWithCloud: Boolean = false
) {
    fun toDomain(): UserMemoryItem = UserMemoryItem(
        id = id,
        userId = userId,
        category = runCatching { MemoryCategory.valueOf(category) }.getOrDefault(MemoryCategory.USER_PREFERENCE),
        memoryKey = memoryKey,
        content = content,
        sourceType = runCatching { MemorySourceType.valueOf(sourceType) }.getOrDefault(MemorySourceType.EXPLICIT_USER),
        confidence = confidence,
        conversationId = conversationId,
        isUserApproved = isUserApproved,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs
    )

    companion object {
        fun fromDomain(item: UserMemoryItem, isSynced: Boolean = false): UserMemoryEntity = UserMemoryEntity(
            id = item.id,
            userId = item.userId,
            category = item.category.name,
            memoryKey = item.memoryKey,
            content = item.content,
            sourceType = item.sourceType.name,
            confidence = item.confidence,
            conversationId = item.conversationId,
            isUserApproved = item.isUserApproved,
            createdAtEpochMs = item.createdAtEpochMs,
            updatedAtEpochMs = item.updatedAtEpochMs,
            isSyncedWithCloud = isSynced
        )
    }
}

@Entity(
    tableName = "project_context",
    indices = [
        Index(value = ["userId", "updatedAtEpochMs"]),
        Index(value = ["userId", "projectName"], unique = true)
    ]
)
data class ProjectContextEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val projectName: String,
    val summary: String,
    val techStack: String,
    val recentDecisions: String,
    val openTasks: String,
    val lastConversationId: String? = null,
    val updatedAtEpochMs: Long,
    val isSyncedWithCloud: Boolean = false
) {
    fun toDomain(): ProjectContextMemory = ProjectContextMemory(
        id = id,
        userId = userId,
        projectName = projectName,
        summary = summary,
        techStack = techStack,
        recentDecisions = recentDecisions,
        openTasks = openTasks,
        lastConversationId = lastConversationId,
        updatedAtEpochMs = updatedAtEpochMs
    )

    companion object {
        fun fromDomain(domain: ProjectContextMemory, isSynced: Boolean = false): ProjectContextEntity = ProjectContextEntity(
            id = domain.id,
            userId = domain.userId,
            projectName = domain.projectName,
            summary = domain.summary,
            techStack = domain.techStack,
            recentDecisions = domain.recentDecisions,
            openTasks = domain.openTasks,
            lastConversationId = domain.lastConversationId,
            updatedAtEpochMs = domain.updatedAtEpochMs,
            isSyncedWithCloud = isSynced
        )
    }
}

@Entity(
    tableName = "conversation_summaries",
    indices = [
        Index(value = ["userId", "updatedAtEpochMs"]),
        Index(value = ["userId", "conversationId"], unique = true)
    ]
)
data class ConversationSummaryEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val conversationId: String,
    val summaryText: String,
    val keyTopics: String,
    val updatedAtEpochMs: Long,
    val isSyncedWithCloud: Boolean = false
) {
    fun toDomain(): ConversationSummaryMemory = ConversationSummaryMemory(
        id = id,
        userId = userId,
        conversationId = conversationId,
        summaryText = summaryText,
        keyTopics = keyTopics,
        updatedAtEpochMs = updatedAtEpochMs
    )

    companion object {
        fun fromDomain(domain: ConversationSummaryMemory, isSynced: Boolean = false): ConversationSummaryEntity =
            ConversationSummaryEntity(
                id = domain.id,
                userId = domain.userId,
                conversationId = domain.conversationId,
                summaryText = domain.summaryText,
                keyTopics = domain.keyTopics,
                updatedAtEpochMs = domain.updatedAtEpochMs,
                isSyncedWithCloud = isSynced
            )
    }
}

@Entity(
    tableName = "pending_memory_sync_queue",
    indices = [
        Index(value = ["userId", "createdAtEpochMs"]),
        Index(value = ["userId", "entityType", "naturalKey"], unique = true)
    ]
)
data class PendingMemorySyncEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val entityType: String,
    val operationType: String,
    val naturalKey: String,
    val targetId: String,
    val payloadJson: String = "",
    val updatedAtEpochMs: Long,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val createdAtEpochMs: Long
) {
    fun toDomain(): PendingMemorySyncOperation = PendingMemorySyncOperation(
        id = id,
        userId = userId,
        entityType = MemorySyncEntityType.fromDbValue(entityType),
        operationType = MemorySyncOperationType.fromDbValue(operationType),
        naturalKey = naturalKey,
        targetId = targetId,
        payloadJson = payloadJson,
        updatedAtEpochMs = updatedAtEpochMs,
        retryCount = retryCount,
        lastError = lastError,
        createdAtEpochMs = createdAtEpochMs
    )
}

@Dao
interface ChatCacheDao {
    // ========================================================================
    // Conversations (Strictly Scoped by userId)
    // ========================================================================

    @Query(
        """
        SELECT * FROM conversations
        WHERE userId = :userId AND archivedAtEpochMs IS NULL
        ORDER BY updatedAtEpochMs DESC
        """
    )
    fun observeConversationsForUser(userId: String): Flow<List<ConversationEntity>>

    @Query(
        """
        SELECT * FROM conversations
        WHERE userId = :userId AND archivedAtEpochMs IS NULL
        ORDER BY updatedAtEpochMs DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getConversationsPaged(userId: String, limit: Int, offset: Int): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :conversationId AND userId = :userId LIMIT 1")
    suspend fun getConversation(conversationId: String, userId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :conversationId AND userId = :userId LIMIT 1")
    suspend fun getConversationByIdForUser(conversationId: String, userId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversations(conversations: List<ConversationEntity>)

    @Query("UPDATE conversations SET title = :title, updatedAtEpochMs = :updatedAt WHERE id = :conversationId AND userId = :userId")
    suspend fun updateConversationTitle(conversationId: String, userId: String, title: String, updatedAt: Long): Int

    @Query("UPDATE conversations SET selectedModelId = :modelId, updatedAtEpochMs = :updatedAt WHERE id = :conversationId AND userId = :userId")
    suspend fun updateConversationModel(conversationId: String, userId: String, modelId: String, updatedAt: Long): Int

    @Query("UPDATE conversations SET updatedAtEpochMs = :updatedAt WHERE id = :conversationId AND userId = :userId")
    suspend fun touchConversationTimestamp(conversationId: String, userId: String, updatedAt: Long): Int

    @Query("DELETE FROM conversations WHERE id = :conversationId AND userId = :userId")
    suspend fun deleteConversation(conversationId: String, userId: String): Int

    @Query("DELETE FROM conversations WHERE id = :conversationId AND userId = :userId")
    suspend fun deleteConversationForUser(conversationId: String, userId: String): Int

    @Query("DELETE FROM conversations WHERE userId = :userId")
    suspend fun deleteAllConversationsForUser(userId: String)

    // ========================================================================
    // Messages (Strictly Scoped by userId and conversationId)
    // ========================================================================

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId AND userId = :userId
        ORDER BY createdAtEpochMs ASC
        """
    )
    fun observeMessagesForConversation(conversationId: String, userId: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId AND userId = :userId
        ORDER BY createdAtEpochMs ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getMessagesPaged(
        conversationId: String,
        userId: String,
        limit: Int,
        offset: Int
    ): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :messageId AND userId = :userId LIMIT 1")
    suspend fun getMessageByIdForUser(messageId: String, userId: String): MessageEntity?

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId
          AND clientRequestId = :clientRequestId
          AND userId = :userId
        LIMIT 1
        """
    )
    suspend fun findByClientRequestId(
        conversationId: String,
        clientRequestId: String,
        userId: String
    ): MessageEntity?

    @Query("SELECT * FROM messages WHERE clientRequestId = :clientRequestId AND userId = :userId LIMIT 1")
    suspend fun getMessageByClientRequestId(clientRequestId: String, userId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND userId = :userId AND createdAtEpochMs > :afterEpochMs")
    suspend fun deleteMessagesAfter(conversationId: String, userId: String, afterEpochMs: Long)

    @Query("DELETE FROM messages WHERE id = :messageId AND userId = :userId")
    suspend fun deleteMessage(messageId: String, userId: String): Int

    @Query("DELETE FROM messages WHERE id = :messageId AND userId = :userId")
    suspend fun deleteMessageByIdForUser(messageId: String, userId: String): Int

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND userId = :userId")
    suspend fun deleteMessagesForConversation(conversationId: String, userId: String)

    @Query("DELETE FROM messages WHERE userId = :userId")
    suspend fun deleteAllMessagesForUser(userId: String)

    @Query(
        """
        UPDATE messages
        SET status = 'interrupted',
            errorMessage = 'Response generation was interrupted.'
        WHERE userId = :userId AND status = 'streaming'
        """
    )
    suspend fun markInFlightMessagesInterrupted(userId: String)

    // ========================================================================
    // Search Across Titles & Message Content (Case-Insensitive, User-Scoped)
    // ========================================================================

    @Query(
        """
        SELECT DISTINCT c.* FROM conversations c
        LEFT JOIN messages m ON c.id = m.conversationId AND c.userId = m.userId
        WHERE c.userId = :userId
          AND c.archivedAtEpochMs IS NULL
          AND (
            :query = ''
            OR LOWER(c.title) LIKE '%' || LOWER(:query) || '%'
            OR LOWER(m.content) LIKE '%' || LOWER(:query) || '%'
          )
        ORDER BY c.updatedAtEpochMs DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun searchLocalConversations(
        userId: String,
        query: String,
        limit: Int,
        offset: Int
    ): List<ConversationEntity>

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId
          AND userId = :userId
          AND LOWER(content) LIKE '%' || LOWER(:query) || '%'
        ORDER BY createdAtEpochMs DESC
        LIMIT 1
        """
    )
    suspend fun findMatchingMessageForSnippet(
        conversationId: String,
        userId: String,
        query: String
    ): MessageEntity?

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId AND userId = :userId
        ORDER BY createdAtEpochMs DESC
        LIMIT 1
        """
    )
    suspend fun getLatestMessageForConversation(
        conversationId: String,
        userId: String
    ): MessageEntity?

    // ========================================================================
    // Personal Intelligence Engine — User Memory & Project Context (User-Scoped)
    // ========================================================================

    @Query(
        """
        SELECT * FROM user_memory
        WHERE userId = :userId
        ORDER BY updatedAtEpochMs DESC
        """
    )
    fun observeUserMemories(userId: String): Flow<List<UserMemoryEntity>>

    @Query(
        """
        SELECT * FROM user_memory
        WHERE userId = :userId
        ORDER BY updatedAtEpochMs DESC
        LIMIT :limit
        """
    )
    suspend fun getUserMemories(userId: String, limit: Int = 200): List<UserMemoryEntity>

    @Query(
        """
        SELECT * FROM user_memory
        WHERE userId = :userId AND isSyncedWithCloud = 0
        ORDER BY updatedAtEpochMs ASC
        """
    )
    suspend fun getUnsyncedUserMemories(userId: String): List<UserMemoryEntity>

    @Query(
        """
        SELECT * FROM user_memory
        WHERE userId = :userId AND category = :category AND LOWER(memoryKey) = LOWER(:memoryKey)
        LIMIT 1
        """
    )
    suspend fun getUserMemoryByKey(userId: String, category: String, memoryKey: String): UserMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUserMemory(memory: UserMemoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUserMemories(memories: List<UserMemoryEntity>)

    @Query("DELETE FROM user_memory WHERE id = :memoryId AND userId = :userId")
    suspend fun deleteUserMemoryById(memoryId: String, userId: String): Int

    @Query("DELETE FROM user_memory WHERE userId = :userId")
    suspend fun deleteAllMemoriesForUser(userId: String)

    @Query(
        """
        SELECT * FROM project_context
        WHERE userId = :userId
        ORDER BY updatedAtEpochMs DESC
        """
    )
    fun observeProjectContexts(userId: String): Flow<List<ProjectContextEntity>>

    @Query(
        """
        SELECT * FROM project_context
        WHERE userId = :userId
        ORDER BY updatedAtEpochMs DESC
        LIMIT :limit
        """
    )
    suspend fun getProjectContexts(userId: String, limit: Int = 100): List<ProjectContextEntity>

    @Query(
        """
        SELECT * FROM project_context
        WHERE userId = :userId AND isSyncedWithCloud = 0
        ORDER BY updatedAtEpochMs ASC
        """
    )
    suspend fun getUnsyncedProjectContexts(userId: String): List<ProjectContextEntity>

    @Query(
        """
        SELECT * FROM project_context
        WHERE userId = :userId AND LOWER(projectName) = LOWER(:projectName)
        LIMIT 1
        """
    )
    suspend fun getProjectContextByName(userId: String, projectName: String): ProjectContextEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProjectContext(project: ProjectContextEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProjectContexts(projects: List<ProjectContextEntity>)

    @Query("DELETE FROM project_context WHERE id = :projectId AND userId = :userId")
    suspend fun deleteProjectContextById(projectId: String, userId: String): Int

    @Query("DELETE FROM project_context WHERE userId = :userId")
    suspend fun deleteAllProjectContextsForUser(userId: String)

    @Query(
        """
        SELECT * FROM conversation_summaries
        WHERE userId = :userId
        ORDER BY updatedAtEpochMs DESC
        """
    )
    fun observeConversationSummaries(userId: String): Flow<List<ConversationSummaryEntity>>

    @Query(
        """
        SELECT * FROM conversation_summaries
        WHERE userId = :userId
        ORDER BY updatedAtEpochMs DESC
        LIMIT :limit
        """
    )
    suspend fun getConversationSummaries(userId: String, limit: Int = 100): List<ConversationSummaryEntity>

    @Query(
        """
        SELECT * FROM conversation_summaries
        WHERE userId = :userId AND isSyncedWithCloud = 0
        ORDER BY updatedAtEpochMs ASC
        """
    )
    suspend fun getUnsyncedConversationSummaries(userId: String): List<ConversationSummaryEntity>

    @Query(
        """
        SELECT * FROM conversation_summaries
        WHERE userId = :userId AND conversationId = :conversationId
        LIMIT 1
        """
    )
    suspend fun getConversationSummaryByConversationId(userId: String, conversationId: String): ConversationSummaryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversationSummary(summary: ConversationSummaryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversationSummaries(summaries: List<ConversationSummaryEntity>)

    @Query("DELETE FROM conversation_summaries WHERE id = :summaryId AND userId = :userId")
    suspend fun deleteConversationSummaryById(summaryId: String, userId: String): Int

    @Query("DELETE FROM conversation_summaries WHERE userId = :userId")
    suspend fun deleteAllConversationSummariesForUser(userId: String)

    // ========================================================================
    // Offline-First Pending Memory Cloud Sync Queue (Strictly Scoped by userId)
    // ========================================================================

    @Query(
        """
        SELECT * FROM pending_memory_sync_queue
        WHERE userId = :userId
        ORDER BY createdAtEpochMs ASC
        """
    )
    suspend fun getPendingMemorySyncOperations(userId: String): List<PendingMemorySyncEntity>

    @Query(
        """
        SELECT * FROM pending_memory_sync_queue
        WHERE userId = :userId AND entityType = :entityType AND naturalKey = :naturalKey
        LIMIT 1
        """
    )
    suspend fun getPendingMemorySyncByNaturalKey(
        userId: String,
        entityType: String,
        naturalKey: String
    ): PendingMemorySyncEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingMemorySync(operation: PendingMemorySyncEntity)

    @Query("DELETE FROM pending_memory_sync_queue WHERE id = :id AND userId = :userId")
    suspend fun deletePendingMemorySyncById(id: String, userId: String): Int

    @Query("DELETE FROM pending_memory_sync_queue WHERE userId = :userId AND entityType = :entityType AND naturalKey = :naturalKey")
    suspend fun deletePendingMemorySyncByNaturalKey(userId: String, entityType: String, naturalKey: String): Int

    @Query("DELETE FROM pending_memory_sync_queue WHERE userId = :userId")
    suspend fun deleteAllPendingMemorySyncForUser(userId: String)
}

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        UserMemoryEntity::class,
        ProjectContextEntity::class,
        ConversationSummaryEntity::class,
        PendingMemorySyncEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class NexusAiLocalDatabase : RoomDatabase() {
    abstract fun chatCacheDao(): ChatCacheDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `user_memory` ADD COLUMN `isSyncedWithCloud` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `project_context` ADD COLUMN `isSyncedWithCloud` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `conversation_summaries` ADD COLUMN `isSyncedWithCloud` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_memory_sync_queue` (
                        `id` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `operationType` TEXT NOT NULL,
                        `naturalKey` TEXT NOT NULL,
                        `targetId` TEXT NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        `retryCount` INTEGER NOT NULL,
                        `lastError` TEXT,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_memory_sync_queue_userId_createdAtEpochMs` ON `pending_memory_sync_queue` (`userId`, `createdAtEpochMs`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_memory_sync_queue_userId_entityType_naturalKey` ON `pending_memory_sync_queue` (`userId`, `entityType`, `naturalKey`)")
            }
        }
    }
}
