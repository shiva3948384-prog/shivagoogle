package com.example.domain.usecases

import com.example.domain.models.ChatMessage
import com.example.domain.models.Conversation
import com.example.domain.models.ConversationSummaryMemory
import com.example.domain.models.MemoryCategory
import com.example.domain.models.MemorySourceType
import com.example.domain.models.MessageRole
import com.example.domain.models.PersonalIntelligenceSnapshot
import com.example.domain.models.ProjectContextMemory
import com.example.domain.models.UserMemoryItem
import java.util.Locale
import java.util.UUID

/**
 * Structured, privacy-conscious Personal Intelligence Engine for Smarter AI 1.0.
 *
 * Responsibilities:
 * 1. Selective Memory Retrieval: Retrieves only relevant user preferences, active project contexts,
 *    and prior conversation insights for a given prompt without dumping the entire database.
 * 2. Project Continuation Detection: Recognizes prompts like "Continue working on my app" or
 *    "Continue my Android app project" and attaches the exact ongoing project architecture,
 *    tech stack, recent decisions, and open tasks.
 * 3. Privacy & Provenance Separation: Explicitly tags each memory with its provenance:
 *    - EXPLICIT_USER (confirmed user-provided preference/fact)
 *    - PREVIOUS_CONVERSATION (prior session discussion)
 *    - INFERRED_CONTEXT (tentative workflow inference — never stated as a confirmed personal fact)
 *    - EXTERNAL_SOURCE (verified external data)
 * 4. Respects Opt-Out & Sensitive Data Exclusion: Never stores sensitive personal characteristics
 *    and honors "don't remember this" / "forget" commands.
 */
object PersonalIntelligenceEngine {

    private const val MAX_RETRIEVED_MEMORIES = 6
    private const val MAX_RETRIEVED_PROJECTS = 2

    private val OPT_OUT_PATTERNS = listOf(
        Regex("""\b(don'?t|do not|never)\s+(remember|save|store|log)\s+this\b""", RegexOption.IGNORE_CASE),
        Regex("""\boff\s+the\s+record\b""", RegexOption.IGNORE_CASE),
        Regex("""\bincognito\b""", RegexOption.IGNORE_CASE)
    )

    private val FORGET_COMMAND_REGEX = Regex(
        """^\s*(?:please\s+)?(?:forget|delete|remove|clear)\s+(?:that\s+|my\s+)?(.+?)\s*[.!?]*$""",
        RegexOption.IGNORE_CASE
    )

    private val SENSITIVE_EXCLUSION_PATTERNS = listOf(
        Regex("""\b(ssn|social security|credit card|cvv|passport number|bank account|medical diagnosis|HIV|diabetes|religion|caste|political party|biometric|password is)\b""", RegexOption.IGNORE_CASE)
    )

    private val PROJECT_CONTINUATION_REGEX = Regex(
        """\b(continue|resume|back\s+to|keep\s+working\s+on|pick\s+up|what'?s\s+next\s+for|update\s+on|next\s+step\s+for|remind\s+me\s+of)\b.*\b(my\s+app|my\s+android\s+app|my\s+project|the\s+app|the\s+project|our\s+app|our\s+project|my\s+website|my\s+backend|my\s+service|project\s+called|project\s+name)\b""",
        RegexOption.IGNORE_CASE
    )

    private val PROJECT_RECALL_REGEX = Regex(
        """^\s*(?:what\s+is|what'?s|tell\s+me\s+about|remind\s+me\s+of|do\s+you\s+remember)\s+(?:the\s+name\s+of\s+)?(?:my|our)\s+(?:active\s+|current\s+)?(?:project|app|application)(?:\s+called|\s+named|\s+name)?\s*[?.!]*\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val PERSONAL_MEMORY_RECALL_REGEX = Regex(
        """\b(what\s+do\s+you\s+remember|what\s+do\s+you\s+know\s+about\s+me|my\s+preferences|my\s+preferred|what\s+is\s+my\s+name|who\s+am\s+i|my\s+tech\s+stack|my\s+coding\s+style|my\s+workflow|saved\s+memories)\b""",
        RegexOption.IGNORE_CASE
    )

    private val GENERIC_TECH_PROJECT_WORDS = setOf(
        "android", "application", "project", "active", "software", "kotlin", "python",
        "java", "javascript", "typescript", "react", "compose", "jetpack", "mobile", "web", "app"
    )

    /**
     * Checks whether the user explicitly asked not to remember the current message.
     */
    fun hasOptOutDirective(userPrompt: String): Boolean {
        val clean = userPrompt.trim()
        if (clean.isEmpty()) return false
        return OPT_OUT_PATTERNS.any { it.containsMatchIn(clean) }
    }

    /**
     * Checks whether the prompt contains sensitive personal attributes that must never be stored.
     */
    fun containsSensitivePersonalData(text: String): Boolean {
        return SENSITIVE_EXCLUSION_PATTERNS.any { it.containsMatchIn(text) }
    }

    /**
     * Detects whether the user is asking to continue or recall an existing project
     * (e.g., "Continue working on my app." or "What is my project called?").
     */
    fun isProjectContinuationRequest(userPrompt: String): Boolean {
        val clean = userPrompt.trim()
        if (clean.isEmpty()) return false
        if (PROJECT_CONTINUATION_REGEX.containsMatchIn(clean) || PROJECT_RECALL_REGEX.matches(clean)) return true
        val lower = clean.lowercase(Locale.ROOT)
        return lower.contains("continue working on my") ||
            lower.contains("continue my android") ||
            lower.contains("continue my app") ||
            lower.contains("continue my project") ||
            lower.contains("resume my project") ||
            lower.contains("continue our project") ||
            lower.contains("what is my project called") ||
            lower.contains("what's my project called") ||
            lower.contains("what is the name of my project") ||
            lower.contains("what project am i working on")
    }

    fun isPersonalMemoryRecallRequest(userPrompt: String): Boolean {
        val clean = userPrompt.trim()
        if (clean.isEmpty()) return false
        return PERSONAL_MEMORY_RECALL_REGEX.containsMatchIn(clean) || isProjectContinuationRequest(clean)
    }

    /**
     * Extracts structured memories and project context updates from a user message (and optional assistant reply)
     * when Personal Intelligence is enabled.
     */
    fun extractMemoriesFromTurn(
        userId: String,
        conversationId: String?,
        userPrompt: String,
        assistantReply: String = "",
        existingMemories: List<UserMemoryItem> = emptyList(),
        existingProjects: List<ProjectContextMemory> = emptyList(),
        nowEpochMs: Long = System.currentTimeMillis()
    ): ExtractedIntelligenceResult {
        val trimmed = userPrompt.trim()
        if (trimmed.isEmpty() || hasOptOutDirective(trimmed) || containsSensitivePersonalData(trimmed)) {
            return ExtractedIntelligenceResult()
        }

        val newOrUpdatedMemories = mutableListOf<UserMemoryItem>()
        val newOrUpdatedProjects = mutableListOf<ProjectContextMemory>()
        val keysToForget = mutableListOf<String>()

        // 1. Check for explicit "Forget ..." command
        val forgetMatch = FORGET_COMMAND_REGEX.find(trimmed)
        if (forgetMatch != null && trimmed.lowercase(Locale.ROOT).startsWith("forget")) {
            val targetTopic = forgetMatch.groupValues[1].trim().lowercase(Locale.ROOT)
            if (targetTopic.isNotEmpty()) {
                keysToForget.add(targetTopic)
            }
        }

        // 2. Explicit preference statements ("Remember that I...", "I prefer...", "Always use...", "My preferred language is...")
        val explicitRememberRegex = Regex(
            """(?:remember\s+that\s+|please\s+remember\s+|note\s+that\s+|always\s+remember\s+)(.+?)(?:[.!]|$)""",
            RegexOption.IGNORE_CASE
        )
        explicitRememberRegex.find(trimmed)?.let { match ->
            val statement = match.groupValues[1].trim()
            if (statement.length in 4..220) {
                val key = "explicit_" + normalizeKey(statement).take(36)
                newOrUpdatedMemories.add(
                    createOrMergeMemory(
                        userId = userId,
                        category = classifyCategory(statement),
                        memoryKey = key,
                        content = statement,
                        sourceType = MemorySourceType.EXPLICIT_USER,
                        confidence = 1.0,
                        conversationId = conversationId,
                        existing = existingMemories,
                        nowEpochMs = nowEpochMs
                    )
                )
            }
        }

        // 3. Explicit communication or coding style preferences ("I prefer Kotlin and Jetpack Compose", "Keep answers concise")
        val preferenceRegex = Regex(
            """\b(?:i\s+prefer|my\s+preferred\s+[a-z\s]+\s+is|always\s+use|always\s+prefer|keep\s+responses?|keep\s+answers?)\s+([^.!?\n]{3,160})""",
            RegexOption.IGNORE_CASE
        )
        preferenceRegex.findAll(trimmed).forEach { match ->
            val fullClause = match.value.trim()
            val category = if (
                fullClause.contains("concise", ignoreCase = true) ||
                fullClause.contains("detailed", ignoreCase = true) ||
                fullClause.contains("step-by-step", ignoreCase = true) ||
                fullClause.contains("tone", ignoreCase = true) ||
                fullClause.contains("hindi", ignoreCase = true) ||
                fullClause.contains("hinglish", ignoreCase = true) ||
                fullClause.contains("english", ignoreCase = true)
            ) {
                MemoryCategory.COMMUNICATION_STYLE
            } else {
                MemoryCategory.USER_PREFERENCE
            }
            val key = "pref_" + normalizeKey(fullClause).take(36)
            newOrUpdatedMemories.add(
                createOrMergeMemory(
                    userId = userId,
                    category = category,
                    memoryKey = key,
                    content = fullClause,
                    sourceType = MemorySourceType.EXPLICIT_USER,
                    confidence = 0.98,
                    conversationId = conversationId,
                    existing = existingMemories,
                    nowEpochMs = nowEpochMs
                )
            )
        }

        // 4. Professional role / background explicitly shared ("I am an Android developer", "I work as a backend engineer")
        val roleRegex = Regex(
            """\b(?:i\s+am\s+an?|i'm\s+an?|i\s+work\s+as\s+an?)\s+((?:senior\s+|lead\s+|fullstack\s+|full-stack\s+)?(?:android|ios|kotlin|python|web|frontend|backend|software|machine\s+learning|data|devops|cloud|product|ui/ux)\s+(?:developer|engineer|architect|designer|manager|researcher|student)[^.!?\n]{0,80})""",
            RegexOption.IGNORE_CASE
        )
        roleRegex.find(trimmed)?.let { match ->
            val roleDesc = "Works as ${match.groupValues[1].trim()}"
            newOrUpdatedMemories.add(
                createOrMergeMemory(
                    userId = userId,
                    category = MemoryCategory.PROFESSIONAL_CONTEXT,
                    memoryKey = "professional_role",
                    content = roleDesc,
                    sourceType = MemorySourceType.EXPLICIT_USER,
                    confidence = 0.95,
                    conversationId = conversationId,
                    existing = existingMemories,
                    nowEpochMs = nowEpochMs
                )
            )
        }

        // 5. Explicit Project Name Declaration (e.g., "My project is called Smarter AI.", "Our app is named FinTrack")
        val explicitProjectNameRegex = Regex(
            """\b(?:my|our|the)\s+(?:project|app|application|product|platform|startup|service)\s+(?:is\s+called|is\s+named|name\s+is)\s+["']?([^.!?\n"']{2,70})["']?""",
            RegexOption.IGNORE_CASE
        )
        val calledProjectRegex = Regex(
            """\b(?:app|application|project|product|platform)\s+(?:called|named)\s+["']?([A-Za-z0-9][A-Za-z0-9\s._-]{1,50}?)(?:\s+(?:using|with|in|for|that|which)\b|[.!?\n"']|$)""",
            RegexOption.IGNORE_CASE
        )
        val explicitNamedProject = explicitProjectNameRegex.find(trimmed)?.groupValues?.getOrNull(1)?.trim()
            ?: calledProjectRegex.find(trimmed)?.groupValues?.getOrNull(1)?.trim()

        // 5b. Project Context Extraction (e.g., building an Android app, web app, backend service, etc.)
        val projectBuildRegex = Regex(
            """\b(?:i'?m\s+building|we\s+are\s+building|working\s+on|developing|building|designing|creating)\s+(?:an?\s+|my\s+|our\s+)?((?:android|kotlin|jetpack\s+compose|flutter|react|next\.?js|python|saas|fintech|e-?commerce|fitness|health|chat|weather|ai|mobile|web)\s+(?:app|application|project|platform|service|website|dashboard)[^.!?\n]{0,120})""",
            RegexOption.IGNORE_CASE
        )
        val projectMatch = projectBuildRegex.find(trimmed)
        if (explicitNamedProject != null || projectMatch != null || isSubstantialProjectDiscussion(trimmed)) {
            val extractedProject = buildOrUpdateProjectContext(
                userId = userId,
                conversationId = conversationId,
                userPrompt = trimmed,
                assistantReply = assistantReply,
                matchedPhrase = explicitNamedProject ?: projectMatch?.groupValues?.getOrNull(1)?.trim(),
                existingProjects = existingProjects,
                nowEpochMs = nowEpochMs
            )
            if (extractedProject != null) {
                newOrUpdatedProjects.add(extractedProject)
                // Also record an explicit/conversation memory item linking to this active project
                newOrUpdatedMemories.add(
                    createOrMergeMemory(
                        userId = userId,
                        category = MemoryCategory.ACTIVE_PROJECT,
                        memoryKey = "project_${normalizeKey(extractedProject.projectName).take(28)}",
                        content = "${extractedProject.projectName}: ${extractedProject.summary}" +
                            if (extractedProject.techStack.isNotBlank()) " (Tech stack: ${extractedProject.techStack})" else "",
                        sourceType = if (explicitNamedProject != null || projectMatch != null) MemorySourceType.EXPLICIT_USER else MemorySourceType.PREVIOUS_CONVERSATION,
                        confidence = if (explicitNamedProject != null || projectMatch != null) 0.99 else 0.85,
                        conversationId = conversationId,
                        existing = existingMemories,
                        nowEpochMs = nowEpochMs
                    )
                )
            }
        }

        // 6. Contextual Workflow Inference (labelled strictly as INFERRED_CONTEXT with lower confidence)
        val inferredTech = detectTechWorkflowInference(trimmed)
        if (inferredTech != null && newOrUpdatedMemories.isEmpty()) {
            val key = "inferred_workflow_${normalizeKey(inferredTech).take(24)}"
            newOrUpdatedMemories.add(
                createOrMergeMemory(
                    userId = userId,
                    category = MemoryCategory.WORKFLOW,
                    memoryKey = key,
                    content = "Frequently works with $inferredTech",
                    sourceType = MemorySourceType.INFERRED_CONTEXT,
                    confidence = 0.65,
                    conversationId = conversationId,
                    existing = existingMemories,
                    nowEpochMs = nowEpochMs
                )
            )
        }

        return ExtractedIntelligenceResult(
            memoriesToUpsert = newOrUpdatedMemories.distinctBy { "${it.category.name}:${it.memoryKey}" },
            projectsToUpsert = newOrUpdatedProjects.distinctBy { it.projectName.lowercase(Locale.ROOT) },
            topicsToForget = keysToForget
        )
    }

    /**
     * Selects only relevant memories and active project context for the current user prompt,
     * formatting a clear provenance-tagged instruction block for Smarter AI 1.0.
     *
     * Strictly avoids injecting unrelated saved projects or memories into ordinary general-knowledge,
     * coding, debugging, or writing questions so the user's current prompt always remains primary.
     */
    fun buildPersonalIntelligenceSnapshot(
        userDisplayName: String?,
        personalIntelligenceEnabled: Boolean,
        userPrompt: String,
        allMemories: List<UserMemoryItem>,
        allProjects: List<ProjectContextMemory>,
        recentConversations: List<Conversation> = emptyList()
    ): PersonalIntelligenceSnapshot {
        val cleanName = userDisplayName?.trim()?.takeIf { it.isNotEmpty() }
        val isContinuation = isProjectContinuationRequest(userPrompt)
        val isPersonalRecall = isPersonalMemoryRecallRequest(userPrompt)

        if (!personalIntelligenceEnabled) {
            val minimalHeader = if (cleanName != null && isPersonalRecall) {
                "[USER PROFILE CONTEXT]\n- Authenticated User Display Name: $cleanName (Explicitly provided by user profile)."
            } else {
                ""
            }
            return PersonalIntelligenceSnapshot(
                relevantMemories = emptyList(),
                activeProjects = emptyList(),
                matchedProjectForContinuation = null,
                formattedSystemContext = minimalHeader
            )
        }

        val approvedMemories = allMemories.filter { it.isUserApproved }
        val queryTokens = tokenizeForRelevance(userPrompt)
        val lowerPrompt = userPrompt.trim().lowercase(Locale.ROOT)

        // 1. Score and select relevant project contexts (ONLY on explicit project continuation,
        //    personal recall, or when the user explicitly names the project)
        val scoredProjects = allProjects
            .map { project ->
                val score = scoreProjectRelevance(project, lowerPrompt, queryTokens, isContinuation)
                project to score
            }
            .sortedWith(compareByDescending<Pair<ProjectContextMemory, Double>> { it.second }.thenByDescending { it.first.updatedAtEpochMs })

        val matchedContinuationProject = if (isContinuation) {
            scoredProjects.firstOrNull()?.first
        } else {
            scoredProjects.firstOrNull { it.second >= 4.0 }?.first
        }

        val selectedProjects = when {
            matchedContinuationProject != null -> listOf(matchedContinuationProject)
            isPersonalRecall -> scoredProjects.take(MAX_RETRIEVED_PROJECTS).map { it.first }
            else -> scoredProjects.filter { it.second >= 3.5 }.take(MAX_RETRIEVED_PROJECTS).map { it.first }
        }

        // 2. Score and select relevant user memories
        val scoredMemories = approvedMemories
            .map { memory ->
                val score = scoreMemoryRelevance(memory, queryTokens, isContinuation, isPersonalRecall)
                memory to score
            }
            .filter { (memory, score) ->
                when {
                    isContinuation || isPersonalRecall -> score > 0.0 ||
                        memory.category == MemoryCategory.COMMUNICATION_STYLE ||
                        memory.sourceType == MemorySourceType.EXPLICIT_USER
                    memory.category == MemoryCategory.COMMUNICATION_STYLE -> true
                    memory.category == MemoryCategory.ACTIVE_PROJECT -> score >= 3.5
                    memory.sourceType == MemorySourceType.INFERRED_CONTEXT -> score >= 2.5
                    else -> score >= 1.8
                }
            }
            .sortedWith(compareByDescending<Pair<UserMemoryItem, Double>> { it.second }.thenByDescending { it.first.updatedAtEpochMs })
            .take(MAX_RETRIEVED_MEMORIES)
            .map { it.first }

        // If this is an ordinary question with no matched projects, no matched memories, and not a continuation/personal recall,
        // return an empty snapshot so the prompt is completely clean and focused on the user's question.
        if (selectedProjects.isEmpty() && scoredMemories.isEmpty() && !isContinuation && !isPersonalRecall) {
            return PersonalIntelligenceSnapshot()
        }

        // 3. Build structured, privacy-conscious prompt block with explicit provenance tags
        val sb = StringBuilder()
        sb.appendLine("[SMARTER AI 1.0 — PERSONAL INTELLIGENCE & MEMORY CONTEXT]")
        sb.appendLine("CRITICAL PRIORITY RULE: The user's current message is the primary instruction. Answer the user's actual question directly and completely. Never let saved background memories or project names override or replace the user's request.")
        sb.appendLine("Privacy & Honesty Rules for Using Personal Context:")
        sb.appendLine("1. Distinguish strictly between:")
        sb.appendLine("   - [EXPLICITLY PROVIDED BY USER]: Confirmed preferences or facts directly shared by the user.")
        sb.appendLine("   - [FROM PREVIOUS CONVERSATION]: Context retrieved from earlier sessions/projects.")
        sb.appendLine("   - [INFERRED CONTEXT — TENTATIVE]: Patterns inferred from workflows. NEVER state an inference as a confirmed personal fact.")
        sb.appendLine("2. Use this context naturally to tailor your response, maintain continuity across sessions, and avoid asking redundant questions when the answer is already known below.")
        sb.appendLine("3. Do not dump or recite this memory block unless the user asks what you remember or asks to continue their project.")

        if (cleanName != null && (isContinuation || isPersonalRecall)) {
            sb.appendLine("\nUser Profile:")
            sb.appendLine("- Display Name [EXPLICITLY PROVIDED BY USER]: $cleanName")
        }

        if (matchedContinuationProject != null) {
            sb.appendLine("\nActive Project Continuation Context [FROM PREVIOUS CONVERSATION]:")
            sb.appendLine("- Project Name: ${matchedContinuationProject.projectName}")
            sb.appendLine("- Project Summary: ${matchedContinuationProject.summary}")
            if (matchedContinuationProject.techStack.isNotBlank()) {
                sb.appendLine("- Tech Stack & Architecture: ${matchedContinuationProject.techStack}")
            }
            if (matchedContinuationProject.recentDecisions.isNotBlank()) {
                sb.appendLine("- Recent Decisions: ${matchedContinuationProject.recentDecisions}")
            }
            if (matchedContinuationProject.openTasks.isNotBlank()) {
                sb.appendLine("- Next Steps / Open Tasks: ${matchedContinuationProject.openTasks}")
            }
            sb.appendLine("Instruction: The user is continuing work on '${matchedContinuationProject.projectName}'. Immediately resume from this context without asking them to re-explain the project from scratch.")
        } else if (selectedProjects.isNotEmpty()) {
            sb.appendLine("\nRelevant Ongoing Projects [FROM PREVIOUS CONVERSATION]:")
            selectedProjects.forEach { proj ->
                sb.appendLine("- ${proj.projectName}: ${proj.summary}" +
                    if (proj.techStack.isNotBlank()) " [Stack: ${proj.techStack}]" else "")
            }
        } else if (isContinuation && recentConversations.isNotEmpty()) {
            val latestConv = recentConversations.first()
            sb.appendLine("\nMost Recent Conversation Topic [FROM PREVIOUS CONVERSATION]:")
            sb.appendLine("- Title: ${latestConv.title}")
        }

        val explicitItems = scoredMemories.filter { it.sourceType == MemorySourceType.EXPLICIT_USER }
        val conversationItems = scoredMemories.filter { it.sourceType == MemorySourceType.PREVIOUS_CONVERSATION }
        val inferredItems = scoredMemories.filter { it.sourceType == MemorySourceType.INFERRED_CONTEXT }

        if (explicitItems.isNotEmpty()) {
            sb.appendLine("\nUser Preferences & Context [EXPLICITLY PROVIDED BY USER]:")
            explicitItems.forEach { item ->
                sb.appendLine("- (${item.category.displayTitle}) ${item.content}")
            }
        }

        if (conversationItems.isNotEmpty()) {
            sb.appendLine("\nPrior Session Context [FROM PREVIOUS CONVERSATION]:")
            conversationItems.forEach { item ->
                sb.appendLine("- (${item.category.displayTitle}) ${item.content}")
            }
        }

        if (inferredItems.isNotEmpty()) {
            sb.appendLine("\nTentative Workflow Signals [INFERRED CONTEXT — DO NOT PRESENT AS CONFIRMED PERSONAL FACT]:")
            inferredItems.forEach { item ->
                sb.appendLine("- ${item.content}")
            }
        }

        return PersonalIntelligenceSnapshot(
            relevantMemories = scoredMemories,
            activeProjects = selectedProjects,
            matchedProjectForContinuation = matchedContinuationProject,
            formattedSystemContext = sb.toString().trim()
        )
    }

    /**
     * Generates adaptive prompt suggestions based on the user's active projects and saved preferences,
     * while preserving the core Smarter AI 1.0 suggestion categories.
     */
    fun getAdaptiveTopicHint(
        activeProjects: List<ProjectContextMemory>,
        memories: List<UserMemoryItem>
    ): String? {
        val topProject = activeProjects.firstOrNull()
        if (topProject != null) {
            return "Continue working on ${topProject.projectName}"
        }
        val projectMemory = memories.firstOrNull { it.category == MemoryCategory.ACTIVE_PROJECT }
        if (projectMemory != null) {
            return "Continue my project: ${projectMemory.content.take(48)}"
        }
        return null
    }

    private fun scoreProjectRelevance(
        project: ProjectContextMemory,
        lowerPrompt: String,
        queryTokens: Set<String>,
        isContinuation: Boolean
    ): Double {
        var score = 0.0
        if (isContinuation) {
            score += 5.0
        }
        val specificNameTokens = tokenizeForRelevance(project.projectName)
            .filterNot { it in GENERIC_TECH_PROJECT_WORDS }
            .toSet()
        val fullNameLower = project.projectName.trim().lowercase(Locale.ROOT)
        if (fullNameLower.length >= 3 && fullNameLower !in GENERIC_TECH_PROJECT_WORDS && lowerPrompt.contains(fullNameLower)) {
            score += 4.0
        } else if (specificNameTokens.isNotEmpty() && queryTokens.intersect(specificNameTokens).isNotEmpty()) {
            score += 2.5
        }
        if (isContinuation) {
            val projectTokens = tokenizeForRelevance(
                "${project.projectName} ${project.summary} ${project.techStack} ${project.recentDecisions} ${project.openTasks}"
            )
            val overlap = queryTokens.intersect(projectTokens).size
            score += overlap * 1.5
        }
        return score
    }

    private fun scoreMemoryRelevance(
        memory: UserMemoryItem,
        queryTokens: Set<String>,
        isContinuation: Boolean,
        isPersonalRecall: Boolean = false
    ): Double {
        var score = 0.0
        if (memory.category == MemoryCategory.COMMUNICATION_STYLE) {
            score += 2.0
        }
        if ((isContinuation || isPersonalRecall) &&
            memory.sourceType == MemorySourceType.EXPLICIT_USER &&
            memory.category == MemoryCategory.USER_PREFERENCE
        ) {
            score += 1.5
        }
        if (isContinuation && memory.category == MemoryCategory.ACTIVE_PROJECT) {
            score += 4.0
        }
        val memoryTokens = tokenizeForRelevance("${memory.memoryKey} ${memory.content}")
        val nonGenericMemoryTokens = if (!isContinuation && !isPersonalRecall) {
            memoryTokens.filterNot { it in GENERIC_TECH_PROJECT_WORDS }.toSet()
        } else if (memory.category == MemoryCategory.ACTIVE_PROJECT) {
            memoryTokens.filterNot { it in GENERIC_TECH_PROJECT_WORDS }.toSet()
        } else {
            memoryTokens
        }
        val overlap = queryTokens.intersect(nonGenericMemoryTokens).size
        if (overlap > 0 && memory.sourceType == MemorySourceType.EXPLICIT_USER && memory.category == MemoryCategory.USER_PREFERENCE) {
            score += 1.0
        }
        score += overlap * 1.4
        score *= memory.confidence
        return score
    }

    private fun tokenizeForRelevance(text: String): Set<String> {
        val stopWords = setOf(
            "the", "and", "for", "with", "that", "this", "from", "have", "what",
            "how", "can", "you", "help", "today", "please", "about", "into", "your"
        )
        return text.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9+#._-]+"))
            .map { it.trim() }
            .filter { it.length >= 3 && it !in stopWords }
            .toSet()
    }

    private fun classifyCategory(statement: String): MemoryCategory {
        val lower = statement.lowercase(Locale.ROOT)
        return when {
            lower.contains("project") || lower.contains("building") || lower.contains("app") -> MemoryCategory.ACTIVE_PROJECT
            lower.contains("concise") || lower.contains("hindi") || lower.contains("english") ||
                lower.contains("hinglish") || lower.contains("explain") || lower.contains("tone") -> MemoryCategory.COMMUNICATION_STYLE
            lower.contains("developer") || lower.contains("engineer") || lower.contains("company") ||
                lower.contains("team") -> MemoryCategory.PROFESSIONAL_CONTEXT
            lower.contains("interested in") || lower.contains("love") || lower.contains("favorite") -> MemoryCategory.INTEREST
            else -> MemoryCategory.USER_PREFERENCE
        }
    }

    private fun isSubstantialProjectDiscussion(prompt: String): Boolean {
        val lower = prompt.lowercase(Locale.ROOT)
        val hasProjectNoun = lower.contains("android app") ||
            lower.contains("our app") ||
            lower.contains("my app") ||
            lower.contains("payment service") ||
            lower.contains("architecture")
        val hasTechDetail = lower.contains("kotlin") ||
            lower.contains("jetpack compose") ||
            lower.contains("mvvm") ||
            lower.contains("supabase") ||
            lower.contains("room") ||
            lower.contains("coroutines") ||
            lower.contains("fastapi") ||
            lower.contains("react")
        return hasProjectNoun && hasTechDetail
    }

    private fun buildOrUpdateProjectContext(
        userId: String,
        conversationId: String?,
        userPrompt: String,
        assistantReply: String,
        matchedPhrase: String?,
        existingProjects: List<ProjectContextMemory>,
        nowEpochMs: Long
    ): ProjectContextMemory? {
        val inferredName = when {
            !matchedPhrase.isNullOrBlank() -> {
                matchedPhrase
                    .split(Regex("""\b(using|with|that|where|in|for)\b""", RegexOption.IGNORE_CASE))
                    .firstOrNull()
                    ?.trim()
                    ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                    ?.take(48)
                    ?: "Android Application Project"
            }
            userPrompt.contains("android", ignoreCase = true) -> "Android Application Project"
            userPrompt.contains("payment service", ignoreCase = true) -> "Payment Service Architecture"
            else -> "Active Software Project"
        }

        val existing = existingProjects.firstOrNull {
            it.projectName.equals(inferredName, ignoreCase = true) ||
                (it.projectName.contains("Android", ignoreCase = true) && inferredName.contains("Android", ignoreCase = true))
        }

        val detectedStack = extractTechStackKeywords("$userPrompt $assistantReply")
        val mergedStack = listOfNotNull(
            existing?.techStack?.takeIf { it.isNotBlank() },
            detectedStack.takeIf { it.isNotBlank() }
        ).flatMap { it.split(",").map { s -> s.trim() } }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .joinToString(", ")

        val decisionSnippet = userPrompt.take(180)
        val openTaskSnippet = extractOpenTaskFromTurn(userPrompt, assistantReply)
            ?: existing?.openTasks.orEmpty()

        return ProjectContextMemory(
            id = existing?.id ?: UUID.randomUUID().toString(),
            userId = userId,
            projectName = existing?.projectName ?: inferredName,
            summary = existing?.summary?.takeIf { it.length > decisionSnippet.length } ?: decisionSnippet,
            techStack = mergedStack,
            recentDecisions = decisionSnippet,
            openTasks = openTaskSnippet,
            lastConversationId = conversationId ?: existing?.lastConversationId,
            updatedAtEpochMs = nowEpochMs
        )
    }

    private fun extractTechStackKeywords(text: String): String {
        val catalog = listOf(
            "Kotlin", "Jetpack Compose", "Coroutines", "StateFlow", "MVVM", "Clean Architecture",
            "Room", "Supabase", "PostgreSQL", "Python", "FastAPI", "TypeScript", "React",
            "Next.js", "Tailwind CSS", "Docker", "GraphQL", "REST API", "Material 3"
        )
        return catalog.filter { keyword ->
            text.contains(keyword, ignoreCase = true)
        }.joinToString(", ")
    }

    private fun extractOpenTaskFromTurn(userPrompt: String, assistantReply: String): String? {
        val todoRegex = Regex("""(?:TODO|Next step|Next we need to|We still need to)[:\s]+([^.\n]{5,140})""", RegexOption.IGNORE_CASE)
        todoRegex.find(userPrompt)?.let { return it.groupValues[1].trim() }
        todoRegex.find(assistantReply)?.let { return it.groupValues[1].trim() }
        return null
    }

    private fun detectTechWorkflowInference(prompt: String): String? {
        val stacks = extractTechStackKeywords(prompt)
        return stacks.takeIf { it.isNotBlank() }
    }

    private fun createOrMergeMemory(
        userId: String,
        category: MemoryCategory,
        memoryKey: String,
        content: String,
        sourceType: MemorySourceType,
        confidence: Double,
        conversationId: String?,
        existing: List<UserMemoryItem>,
        nowEpochMs: Long
    ): UserMemoryItem {
        val match = existing.firstOrNull {
            it.userId == userId &&
                it.category == category &&
                it.memoryKey.equals(memoryKey, ignoreCase = true)
        }
        return UserMemoryItem(
            id = match?.id ?: UUID.randomUUID().toString(),
            userId = userId,
            category = category,
            memoryKey = memoryKey,
            content = content,
            sourceType = sourceType,
            confidence = confidence,
            conversationId = conversationId ?: match?.conversationId,
            isUserApproved = match?.isUserApproved ?: true,
            createdAtEpochMs = match?.createdAtEpochMs ?: nowEpochMs,
            updatedAtEpochMs = nowEpochMs
        )
    }

    /**
     * Validates that a memory item is safe and authorized to upload to Supabase:
     * - Belongs to the authenticated user.
     * - Has non-blank key and content.
     * - Is user-approved (`isUserApproved == true`).
     * - Does not contain opt-out directives or sensitive personal data (especially inferred sensitive data).
     */
    fun isMemoryEligibleForCloudUpload(
        item: UserMemoryItem,
        authenticatedUserId: String
    ): Boolean {
        if (authenticatedUserId.isBlank() || item.userId != authenticatedUserId) return false
        if (item.memoryKey.isBlank() || item.content.isBlank()) return false
        if (!item.isUserApproved) return false
        if (hasOptOutDirective(item.content)) return false
        if (containsSensitivePersonalData(item.content) || containsSensitivePersonalData(item.memoryKey)) return false
        return true
    }

    fun isProjectEligibleForCloudUpload(
        project: ProjectContextMemory,
        authenticatedUserId: String
    ): Boolean {
        if (authenticatedUserId.isBlank() || project.userId != authenticatedUserId) return false
        if (project.projectName.isBlank()) return false
        val combined = "${project.projectName} ${project.summary} ${project.recentDecisions} ${project.openTasks}"
        if (hasOptOutDirective(combined) || containsSensitivePersonalData(combined)) return false
        return true
    }

    fun isConversationSummaryEligibleForCloudUpload(
        summary: ConversationSummaryMemory,
        authenticatedUserId: String
    ): Boolean {
        if (authenticatedUserId.isBlank() || summary.userId != authenticatedUserId) return false
        if (summary.conversationId.isBlank() || summary.summaryText.isBlank()) return false
        val combined = "${summary.summaryText} ${summary.keyTopics}"
        if (hasOptOutDirective(combined) || containsSensitivePersonalData(combined)) return false
        return true
    }

    /**
     * Deterministic conflict resolution for `user_memory` records sharing the same `(userId, category, memoryKey)`.
     * Primary rule: Newer `updatedAtEpochMs` wins.
     * Tie-breaker when `updatedAtEpochMs` are equal:
     * 1. Prefer `EXPLICIT_USER` over other source types.
     * 2. Prefer higher confidence score.
     * 3. Deterministic lexicographical comparison on content, preserving earliest `createdAtEpochMs`.
     */
    fun resolveMemoryConflict(
        local: UserMemoryItem,
        remote: UserMemoryItem
    ): UserMemoryItem {
        val earliestCreatedAt = minOf(local.createdAtEpochMs, remote.createdAtEpochMs)
        val winner = when {
            local.updatedAtEpochMs > remote.updatedAtEpochMs -> local
            remote.updatedAtEpochMs > local.updatedAtEpochMs -> remote
            else -> {
                val localRank = sourceTypePriority(local.sourceType)
                val remoteRank = sourceTypePriority(remote.sourceType)
                when {
                    localRank > remoteRank -> local
                    remoteRank > localRank -> remote
                    local.confidence > remote.confidence -> local
                    remote.confidence > local.confidence -> remote
                    local.content >= remote.content -> local
                    else -> remote
                }
            }
        }
        return winner.copy(
            createdAtEpochMs = earliestCreatedAt,
            conversationId = winner.conversationId ?: local.conversationId ?: remote.conversationId
        )
    }

    /**
     * Deterministic conflict resolution for `project_context` records sharing `(userId, projectName)`.
     * Newer `updatedAtEpochMs` wins for summary/decisions/openTasks, while distinct `techStack` keywords
     * are deterministically merged so stack knowledge is never lost.
     */
    fun resolveProjectContextConflict(
        local: ProjectContextMemory,
        remote: ProjectContextMemory
    ): ProjectContextMemory {
        val winner = when {
            local.updatedAtEpochMs > remote.updatedAtEpochMs -> local
            remote.updatedAtEpochMs > local.updatedAtEpochMs -> remote
            local.summary.length >= remote.summary.length -> local
            else -> remote
        }
        val loser = if (winner === local) remote else local
        val mergedStack = listOf(winner.techStack, loser.techStack)
            .flatMap { it.split(",").map { token -> token.trim() } }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .joinToString(", ")

        return winner.copy(
            techStack = mergedStack.ifBlank { winner.techStack },
            openTasks = winner.openTasks.ifBlank { loser.openTasks },
            lastConversationId = winner.lastConversationId ?: loser.lastConversationId
        )
    }

    /**
     * Deterministic conflict resolution for `conversation_summaries` sharing `(userId, conversationId)`.
     */
    fun resolveConversationSummaryConflict(
        local: ConversationSummaryMemory,
        remote: ConversationSummaryMemory
    ): ConversationSummaryMemory {
        val winner = when {
            local.updatedAtEpochMs > remote.updatedAtEpochMs -> local
            remote.updatedAtEpochMs > local.updatedAtEpochMs -> remote
            local.summaryText.length >= remote.summaryText.length -> local
            else -> remote
        }
        val loser = if (winner === local) remote else local
        val mergedTopics = listOf(winner.keyTopics, loser.keyTopics)
            .flatMap { it.split(",").map { token -> token.trim() } }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .joinToString(", ")
        return winner.copy(
            keyTopics = mergedTopics.ifBlank { winner.keyTopics }
        )
    }

    /**
     * Builds or updates a structured conversation summary for `conversation_summaries`.
     */
    fun buildConversationSummaryFromTurn(
        userId: String,
        conversationId: String,
        userPrompt: String,
        assistantReply: String,
        existingSummary: ConversationSummaryMemory? = null,
        nowEpochMs: Long = System.currentTimeMillis()
    ): ConversationSummaryMemory? {
        val cleanPrompt = userPrompt.trim()
        if (cleanPrompt.isEmpty() || hasOptOutDirective(cleanPrompt) || containsSensitivePersonalData(cleanPrompt)) {
            return null
        }
        val topics = tokenizeForRelevance("$cleanPrompt ${extractTechStackKeywords("$cleanPrompt $assistantReply")}")
            .take(8)
            .joinToString(", ")
        val turnDigest = cleanPrompt.lines().firstOrNull()?.take(140).orEmpty()
        val combinedSummary = if (existingSummary != null && existingSummary.summaryText.isNotBlank()) {
            if (existingSummary.summaryText.contains(turnDigest, ignoreCase = true)) {
                existingSummary.summaryText
            } else {
                "${existingSummary.summaryText.take(220)} | $turnDigest"
            }
        } else {
            turnDigest
        }
        val mergedTopics = listOfNotNull(existingSummary?.keyTopics, topics)
            .flatMap { it.split(",").map { t -> t.trim() } }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(10)
            .joinToString(", ")

        return ConversationSummaryMemory(
            id = existingSummary?.id ?: UUID.randomUUID().toString(),
            userId = userId,
            conversationId = conversationId,
            summaryText = combinedSummary,
            keyTopics = mergedTopics,
            updatedAtEpochMs = nowEpochMs
        )
    }

    private fun sourceTypePriority(sourceType: MemorySourceType): Int {
        return when (sourceType) {
            MemorySourceType.EXPLICIT_USER -> 4
            MemorySourceType.EXTERNAL_SOURCE -> 3
            MemorySourceType.PREVIOUS_CONVERSATION -> 2
            MemorySourceType.INFERRED_CONTEXT -> 1
        }
    }

    private fun normalizeKey(raw: String): String {
        return raw.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }
}

data class ExtractedIntelligenceResult(
    val memoriesToUpsert: List<UserMemoryItem> = emptyList(),
    val projectsToUpsert: List<ProjectContextMemory> = emptyList(),
    val topicsToForget: List<String> = emptyList()
)
