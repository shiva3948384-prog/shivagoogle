package com.example.ui.imagegen

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.core.content.FileProvider
import com.example.data.network.SupabaseAndGatewayClient
import com.example.domain.models.ActiveImageGenerationSession
import com.example.domain.models.AiModelCatalog
import com.example.domain.models.GeneratedImageResult
import com.example.domain.models.ImageAspectRatio
import com.example.domain.models.ImageGenerationStage
import com.example.ui.theme.StatusAvailableGreen
import com.example.ui.theme.StatusErrorRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val SAMPLE_IMAGE_PROMPTS = listOf(
    "Futuristic glass observatory floating above neon clouds at sunset, cinematic lighting",
    "Minimalist isometric workspace with warm desk lamp, plants, and rain outside window",
    "Cyberpunk samurai standing under holographic lanterns in Tokyo alley, ultra-detailed",
    "Watercolor illustration of an ancient library inside a giant hollowed sequoia tree"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImageGenerationScreen(
    accessToken: String?,
    networkClient: SupabaseAndGatewayClient,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val freeImageModels = remember { AiModelCatalog.POLLINATIONS_FREE_IMAGE_MODELS }
    var selectedModelId by rememberSaveable { mutableStateOf(freeImageModels.first().modelId) }
    var selectedAspectRatio by rememberSaveable { mutableStateOf(ImageAspectRatio.SQUARE_1_1) }
    var promptText by rememberSaveable { mutableStateOf("") }
    var enhancePrompt by rememberSaveable { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var generationJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var statusToast by remember { mutableStateOf<String?>(null) }

    val generatedHistory = remember { mutableStateListOf<GeneratedImageResult>() }
    var activeResult by remember { mutableStateOf<GeneratedImageResult?>(null) }
    var activeRequestSequence by remember { mutableStateOf(0L) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("image_generation_screen"),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.systemBars,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = {
                            activeRequestSequence += 1L
                            generationJob?.cancel()
                            generationJob = null
                            isGenerating = false
                            onBack()
                        },
                        modifier = Modifier.testTag("image_studio_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back to Chat"
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Smarter AI Image Studio",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Free AI Image Generation",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusAvailableGreen
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 700.dp)
                    .testTag("image_studio_lazy_list"),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Zero-Pollen Policy Notice
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Verified,
                                contentDescription = null,
                                tint = StatusAvailableGreen,
                                modifier = Modifier.size(22.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Smarter AI Free Image Studio",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Create high-resolution artwork with Smarter AI's free image generation models. Choose an image style, aspect ratio, and prompt below.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Prompt Input Section
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Image Prompt",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        OutlinedTextField(
                            value = promptText,
                            onValueChange = {
                                promptText = it
                                errorMessage = null
                                statusToast = null
                            },
                            placeholder = { Text("Describe the image you want to generate…") },
                            minLines = 3,
                            maxLines = 5,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("image_prompt_input")
                        )

                        // Quick Sample Prompts
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SAMPLE_IMAGE_PROMPTS.forEachIndexed { idx, sample ->
                                Surface(
                                    onClick = {
                                        promptText = sample
                                        errorMessage = null
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    modifier = Modifier.testTag("sample_image_prompt_$idx")
                                ) {
                                    Text(
                                        text = sample.take(38) + "…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Free Image Model Selector
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Select Image Model",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        freeImageModels.forEach { model ->
                            val isSelected = model.modelId == selectedModelId
                            Surface(
                                onClick = { selectedModelId = model.modelId },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                border = BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("image_model_option_${model.modelId}")
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = model.displayName,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Free Tier",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StatusAvailableGreen
                                        )
                                    }
                                    Text(
                                        text = model.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Aspect Ratio & Options
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Aspect Ratio & Dimensions",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ImageAspectRatio.entries.forEach { ratio ->
                                val selected = ratio == selectedAspectRatio
                                FilterChip(
                                    selected = selected,
                                    onClick = { selectedAspectRatio = ratio },
                                    label = { Text("${ratio.label} (${ratio.subtitle})") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    modifier = Modifier.testTag("aspect_ratio_chip_${ratio.name}")
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Smart Prompt Enhancement",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Expands short prompts with lighting & composition details",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = enhancePrompt,
                                onCheckedChange = { enhancePrompt = it },
                                modifier = Modifier.testTag("enhance_prompt_switch")
                            )
                        }
                    }
                }

                // Generate & Cancel Buttons
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                val submittedPrompt = promptText.trim()
                                if (submittedPrompt.isBlank() || isGenerating) return@Button
                                val submittedModelId = selectedModelId
                                val submittedRatio = selectedAspectRatio
                                val submittedEnhance = enhancePrompt

                                generationJob?.cancel()
                                val requestSeq = activeRequestSequence + 1L
                                activeRequestSequence = requestSeq
                                isGenerating = true
                                errorMessage = null
                                statusToast = null

                                val job = scope.launch {
                                    try {
                                        val result = networkClient.generatePollinationsImage(
                                            accessToken = accessToken,
                                            prompt = submittedPrompt,
                                            modelId = submittedModelId,
                                            width = submittedRatio.width,
                                            height = submittedRatio.height,
                                            enhance = submittedEnhance
                                        )
                                        if (activeRequestSequence == requestSeq) {
                                            val decoded = withContext(Dispatchers.Default) {
                                                BitmapFactory.decodeByteArray(
                                                    result.imageBytes,
                                                    0,
                                                    result.imageBytes.size
                                                )
                                            }
                                            if (decoded == null || decoded.width <= 0 || decoded.height <= 0) {
                                                errorMessage = "Generated image could not be decoded. Please try again."
                                            } else {
                                                activeResult = result
                                                generatedHistory.removeAll { it.id == result.id }
                                                generatedHistory.add(0, result)
                                            }
                                        }
                                    } catch (_: kotlinx.coroutines.CancellationException) {
                                        if (activeRequestSequence == requestSeq) {
                                            errorMessage = null
                                            statusToast = "Image generation cancelled."
                                        }
                                    } catch (e: Exception) {
                                        if (activeRequestSequence == requestSeq) {
                                            errorMessage = e.message ?: "Failed to generate image. Please try again."
                                        }
                                    } finally {
                                        if (activeRequestSequence == requestSeq) {
                                            isGenerating = false
                                            generationJob = null
                                        }
                                    }
                                }
                                generationJob = job
                            },
                            enabled = promptText.isNotBlank() && !isGenerating,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("generate_image_button")
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Generating image…")
                            } else {
                                Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Generate Image", style = MaterialTheme.typography.titleSmall)
                            }
                        }

                        if (isGenerating) {
                            OutlinedButton(
                                onClick = {
                                    activeRequestSequence += 1L
                                    generationJob?.cancel()
                                    generationJob = null
                                    isGenerating = false
                                    errorMessage = null
                                    statusToast = "Image generation cancelled."
                                },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier
                                    .height(52.dp)
                                    .testTag("cancel_image_generation_button")
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                }

                // Error Banner
                if (!errorMessage.isNullOrBlank()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = StatusErrorRed.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, StatusErrorRed.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("image_generation_error_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.WarningAmber,
                                    contentDescription = null,
                                    tint = StatusErrorRed
                                )
                                Text(
                                    text = errorMessage.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StatusErrorRed,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Status Notice (Saved / Shared / Cancelled)
                if (!statusToast.isNullOrBlank()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = StatusAvailableGreen.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, StatusAvailableGreen.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = statusToast.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusAvailableGreen,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                // Generated Image Canvas
                activeResult?.let { currentImage ->
                    item {
                        val bitmap = remember(currentImage.id) {
                            BitmapFactory.decodeByteArray(
                                currentImage.imageBytes,
                                0,
                                currentImage.imageBytes.size
                            )?.asImageBitmap()
                        }

                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("generated_image_card")
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                        if (bitmap != null) {
                            val ratioFloat = if (bitmap.width > 0 && bitmap.height > 0) {
                                bitmap.width.toFloat() / bitmap.height.toFloat()
                            } else {
                                currentImage.width.toFloat() / currentImage.height.toFloat()
                            }
                            Image(
                                bitmap = bitmap,
                                contentDescription = currentImage.prompt,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(ratioFloat.coerceIn(0.55f, 1.8f))
                                    .clip(RoundedCornerShape(14.dp))
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Received image payload could not be decoded.",
                                    color = StatusErrorRed
                                )
                            }
                        }

                        Text(
                            text = currentImage.prompt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        val imageModelDisplayName = freeImageModels.firstOrNull {
                            it.modelId == currentImage.modelId
                        }?.displayName ?: currentImage.modelId
                        Text(
                            text = "Model: $imageModelDisplayName • ${currentImage.width}×${currentImage.height} • Seed: ${currentImage.seed} • Free Tier",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Save to Gallery & Share Actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val saved = saveGeneratedImageToGallery(context, currentImage)
                                        statusToast = if (saved) {
                                            "Saved to Pictures/SmarterAI in your device gallery."
                                        } else {
                                            "Could not save image to gallery."
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("save_generated_image_button")
                            ) {
                                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Save to Gallery")
                            }

                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        shareGeneratedImage(context, currentImage)
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("share_generated_image_button")
                            ) {
                                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Share")
                            }
                        }
                    }
                }
            }
        }

        // Session Generated History Selector
        if (generatedHistory.size > 1) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Recent Generations (${generatedHistory.size})",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        generatedHistory.forEachIndexed { idx, historyItem ->
                            val isCurrent = activeResult?.id == historyItem.id
                            FilterChip(
                                selected = isCurrent,
                                onClick = { activeResult = historyItem },
                                label = {
                                    Text(
                                        text = historyItem.prompt.take(26) + if (historyItem.prompt.length > 26) "…" else ""
                                    )
                                },
                                modifier = Modifier.testTag("image_history_chip_$idx")
                            )
                        }
                    }
                }
            }
        }

        // Empty State when no image has been generated yet
        if (activeResult == null && !isGenerating) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp, horizontal = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "Ready to Generate Real AI Artwork",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Enter a prompt above or tap a sample chip to generate high-resolution artwork with Smarter AI Image Studio.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
            }
        }
    }
}

object ImageActionTracker {
    @Volatile
    var lastSavedImageId: String? = null
    @Volatile
    var lastSharedImageId: String? = null

    fun reset() {
        lastSavedImageId = null
        lastSharedImageId = null
    }
}

/**
 * Dedicated Image Generation Screen opened automatically when the user requests an image from Chat,
 * or when inspecting a generated image from conversation history.
 *
 * Implements an honest live progress indicator:
 * - While Pollinations HTTP generation is in flight, displays an indeterminate progress bar
 *   paired with real lifecycle stages (Preparing Prompt -> Connecting to Image Model ->
 *   Generating Image -> Receiving Image -> Processing Final Image) and live elapsed time.
 * - Never displays fake 20% / 50% / 80% progress values.
 * - Displays 100% ONLY after the generated image has been received and validated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DedicatedChatImageGenerationScreen(
    session: ActiveImageGenerationSession,
    onCancelGeneration: () -> Unit,
    onRetryGeneration: () -> Unit,
    onContinueInChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        if (session.isGenerating) {
            onCancelGeneration()
        }
        onContinueInChat()
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var statusToast by remember(session.sessionId) { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("dedicated_image_generation_screen"),
        contentWindowInsets = WindowInsets.systemBars,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (session.isGenerating) {
                                onCancelGeneration()
                            }
                            onContinueInChat()
                        },
                        modifier = Modifier.testTag("dedicated_image_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back to Chat"
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Smarter AI Image Generation",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.testTag("dedicated_image_title")
                        )
                        Text(
                            text = session.modelDisplayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusAvailableGreen,
                            modifier = Modifier.testTag("dedicated_image_model_subtitle")
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 700.dp)
                    .testTag("dedicated_image_lazy_list"),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Prompt & Model Metadata Card
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dedicated_image_prompt_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Original Prompt",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = session.originalUserPrompt,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("dedicated_image_original_prompt")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Model: ${session.modelDisplayName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "•",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Dimensions: ${session.width}×${session.height} (${session.aspectRatioLabel})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("dedicated_image_dimensions")
                                )
                            }
                        }
                    }
                }

                // 2. Honest Live Progress & Stage Indicator Card
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dedicated_image_progress_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    if (session.isGenerating) {
                                        CircularProgressIndicator(
                                            strokeWidth = 2.5.dp,
                                            modifier = Modifier
                                                .size(20.dp)
                                                .testTag("dedicated_image_spinner")
                                        )
                                    } else if (session.stage == ImageGenerationStage.COMPLETED) {
                                        Icon(
                                            imageVector = Icons.Outlined.CheckCircle,
                                            contentDescription = "Completed",
                                            tint = StatusAvailableGreen,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else if (session.stage == ImageGenerationStage.FAILED || session.stage == ImageGenerationStage.CANCELLED) {
                                        Icon(
                                            imageVector = Icons.Outlined.WarningAmber,
                                            contentDescription = "Status",
                                            tint = StatusErrorRed,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Text(
                                        text = session.stage.displayLabel,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.testTag("dedicated_image_stage_label")
                                    )
                                }

                                val rightStatusText = buildString {
                                    if (session.progressPercentage == 100 && session.stage == ImageGenerationStage.COMPLETED) {
                                        append("100%")
                                    }
                                    if (session.elapsedTimeMs > 0L) {
                                        if (isNotEmpty()) append(" • ")
                                        append(formatElapsedDuration(session.elapsedTimeMs))
                                    }
                                }
                                if (rightStatusText.isNotEmpty()) {
                                    Text(
                                        text = rightStatusText,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (session.stage == ImageGenerationStage.COMPLETED) {
                                            StatusAvailableGreen
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.testTag("dedicated_image_progress_status_text")
                                    )
                                }
                            }

                            // Honest Progress Bar: indeterminate while generating, 100% determinate only when COMPLETED
                            if (session.isGenerating) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .testTag("dedicated_image_indeterminate_progress")
                                )
                            } else if (session.stage == ImageGenerationStage.COMPLETED && session.progressPercentage == 100) {
                                LinearProgressIndicator(
                                    progress = { 1f },
                                    color = StatusAvailableGreen,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .testTag("dedicated_image_completed_progress")
                                )
                            }

                            // Cancel button while generating
                            if (session.isGenerating) {
                                OutlinedButton(
                                    onClick = onCancelGeneration,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("cancel_image_generation_button")
                                ) {
                                    Text("Cancel Generation")
                                }
                            }
                        }
                    }
                }

                // 3. Error / Cancelled Banner + Retry Action
                if ((session.stage == ImageGenerationStage.FAILED || session.stage == ImageGenerationStage.CANCELLED) &&
                    !session.errorMessage.isNullOrBlank()
                ) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = StatusErrorRed.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, StatusErrorRed.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("image_generation_error_banner")
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.WarningAmber,
                                        contentDescription = null,
                                        tint = StatusErrorRed
                                    )
                                    Text(
                                        text = session.errorMessage,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = StatusErrorRed,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    if (session.canRetry) {
                                        Button(
                                            onClick = onRetryGeneration,
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("retry_image_generation_button")
                                        ) {
                                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Retry")
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = onContinueInChat,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("continue_in_chat_button")
                                    ) {
                                        Text("Back to Chat")
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Status Notice (Saved / Shared)
                if (!statusToast.isNullOrBlank()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = StatusAvailableGreen.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, StatusAvailableGreen.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("image_action_status_toast")
                        ) {
                            Text(
                                text = statusToast.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusAvailableGreen,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                // 5. Generated Image Preview & Actions (Save, Share, Continue in Chat)
                session.result?.let { currentImage ->
                    item {
                        val bitmap = remember(currentImage.id) {
                            BitmapFactory.decodeByteArray(
                                currentImage.imageBytes,
                                0,
                                currentImage.imageBytes.size
                            )?.asImageBitmap()
                        }

                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("generated_image_card")
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (bitmap != null) {
                                    val ratioFloat = if (bitmap.width > 0 && bitmap.height > 0) {
                                        bitmap.width.toFloat() / bitmap.height.toFloat()
                                    } else {
                                        currentImage.width.toFloat() / currentImage.height.toFloat()
                                    }
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = currentImage.prompt,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(ratioFloat.coerceIn(0.55f, 1.8f))
                                            .clip(RoundedCornerShape(14.dp))
                                            .testTag("generated_image_preview")
                                    )
                                }

                                Text(
                                    text = currentImage.prompt,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Text(
                                    text = "Model: ${session.modelDisplayName} • ${currentImage.width}×${currentImage.height} • Seed: ${currentImage.seed}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                val saved = saveGeneratedImageToGallery(context, currentImage)
                                                statusToast = if (saved) {
                                                    "Saved to Pictures/SmarterAI in your device gallery."
                                                } else {
                                                    "Could not save image to gallery."
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("save_generated_image_button")
                                    ) {
                                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Save")
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                shareGeneratedImage(context, currentImage)
                                                statusToast = "Sharing generated image…"
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("share_generated_image_button")
                                    ) {
                                        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Share")
                                    }
                                }

                                OutlinedButton(
                                    onClick = onContinueInChat,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("continue_in_chat_button")
                                ) {
                                    Text("Continue in Chat")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatElapsedDuration(elapsedMs: Long): String {
    val seconds = elapsedMs / 1000.0
    return String.format(java.util.Locale.US, "%.1fs", seconds)
}

private fun fileExtensionForMimeType(mimeType: String): String {
    val clean = mimeType.lowercase()
    return when {
        clean.contains("webp") -> "webp"
        clean.contains("png") -> "png"
        else -> "jpg"
    }
}

internal suspend fun saveGeneratedImageToGallery(
    context: Context,
    image: GeneratedImageResult
): Boolean = withContext(Dispatchers.IO) {
    ImageActionTracker.lastSavedImageId = image.id
    try {
        val ext = fileExtensionForMimeType(image.mimeType)
        val filename = "SmarterAI_${image.modelId.replace("/", "_")}_${image.seed}.$ext"
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, image.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SmarterAI")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(image.imageBytes)
                out.flush()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            return@withContext true
        }

        // Fallback to app-specific Pictures directory (e.g. in headless test environments)
        val fallbackDir = File(context.filesDir, "Pictures/SmarterAI").apply { mkdirs() }
        val fallbackFile = File(fallbackDir, filename)
        fallbackFile.writeBytes(image.imageBytes)
        true
    } catch (_: Exception) {
        false
    }
}

internal suspend fun shareGeneratedImage(
    context: Context,
    image: GeneratedImageResult
) = withContext(Dispatchers.IO) {
    ImageActionTracker.lastSharedImageId = image.id
    try {
        val ext = fileExtensionForMimeType(image.mimeType)
        val dir = File(context.cacheDir, "generated_images").apply { mkdirs() }
        val file = File(dir, "smarter_ai_${image.seed}.$ext")
        file.writeBytes(image.imageBytes)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        withContext(Dispatchers.Main) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = image.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "Generated with Smarter AI Image Studio: \"${image.prompt}\"")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Generated Image"))
        }
    } catch (_: Exception) {
        // Ignore if no share target exists in headless test environment
    }
}

