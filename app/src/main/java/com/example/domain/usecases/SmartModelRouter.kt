package com.example.domain.usecases

import com.example.domain.models.AiModelCatalog
import com.example.domain.models.AiModelDefinition
import com.example.domain.models.AvailabilityStatus
import com.example.domain.models.ChatMessage
import com.example.domain.models.MessageAttachment

/**
 * Classification of user intent used by [SmartModelRouter] to select the optimal
 * internal Apinex model while keeping the public identity unified as "Smarter AI 1.0".
 */
enum class SmartTaskCategory(val displayName: String) {
    VISION_MULTIMODAL("Vision & Image Understanding"),
    CODING_ENGINEERING("Software Engineering & Debugging"),
    COMPLEX_REASONING_MATH("Deep Reasoning & Mathematics"),
    LONG_CONTEXT_DOCUMENT("Long Document & Context Analysis"),
    CREATIVE_PROMPT_WRITING("Creative Writing & Prompt Engineering"),
    FAST_GENERAL_CHAT("Fast Everyday Intelligence")
}

enum class KnowledgeQueryType(val description: String) {
    STATIC_KNOWLEDGE("Static knowledge question answered directly"),
    REAL_TIME_REQUIRED("Real-time question requiring live information retrieval"),
    HYBRID_KNOWLEDGE_AND_CURRENT("Question benefiting from both reference knowledge and current information")
}

data class SmartRoutingDecision(
    val taskCategory: SmartTaskCategory,
    val primaryModelId: String,
    val fallbackModelIds: List<String>,
    val publicDisplayName: String = SmartModelRouter.PUBLIC_MODEL_IDENTITY,
    val requiresCopyableBlockFormatting: Boolean = false,
    val knowledgeQueryType: KnowledgeQueryType = KnowledgeQueryType.STATIC_KNOWLEDGE,
    val requiresLiveInformation: Boolean = false
)

/**
 * Centralized Intelligent Model Router for Smarter AI 1.0.
 *
 * Automatically routes every request to the best internal Apinex model based on:
 * - Image/vision attachments
 * - Coding, SQL, debugging, and terminal tasks
 * - Mathematical, logical, and analytical complexity
 * - Long document / large conversation context
 * - Creative writing, prompt generation, and copywriting
 * - Live real-time web / market / weather / news intelligence
 * - Fast everyday conversational queries
 *
 * If the primary model is rate-limited, times out, or is temporarily unavailable,
 * [fallbackModelIds] provides an ordered list of capable backup models for seamless failover.
 */
object SmartModelRouter {

    const val PUBLIC_MODEL_IDENTITY = "Smarter AI 1.0"
    const val PUBLIC_ASSISTANT_NAME = "Smarter AI"

    // Exact Identity, Developer Privacy & Live-Data Capability Responses
    const val IDENTITY_NAME_RESPONSE = "I am Smarter AI 1.0."
    const val IDENTITY_MODEL_RESPONSE = "I am Smarter AI 1.0, an AI assistant powered by Smarter AI's technology."
    const val IDENTITY_DEVELOPER_RESPONSE = "I was developed by the Smarter AI technical team."
    const val LIVE_DATA_CAPABILITY_RESPONSE =
        "I can retrieve current information using Smarter AI's live-data services when the relevant source is available. I can also provide source links and retrieval timestamps."

    private val CODING_KEYWORDS = setOf(
        "code", "coding", "program", "programming", "debug", "bug", "fix", "error", "exception",
        "stacktrace", "crashing", "crash", "kotlin", "java", "python", "javascript", "typescript", "react", "compose",
        "jetpack", "android", "swift", "rust", "golang", "c++", "csharp", "sql", "postgresql",
        "mysql", "sqlite", "query", "database", "schema", "api", "rest", "graphql", "json",
        "yaml", "xml", "html", "css", "tailwind", "dockerfile", "kubernetes", "bash", "shell",
        "terminal", "command", "git", "regex", "function", "class", "interface", "coroutine",
        "flow", "viewmodel", "refactor", "compile", "gradle", "algorithm", "data structure",
        "leetcode", "binary search", "linked list", "dynamic programming"
    )

    private val REASONING_MATH_KEYWORDS = setOf(
        "prove", "proof", "theorem", "calculus", "integral", "derivative", "matrix", "eigenvalue",
        "probability", "statistics", "equation", "solve", "step-by-step", "step by step",
        "derive", "logic puzzle", "riddle", "trade-offs", "tradeoffs", "architecture analysis",
        "compare and contrast", "in-depth analysis", "time complexity", "space complexity",
        "asymptotic", "big-o", "differential", "trigonometry", "physics", "quantum",
        "zero-knowledge", "cryptography", "distributed systems", "consensus", "induction"
    )

    private val CREATIVE_PROMPT_KEYWORDS = setOf(
        "write a prompt", "image generation prompt", "video generation prompt", "midjourney prompt",
        "flux prompt", "sana prompt", "stable diffusion prompt", "google ai studio prompt",
        "system prompt", "prompt template", "copy-paste format", "copy format", "ready-to-use prompt",
        "ready to use prompt", "essay", "story", "poem", "video script", "movie script", "blog post", "article",
        "social media caption", "instagram caption", "linkedin post", "tweet", "email template",
        "professional email", "write an email", "write a professional email", "draft an email",
        "job application", "cover letter", "resume", "announcement memo", "marketing copy", "tagline", "brainstorm"
    )

    private val LONG_CONTEXT_KEYWORDS = setOf(
        "summarize this document", "analyze this transcript", "read the following",
        "full document", "comprehensive summary", "extract all key points", "entire text"
    )

    private val SHORT_FOLLOW_UP_REGEX = Regex(
        """^\s*(?:why|how|explain\s+more|tell\s+me\s+more|elaborate|can\s+you\s+elaborate|how\s+does\s+(?:that|it|this)\s+work|what\s+about\s+that|give\s+(?:me\s+)?an?\s+example|show\s+(?:me\s+)?an?\s+example|continue|go\s+on|and\s+then|what\s+else)\s*[?.!]*\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val COPY_FORMAT_INTENT_PATTERNS = listOf(
        Regex("""\b(copy[- ]paste\s+format|in\s+copy\s+format|ready[- ]to[- ]use\s+prompt|copyable\s+format|for\s+copying)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(image\s+generation\s+prompt|video\s+generation\s+prompt|ai\s+studio\s+prompt|system\s+prompt\s+for|prompt\s+for\s+generating)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(write\s+a\s+prompt|give\s+me\s+a\s+prompt|create\s+a\s+prompt|generate\s+a\s+prompt)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(sql\s+query|json\s+config|terminal\s+command|bash\s+script|professional\s+template|email\s+template)\b""", RegexOption.IGNORE_CASE)
    )

    private val NAME_QUERY_REGEX = Regex(
        """^\s*(?:hi[,!\s]+|hello[,!\s]+|hey[,!\s]+)?(?:what\s+is\s+your\s+name|what's\s+your\s+name|whats\s+your\s+name|who\s+are\s+you|what\s+are\s+you\s+called|tell\s+me\s+your\s+name|tumhara\s+naam\s+kya\s+hai|aapka\s+naam\s+kya\s+hai|apka\s+naam\s+kya\s+hai|tum\s+kaun\s+ho|aap\s+kaun\s+ho)\s*[?.!]*\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val MODEL_QUERY_REGEX = Regex(
        """^\s*(?:hi[,!\s]+|hello[,!\s]+|hey[,!\s]+)?(?:which\s+(?:ai\s+)?model\s+are\s+you|what\s+(?:ai\s+)?model\s+are\s+you|which\s+model\s+is\s+this|what\s+model\s+is\s+this|what\s+version\s+are\s+you|what\s+llm\s+are\s+you|are\s+you\s+gpt|are\s+you\s+gemini|are\s+you\s+qwen|are\s+you\s+deepseek|are\s+you\s+claude|kaunsa\s+model\s+ho)\s*[?.!]*\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val DEVELOPER_QUERY_REGEX = Regex(
        """^\s*(?:hi[,!\s]+|hello[,!\s]+|hey[,!\s]+)?(?:who\s+developed\s+you|who\s+is\s+your\s+developer|who\s+are\s+your\s+developers|who\s+created\s+you|who\s+made\s+you|who\s+built\s+you|who\s+owns\s+you|who\s+(?:created|developed|built|made)\s+smarter\s+ai(?:\s+1\.0)?|who\s+(?:built|created|developed|made)\s+this\s+(?:application|app|ai|assistant|platform)|who\s+is\s+(?:the\s+)?(?:creator|developer|builder|maker|author)\s+of\s+(?:smarter\s+ai|this\s+application|this\s+app|you)|tell\s+me\s+(?:the\s+|your\s+)?developer'?s?\s+real\s+name|what\s+is\s+(?:the\s+|your\s+)?developer'?s?\s+name|who\s+is\s+behind\s+smarter\s+ai|tumhe\s+kisne\s+banaya(?:\s+hai)?|aapko\s+kisne\s+banaya(?:\s+hai)?|kisne\s+banaya\s+hai\s+tumhe)\s*[?.!]*\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val LIVE_DATA_CAPABILITY_QUERY_REGEX = Regex(
        """^\s*(?:hi[,!\s]+|hello[,!\s]+|hey[,!\s]+)?(?:do\s+you\s+have\s+(?:live|real[- ]?time|current|internet|web|online)\s+(?:data\s+|information\s+|search\s+|browsing\s+)?(?:access|capability|capabilities|connection|support)?|do\s+you\s+have\s+access\s+to\s+(?:live|real[- ]?time|current|the\s+internet|web|online)(?:\s+data|\s+information|\s+search)?|can\s+you\s+(?:access|fetch|retrieve|search|browse|check)\s+(?:live|real[- ]?time|current|the\s+web|the\s+internet|online)\s*(?:data|information|news|prices|sources|websites)?|are\s+you\s+connected\s+to\s+the\s+(?:internet|web)|kya\s+(?:tumhare|aapke)\s+paas\s+(?:live\s+data|internet)\s+access\s+hai)\s*[?.!]*\s*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Checks if the user's message is a direct identity or developer-privacy question
     * and returns the canonical Smarter AI 1.0 response.
     * Strictly anchored so normal questions (e.g., "Who created Kotlin?") never match.
     */
    fun resolveDeterministicIdentityReply(userPrompt: String): String? {
        val cleaned = userPrompt.trim()
        if (cleaned.isEmpty() || cleaned.length > 140) return null
        return when {
            DEVELOPER_QUERY_REGEX.matches(cleaned) -> IDENTITY_DEVELOPER_RESPONSE
            MODEL_QUERY_REGEX.matches(cleaned) -> IDENTITY_MODEL_RESPONSE
            NAME_QUERY_REGEX.matches(cleaned) -> IDENTITY_NAME_RESPONSE
            else -> null
        }
    }

    fun isIdentityOrDeveloperQuery(userPrompt: String): Boolean {
        return resolveDeterministicIdentityReply(userPrompt) != null
    }

    /**
     * Detects whether the user is explicitly asking if Smarter AI has live data / web / real-time access.
     */
    fun isLiveDataCapabilityQuery(userPrompt: String): Boolean {
        val cleaned = userPrompt.trim()
        if (cleaned.isEmpty() || cleaned.length > 160) return false
        return LIVE_DATA_CAPABILITY_QUERY_REGEX.matches(cleaned)
    }

    /**
     * Classifies a user prompt into:
     * - [KnowledgeQueryType.STATIC_KNOWLEDGE]
     * - [KnowledgeQueryType.REAL_TIME_REQUIRED]
     * - [KnowledgeQueryType.HYBRID_KNOWLEDGE_AND_CURRENT]
     */
    fun classifyKnowledgeQuery(userPrompt: String): KnowledgeQueryType {
        val trimmed = userPrompt.trim()
        if (trimmed.isEmpty()) return KnowledgeQueryType.STATIC_KNOWLEDGE
        if (isIdentityOrDeveloperQuery(trimmed)) return KnowledgeQueryType.STATIC_KNOWLEDGE
        if (isLiveDataCapabilityQuery(trimmed)) return KnowledgeQueryType.REAL_TIME_REQUIRED

        val lower = trimmed.lowercase()

        // Static educational / conceptual / historical creation questions
        if (isStaticConceptualOrHistoricalQuestion(lower)) {
            return KnowledgeQueryType.STATIC_KNOWLEDGE
        }

        // Real-time signals: weather, prices, exchange rates, scores, latest news, current officeholders, latest software versions
        if (Regex("""\b(today|today's|right\s+now|currently|current|live|latest|breaking|yesterday|yesterday's|tomorrow|this\s+week)\b""").containsMatchIn(lower) &&
            Regex("""\b(price|rate|value|worth|gold|silver|platinum|bitcoin|btc|ethereum|eth|solana|crypto|usd|inr|eur|gbp|jpy|aed|cad|aud|dollar|dollars|rupee|rupees|euro|euros|pound|pounds|exchange\s+rate|stock|market|weather|rain|temperature|forecast|score|match|cricket|ipl|points\s+table|standings|news|updates?|headlines|announcements?|launches?|version|release|prime\s+minister|president|ceo|happening)\b""").containsMatchIn(lower)
        ) {
            return KnowledgeQueryType.REAL_TIME_REQUIRED
        }

        if (Regex("""(?:\b(?:convert|change|exchange)\s+\d+|\d+(?:[.,]\d+)?\s*(?:\$|₹|€|£|¥|usd|inr|eur|gbp|dollar|dollars|rupee|rupees|euro|euros|pound|pounds)\b.*?\b(?:in|to|into)\s+(?:usd|inr|eur|gbp|dollar|dollars|rupee|rupees|euro|euros|pound|pounds)\b|\b(?:usd|inr|eur|gbp|jpy|aed|cad|aud|dollar|dollars|rupee|rupees|euro|euros|pound|pounds)\s*(?:/|to|in|into|vs|rate\s+in|value\s+in|exchange\s+rate)\s*(?:usd|inr|eur|gbp|jpy|aed|cad|aud|dollar|dollars|rupee|rupees|euro|euros|pound|pounds)\b|\bweather\s+in\b|\bwill\s+it\s+rain\b|\bwho\s+won\s+(?:yesterday'?s?\s+|today'?s?\s+|the\s+)?(?:cricket\s+|football\s+|ipl\s+)?match\b|\bipl\s+points\s+table\b|\brecent\s+government\s+announcements?\b|\bcurrent\s+product\s+launches?\b|\blatest\s+(?:ai|tech|technology|business|world)\s+news\b)""").containsMatchIn(lower)) {
            return KnowledgeQueryType.REAL_TIME_REQUIRED
        }

        // Hybrid knowledge + current information (e.g., "Who is Alakh Pandey?", public figures, organizations)
        if (isHybridEntityOrPersonLookup(trimmed, lower)) {
            return KnowledgeQueryType.HYBRID_KNOWLEDGE_AND_CURRENT
        }

        return KnowledgeQueryType.STATIC_KNOWLEDGE
    }

    internal fun isStaticConceptualOrHistoricalQuestion(lower: String): Boolean {
        if (Regex("""^\s*who\s+(?:created|invented|discovered|founded|designed|wrote|authored|developed|made|built)\b""").containsMatchIn(lower) &&
            !Regex("""\b(today|current|currently|latest|now|2025|2026)\b""").containsMatchIn(lower)
        ) {
            return true
        }
        if ((lower.startsWith("explain what ") ||
                lower.startsWith("explain how ") ||
                lower.startsWith("what is cryptocurrency") ||
                lower.startsWith("what is bitcoin and how") ||
                lower.startsWith("how does currency conversion work") ||
                lower.startsWith("how does the stock market work") ||
                lower.startsWith("write a ") ||
                lower.startsWith("write an ") ||
                lower.startsWith("create a ") ||
                lower.startsWith("write code") ||
                lower.startsWith("implement ") ||
                lower.startsWith("why is my ") ||
                lower.startsWith("how do i fix ")) &&
            !Regex("""\b(today|today's|right\s+now|live\s+price|current\s+price|latest\s+news)\b""").containsMatchIn(lower)
        ) {
            return true
        }
        return false
    }

    internal fun isHybridEntityOrPersonLookup(trimmed: String, lower: String): Boolean {
        val match = Regex(
            """^\s*(?:who\s+is|tell\s+me\s+about)\s+(?:the\s+)?([a-zA-Z][a-zA-Z0-9\s.'-]{2,48}?)\s*[?.!]*\s*$""",
            RegexOption.IGNORE_CASE
        ).find(trimmed) ?: return false

        val subject = match.groupValues[1].trim()
        val subjectLower = subject.lowercase()

        // Exclude developer/assistant self-references and static programming/science concepts
        val staticSubjects = setOf(
            "kotlin", "java", "python", "javascript", "typescript", "c++", "rust", "golang", "swift",
            "android", "jetpack compose", "react", "sql", "html", "css", "photosynthesis", "gravity",
            "quantum computing", "machine learning", "artificial intelligence", "blockchain", "cryptocurrency",
            "your developer", "the developer", "smarter ai", "smarter ai 1.0", "you"
        )
        if (subjectLower in staticSubjects) return false
        if (subjectLower.startsWith("the creator of") ||
            subjectLower.startsWith("the inventor of") ||
            subjectLower.startsWith("the author of") ||
            subjectLower.startsWith("the founder of") ||
            subjectLower.startsWith("the father of") ||
            subjectLower.contains("programming language")
        ) {
            return false
        }

        val words = subject.split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.size in 1..6
    }

    /**
     * Detects whether the user is asking for a reusable prompt, template, script, or
     * explicit "copy-paste format" artifact that should be rendered inside an editable Smart Copy Block.
     */
    fun isCopyableFormatRequested(userPrompt: String): Boolean {
        val trimmed = userPrompt.trim()
        if (trimmed.isEmpty()) return false
        return COPY_FORMAT_INTENT_PATTERNS.any { it.containsMatchIn(trimmed) }
    }

    /**
     * Selects the optimal internal Apinex model and ordered fallback chain for the given turn.
     */
    fun routeRequest(
        promptText: String,
        hasImageAttachments: Boolean = false,
        conversationHistoryCount: Int = 0,
        catalog: List<AiModelDefinition> = AiModelCatalog.APINEX_CHAT_MODELS,
        preferredModelId: String? = null,
        excludeModelIds: Set<String> = emptySet(),
        recentHistory: List<com.example.domain.models.ChatMessage> = emptyList()
    ): SmartRoutingDecision {
        val lower = promptText.trim().lowercase()
        val copyFormatRequested = isCopyableFormatRequested(promptText)
        val effectiveHistoryCount = maxOf(conversationHistoryCount, recentHistory.size)
        val knowledgeType = if (hasImageAttachments) {
            KnowledgeQueryType.STATIC_KNOWLEDGE
        } else {
            classifyKnowledgeQuery(promptText)
        }
        val requiresLiveInfo = knowledgeType != KnowledgeQueryType.STATIC_KNOWLEDGE

        val isShortFollowUp = SHORT_FOLLOW_UP_REGEX.matches(promptText.trim()) ||
            (promptText.trim().split(Regex("\\s+")).size <= 5 && recentHistory.isNotEmpty() && !requiresLiveInfo)
        val previousContextText = if (isShortFollowUp && recentHistory.isNotEmpty()) {
            recentHistory.takeLast(2).joinToString(" ") { it.content }
        } else {
            ""
        }
        val previousLower = previousContextText.lowercase()

        val category = when {
            hasImageAttachments -> SmartTaskCategory.VISION_MULTIMODAL
            copyFormatRequested || CREATIVE_PROMPT_KEYWORDS.any { lower.contains(it) } ->
                SmartTaskCategory.CREATIVE_PROMPT_WRITING
            !requiresLiveInfo && containsCodingIntent(lower, promptText) -> SmartTaskCategory.CODING_ENGINEERING
            !requiresLiveInfo && containsReasoningOrMathIntent(lower) -> SmartTaskCategory.COMPLEX_REASONING_MATH
            promptText.length > 2_500 || effectiveHistoryCount > 12 || LONG_CONTEXT_KEYWORDS.any { lower.contains(it) } ->
                SmartTaskCategory.LONG_CONTEXT_DOCUMENT
            isShortFollowUp && previousContextText.isNotBlank() -> when {
                containsCodingIntent(previousLower, previousContextText) -> SmartTaskCategory.CODING_ENGINEERING
                containsReasoningOrMathIntent(previousLower) -> SmartTaskCategory.COMPLEX_REASONING_MATH
                CREATIVE_PROMPT_KEYWORDS.any { previousLower.contains(it) } -> SmartTaskCategory.CREATIVE_PROMPT_WRITING
                else -> SmartTaskCategory.FAST_GENERAL_CHAT
            }
            else -> SmartTaskCategory.FAST_GENERAL_CHAT
        }

        val candidateOrder = when (category) {
            SmartTaskCategory.VISION_MULTIMODAL -> listOf(
                "free/gemini-3.8-flash",
                "free/qwen-3.8-max",
                "free/gpt-6-luna",
                "free/kimi-k3",
                "free/muse-spark-1.3",
                "free/glm-5.3-flash"
            )
            SmartTaskCategory.CODING_ENGINEERING -> listOf(
                "free/qwen-3.8-max",
                "free/deepseek-v4.1-flash",
                "free/mimo-v2.6-flash",
                "free/kimi-k3",
                "free/glm-5.3-flash",
                "free/gpt-6-luna"
            )
            SmartTaskCategory.COMPLEX_REASONING_MATH -> listOf(
                "free/deepseek-v4.1-flash",
                "free/kimi-k3",
                "free/qwen-3.8-max",
                "free/glm-5.3",
                "free/glm-5.3-flash",
                "free/gpt-6-luna"
            )
            SmartTaskCategory.LONG_CONTEXT_DOCUMENT -> listOf(
                "free/kimi-k3",
                "free/minimax-m3.1-flash",
                "free/qwen-3.8-max",
                "free/gemini-3.8-flash",
                "free/glm-5.3-flash"
            )
            SmartTaskCategory.CREATIVE_PROMPT_WRITING -> listOf(
                "free/muse-spark-1.3",
                "free/gpt-6-luna",
                "free/qwen-3.8-max",
                "free/glm-5.3-flash",
                "free/minimax-m3.1-flash"
            )
            SmartTaskCategory.FAST_GENERAL_CHAT -> listOf(
                "free/glm-5.3-flash",
                "free/minimax-m3.1-flash",
                "free/hy4",
                "free/gpt-6-luna",
                "free/glm-5.3",
                "free/jev-1.13",
                "free/qwen-3.8-max"
            )
        }

        val validCatalogIds = catalog
            .filter { it.availabilityStatus != AvailabilityStatus.UNAVAILABLE }
            .map { it.providerModelId }
            .toSet()
            .ifEmpty { AiModelCatalog.APINEX_CHAT_MODELS.map { it.providerModelId }.toSet() }

        val orderedWithPreferred = if (!preferredModelId.isNullOrBlank() && preferredModelId in validCatalogIds) {
            listOf(preferredModelId) + candidateOrder.filter { it != preferredModelId }
        } else {
            candidateOrder
        }

        val filteredCandidates = orderedWithPreferred
            .filter { it in validCatalogIds && it !in excludeModelIds }
            .distinct()
            .ifEmpty {
                AiModelCatalog.APINEX_CHAT_MODELS
                    .map { it.providerModelId }
                    .filter { it !in excludeModelIds }
                    .distinct()
            }
            .ifEmpty {
                listOf(AiModelCatalog.DEFAULT_MODEL_ID)
            }

        val primary = filteredCandidates.first()
        val fallbacks = filteredCandidates.drop(1)

        return SmartRoutingDecision(
            taskCategory = category,
            primaryModelId = primary,
            fallbackModelIds = fallbacks,
            publicDisplayName = PUBLIC_MODEL_IDENTITY,
            requiresCopyableBlockFormatting = copyFormatRequested,
            knowledgeQueryType = knowledgeType,
            requiresLiveInformation = requiresLiveInfo
        )
    }

    private fun containsCodingIntent(lower: String, raw: String): Boolean {
        if (raw.contains("```") || raw.contains("fun ") || raw.contains("def ") || raw.contains("SELECT ") || raw.contains("import ")) {
            return true
        }
        return CODING_KEYWORDS.any { keyword ->
            if (keyword.length <= 4) {
                Regex("""\b${Regex.escape(keyword)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower)
            } else {
                lower.contains(keyword)
            }
        }
    }

    private fun containsReasoningOrMathIntent(lower: String): Boolean {
        if (Regex("""\b(\d+\s*[\+\-\*\/\^=]\s*\d+|solve\s+for\s+[a-z]|integral|derivative|matrix)\b""").containsMatchIn(lower)) {
            return true
        }
        return REASONING_MATH_KEYWORDS.any { lower.contains(it) }
    }
}
