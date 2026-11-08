package com.example.domain.usecases

import com.example.domain.models.ImageAspectRatio

data class ChatImageGenerationParams(
    val originalUserMessage: String,
    val extractedPrompt: String,
    val modelId: String = "sana",
    val width: Int = 1024,
    val height: Int = 1024,
    val aspectRatioLabel: String = "1:1 (1024×1024)",
    val enhance: Boolean = true
)

sealed class ChatRequestRoute {
    data object NormalChat : ChatRequestRoute()
    data object ImageAnalysis : ChatRequestRoute()
    data class ImageGeneration(val params: ChatImageGenerationParams) : ChatRequestRoute()
    data class AmbiguousImageIntent(
        val originalPrompt: String,
        val clarificationResponse: String
    ) : ChatRequestRoute()
}

/**
 * Intelligent request router and natural-language image-generation intent detector.
 * Distinguishes cleanly between:
 * 1. Normal text/code/explanation/prompt-writing requests -> Apinex Chat Gateway
 * 2. Multimodal image analysis requests (when image attachments are present) -> Apinex Vision Model
 * 3. Direct image generation requests in English, Hindi, or Hinglish -> Pollinations Image API
 * 4. Genuinely ambiguous visual requests -> Short clarification response
 */
object ImageGenerationIntentDetector {

    private val RESOLUTION_REGEX = Regex(
        """\b(\d{3,4})\s*[xX×*]\s*(\d{3,4})(?:\s*(?:px|pixels|resolution|res))?\b""",
        RegexOption.IGNORE_CASE
    )

    // Exclusions: questions about how image generation works, prompt writing, code/script writing, or image analysis
    private val EDUCATIONAL_OR_META_EXCLUSION_REGEX = Regex(
        """^\s*(?:explain|what\s+is|what\s+are|how\s+does|how\s+do|how\s+to\s+(?:use|build|code|implement|train)|why\s+does|why\s+do|tell\s+me\s+(?:about|how)|describe\s+how|discuss|compare)\b.*""",
        RegexOption.IGNORE_CASE
    )

    private val IMAGE_ANALYSIS_EXCLUSION_REGEX = Regex(
        """\b(?:analyze\s+this\s+image|analyse\s+this\s+image|what\s+is\s+(?:in|visible\s+in|shown\s+in)\s+this\s+(?:image|photo|picture)|explain\s+this\s+(?:image|photo|picture|diagram)|describe\s+this\s+(?:image|photo|picture)|read\s+(?:the\s+)?text\s+in\s+this\s+image|is\s+photo\s+mein\s+kya\s+hai|is\s+image\s+ko\s+explain\s+karo)\b""",
        RegexOption.IGNORE_CASE
    )

    private val PROMPT_ONLY_EXCLUSION_REGEX = Regex(
        """\b(?:write|give\s+me|create|suggest|generate)\s+(?:a\s+|an\s+|some\s+|5\s+|3\s+)?(?:midjourney\s+|stable\s+diffusion\s+|dall-?e\s+|ai\s+)?(?:image\s+|art\s+|photo\s+)?prompts?\b""",
        RegexOption.IGNORE_CASE
    )

    private val CODE_OR_TEXT_TASK_EXCLUSION_REGEX = Regex(
        """\b(?:python|javascript|typescript|kotlin|java|html|css|sql|bash|powershell|script|code|function|class|command|regex|caption|essay|article|blog|email|story|poem|tweet|linkedin\s+post|instagram\s+caption)\b""",
        RegexOption.IGNORE_CASE
    )

    // Explicit English image generation patterns (supports subject words before the visual noun, e.g. "Create a Diwali poster", "Generate an image of a futuristic city")
    private val ENGLISH_EXPLICIT_IMAGE_GEN_REGEX = Regex(
        """^\s*(?:please\s+|can\s+you\s+|could\s+you\s+|hey\s+smarter\s+ai\s+|smarter\s+ai\s+)?(?:generate|create|draw|paint|render|design|make|produce|sketch|illustrate)\s+(?:me\s+)?(?:a\s+|an\s+|the\s+)?(?:[\w\-]+\s+){0,5}(?:image|picture|photo|photograph|poster|artwork|illustration|drawing|painting|wallpaper|logo|banner|portrait|landscape|product\s+advertisement|advertisement\s+poster|ad\s+poster|product\s+ad|advertisement|concept\s+art|avatar|icon|graphic|scene)\b.*""",
        RegexOption.IGNORE_CASE
    )

    // Direct visual creation verbs even when the noun is the subject itself (e.g. "Draw a beautiful landscape", "Paint a sunset over mountains")
    private val DIRECT_DRAW_PAINT_VERB_REGEX = Regex(
        """^\s*(?:please\s+)?(?:draw|paint|sketch|illustrate|render)\s+(?:a\s+|an\s+|the\s+|me\s+a\s+|me\s+an\s+)(?!table\b|conclusion\b|comparison\b|chart\b|graph\b)(.{3,})""",
        RegexOption.IGNORE_CASE
    )

    // Explicit Hindi (Devanagari) & Hinglish (Roman) image generation patterns
    // e.g., "Bhai ek realistic car image banao", "ek sundar pahad ki photo banao", "Diwali poster bana do", "image generate karo"
    private val HINGLISH_IMAGE_GEN_REGEX = Regex(
        """\b(?:(?:image|photo|picture|pic|poster|wallpaper|logo|banner|tasveer|tasvir|chitra|painting|sketch|drawing|portrait|advertisement)\s+(?:banao|bana\s+do|banaiye|bana\s+de|generate\s+karo|generate\s+kar\s+do|create\s+karo|dikhao)|(?:banao|bana\s+do|banaiye|generate\s+karo|create\s+karo)\s+(?:ek\s+)?(?:realistic\s+|acha\s+|sundar\s+|beautiful\s+|hd\s+)*(?:image|photo|picture|pic|poster|wallpaper|logo|banner|tasveer|tasvir|chitra))\b""",
        RegexOption.IGNORE_CASE
    )

    private val HINGLISH_SUBJECT_IMAGE_BANAO_REGEX = Regex(
        """\b(?:bhai|yaar|please|plz|smarter\s+ai)?\s*(?:mujhe\s+|mere\s+liye\s+)?(?:ek\s+)?(?:[\w\s,\-]+?)\s+(?:image|photo|picture|pic|poster|wallpaper|logo|tasveer|tasvir|painting|sketch)\s+(?:banao|bana\s+do|banaiye|bana\s+de|generate\s+karo|create\s+karo)\b""",
        RegexOption.IGNORE_CASE
    )

    private val DEVANAGARI_IMAGE_GEN_REGEX = Regex(
        """(?:तस्वीर|फोटो|फ़ोटो|इमेज|पोस्टर|चित्र|वॉलपेपर|लोगो|पेंटिंग)\s*(?:बनाओ|बनाइए|बना\s*दो|जनरेट\s*करो|दिखाओ)|(?:बनाओ|बनाइए|बना\s*दो|जनरेट\s*करो)\s*(?:एक\s*)?(?:तस्वीर|फोटो|फ़ोटो|इमेज|पोस्टर|चित्र|वॉलपेपर)"""
    )

    // Ambiguous visual requests where user hasn't specified whether they want an image generated or text/advice
    private val AMBIGUOUS_IMAGE_REQUEST_REGEX = Regex(
        """^\s*(?:can\s+you\s+help\s+with|i\s+need\s+help\s+with|help\s+me\s+with|what\s+about)\s+(?:a\s+|an\s+)?(?:[\w\s]{2,30})\s+(?:image|poster|photo|picture|banner)\s*\??\s*$""",
        RegexOption.IGNORE_CASE
    )

    fun detectRoute(
        promptText: String,
        hasImageAttachments: Boolean = false
    ): ChatRequestRoute {
        val trimmed = promptText.trim()
        if (hasImageAttachments) {
            return ChatRequestRoute.ImageAnalysis
        }
        if (trimmed.isEmpty()) {
            return ChatRequestRoute.NormalChat
        }

        // 1. Check strict exclusions first (avoid false positives)
        if (IMAGE_ANALYSIS_EXCLUSION_REGEX.containsMatchIn(trimmed)) {
            return ChatRequestRoute.NormalChat
        }
        if (EDUCATIONAL_OR_META_EXCLUSION_REGEX.containsMatchIn(trimmed)) {
            return ChatRequestRoute.NormalChat
        }
        if (PROMPT_ONLY_EXCLUSION_REGEX.containsMatchIn(trimmed)) {
            return ChatRequestRoute.NormalChat
        }
        // If user asks to write code/script/command/caption (e.g. "Write a Python script to crop an image" or "Write an Instagram caption"), route to NormalChat
        if (CODE_OR_TEXT_TASK_EXCLUSION_REGEX.containsMatchIn(trimmed) &&
            !Regex("""\b(?:generate|create)\s+an?\s+image\b""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
        ) {
            return ChatRequestRoute.NormalChat
        }

        // 2. Check for genuinely ambiguous intent
        if (AMBIGUOUS_IMAGE_REQUEST_REGEX.matches(trimmed)) {
            return ChatRequestRoute.AmbiguousImageIntent(
                originalPrompt = trimmed,
                clarificationResponse = "Would you like me to **generate an image** for \"$trimmed\", or would you prefer **text ideas and descriptions**? Reply with **\"Generate an image of ...\"** to create the visual directly."
            )
        }

        // 3. Check explicit image generation patterns (English, Hinglish, Hindi Devanagari)
        val isExplicitEnglish = ENGLISH_EXPLICIT_IMAGE_GEN_REGEX.containsMatchIn(trimmed) ||
            DIRECT_DRAW_PAINT_VERB_REGEX.containsMatchIn(trimmed)
        val isExplicitHinglish = HINGLISH_IMAGE_GEN_REGEX.containsMatchIn(trimmed) ||
            HINGLISH_SUBJECT_IMAGE_BANAO_REGEX.containsMatchIn(trimmed)
        val isExplicitDevanagari = DEVANAGARI_IMAGE_GEN_REGEX.containsMatchIn(trimmed)

        if (isExplicitEnglish || isExplicitHinglish || isExplicitDevanagari) {
            val params = parseImageGenerationParams(trimmed)
            return ChatRequestRoute.ImageGeneration(params)
        }

        return ChatRequestRoute.NormalChat
    }

    /**
     * Extracts dimensions, aspect ratio, Pollinations model, and a clean descriptive prompt
     * from a natural-language chat message.
     */
    fun parseImageGenerationParams(rawMessage: String): ChatImageGenerationParams {
        val trimmed = rawMessage.trim()
        val lower = trimmed.lowercase()

        // 1. Parse explicit WxH resolution (e.g., "1080x1350", "1024x768") or aspect ratio keywords
        val resMatch = RESOLUTION_REGEX.find(trimmed)
        val (width, height, aspectRatioLabel) = if (resMatch != null) {
            val w = resMatch.groupValues[1].toIntOrNull()?.coerceIn(256, 1536) ?: 1024
            val h = resMatch.groupValues[2].toIntOrNull()?.coerceIn(256, 1536) ?: 1024
            Triple(w, h, "${w}×${h}")
        } else {
            when {
                lower.contains("16:9") || lower.contains("widescreen") || lower.contains("youtube thumbnail") ->
                    Triple(
                        ImageAspectRatio.WIDESCREEN_16_9.width,
                        ImageAspectRatio.WIDESCREEN_16_9.height,
                        "${ImageAspectRatio.WIDESCREEN_16_9.label} (${ImageAspectRatio.WIDESCREEN_16_9.width}×${ImageAspectRatio.WIDESCREEN_16_9.height})"
                    )
                lower.contains("9:16") || lower.contains("3:4") || lower.contains("portrait") || lower.contains("vertical") || lower.contains("story") || lower.contains("reel") ->
                    Triple(
                        ImageAspectRatio.PORTRAIT_3_4.width,
                        ImageAspectRatio.PORTRAIT_3_4.height,
                        "${ImageAspectRatio.PORTRAIT_3_4.label} (${ImageAspectRatio.PORTRAIT_3_4.width}×${ImageAspectRatio.PORTRAIT_3_4.height})"
                    )
                else ->
                    Triple(
                        ImageAspectRatio.SQUARE_1_1.width,
                        ImageAspectRatio.SQUARE_1_1.height,
                        "${ImageAspectRatio.SQUARE_1_1.label} (${ImageAspectRatio.SQUARE_1_1.width}×${ImageAspectRatio.SQUARE_1_1.height})"
                    )
            }
        }

        // 2. Select Pollinations model ID based on style cues (defaults to "sana" = Smarter Flux Image)
        val modelId = when {
            lower.contains("concept art") || lower.contains("anime") || lower.contains("cyberpunk") ->
                "community/scriptsnsenses-sys/flux-2-dev-free"
            lower.contains("photorealistic") || lower.contains("realistic") || lower.contains("photograph") || lower.contains("dslr") ->
                "flux"
            else -> "sana"
        }

        // 3. Extract clean visual subject while preserving important descriptive words
        val cleanedSubject = extractVisualPromptSubject(trimmed)

        return ChatImageGenerationParams(
            originalUserMessage = trimmed,
            extractedPrompt = cleanedSubject,
            modelId = modelId,
            width = width,
            height = height,
            aspectRatioLabel = aspectRatioLabel,
            enhance = true
        )
    }

    internal fun extractVisualPromptSubject(rawMessage: String): String {
        var cleaned = rawMessage.trim()

        // Strip explicit resolution phrase like "in 1080x1350 resolution"
        cleaned = Regex(
            """\s*(?:in\s+|with\s+|at\s+)?\d{3,4}\s*[xX×*]\s*\d{3,4}(?:\s*(?:px|pixels|resolution|res))?""",
            RegexOption.IGNORE_CASE
        ).replace(cleaned, "")

        // Strip leading English command prefixes like "Generate an image of ", "Please create a picture of "
        cleaned = Regex(
            """^\s*(?:please\s+|can\s+you\s+|could\s+you\s+|hey\s+smarter\s+ai\s+|smarter\s+ai\s+)?(?:generate|create|draw|paint|render|design|make|produce|sketch|illustrate)\s+(?:me\s+)?(?:a\s+|an\s+|the\s+)?(?:image|picture|photo|photograph)\s+(?:of\s+|showing\s+|depicting\s+|for\s+|with\s+)?""",
            RegexOption.IGNORE_CASE
        ).replace(cleaned, "")

        // If the command was e.g. "Create a Diwali poster" or "Draw a beautiful landscape" or "Generate a product advertisement",
        // strip just the leading verb ("Create ", "Draw ", "Generate ") so "a Diwali poster" / "a beautiful landscape" / "a product advertisement" remains intact!
        cleaned = Regex(
            """^\s*(?:please\s+|can\s+you\s+|could\s+you\s+)?(?:generate|create|draw|paint|render|design|make|produce|sketch|illustrate)\s+(?:me\s+)?""",
            RegexOption.IGNORE_CASE
        ).replace(cleaned, "")

        // Strip Hinglish conversational wrappers like "Bhai ek ... image banao" -> "..."
        cleaned = Regex(
            """^\s*(?:arey\s+|bhai\s+|yaar\s+|dost\s+|please\s+|plz\s+|mujhe\s+|mere\s+liye\s+)+""",
            RegexOption.IGNORE_CASE
        ).replace(cleaned, "")
        cleaned = Regex(
            """^\s*ek\s+""",
            RegexOption.IGNORE_CASE
        ).replace(cleaned, "")
        cleaned = Regex(
            """\s+(?:ki\s+|ka\s+)?(?:image|photo|picture|pic|tasveer|tasvir)?\s*(?:banao|bana\s+do|banaiye|bana\s+de|generate\s+karo|generate\s+kar\s+do|create\s+karo|dikhao)\s*[.!]?\s*$""",
            RegexOption.IGNORE_CASE
        ).replace(cleaned, "")

        cleaned = cleaned.trim().trimEnd('.', '!')
        return if (cleaned.length >= 3) cleaned else rawMessage.trim()
    }
}
