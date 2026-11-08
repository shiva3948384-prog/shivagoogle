package com.example.domain.usecases

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.data.local.GeneratedImageBinaryCache
import com.example.domain.models.AiModelCatalog
import com.example.domain.models.AiModelDefinition
import com.example.domain.models.MessageAttachment
import java.io.ByteArrayOutputStream
import java.util.Base64

sealed class ImageValidationResult {
    val isValid: Boolean
        get() = this is Valid

    val preparedAttachment: MessageAttachment?
        get() = (this as? Valid)?.normalizedAttachment

    val errorMessage: String?
        get() = (this as? Invalid)?.userFriendlyError

    data class Valid(
        val normalizedAttachment: MessageAttachment,
        val detectedMimeType: String,
        val byteSize: Int,
        val wasCompressed: Boolean
    ) : ImageValidationResult()

    data class Invalid(
        val userFriendlyError: String
    ) : ImageValidationResult()
}

sealed class VisionModelCheckResult {
    val isSupported: Boolean
        get() = this is Compatible

    val errorMessage: String?
        get() = (this as? Incompatible)?.userFriendlyExplanation

    val recommendedVisionModels: List<AiModelDefinition>
        get() = AiModelCatalog.INITIAL_DISCOVERY_MODELS.filter { it.supportsVision }

    data class Compatible(val modelId: String, val displayName: String) : VisionModelCheckResult()
    data class Incompatible(
        val selectedModelId: String,
        val selectedDisplayName: String,
        val recommendedVisionModelId: String = AiModelCatalog.DEFAULT_VISION_MODEL_ID,
        val recommendedVisionDisplayName: String = AiModelCatalog.getDisplayName(AiModelCatalog.DEFAULT_VISION_MODEL_ID),
        val userFriendlyExplanation: String
    ) : VisionModelCheckResult()
}

/**
 * Validates, detects MIME types, compresses oversized images, and prepares provider-compatible
 * multimodal vision payloads (PART 5 — Image Analysis Reliability).
 */
object ImageAttachmentProcessor {

    val SUPPORTED_IMAGE_MIME_TYPES = setOf(
        "image/jpeg",
        "image/jpg",
        "image/png",
        "image/webp",
        "image/gif"
    )

    private const val MAX_ALLOWED_RAW_BYTES = 10 * 1024 * 1024 // 10 MB hard ceiling
    private const val COMPRESSION_THRESHOLD_BYTES = 320 * 1024 // 320 KB target threshold for fast upload
    private const val MAX_VISION_DIMENSION_PX = 896

    /**
     * Detects MIME type from magic bytes first, falling back to data URL header or filename extension.
     */
    fun detectMimeType(
        bytes: ByteArray?,
        declaredMimeType: String? = null,
        fileName: String? = null,
        dataUrl: String? = null
    ): String {
        if (bytes != null && bytes.size >= 4) {
            // JPEG: FF D8 FF
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
                return "image/jpeg"
            }
            // PNG: 89 50 4E 47
            if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
            ) {
                return "image/png"
            }
            // GIF: 47 49 46 38
            if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()
            ) {
                return "image/gif"
            }
            // WEBP: RIFF....WEBP
            if (bytes.size >= 12 &&
                bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
            ) {
                return "image/webp"
            }
        }

        if (!dataUrl.isNullOrBlank() && dataUrl.startsWith("data:", ignoreCase = true)) {
            val headerMime = dataUrl.substringAfter("data:").substringBefore(";").trim().lowercase()
            if (headerMime in SUPPORTED_IMAGE_MIME_TYPES) {
                return if (headerMime == "image/jpg") "image/jpeg" else headerMime
            }
            if (headerMime.isNotBlank() && !headerMime.startsWith("image/")) {
                return headerMime
            }
        }

        val lowerName = fileName?.trim()?.lowercase().orEmpty()
        when {
            lowerName.endsWith(".png") -> return "image/png"
            lowerName.endsWith(".webp") -> return "image/webp"
            lowerName.endsWith(".gif") -> return "image/gif"
            lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") -> return "image/jpeg"
            lowerName.endsWith(".pdf") -> return "application/pdf"
            lowerName.endsWith(".txt") -> return "text/plain"
            lowerName.endsWith(".mp4") -> return "video/mp4"
        }

        val cleanDeclared = declaredMimeType?.trim()?.lowercase().orEmpty()
        if (cleanDeclared == "image/jpg") return "image/jpeg"
        return cleanDeclared.ifBlank { "image/jpeg" }
    }

    /**
     * Validates an image attachment and prepares a normalized [MessageAttachment] with a guaranteed
     * non-empty `data:image/...;base64,...` URL suitable for Apinex vision models.
     */
    fun validateAndPrepareAttachment(attachment: MessageAttachment): ImageValidationResult {
        val rawDataUrl = attachment.base64DataUrl?.trim().orEmpty()
        val resolvedBytes: ByteArray? = when {
            rawDataUrl.isNotEmpty() -> {
                val base64Part = if (rawDataUrl.contains("base64,")) {
                    rawDataUrl.substringAfter("base64,").trim()
                } else {
                    rawDataUrl
                }
                if (base64Part.isEmpty()) {
                    null
                } else {
                    runCatching { Base64.getDecoder().decode(base64Part) }.getOrNull()
                        ?: runCatching { Base64.getMimeDecoder().decode(base64Part) }.getOrNull()
                }
            }
            else -> {
                GeneratedImageBinaryCache.getBytes(
                    attachmentId = attachment.id,
                    uriString = attachment.uriString,
                    base64DataUrl = null
                )
            }
        }

        if (resolvedBytes == null || resolvedBytes.isEmpty()) {
            return ImageValidationResult.Invalid(
                "The selected image attachment is empty or could not be read. Please re-attach a valid JPEG, PNG, or WebP image."
            )
        }

        if (resolvedBytes.size > MAX_ALLOWED_RAW_BYTES) {
            return ImageValidationResult.Invalid(
                "The attached image exceeds the 10 MB size limit. Please choose a smaller image."
            )
        }

        val detectedMime = detectMimeType(
            bytes = resolvedBytes,
            declaredMimeType = attachment.mimeType,
            fileName = attachment.fileName,
            dataUrl = rawDataUrl
        )

        if (detectedMime !in SUPPORTED_IMAGE_MIME_TYPES) {
            return ImageValidationResult.Invalid(
                "Unsupported attachment format '$detectedMime'. Please upload a JPEG, PNG, WebP, or GIF image."
            )
        }

        // Compress or downscale only when appropriate (oversized payload > 320 KB)
        var finalBytes: ByteArray = resolvedBytes
        var finalMime = if (detectedMime == "image/jpg") "image/jpeg" else detectedMime
        var wasCompressed = false

        if (resolvedBytes.size > COMPRESSION_THRESHOLD_BYTES) {
            runCatching {
                val decoded: Bitmap? = BitmapFactory.decodeByteArray(resolvedBytes, 0, resolvedBytes.size)
                if (decoded != null && decoded.width > 0 && decoded.height > 0) {
                    val maxDim = maxOf(decoded.width, decoded.height)
                    val targetBitmap = if (maxDim > MAX_VISION_DIMENSION_PX) {
                        val scale = MAX_VISION_DIMENSION_PX.toFloat() / maxDim.toFloat()
                        val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
                        val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
                        Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
                    } else {
                        decoded
                    }
                    val out = ByteArrayOutputStream()
                    val ok = targetBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    val compressed = out.toByteArray()
                    if (ok && compressed.isNotEmpty() && compressed.size < resolvedBytes.size) {
                        finalBytes = compressed
                        finalMime = "image/jpeg"
                        wasCompressed = true
                    }
                }
            }
        }

        // Cache in GeneratedImageBinaryCache so subsequent Room reloads can always recover the binary
        if (attachment.id.isNotBlank()) {
            GeneratedImageBinaryCache.putGeneratedImage(
                attachmentId = attachment.id,
                imageBytes = finalBytes,
                mimeType = finalMime,
                remoteUrl = attachment.uriString
            )
        }

        val encodedBase64 = Base64.getEncoder().encodeToString(finalBytes)
        val normalizedDataUrl = "data:$finalMime;base64,$encodedBase64"

        return ImageValidationResult.Valid(
            normalizedAttachment = attachment.copy(
                mimeType = finalMime,
                base64DataUrl = normalizedDataUrl
            ),
            detectedMimeType = finalMime,
            byteSize = finalBytes.size,
            wasCompressed = wasCompressed
        )
    }

    /**
     * Checks whether [selectedModelId] has verified vision support in [catalog].
     */
    fun checkVisionModelSupport(
        selectedModelId: String,
        catalog: List<AiModelDefinition> = AiModelCatalog.INITIAL_DISCOVERY_MODELS
    ): VisionModelCheckResult {
        val trimmed = selectedModelId.trim()
        val isVisionSupported = AiModelCatalog.isVisionCapableModel(trimmed, catalog)
        val displayName = AiModelCatalog.getDisplayName(trimmed, catalog)
        if (isVisionSupported) {
            return VisionModelCheckResult.Compatible(trimmed, displayName)
        }
        val recommendedVision = catalog.firstOrNull {
            it.providerModelId == AiModelCatalog.DEFAULT_VISION_MODEL_ID && it.supportsVision
        } ?: catalog.firstOrNull { it.supportsVision }
        val recId = recommendedVision?.providerModelId ?: AiModelCatalog.DEFAULT_VISION_MODEL_ID
        val recName = recommendedVision?.let { AiModelCatalog.formatUserFacingModelName(it.displayName) }
            ?: "Gemini 3.8 Flash"

        return VisionModelCheckResult.Incompatible(
            selectedModelId = trimmed,
            selectedDisplayName = displayName,
            recommendedVisionModelId = recId,
            recommendedVisionDisplayName = recName,
            userFriendlyExplanation = "$displayName is a text-only model and does not support image analysis. Switch to $recName or GPT 6 Luna to analyze images."
        )
    }

    fun checkModelVisionCompatibility(
        selectedModelId: String,
        catalog: List<AiModelDefinition> = AiModelCatalog.INITIAL_DISCOVERY_MODELS
    ): VisionModelCheckResult = checkVisionModelSupport(selectedModelId, catalog)

    fun checkModelVisionSupport(
        selectedModelId: String,
        catalog: List<AiModelDefinition> = AiModelCatalog.INITIAL_DISCOVERY_MODELS
    ): VisionModelCheckResult = checkVisionModelSupport(selectedModelId, catalog)
}
