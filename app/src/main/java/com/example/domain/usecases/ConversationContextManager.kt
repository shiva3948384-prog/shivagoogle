package com.example.domain.usecases

import com.example.domain.models.AiModelCatalog
import com.example.domain.models.ChatMessage
import com.example.domain.models.MessageRole
import com.example.domain.models.MessageStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent compacted state for a single conversation (PART 4 — Long Conversation Intelligence).
 */
data class ConversationCompactedSummary(
    val conversationId: String,
    val summarizedMessageIds: Set<String>,
    val lastSummarizedTimestampMs: Long,
    val overviewSummary: String,
    val preservedUserInstructions: List<String>,
    val architecturalDecisions: List<String>,
    val unfinishedTasks: List<String>,
    val attachmentReferences: List<String>,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    fun toSystemSummaryBlock(): String = buildString {
        appendLine("[PERSISTENT CONVERSATION SUMMARY — EARLIER TURNS]")
        if (preservedUserInstructions.isNotEmpty()) {
            appendLine("• User Instructions & Preferences:")
            preservedUserInstructions.takeLast(8).forEach { appendLine("  - $it") }
        }
        if (architecturalDecisions.isNotEmpty()) {
            appendLine("• Project Architecture & Technical Decisions:")
            architecturalDecisions.takeLast(8).forEach { appendLine("  - $it") }
        }
        if (unfinishedTasks.isNotEmpty()) {
            appendLine("• Unfinished Tasks & Open Items:")
            unfinishedTasks.takeLast(6).forEach { appendLine("  - $it") }
        }
        if (attachmentReferences.isNotEmpty()) {
            appendLine("• Earlier Attachments & Generated Media:")
            attachmentReferences.takeLast(8).forEach { appendLine("  - $it") }
        }
        if (overviewSummary.isNotBlank()) {
            appendLine("• Earlier Discussion Summary:")
            appendLine(overviewSummary.trim())
        }
    }.trim()
}

data class OptimizedContextWindow(
    val selectedMessages: List<ChatMessage>,
    val compactedSummarySystemPrompt: String? = null,
    val wasCompacted: Boolean = false
) {
    val summaryBlock: String?
        get() = compactedSummarySystemPrompt
}

/**
 * Intelligent context manager for long multi-turn conversations (PART 4).
 *
 * Ensures:
 * 1. Fast request preparation & low prefill latency (PART 2) by avoiding resending unlimited
 *    raw history on every request.
 * 2. Incremental summarization that never repeatedly summarizes the same messages.
 * 3. Preservation of user instructions, architectural/technical decisions, unfinished tasks,
 *    and attachment references.
 * 4. Retrieval of relevant older messages when the current user prompt references earlier topics.
 * 5. Model-aware token & character budgeting (e.g. 64K for `free/jev-1.13` vs 1M for other models)
 *    and emergency compaction for `CONTEXT_LENGTH_EXCEEDED` recovery.
 */
object ConversationContextManager {

    private val summaryStore = ConcurrentHashMap<String, ConversationCompactedSummary>()

    private const val DEFAULT_RECENT_WINDOW_MESSAGES = 10
    private const val AGGRESSIVE_RECOVERY_WINDOW_MESSAGES = 4
    private const val MAX_SINGLE_HISTORICAL_MSG_CHARS = 3_600
    private const val MAX_LATEST_USER_MSG_CHARS = 18_000

    private val INSTRUCTION_KEYWORDS = listOf(
        "always", "never", "must", "do not", "don't", "prefer", "use ", "make sure",
        "remember", "in hindi", "in hinglish", "in english", "keep it", "format as"
    )

    private val ARCHITECTURE_KEYWORDS = listOf(
        "kotlin", "compose", "supabase", "room", "sqlite", "okhttp", "viewmodel",
        "repository", "architecture", "database", "api", "endpoint", "schema",
        "python", "typescript", "javascript", "react", "postgres", "docker"
    )

    private val UNFINISHED_KEYWORDS = listOf(
        "todo", "next step", "pending", "later", "unfinished", "remaining",
        "need to", "will implement", "follow up", "fix next"
    )

    fun getSummaryForConversation(conversationId: String): ConversationCompactedSummary? {
        return summaryStore[conversationId]
    }

    fun clearConversationSummary(conversationId: String) {
        summaryStore.remove(conversationId)
    }

    fun clearSummary(conversationId: String) {
        summaryStore.remove(conversationId)
    }

    fun clearAllSummaries() {
        summaryStore.clear()
    }

    /**
     * Estimates token count (~4 characters per token for English/code, ~2.5 for CJK/Devanagari).
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return ((text.length + 3) / 4).coerceAtLeast(1)
    }

    /**
     * Builds an optimized, deduplicated, ordered [OptimizedContextWindow] ready for serialization.
     */
    fun buildOptimizedContext(
        conversationId: String,
        fullHistory: List<ChatMessage> = emptyList(),
        rawHistory: List<ChatMessage> = fullHistory,
        modelId: String = AiModelCatalog.DEFAULT_MODEL_ID,
        aggressiveRecovery: Boolean = false
    ): OptimizedContextWindow {
        val effectiveHistory = if (fullHistory.isNotEmpty()) fullHistory else rawHistory
        if (effectiveHistory.isEmpty()) {
            return OptimizedContextWindow(
                selectedMessages = emptyList(),
                compactedSummarySystemPrompt = null,
                wasCompacted = false
            )
        }

        // 1. Filter to strictly matching conversationId, non-error messages, and deduplicate by ID
        //    while maintaining chronological ordering.
        val seenIds = HashSet<String>()
        val seenRoleAndContent = HashSet<String>()
        val cleanOrdered = effectiveHistory
            .filter { msg ->
                (conversationId.isBlank() || msg.conversationId.isBlank() || msg.conversationId == conversationId) &&
                    msg.status != MessageStatus.ERROR &&
                    (msg.content.isNotBlank() || msg.attachments.isNotEmpty())
            }
            .sortedBy { it.createdAtEpochMs }
            .filter { msg ->
                val uniqueId = msg.id.ifBlank { "${msg.role}_${msg.createdAtEpochMs}" }
                if (!seenIds.add(uniqueId)) return@filter false
                val dedupKey = "${msg.role}:${msg.content.trim()}"
                seenRoleAndContent.add(dedupKey)
                true
            }

        if (cleanOrdered.isEmpty()) {
            return OptimizedContextWindow(
                selectedMessages = emptyList(),
                compactedSummarySystemPrompt = null,
                wasCompacted = false
            )
        }

        val modelDef = AiModelCatalog.findById(modelId)
        val modelContextWindowTokens = modelDef.contextWindowTokens ?: 1_000_000
        val effectiveTokenBudget = when {
            aggressiveRecovery -> 3_500
            modelContextWindowTokens <= 64_000 -> 8_000
            else -> 14_000
        }

        val recentWindowCount = if (aggressiveRecovery) {
            AGGRESSIVE_RECOVERY_WINDOW_MESSAGES
        } else {
            DEFAULT_RECENT_WINDOW_MESSAGES
        }

        val totalEstimatedTokens = cleanOrdered.sumOf { estimateTokens(it.content) }
        val needsCompaction = cleanOrdered.size > recentWindowCount ||
            totalEstimatedTokens > effectiveTokenBudget ||
            aggressiveRecovery

        if (!needsCompaction) {
            return OptimizedContextWindow(
                selectedMessages = trimHistoricalMessagesForFastTransport(cleanOrdered),
                compactedSummarySystemPrompt = null,
                wasCompacted = false
            )
        }

        // Split into older messages (to summarize) and recent messages (to keep intact)
        val splitIndex = (cleanOrdered.size - recentWindowCount).coerceAtLeast(1)
        val olderMessages = cleanOrdered.subList(0, splitIndex)
        val recentMessages = cleanOrdered.subList(splitIndex, cleanOrdered.size)

        // Update persistent summary incrementally (only summarizing older messages not yet summarized)
        val updatedSummary = updatePersistentSummary(
            conversationId = conversationId.ifBlank { "default" },
            olderMessages = olderMessages
        )
        val summarySystemBlock = updatedSummary.toSystemSummaryBlock()

        // Also check if any older message is directly relevant to the latest user query
        val latestUserText = recentMessages.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
        val relevantOlderSnippetMessages = findRelevantOlderMessages(
            olderMessages = olderMessages,
            recentMessageIds = recentMessages.map { it.id }.toSet(),
            latestUserQuery = latestUserText,
            maxCount = if (aggressiveRecovery) 0 else 2
        )

        val combined = buildList {
            addAll(relevantOlderSnippetMessages)
            addAll(recentMessages)
        }

        val budgetedMessages = enforceTokenBudget(
            messages = trimHistoricalMessagesForFastTransport(combined),
            tokenBudget = effectiveTokenBudget
        )

        return OptimizedContextWindow(
            selectedMessages = budgetedMessages,
            compactedSummarySystemPrompt = summarySystemBlock,
            wasCompacted = true
        )
    }

    private fun updatePersistentSummary(
        conversationId: String,
        olderMessages: List<ChatMessage>
    ): ConversationCompactedSummary {
        val existing = summaryStore[conversationId]
        val alreadySummarizedIds = existing?.summarizedMessageIds ?: emptySet()

        val newlyAgedMessages = olderMessages.filter { it.id !in alreadySummarizedIds }
        if (newlyAgedMessages.isEmpty() && existing != null) {
            return existing
        }

        val instructions = (existing?.preservedUserInstructions ?: emptyList()).toMutableList()
        val decisions = (existing?.architecturalDecisions ?: emptyList()).toMutableList()
        val unfinished = (existing?.unfinishedTasks ?: emptyList()).toMutableList()
        val attachments = (existing?.attachmentReferences ?: emptyList()).toMutableList()
        val summaryBullets = mutableListOf<String>()

        if (!existing?.overviewSummary.isNullOrBlank()) {
            summaryBullets.add(existing!!.overviewSummary)
        }

        for (msg in newlyAgedMessages) {
            val cleanText = msg.content.trim()
            val lower = cleanText.lowercase()

            // 1. Preserve attachment references
            if (msg.attachments.isNotEmpty()) {
                val names = msg.attachments.joinToString(", ") { "${it.fileName} (${it.mimeType})" }
                val ref = "${msg.role.name}: attached [$names] — \"${cleanText.take(90)}\""
                if (attachments.none { it == ref }) {
                    attachments.add(ref)
                }
            }

            // 2. Extract user instructions
            if (msg.role == MessageRole.USER && INSTRUCTION_KEYWORDS.any { lower.contains(it) }) {
                val snippet = cleanText.lineSequence().firstOrNull { line ->
                    INSTRUCTION_KEYWORDS.any { line.lowercase().contains(it) }
                }?.take(160) ?: cleanText.take(160)
                if (snippet.isNotBlank() && instructions.none { it.equals(snippet, ignoreCase = true) }) {
                    instructions.add(snippet)
                }
            }

            // 3. Extract technical & architectural decisions
            if (ARCHITECTURE_KEYWORDS.any { lower.contains(it) }) {
                val snippet = cleanText.lineSequence().firstOrNull { line ->
                    ARCHITECTURE_KEYWORDS.any { line.lowercase().contains(it) }
                }?.take(160) ?: cleanText.take(160)
                val labeled = "${msg.role.name}: $snippet"
                if (decisions.none { it.equals(labeled, ignoreCase = true) }) {
                    decisions.add(labeled)
                }
            }

            // 4. Extract unfinished tasks
            if (UNFINISHED_KEYWORDS.any { lower.contains(it) }) {
                val snippet = cleanText.lineSequence().firstOrNull { line ->
                    UNFINISHED_KEYWORDS.any { line.lowercase().contains(it) }
                }?.take(160) ?: cleanText.take(160)
                if (snippet.isNotBlank() && unfinished.none { it.equals(snippet, ignoreCase = true) }) {
                    unfinished.add(snippet)
                }
            }

            // 5. Add concise turn digest
            val condensed = cleanText.replace(Regex("""```[\s\S]*?```"""), "[Code Block]")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .take(180)
            if (condensed.isNotBlank()) {
                summaryBullets.add("- ${msg.role.name}: $condensed")
            }
        }

        val mergedOverview = summaryBullets
            .joinToString("\n")
            .let { full ->
                if (full.length > 2_000) full.takeLast(2_000) else full
            }

        val updated = ConversationCompactedSummary(
            conversationId = conversationId,
            summarizedMessageIds = alreadySummarizedIds + newlyAgedMessages.map { it.id },
            lastSummarizedTimestampMs = olderMessages.maxOfOrNull { it.createdAtEpochMs } ?: 0L,
            overviewSummary = mergedOverview,
            preservedUserInstructions = instructions.takeLast(10),
            architecturalDecisions = decisions.takeLast(10),
            unfinishedTasks = unfinished.takeLast(8),
            attachmentReferences = attachments.takeLast(10)
        )
        summaryStore[conversationId] = updated
        return updated
    }

    private fun findRelevantOlderMessages(
        olderMessages: List<ChatMessage>,
        recentMessageIds: Set<String>,
        latestUserQuery: String,
        maxCount: Int
    ): List<ChatMessage> {
        if (maxCount <= 0 || latestUserQuery.isBlank() || olderMessages.isEmpty()) return emptyList()
        val keywords = latestUserQuery.lowercase()
            .split(Regex("""[^a-z0-9_]+"""))
            .filter { it.length >= 4 && it !in STOP_WORDS }
            .toSet()
        if (keywords.isEmpty()) return emptyList()

        return olderMessages
            .asSequence()
            .filter { it.id !in recentMessageIds }
            .map { msg ->
                val lower = msg.content.lowercase()
                val score = keywords.count { kw -> lower.contains(kw) }
                msg to score
            }
            .filter { (_, score) -> score >= 2 }
            .sortedByDescending { (msg, score) -> score * 1000L + (msg.createdAtEpochMs % 1000L) }
            .take(maxCount)
            .map { (msg, _) ->
                msg.copy(
                    content = msg.content.take(1_200),
                    attachments = emptyList() // Referenced via summary; binary payload only needed on active image turn
                )
            }
            .sortedBy { it.createdAtEpochMs }
            .toList()
    }

    /**
     * Prevents multi-kilobyte historical code dumps or old generated image Base64 strings from
     * bloating every subsequent chat request (critical for 5–8s TTFT target and multi-turn reliability).
     */
    private fun trimHistoricalMessagesForFastTransport(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()
        val lastUserIdx = messages.indexOfLast { it.role == MessageRole.USER }

        return messages.mapIndexed { index, msg ->
            val isLatestUserTurn = index == lastUserIdx
            val maxChars = if (isLatestUserTurn) MAX_LATEST_USER_MSG_CHARS else MAX_SINGLE_HISTORICAL_MSG_CHARS
            val trimmedContent = if (msg.content.length > maxChars) {
                val head = msg.content.take(maxChars * 2 / 3)
                val tail = msg.content.takeLast(maxChars / 3)
                "$head\n… [earlier content compacted for speed] …\n$tail"
            } else {
                msg.content
            }

            // Strip heavy Base64 data from historical assistant/user turns while keeping attachment metadata
            // on the latest user turn (where vision analysis occurs).
            val cleanedAttachments = if (isLatestUserTurn) {
                msg.attachments
            } else {
                msg.attachments.map { att -> att.copy(base64DataUrl = null) }
            }

            val contentWithAttachmentNote = if (!isLatestUserTurn && msg.attachments.isNotEmpty() &&
                !trimmedContent.contains("[Attached:", ignoreCase = true)
            ) {
                val attNote = msg.attachments.joinToString(", ") { it.fileName }
                "$trimmedContent\n[Attached media in this turn: $attNote]".trim()
            } else {
                trimmedContent
            }

            msg.copy(
                content = contentWithAttachmentNote,
                attachments = cleanedAttachments
            )
        }
    }

    private fun enforceTokenBudget(
        messages: List<ChatMessage>,
        tokenBudget: Int
    ): List<ChatMessage> {
        if (messages.size <= 2) return messages
        var currentTokens = messages.sumOf { estimateTokens(it.content) }
        if (currentTokens <= tokenBudget) return messages

        val mutable = messages.toMutableList()
        // Keep index 0 if it's SYSTEM summary, and always keep the last 2 messages
        val minKeepStartIndex = if (mutable.firstOrNull()?.role == MessageRole.SYSTEM) 1 else 0
        while (currentTokens > tokenBudget && mutable.size > minKeepStartIndex + 2) {
            val removed = mutable.removeAt(minKeepStartIndex)
            currentTokens -= estimateTokens(removed.content)
        }
        return mutable
    }

    private val STOP_WORDS = setOf(
        "what", "when", "where", "which", "that", "this", "with", "from", "have",
        "will", "your", "about", "please", "tell", "show", "give", "make", "write",
        "explain", "code", "into", "more", "some", "like", "just"
    )
}
