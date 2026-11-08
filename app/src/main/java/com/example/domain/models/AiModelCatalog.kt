package com.example.domain.models

/**
 * Single source of truth for Smarter AI's 12 verified Apinex Bond API (`https://api.apinex.bond/v1`)
 * chat models, dedicated GPT 6 Luna Voice Chat model (`free/gpt-6-luna`), and Pollinations Media Studio models.
 *
 * All 12 chat models preserve their exact internal `free/...` providerModelId for API requests,
 * while user-facing labels (`displayName`) never expose the raw `free/` prefix or Apinex branding.
 */
object AiModelCatalog {

    const val DEFAULT_MODEL_ID: String = "free/glm-5.3-flash"
    const val DEFAULT_CODING_MODEL_ID: String = "free/qwen-3.8-max"
    const val DEFAULT_REASONING_MODEL_ID: String = "free/deepseek-v4.1-flash"
    const val DEFAULT_VISION_MODEL_ID: String = "free/gemini-3.8-flash"

    /**
     * Dedicated Voice Chat model ID (PART 14).
     * Voice Chat always uses `free/gpt-6-luna` regardless of the selected normal chat model.
     */
    const val VOICE_CHAT_LUNA_MODEL_ID: String = "free/gpt-6-luna"

    const val DEFAULT_IMAGE_MODEL_ID: String = "sana"
    const val DEFAULT_SPEECH_VOICE_ID: String = "alloy"

    /**
     * Complete 12-model catalog verified against `https://apinex.bond/api/public/models` (`allowFree: true`).
     * Capabilities (`supportsVision`, `supportsTools`, `supportsStreaming`, `contextWindowTokens`)
     * match the provider's verified public model metadata.
     */
    val APINEX_CHAT_MODELS: List<AiModelDefinition> = listOf(
        // 1. Muse Spark 1.3
        AiModelDefinition(
            providerModelId = "free/muse-spark-1.3",
            displayName = "Muse Spark 1.3",
            provider = AiProvider.APINEX,
            category = ModelCategory.CREATIVE_WRITING,
            description = "Expressive creative writing, agentic synthesis, and multimodal reasoning with a 1M token context window.",
            capabilities = setOf(
                ModelCapability.STREAMING,
                ModelCapability.VISION,
                ModelCapability.TOOL_CALLING,
                ModelCapability.LONG_CONTEXT,
                ModelCapability.MULTILINGUAL
            ),
            supportsStreaming = true,
            supportsVision = true,
            supportsTools = true,
            isRecommended = true,
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            contextWindowTokens = 1_000_000
        ),
        // 2. Gemini 3.8 Flash
        AiModelDefinition(
            providerModelId = "free/gemini-3.8-flash",
            displayName = "Gemini 3.8 Flash",
            provider = AiProvider.APINEX,
            category = ModelCategory.MULTIMODAL_VISION,
            description = "High-speed multimodal assistant with verified image analysis, tool calling, and 1M token context.",
            capabilities = setOf(
                ModelCapability.STREAMING,
                ModelCapability.VISION,
                ModelCapability.TOOL_CALLING,
                ModelCapability.FAST_INFERENCE,
                ModelCapability.LONG_CONTEXT,
                ModelCapability.MULTILINGUAL
            ),
            supportsStreaming = true,
            supportsVision = true,
            supportsTools = true,
            isRecommended = true,
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            contextWindowTokens = 1_000_000
        ),
        // 3. Qwen 3.8 Max
        AiModelDefinition(
            providerModelId = "free/qwen-3.8-max",
            displayName = "Qwen 3.8 MAX",
            provider = AiProvider.APINEX,
            category = ModelCategory.CODING,
            description = "Flagship coding, software architecture, debugging, and multimodal technical reasoning model with 1M context.",
            capabilities = setOf(
                ModelCapability.STREAMING,
                ModelCapability.CODE_GENERATION,
                ModelCapability.REASONING,
                ModelCapability.VISION,
                ModelCapability.TOOL_CALLING,
                ModelCapability.LONG_CONTEXT,
                ModelCapability.MULTILINGUAL
            ),
            supportsStreaming = true,
            supportsVision = true,
            supportsTools = true,
            isRecommended = true,
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            contextWindowTokens = 1_000_000
        ),
        // 4. Kimi K3
        AiModelDefinition(
            providerModelId = "free/kimi-k3",
            displayName = "Kimi K3",
            provider = AiProvider.APINEX,
            category = ModelCategory.REASONING,
            description = "Deep long-context reasoning, document synthesis, and structured technical analysis with 1M token capacity.",
            capabilities = setOf(
                ModelCapability.STREAMING,
                ModelCapability.REASONING,
                ModelCapability.CODE_GENERATION,
                ModelCapability.VISION,
                ModelCapability.TOOL_CALLING,
                ModelCapability.LONG_CONTEXT,
                ModelCapability.MULTILINGUAL
            ),
            supportsStreaming = true,
            supportsVision = true,
            supportsTools = true,
            isRecommended = true,
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            contextWindowTokens = 1_000_000
        ),
        // 5. DeepSeek V4.1 Flash
        AiModelDefinition(
            providerModelId = "free/deepseek-v4.1-flash",
            displayName = "Deepseek V4.1 Flash 1017",
            provider = AiProvider.APINEX,
            category = ModelCategory.REASONING,
            description = "High-speed analytical reasoning, mathematics, algorithm design, and multimodal code debugging.",
            capabilities = setOf(
                ModelCapability.STREAMING,
                ModelCapability.REASONING,
                ModelCapability.CODE_GENERATION,
                ModelCapability.VISION,
                ModelCapability.TOOL_CALLING,
                ModelCapability.FAST_INFERENCE,
                ModelCapability.LONG_CONTEXT
            ),
            supportsStreaming = true,
            supportsVision = true,
            supportsTools = true,
            isRecommended = true,
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            contextWindowTokens = 1_000_000
        ),
        // 6. GLM 5.3
        AiModelDefinition(
            providerModelId = "free/glm-5.3",
            displayName = "GLM 5.3",
            provider = AiProvider.APINEX,
            category = ModelCategory.GENERAL_CHAT,
            description = "Versatile bilingual and general-purpose intelligence for structured analysis, coding, and writing (text-only).",
            capabilities = setOf(
                ModelCapability.STREAMING,
                ModelCapability.REASONING,
                ModelCapability.CODE_GENERATION,
                ModelCapability.TOOL_CALLING,
                ModelCapability.LONG_CONTEXT,
                ModelCapability.MULTILINGUAL
            ),
            supportsStreaming = true,
            supportsVision = false,
            supportsTools = true,
            isRecommended = false,
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            contextWindowTokens = 1_000_000
        ),
        // 7. GLM 5.3 Flash (Default)
        AiModelDefinition(
            providerModelId = "free/glm-5.3-flash",
            displayName = "GLM-5.3 Flash",
            provider = AiProvider.APINEX,
            category = ModelCategory.RECOMMENDED,
            description = "Ultra-responsive default model for everyday chat, multimodal vision, coding assistance, and instant answers.",
            capabilities = setOf(
                ModelCapability.STREAMING,
                ModelCapability.VISION,
                ModelCapability.TOOL_CALLING,
                ModelCapability.FAST_INFERENCE,
                ModelCapability.CODE_GENERATION,
                ModelCapability.LONG_CONTEXT,
                ModelCapability.MULTILINGUAL
            ),
            supportsStreaming = true,
            supportsVision = true,
            supportsTools = true,
            isRecommended = true,
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            contextWindowTokens = 1_000_000
        ),
        // 8. GPT 6 Luna (Dedicated Voice Chat & Multimodal Flagship)
        AiModelDefinition(
            providerModelId = "free/gpt-6-luna",
            displayName = "GPT 6 Luna",
            provider = AiProvider.APINEX,
            category = ModelCategory.RECOMMENDED,
            description = "Flagship multimodal conversational and reasoning model powering Smarter AI Voice Chat and 1M context dialogues.",
            capabilities = setOf(
                ModelCapability.STREAMING,
                ModelCapability.VISION,
                ModelCapability.REASONING,
                ModelCapability.CODE_GENERATION,
                ModelCapability.TOOL_CALLING,
                ModelCapability.VOICE_CHAT,
                ModelCapability.LONG_CONTEXT,
                ModelCapability.MULTILINGUAL
            ),
            supportsStreaming = true,
            supportsVision = true,
            supportsTools = true,
            isRecommended = true,
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            contextWindowTokens = 1_000_000
        ),
        // 9. Jev 1.13
        AiModelDefinition(
            providerModelId = "free/jev-1.13",
            displayName = "JEV 1.13",
            provider = AiProvider.APINEX,
            category = ModelCategory.REASONING,
            description = "Low-latency decision and structured logic model optimized for rapid classification and concise reasoning (64K context).",
            capabilities = setOf(
                ModelCapability.STREAMING,
                ModelCapability.REASONING,
                ModelCapability.FAST_INFERENCE
            ),
            supportsStreaming = true,
            supportsVision = false,
            supportsTools = false,
            isRecommended = false,
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            contextWindowTokens = 1_000_000
        ),
        // 10. HY4
        AiModelDefinition(
            providerModelId = "free/hy4",
            displayName = "HY4",
            provider = AiProvider.APINEX,
            category = ModelCategory.GENERAL_CHAT,
            description = "Lightweight, high-throughput multimodal model with fast streaming, vision analysis, and 1M context support.",
            capabilities = setOf(
                ModelCapability.STREAMING,
                ModelCapability.VISION,
                ModelCapability.TOOL_CALLING,
                ModelCapability.FAST_INFERENCE,
                ModelCapability.LONG_CONTEXT,
                ModelCapability.MULTILINGUAL
            ),
            supportsStreaming = true,
            supportsVision = true,
            supportsTools = true,
            isRecommended = true,
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            contextWindowTokens = 1_000_000
        ),
        // 11. Mimo V2.6 Flash
        AiModelDefinition(
            providerModelId = "free/mimo-v2.6-flash",
            displayName = "MiMo V2.6 Flash",
            provider = AiProvider.APINEX,
            category = ModelCategory.CODING,
            description = "Fast Mixture-of-Experts multimodal model engineered for coding workflows, agentic tasks, and visual inspection.",
            capabilities = setOf(
                ModelCapability.STREAMING,
                ModelCapability.CODE_GENERATION,
                ModelCapability.VISION,
                ModelCapability.TOOL_CALLING,
                ModelCapability.FAST_INFERENCE,
                ModelCapability.LONG_CONTEXT
            ),
            supportsStreaming = true,
            supportsVision = true,
            supportsTools = true,
            isRecommended = true,
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            contextWindowTokens = 1_000_000
        ),
        // 12. MiniMax M3.1 Flash
        AiModelDefinition(
            providerModelId = "free/minimax-m3.1-flash",
            displayName = "MiniMax M3.1 Flash",
            provider = AiProvider.APINEX,
            category = ModelCategory.GENERAL_CHAT,
            description = "High-speed long-context multimodal assistant with low token weight, vision support, and fluent multilingual output.",
            capabilities = setOf(
                ModelCapability.STREAMING,
                ModelCapability.VISION,
                ModelCapability.TOOL_CALLING,
                ModelCapability.FAST_INFERENCE,
                ModelCapability.LONG_CONTEXT,
                ModelCapability.MULTILINGUAL
            ),
            supportsStreaming = true,
            supportsVision = true,
            supportsTools = true,
            isRecommended = false,
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            contextWindowTokens = 1_000_000
        )
    )

    val INITIAL_DISCOVERY_MODELS: List<AiModelDefinition> = APINEX_CHAT_MODELS

    private val SUPPORTED_MODEL_IDS: Set<String> = APINEX_CHAT_MODELS.map { it.providerModelId }.toSet()

    // ========================================================================
    // Media Studio Models (Pollinations Image & Audio Studios Only)
    // ========================================================================

    val POLLINATIONS_FREE_IMAGE_MODELS: List<ImageModelSpec> = listOf(
        ImageModelSpec(
            modelId = "sana",
            displayName = "Smarter Flux Image",
            description = "Fast, high-detail image generation in 1024×1024, portrait, or landscape aspect ratios.",
            requiresApiKeyHeader = false
        ),
        ImageModelSpec(
            modelId = "flux",
            displayName = "Smarter Artistic Studio (Free Tier)",
            description = "Expressive concept art and stylized visual composition.",
            requiresApiKeyHeader = false
        ),
        ImageModelSpec(
            modelId = "community/scriptsnsenses-sys/flux-2-dev-free",
            displayName = "FLUX.2 Dev Community (Free Tier)",
            description = "High-detail community photorealistic and creative image synthesis.",
            requiresApiKeyHeader = false
        )
    )

    val POLLINATIONS_PAID_IMAGE_MODELS: List<ImageModelSpec> = listOf(
        ImageModelSpec(
            modelId = "gptimage",
            displayName = "GPT Image 1 Mini",
            description = "High prompt-adherence image synthesis.",
            requiresApiKeyHeader = true
        ),
        ImageModelSpec(
            modelId = "kontext",
            displayName = "FLUX.1 Kontext",
            description = "Context-aware visual composition and artistic synthesis.",
            requiresApiKeyHeader = true
        )
    )

    val POLLINATIONS_IMAGE_MODELS: List<ImageModelSpec> =
        POLLINATIONS_FREE_IMAGE_MODELS + POLLINATIONS_PAID_IMAGE_MODELS

    val POLLINATIONS_AUDIO_VOICES: List<SpeechVoiceSpec> = listOf(
        SpeechVoiceSpec("alloy", "Smarter Voice — Alloy", "Balanced, natural studio voice"),
        SpeechVoiceSpec("echo", "Smarter Voice — Echo", "Warm, articulate male voice"),
        SpeechVoiceSpec("fable", "Smarter Voice — Fable", "Expressive storytelling voice"),
        SpeechVoiceSpec("onyx", "Smarter Voice — Onyx", "Deep, authoritative voice"),
        SpeechVoiceSpec("nova", "Smarter Voice — Nova", "Bright, conversational female voice"),
        SpeechVoiceSpec("shimmer", "Smarter Voice — Shimmer", "Clear, calm narrative voice")
    )

    fun isSupportedApinexModel(modelId: String?): Boolean {
        if (modelId.isNullOrBlank()) return false
        return SUPPORTED_MODEL_IDS.contains(modelId.trim())
    }

    fun isVisionCapableModel(
        modelId: String?,
        catalog: List<AiModelDefinition> = INITIAL_DISCOVERY_MODELS
    ): Boolean {
        if (modelId.isNullOrBlank()) return false
        return catalog.firstOrNull { it.providerModelId == modelId.trim() }?.supportsVision == true
    }

    fun findById(
        modelId: String,
        catalog: List<AiModelDefinition> = INITIAL_DISCOVERY_MODELS
    ): AiModelDefinition {
        val trimmed = modelId.trim()
        catalog.firstOrNull { it.providerModelId == trimmed }?.let { return it }
        return AiModelDefinition(
            providerModelId = trimmed,
            displayName = formatUserFacingModelName(trimmed),
            provider = AiProvider.APINEX,
            category = ModelCategory.GENERAL_CHAT,
            description = "This model is not in the active Smarter AI catalog.",
            capabilities = emptySet(),
            supportsStreaming = false,
            supportsVision = false,
            supportsTools = false,
            isRecommended = false,
            availabilityStatus = AvailabilityStatus.UNAVAILABLE
        )
    }

    /**
     * Returns the clean user-facing display name for any model ID, guaranteed never to show
     * the raw `free/` prefix (PART 13).
     */
    fun getDisplayName(
        modelId: String,
        catalog: List<AiModelDefinition> = INITIAL_DISCOVERY_MODELS
    ): String {
        val trimmed = modelId.trim()
        if (trimmed == "sana" || trimmed == "flux" || trimmed.contains("flux", ignoreCase = true)) {
            return "Smarter Flux Image"
        }
        val matched = catalog.firstOrNull { it.providerModelId.equals(trimmed, ignoreCase = true) }
            ?: INITIAL_DISCOVERY_MODELS.firstOrNull { it.providerModelId.equals(trimmed, ignoreCase = true) }
        if (matched != null) {
            return matched.displayName.removePrefix("free/").removePrefix("FREE/").trim()
        }
        return formatUserFacingModelName(trimmed)
    }

    /**
     * Strips any raw `free/` prefix and formats an unknown model slug into a clean user-facing label.
     */
    fun formatUserFacingModelName(rawIdOrName: String): String {
        val withoutPrefix = rawIdOrName.trim()
            .removePrefix("free/")
            .removePrefix("FREE/")
            .trim()
        if (withoutPrefix.isEmpty()) return "Smarter AI"
        return when (withoutPrefix.lowercase()) {
            "gpt-6-luna", "gpt 6 luna" -> "GPT 6 Luna"
            "jev-1.13", "jev 1.13" -> "Jev 1.13"
            "hy4", "hy 4" -> "HY4"
            "mimo-v2.6-flash", "mimo v2.6 flash" -> "Mimo V2.6 Flash"
            "gemini-3.8-flash", "gemini 3.8 flash" -> "Gemini 3.8 Flash"
            "muse-spark-1.3", "muse spark 1.3" -> "Muse Spark 1.3"
            "qwen-3.8-max", "qwen 3.8 max" -> "Qwen 3.8 Max"
            "kimi-k3", "kimi k3" -> "Kimi K3"
            "deepseek-v4.1-flash", "deepseek v4.1 flash", "deepseek v4.1 flash 1017" -> "DeepSeek V4.1 Flash"
            "glm-5.3", "glm 5.3" -> "GLM 5.3"
            "glm-5.3-flash", "glm-5.3 flash", "glm 5.3 flash" -> "GLM 5.3 Flash"
            "minimax-m3.1-flash", "minimax m3.1 flash" -> "MiniMax M3.1 Flash"
            else -> {
                if (withoutPrefix.contains('-') || withoutPrefix.contains('_')) {
                    withoutPrefix.split('-', '_')
                        .filter { it.isNotBlank() }
                        .joinToString(" ") { token ->
                            token.replaceFirstChar { ch ->
                                if (ch.isLowerCase()) ch.titlecase() else ch.toString()
                            }
                        }
                } else {
                    withoutPrefix
                }
            }
        }
    }

    /**
     * Merges live model IDs returned by `/models` with the 12-model Smarter AI catalog.
     */
    fun mergeWithLiveProviderModels(
        baseCatalog: List<AiModelDefinition> = INITIAL_DISCOVERY_MODELS,
        liveModelIds: Set<String>
    ): List<AiModelDefinition> {
        if (liveModelIds.isEmpty()) return baseCatalog
        return baseCatalog.map { model ->
            val isLive = liveModelIds.contains(model.providerModelId)
            model.copy(
                availabilityStatus = if (isLive) AvailabilityStatus.AVAILABLE else AvailabilityStatus.UNAVAILABLE
            )
        }
    }
}
