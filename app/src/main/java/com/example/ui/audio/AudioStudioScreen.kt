package com.example.ui.audio

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.data.network.SupabaseAndGatewayClient
import com.example.domain.models.AiModelCatalog
import com.example.domain.models.GeneratedSpeechResult
import com.example.ui.theme.StatusAvailableGreen
import com.example.ui.theme.StatusErrorRed
import com.example.ui.theme.StatusWarningAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private val SAMPLE_TTS_PHRASES = listOf(
    "Welcome to Smarter AI. Your multi-model assistant is ready to help with chat, image creation, and voice read-aloud.",
    "Consistency and clean architecture make Android applications resilient across devices.",
    "Zero-knowledge proofs allow one party to prove a statement is true without revealing the secret itself."
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AudioStudioScreen(
    accessToken: String?,
    networkClient: SupabaseAndGatewayClient,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val voices = remember { AiModelCatalog.POLLINATIONS_AUDIO_VOICES }
    var selectedVoiceId by rememberSaveable { mutableStateOf(voices.first().voiceId) }
    var inputText by rememberSaveable { mutableStateOf("") }
    var isSynthesizing by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var activeSpeechResult by remember { mutableStateOf<GeneratedSpeechResult?>(null) }

    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var deviceTts by remember { mutableStateOf<TextToSpeech?>(null) }
    var isDeviceTtsReady by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isDeviceTtsReady = true
            }
        }
        deviceTts = tts
        onDispose {
            runCatching {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            }
            runCatching {
                tts.stop()
                tts.shutdown()
            }
        }
    }

    fun playMp3Bytes(bytes: ByteArray) {
        scope.launch(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "generated_audio").apply { mkdirs() }
                val audioFile = File(dir, "smarter_ai_tts.mp3")
                audioFile.writeBytes(bytes)

                withContext(Dispatchers.Main) {
                    runCatching {
                        mediaPlayer?.stop()
                        mediaPlayer?.release()
                    }
                    val mp = MediaPlayer().apply {
                        setDataSource(audioFile.absolutePath)
                        setOnCompletionListener {
                            isPlaying = false
                        }
                        setOnErrorListener { _, _, _ ->
                            isPlaying = false
                            true
                        }
                        prepare()
                        start()
                    }
                    mediaPlayer = mp
                    isPlaying = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isPlaying = false
                    errorMessage = "Could not play synthesized MP3 audio: ${e.message}"
                }
            }
        }
    }

    fun speakWithDeviceTts(text: String) {
        val tts = deviceTts ?: return
        if (isDeviceTtsReady) {
            tts.language = Locale.US
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "smarter_ai_utt")
            isPlaying = true
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("audio_studio_screen"),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.systemBars,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("audio_studio_back_button")
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
                            text = "Voice & Read Aloud",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Smarter AI Free On-Device Speech Synthesis",
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
                    .widthIn(max = 700.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Zero-Pollen Audio Capability Disclosure Card
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.testTag("zero_pollen_audio_audit_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Verified,
                                    contentDescription = null,
                                    tint = StatusAvailableGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Smarter AI Read Aloud (Free On-Device)",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = "• Free On-Device Voice Engine: Instant local speech synthesis using your Android device's built-in Text-to-Speech engine — completely free and works offline.\n" +
                                    "• Natural Read Aloud: Listen to articles, summaries, and assistant responses at any time.\n" +
                                    "• Optional Cloud Voice Profiles: Select a voice profile below when cloud audio synthesis is enabled.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Text Input for Speech Generation
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Text to Synthesize into Speech",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = {
                                inputText = it
                                errorMessage = null
                            },
                            placeholder = { Text("Enter text to read aloud using Smarter AI Voice…") },
                            minLines = 3,
                            maxLines = 5,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("audio_tts_input")
                        )

                        // Quick Sample Phrases
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SAMPLE_TTS_PHRASES.forEachIndexed { idx, sample ->
                                Surface(
                                    onClick = {
                                        inputText = sample
                                        errorMessage = null
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    modifier = Modifier.testTag("sample_tts_phrase_$idx")
                                ) {
                                    Text(
                                        text = sample.take(36) + "…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Voice Selector
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Voice Profile",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            voices.forEach { voice ->
                                val selected = voice.voiceId == selectedVoiceId
                                FilterChip(
                                    selected = selected,
                                    onClick = { selectedVoiceId = voice.voiceId },
                                    label = { Text("${voice.displayName} (${voice.toneDescription})") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    modifier = Modifier.testTag("voice_chip_${voice.voiceId}")
                                )
                            }
                        }
                    }
                }

                // Action Buttons: Primary On-Device TTS (0 Pollen) & Optional Cloud Audio Proxy
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                if (inputText.isBlank()) return@Button
                                errorMessage = null
                                speakWithDeviceTts(inputText.trim())
                                activeSpeechResult = GeneratedSpeechResult(
                                    id = "device_tts_${System.currentTimeMillis()}",
                                    text = inputText.trim(),
                                    voiceId = "Android System TTS",
                                    audioBytes = null,
                                    usedDeviceFallbackTts = true,
                                    statusNotice = "Played using Smarter AI Free On-Device Speech Engine."
                                )
                            },
                            enabled = inputText.isNotBlank(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("speak_device_tts_button")
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Read Aloud (Free)")
                        }

                        OutlinedButton(
                            onClick = {
                                if (inputText.isBlank() || isSynthesizing) return@OutlinedButton
                                isSynthesizing = true
                                errorMessage = null
                                scope.launch {
                                    try {
                                        val res = networkClient.generatePollinationsSpeech(
                                            accessToken = accessToken,
                                            text = inputText.trim(),
                                            voiceId = selectedVoiceId
                                        )
                                        activeSpeechResult = res
                                        res.audioBytes?.let { playMp3Bytes(it) }
                                    } catch (e: Exception) {
                                        errorMessage = e.message ?: "Cloud speech synthesis is unavailable on the free tier. Use Read Aloud (Free) for instant on-device speech."
                                    } finally {
                                        isSynthesizing = false
                                    }
                                }
                            },
                            enabled = inputText.isNotBlank() && !isSynthesizing,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("synthesize_pollinations_speech_button")
                        ) {
                            if (isSynthesizing) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Checking…")
                            } else {
                                Icon(Icons.Outlined.GraphicEq, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Cloud Voice MP3")
                            }
                        }
                    }
                }

                // Error Banner with Instant Fallback Option
                if (!errorMessage.isNullOrBlank()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = StatusWarningAmber.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, StatusWarningAmber.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("audio_error_banner")
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.WarningAmber,
                                        contentDescription = null,
                                        tint = StatusWarningAmber
                                    )
                                    Text(
                                        text = errorMessage.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }

                // Playback Control Card
                activeSpeechResult?.let { speech ->
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("generated_speech_card")
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (speech.usedDeviceFallbackTts) {
                                            "On-Device Read Aloud"
                                        } else {
                                            "Smarter AI Voice (${speech.voiceId})"
                                        },
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
                                    text = "\"${speech.text}\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            if (speech.audioBytes != null) {
                                                playMp3Bytes(speech.audioBytes)
                                            } else {
                                                speakWithDeviceTts(speech.text)
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Replay")
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            runCatching { mediaPlayer?.stop() }
                                            runCatching { deviceTts?.stop() }
                                            isPlaying = false
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Outlined.Stop, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Stop")
                                    }

                                    if (speech.audioBytes != null) {
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    shareGeneratedAudio(context, speech.audioBytes, speech.voiceId)
                                                }
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                        ) {
                                            Icon(Icons.Outlined.Share, contentDescription = "Share MP3")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun shareGeneratedAudio(
    context: Context,
    audioBytes: ByteArray,
    voiceId: String
) = withContext(Dispatchers.IO) {
    try {
        val dir = File(context.cacheDir, "generated_audio").apply { mkdirs() }
        val file = File(dir, "smarter_ai_${voiceId}_${System.currentTimeMillis()}.mp3")
        file.writeBytes(audioBytes)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        withContext(Dispatchers.Main) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Generated Speech MP3"))
        }
    } catch (_: Exception) {
        // Ignore in headless test environment
    }
}
