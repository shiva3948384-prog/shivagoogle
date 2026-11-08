package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CodeBlockBackgroundDark
import com.example.ui.theme.CodeBlockBackgroundLight
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.delay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class BulletItem(val bullet: String, val text: String, val indentLevel: Int = 0) : MarkdownBlock
    data class Blockquote(val text: String) : MarkdownBlock
    data class MathBlock(val expression: String) : MarkdownBlock
    data class CodeBlock(
        val language: String,
        val code: String,
        val isIncompleteStream: Boolean,
        val isCopyable: Boolean = true
    ) : MarkdownBlock
    data class TableBlock(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data object Divider : MarkdownBlock
}

/**
 * Tracks clipboard copy actions for deterministic unit and Robolectric verification.
 */
object LastCopiedClipboardTracker {
    @Volatile
    var lastCopiedText: String? = null
    val copyHistory: MutableList<String> = mutableListOf()

    fun recordCopy(text: String) {
        lastCopiedText = text
        synchronized(copyHistory) {
            copyHistory.add(text)
        }
    }

    fun reset() {
        lastCopiedText = null
        synchronized(copyHistory) {
            copyHistory.clear()
        }
    }
}

/**
 * Intelligent detector and formatter for structured executable/copyable blocks
 * (programming code, Python, JS/TS, Kotlin/Java, HTML/CSS, SQL, JSON/YAML, Bash/PowerShell,
 * terminal commands, and config files) vs plain conversational prose or social media captions.
 */
object SmartCopyBlockDetector {

    private val KNOWN_CODE_LANGUAGES = setOf(
        "python", "py", "javascript", "js", "typescript", "ts", "tsx", "jsx",
        "kotlin", "kt", "kts", "java", "c", "cpp", "c++", "csharp", "cs", "go", "golang",
        "rust", "rs", "swift", "php", "ruby", "rb", "dart", "r", "scala", "lua", "perl",
        "html", "xml", "svg", "css", "scss", "sass", "less",
        "sql", "mysql", "postgresql", "postgres", "sqlite", "plsql",
        "json", "jsonc", "yaml", "yml", "toml", "ini", "conf", "config", "env", "properties",
        "dockerfile", "docker", "makefile", "cmake", "gradle", "groovy", "nginx",
        "bash", "sh", "zsh", "shell", "console", "terminal", "cmd", "bat", "batch",
        "powershell", "ps1", "pwsh", "graphql", "gql", "proto", "protobuf", "hcl", "terraform",
        "prompt", "image-prompt", "image_prompt", "video-prompt", "video_prompt",
        "ai-studio", "ai-studio-prompt", "system-prompt", "midjourney", "flux", "sana",
        "template", "email-template", "copy", "copy-paste", "copyable"
    )

    private val REUSABLE_PROMPT_LANGUAGES = setOf(
        "prompt", "image-prompt", "image_prompt", "video-prompt", "video_prompt",
        "ai-studio", "ai-studio-prompt", "system-prompt", "midjourney", "flux", "sana",
        "template", "email-template", "copy", "copy-paste", "copyable"
    )

    private val SHELL_LANGUAGES = setOf(
        "bash", "sh", "zsh", "shell", "console", "terminal", "cmd", "bat", "batch",
        "powershell", "ps1", "pwsh"
    )

    private val TERMINAL_COMMAND_PREFIX_REGEX = Regex(
        """^(?:\$\s+|PS(?:[A-Za-z0-9_:\\/\-. ]*)>\s+|>\s+)?(?:sudo\s+)?(?:supabase|npm|npx|yarn|pnpm|bun|node|deno|python|python3|pip|pip3|uv|poetry|conda|git|gh|docker|docker-compose|kubectl|helm|terraform|aws|gcloud|az|firebase|vercel|netlify|fly| flyctl|railway|heroku|curl|wget|ssh|scp|rsync|chmod|chown|mkdir|rm|cp|mv|ls|cat|grep|find|sed|awk|tar|zip|unzip|brew|apt|apt-get|yum|dnf|pacman|systemctl|journalctl| service|gradle|./gradlew|mvn|cargo|rustc|go|javac|java|kotlinc|dotnet|php|composer|bundle|gem|rails|expo|react-native|flutter|adb|fastboot|keytool|openssl|psql|mysql|sqlite3|redis-cli|mongosh|Get-ChildItem|Set-Location|New-Item|Remove-Item|Copy-Item|Move-Item|Invoke-WebRequest|Invoke-RestMethod|Write-Host|Select-Object|Where-Object|ForEach-Object|Start-Process|Stop-Process|Get-Process|Get-Service|Set-ExecutionPolicy|Install-Module)\b.*"""
    )

    private val MULTI_FILE_HEADER_REGEX = Regex(
        """^(?:#|//|--|/\*+|\<!--)\s*(?:={2,}|-{2,})?\s*(?:File|Filename|Path|Script)\s*:\s*([^\n*>-]+?)(?:\s*(?:={2,}|-{2,}|\*+/|--\>))?\s*$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Determines whether a block with [rawLanguage] and [code] is genuinely structured
     * code, script, command, configuration, or reusable prompt/template content that should display a Smart Copy Block.
     */
    fun isStructuredCopyableContent(rawLanguage: String, code: String): Boolean {
        val trimmedCode = code.trim()
        if (trimmedCode.isEmpty()) return false

        val lang = rawLanguage.trim().lowercase().substringBefore(" ")
        if (lang in KNOWN_CODE_LANGUAGES) {
            return true
        }

        // If the fence is untagged ("code", "text", "plaintext", "markdown", "") check content structure
        return looksLikeStructuredCodeOrCommand(trimmedCode)
    }

    /**
     * Returns a human-friendly header title for the Smart Copy Block Editor.
     */
    fun formatBlockHeaderTitle(rawLanguage: String, code: String): String {
        val lang = inferLanguageLabel(rawLanguage, code).lowercase()
        val lowerCode = code.lowercase()
        return when {
            lang in setOf("image-prompt", "image_prompt", "midjourney", "flux", "sana") ||
                (lang == "prompt" && (lowerCode.contains("cinematic") || lowerCode.contains("photorealistic") || lowerCode.contains("--ar") || lowerCode.contains("lighting") || lowerCode.contains("8k") || lowerCode.contains("resolution"))) ->
                "IMAGE GENERATION PROMPT"
            lang in setOf("video-prompt", "video_prompt") ||
                (lang == "prompt" && (lowerCode.contains("camera pan") || lowerCode.contains("slow motion") || lowerCode.contains("drone shot") || lowerCode.contains("video"))) ->
                "VIDEO GENERATION PROMPT"
            lang in setOf("ai-studio", "ai-studio-prompt", "system-prompt") ||
                (lang == "prompt" && (lowerCode.contains("you are an expert") || lowerCode.contains("act as a") || lowerCode.contains("system instruction"))) ->
                "AI STUDIO PROMPT"
            lang == "prompt" -> "REUSABLE PROMPT"
            lang in setOf("template", "email-template") -> "PROMPT TEMPLATE"
            lang in setOf("copy", "copy-paste", "copyable") -> "COPY-PASTE FORMAT"
            lang in setOf("sql", "postgresql", "postgres", "mysql", "sqlite") -> "SQL QUERY"
            lang in setOf("json", "jsonc") -> "JSON CONFIG"
            lang in setOf("yaml", "yml", "toml", "ini", "env", "conf", "config") -> "CONFIGURATION"
            lang in SHELL_LANGUAGES -> "TERMINAL COMMAND"
            lang in setOf("python", "py") -> "PYTHON"
            lang in setOf("kotlin", "kt", "kts") -> "KOTLIN"
            lang in setOf("javascript", "js") -> "JAVASCRIPT"
            lang in setOf("typescript", "ts", "tsx") -> "TYPESCRIPT"
            lang in setOf("html", "xml", "svg") -> "HTML / MARKUP"
            lang in setOf("css", "scss", "sass") -> "STYLESHEET"
            else -> lang.uppercase()
        }
    }

    /**
     * Inspects raw text to determine if it is structured code, script, SQL, JSON/YAML, config,
     * or terminal command rather than conversational prose or social media captions.
     */
    fun looksLikeStructuredCodeOrCommand(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        // Reject social media captions with hashtags/emojis and no programming syntax
        val hasHashtags = Regex("""(?:^|\s)#[A-Za-z][A-Za-z0-9_]{2,}""").containsMatchIn(trimmed)
        val hasCodePunctuation = trimmed.any { it in "{};=()[]<>\\/$|&" }
        if (hasHashtags && !hasCodePunctuation) {
            return false
        }

        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return false

        // 1. Check for terminal commands
        if (lines.all { TERMINAL_COMMAND_PREFIX_REGEX.matches(it) || it.startsWith("#") }) {
            return true
        }
        if (lines.any { it.startsWith("$ ") || it.startsWith("PS>") || it.startsWith("PS C:") }) {
            return true
        }

        // 2. Check for JSON / XML / HTML
        if ((trimmed.startsWith("{") && trimmed.endsWith("}") && trimmed.contains(":")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.contains("{")) ||
            (trimmed.startsWith("<") && trimmed.endsWith(">") && trimmed.contains("/"))
        ) {
            return true
        }

        // 3. Check for SQL queries
        val sqlPattern = Regex(
            """^\s*(?:SELECT|INSERT\s+INTO|UPDATE|DELETE\s+FROM|CREATE\s+(?:TABLE|VIEW|INDEX|DATABASE|FUNCTION|TRIGGER|POLICY)|ALTER\s+TABLE|DROP\s+TABLE|WITH\s+\w+\s+AS)\b""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )
        if (sqlPattern.containsMatchIn(trimmed)) {
            return true
        }

        // 4. Check for programming language constructs (Python, JS/TS, Kotlin, Java, Bash, PowerShell, YAML/Config)
        val codeConstructPattern = Regex(
            """(?:^\s*(?:def|class|import|from|fun|val|var|const|let|function|export|interface|type|struct|enum|impl|fn|pub|package|using|namespace|#include|#!/bin/|@\w+|if\s*\(|for\s*\(|while\s*\(|switch\s*\(|try\s*\{|return\b|async\s+function|public\s+class|private\s+fun)\b)|(?:=>|->|!==|===|::|\$\{[A-Za-z0-9_]+\}|\bconsole\.log\(|\bSystem\.out\.println\(|\bprint\(|\bprintln\()""",
            RegexOption.MULTILINE
        )
        if (codeConstructPattern.containsMatchIn(trimmed)) {
            return true
        }

        // 5. Check for YAML / .env / INI configuration blocks (at least 2 key=value or key: value lines)
        if (lines.size >= 2) {
            val envOrConfigLines = lines.count { line ->
                Regex("""^[A-Za-z_][A-Za-z0-9_.\-]*\s*(?:=|:)\s*\S+.*$""").matches(line) &&
                    !line.contains("http://") && !line.contains("https://")
            }
            if (envOrConfigLines >= 2 && envOrConfigLines >= (lines.size * 0.6).toInt()) {
                return true
            }
        }

        return false
    }

    /**
     * Infers a clean display language label for unfenced or generically fenced code blocks.
     */
    fun inferLanguageLabel(rawLanguage: String, code: String): String {
        val normalized = rawLanguage.trim().lowercase().substringBefore(" ")
        if (normalized in REUSABLE_PROMPT_LANGUAGES) {
            return normalized
        }
        if (normalized.isNotEmpty() && normalized != "code" && normalized != "text" && normalized != "plaintext") {
            return normalized
        }
        val trimmed = code.trim()
        val firstLine = trimmed.lineSequence().firstOrNull()?.trim().orEmpty()
        return when {
            firstLine.startsWith("#!/bin/bash") || firstLine.startsWith("#!/usr/bin/env bash") -> "bash"
            firstLine.startsWith("#!/bin/sh") -> "sh"
            firstLine.startsWith("PS>") || firstLine.startsWith("PS C:") ||
                Regex("""\b(?:Get-ChildItem|Set-Location|Invoke-WebRequest|Write-Host|New-Item)\b""").containsMatchIn(trimmed) -> "powershell"
            TERMINAL_COMMAND_PREFIX_REGEX.matches(firstLine) || firstLine.startsWith("$ ") -> "terminal"
            Regex("""^\s*(?:def\s+\w+|import\s+\w+|from\s+\w+\s+import)\b""", RegexOption.MULTILINE).containsMatchIn(trimmed) -> "python"
            Regex("""^\s*(?:fun\s+\w+|val\s+\w+|var\s+\w+|data\s+class)\b""", RegexOption.MULTILINE).containsMatchIn(trimmed) -> "kotlin"
            Regex("""^\s*(?:const\s+\w+|let\s+\w+|function\s+\w+|export\s+(?:default|const|function))\b""", RegexOption.MULTILINE).containsMatchIn(trimmed) -> "javascript"
            Regex("""^\s*(?:SELECT|INSERT\s+INTO|UPDATE|DELETE\s+FROM|CREATE\s+TABLE)\b""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).containsMatchIn(trimmed) -> "sql"
            trimmed.startsWith("{") && trimmed.endsWith("}") && trimmed.contains("\":") -> "json"
            trimmed.startsWith("<") && trimmed.endsWith(">") -> "html"
            else -> if (normalized.isEmpty()) "code" else normalized
        }
    }

    /**
     * Detects whether a single line in an unfenced paragraph is a standalone terminal command.
     */
    fun isStandaloneTerminalCommandLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.length > 260) return false
        // Must not end with typical prose sentence punctuation unless it's a path/flag
        if (trimmed.endsWith(".") && !trimmed.endsWith("..") && !trimmed.contains(" .")) return false
        if (trimmed.endsWith("?") || trimmed.endsWith("!")) return false
        return TERMINAL_COMMAND_PREFIX_REGEX.matches(trimmed)
    }

    /**
     * Splits a single fenced code block into multiple blocks if it contains explicit multi-file
     * headers (e.g. `# === File: script1.py ===`), so each file gets its own independent Copy button.
     */
    fun splitMultiFileCodeBlockIfPresent(
        language: String,
        code: String,
        isIncompleteStream: Boolean
    ): List<MarkdownBlock.CodeBlock> {
        val lines = code.split("\n")
        val segments = mutableListOf<Pair<String, StringBuilder>>()
        var currentLabel = language
        var currentBuilder = StringBuilder()
        var foundExplicitFileHeader = false

        for (line in lines) {
            val match = MULTI_FILE_HEADER_REGEX.matchEntire(line.trim())
            if (match != null) {
                foundExplicitFileHeader = true
                if (currentBuilder.isNotEmpty()) {
                    segments.add(currentLabel to currentBuilder)
                    currentBuilder = StringBuilder()
                }
                val fileName = match.groupValues[1].trim()
                val ext = fileName.substringAfterLast('.', "").lowercase()
                currentLabel = if (ext in KNOWN_CODE_LANGUAGES) "$ext • $fileName" else fileName
            } else {
                if (currentBuilder.isNotEmpty()) currentBuilder.append("\n")
                currentBuilder.append(line)
            }
        }
        if (currentBuilder.isNotEmpty()) {
            segments.add(currentLabel to currentBuilder)
        }

        if (!foundExplicitFileHeader || segments.size <= 1) {
            val inferredLang = inferLanguageLabel(language, code)
            val copyable = isStructuredCopyableContent(language, code)
            return listOf(
                MarkdownBlock.CodeBlock(
                    language = inferredLang,
                    code = code,
                    isIncompleteStream = isIncompleteStream,
                    isCopyable = copyable
                )
            )
        }

        return segments.mapIndexed { idx, (label, builder) ->
            val segmentCode = builder.toString().trim('\n')
            MarkdownBlock.CodeBlock(
                language = label,
                code = segmentCode,
                isIncompleteStream = isIncompleteStream && idx == segments.lastIndex,
                isCopyable = true
            )
        }
    }

    /**
     * Cleans the code/script/command text for clipboard copying:
     * - Strips accidental leading/trailing Markdown fences (` ```python `, ` ``` `)
     * - Strips shell prompt prefixes (`$ `, `PS C:\> `, `PS> `) from terminal command blocks
     * - Preserves exact indentation, line breaks, and special characters.
     */
    fun cleanCopyableContent(language: String, rawCode: String): String {
        var cleaned = rawCode.replace("\r\n", "\n")
        val lines = cleaned.lines().toMutableList()
        if (lines.isNotEmpty() && (lines.first().trimStart().startsWith("```") || lines.first().trimStart().startsWith("~~~"))) {
            lines.removeAt(0)
        }
        if (lines.isNotEmpty() && (lines.last().trim() == "```" || lines.last().trim() == "~~~")) {
            lines.removeAt(lines.lastIndex)
        }
        cleaned = lines.joinToString("\n")

        val baseLang = language.trim().lowercase().substringBefore(" ")
        val isShellOrTerminal = baseLang in SHELL_LANGUAGES ||
            lines.all { it.isBlank() || it.trimStart().startsWith("$ ") || it.trimStart().startsWith("PS>") || it.trimStart().startsWith("PS C:") }

        if (isShellOrTerminal) {
            cleaned = cleaned.lines().joinToString("\n") { line ->
                val trimmedStart = line.trimStart()
                when {
                    trimmedStart.startsWith("$ ") -> line.replaceFirst("$ ", "")
                    trimmedStart.startsWith("PS>") -> line.replaceFirst(Regex("""^(\s*)PS>\s?"""), "$1")
                    Regex("""^\s*PS\s+[A-Za-z0-9_:\\/\-. ]*>\s*""").containsMatchIn(line) ->
                        line.replaceFirst(Regex("""^(\s*)PS\s+[A-Za-z0-9_:\\/\-. ]*>\s*"""), "$1")
                    else -> line
                }
            }
        }

        return cleaned
    }
}


/**
 * Converts inline and display LaTeX mathematical expressions (such as `\(g^x = y\)`,
 * `\(s = r + c \cdot x \mod q\)`, `\(2^{-k}\)`, `\[x^2 + y^2 = z^2\]`) into clean,
 * human-readable Unicode mathematical notation without corrupting programming code.
 */
object MathNotationFormatter {

    private val SUPERSCRIPT_MAP = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ',
        'f' to 'ᶠ', 'g' to 'ᵍ', 'h' to 'ʰ', 'i' to 'ⁱ', 'j' to 'ʲ',
        'k' to 'ᵏ', 'l' to 'ˡ', 'm' to 'ᵐ', 'n' to 'ⁿ', 'o' to 'ᵒ',
        'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ', 't' to 'ᵗ', 'u' to 'ᵘ',
        'v' to 'ᵛ', 'w' to 'ʷ', 'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ',
        'A' to 'ᴬ', 'B' to 'ᴮ', 'D' to 'ᴰ', 'E' to 'ᴱ', 'G' to 'ᴳ',
        'H' to 'ᴴ', 'I' to 'ᴵ', 'J' to 'ᴶ', 'K' to 'ᴷ', 'L' to 'ᴸ',
        'M' to 'ᴹ', 'N' to 'ᴺ', 'O' to 'ᴼ', 'P' to 'ᴾ', 'R' to 'ᴿ',
        'T' to 'ᵀ', 'U' to 'ᵁ', 'V' to 'ⱽ', 'W' to 'ᵂ'
    )

    private val SUBSCRIPT_MAP = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ',
        'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ',
        'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ',
        'v' to 'ᵥ', 'x' to 'ₓ'
    )

    private val LATEX_COMMANDS = listOf(
        // Spacing and structural wrappers
        "\\left(" to "(",
        "\\right)" to ")",
        "\\left[" to "[",
        "\\right]" to "]",
        "\\left\\{" to "{",
        "\\right\\}" to "}",
        "\\left|" to "|",
        "\\right|" to "|",
        "\\{" to "{",
        "\\}" to "}",
        "\\," to " ",
        "\\;" to " ",
        "\\:" to " ",
        "\\!" to "",
        "\\quad" to "  ",
        "\\qquad" to "    ",
        // Modular & arithmetic operators
        "\\bmod" to " mod ",
        "\\mod" to " mod ",
        "\\cdot" to "·",
        "\\cdots" to "⋯",
        "\\ldots" to "…",
        "\\dots" to "…",
        "\\times" to "×",
        "\\div" to "÷",
        "\\pm" to "±",
        "\\mp" to "∓",
        "\\ast" to "∗",
        "\\star" to "⋆",
        "\\circ" to "∘",
        "\\bullet" to "•",
        "\\oplus" to "⊕",
        "\\otimes" to "⊗",
        // Relations & logic
        "\\Longleftrightarrow" to "⟺",
        "\\Leftrightarrow" to "⇔",
        "\\leftrightarrow" to "↔",
        "\\Longrightarrow" to "⟹",
        "\\Rightarrow" to "⇒",
        "\\rightarrow" to "→",
        "\\longrightarrow" to "⟶",
        "\\leftarrow" to "←",
        "\\longleftarrow" to "⟵",
        "\\mapsto" to "↦",
        "\\implies" to "⇒",
        "\\iff" to "⇔",
        "\\to" to "→",
        "\\gets" to "←",
        "\\leq" to "≤",
        "\\le" to "≤",
        "\\geq" to "≥",
        "\\ge" to "≥",
        "\\neq" to "≠",
        "\\ne" to "≠",
        "\\approx" to "≈",
        "\\equiv" to "≡",
        "\\cong" to "≅",
        "\\sim" to "∼",
        "\\simeq" to "≃",
        "\\propto" to "∝",
        "\\forall" to "∀",
        "\\exists" to "∃",
        "\\nexists" to "∄",
        "\\notin" to "∉",
        "\\in" to "∈",
        "\\ni" to "∋",
        "\\subseteq" to "⊆",
        "\\subset" to "⊂",
        "\\supseteq" to "⊇",
        "\\supset" to "⊃",
        "\\cup" to "∪",
        "\\cap" to "∩",
        "\\emptyset" to "∅",
        "\\varnothing" to "∅",
        "\\land" to "∧",
        "\\wedge" to "∧",
        "\\lor" to "∨",
        "\\vee" to "∨",
        "\\neg" to "¬",
        "\\lnot" to "¬",
        "\\top" to "⊤",
        "\\bot" to "⊥",
        "\\vdash" to "⊢",
        "\\models" to "⊨",
        // Calculus & big operators
        "\\infty" to "∞",
        "\\partial" to "∂",
        "\\nabla" to "∇",
        "\\sum" to "∑",
        "\\prod" to "∏",
        "\\int" to "∫",
        "\\oint" to "∮",
        // Greek letters (uppercase & lowercase)
        "\\varepsilon" to "ε",
        "\\vartheta" to "ϑ",
        "\\varpi" to "ϖ",
        "\\varrho" to "ϱ",
        "\\varsigma" to "ς",
        "\\varphi" to "φ",
        "\\alpha" to "α",
        "\\beta" to "β",
        "\\gamma" to "γ",
        "\\delta" to "δ",
        "\\epsilon" to "ε",
        "\\zeta" to "ζ",
        "\\eta" to "η",
        "\\theta" to "θ",
        "\\iota" to "ι",
        "\\kappa" to "κ",
        "\\lambda" to "λ",
        "\\mu" to "μ",
        "\\nu" to "ν",
        "\\xi" to "ξ",
        "\\pi" to "π",
        "\\rho" to "ρ",
        "\\sigma" to "σ",
        "\\tau" to "τ",
        "\\upsilon" to "υ",
        "\\phi" to "φ",
        "\\chi" to "χ",
        "\\psi" to "psi",
        "\\omega" to "ω",
        "\\Gamma" to "Γ",
        "\\Delta" to "Δ",
        "\\Theta" to "Θ",
        "\\Lambda" to "Λ",
        "\\Xi" to "Ξ",
        "\\Pi" to "Π",
        "\\Sigma" to "Σ",
        "\\Upsilon" to "Υ",
        "\\Phi" to "Φ",
        "\\Psi" to "Ψ",
        "\\Omega" to "Ω"
    ).map { (k, v) -> if (k == "\\psi") k to "ψ" else k to v }

    fun formatMathExpression(rawLatex: String): String {
        var s = rawLatex.trim()
            .removePrefix("\\(").removeSuffix("\\)")
            .removePrefix("\\[").removeSuffix("\\]")
            .removePrefix("$$").removeSuffix("$$")
            .removePrefix("$").removeSuffix("$")
            .trim()

        // 1. Handle \pmod{...}
        s = Regex("""\\pmod\s*\{([^{}]*)\}""").replace(s) { m ->
            " (mod ${formatMathExpression(m.groupValues[1])})"
        }

        // 2. Handle \frac{a}{b} (iteratively for simple nested fractions)
        val fracRegex = Regex("""\\frac\s*\{([^{}]*)\}\s*\{([^{}]*)\}""")
        var guard = 0
        while (fracRegex.containsMatchIn(s) && guard < 6) {
            s = fracRegex.replace(s) { m ->
                val num = formatMathExpression(m.groupValues[1])
                val den = formatMathExpression(m.groupValues[2])
                val numFormatted = if (num.length > 1 && num.any { it in "+- " }) "($num)" else num
                val denFormatted = if (den.length > 1 && den.any { it in "+- " }) "($den)" else den
                "$numFormatted/$denFormatted"
            }
            guard++
        }

        // 3. Handle \sqrt[n]{x} and \sqrt{x}
        s = Regex("""\\sqrt\s*\[([^\[\]]*)\]\s*\{([^{}]*)\}""").replace(s) { m ->
            val root = toSuperscriptString(m.groupValues[1].trim())
            val inner = formatMathExpression(m.groupValues[2])
            "${root}√($inner)"
        }
        s = Regex("""\\sqrt\s*\{([^{}]*)\}""").replace(s) { m ->
            val inner = formatMathExpression(m.groupValues[1])
            if (inner.length <= 2) "√$inner" else "√($inner)"
        }

        // 4. Unwrap text/font commands: \text{...}, \mathrm{...}, \mathbf{...}, \mathcal{...}, \mathbb{...}, \operatorname{...}
        val textWrapperRegex = Regex("""\\(?:text|mathrm|mathbf|mathit|mathsf|mathtt|mathcal|mathbb|operatorname)\s*\{([^{}]*)\}""")
        guard = 0
        while (textWrapperRegex.containsMatchIn(s) && guard < 6) {
            s = textWrapperRegex.replace(s) { m -> m.groupValues[1] }
            guard++
        }

        // 5. Replace known LaTeX commands (longest first so \Longleftrightarrow matches before \Leftarrow)
        for ((cmd, replacement) in LATEX_COMMANDS) {
            if (cmd.last().isLetter()) {
                // Match command followed by non-letter boundary
                s = Regex(Regex.escape(cmd) + """(?![A-Za-z])""").replace(s) { replacement }
            } else {
                s = s.replace(cmd, replacement)
            }
        }

        // 6. Convert superscripts: ^{...} and ^x
        s = Regex("""\^\{([^{}]*)\}""").replace(s) { m ->
            toSuperscriptString(formatMathExpression(m.groupValues[1]))
        }
        s = Regex("""\^([0-9a-zA-Z+\-])""").replace(s) { m ->
            toSuperscriptString(m.groupValues[1])
        }

        // 7. Convert subscripts: _{...} and _x
        s = Regex("""_\{([^{}]*)\}""").replace(s) { m ->
            toSubscriptString(formatMathExpression(m.groupValues[1]))
        }
        s = Regex("""_([0-9a-zA-Z+\-])""").replace(s) { m ->
            toSubscriptString(m.groupValues[1])
        }

        // 8. Clean up any remaining unknown LaTeX commands \foo -> foo and unescaped braces
        s = Regex("""\\([A-Za-z]+)""").replace(s) { m -> m.groupValues[1] }
        s = s.replace("{", "").replace("}", "")

        // Collapse multiple spaces around 'mod' or operators cleanly
        s = s.replace(Regex("""[ \t]{2,}"""), " ").trim()
        return s
    }

    private fun toSuperscriptString(input: String): String {
        if (input.isEmpty()) return ""
        val allSupported = input.all { SUPERSCRIPT_MAP.containsKey(it) || it == ' ' }
        return if (allSupported) {
            input.map { if (it == ' ') ' ' else SUPERSCRIPT_MAP[it]!! }.joinToString("")
        } else {
            "^($input)"
        }
    }

    private fun toSubscriptString(input: String): String {
        if (input.isEmpty()) return ""
        val allSupported = input.all { SUBSCRIPT_MAP.containsKey(it) || it == ' ' }
        return if (allSupported) {
            input.map { if (it == ' ') ' ' else SUBSCRIPT_MAP[it]!! }.joinToString("")
        } else {
            "_($input)"
        }
    }
}

object MarkdownParser {

    private val UNORDERED_LIST_REGEX = Regex("^([-*+•])\\s+(.*)")
    private val ORDERED_LIST_REGEX = Regex("^(\\d+[.)])\\s+(.*)")
    private val UNORDERED_PREFIX_REGEX = Regex("^[-*+•]\\s+")
    private val ORDERED_PREFIX_REGEX = Regex("^\\d+[.)]\\s+")
    private val LATEX_CMD_PREFIX_REGEX = Regex("""^\\[A-Za-z]+""")

    /**
     * Parses Markdown and LaTeX into structured blocks, gracefully handling unclosed
     * fenced code blocks, tables, display equations, blockquotes, and lists during live streaming.
     */
    fun parseBlocks(rawText: String): List<MarkdownBlock> {
        if (rawText.isEmpty()) return emptyList()
        val lines = rawText.replace("\r\n", "\n").split("\n")
        val blocks = mutableListOf<MarkdownBlock>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            val trimmedBoth = line.trim()

            // 1. Fenced code block (``` or ~~~, gracefully supports unclosed fence while streaming)
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val fence = if (trimmed.startsWith("~~~")) "~~~" else "```"
                val lang = trimmed.removePrefix(fence).trim().ifBlank { "code" }
                val codeBuilder = StringBuilder()
                i++
                var closed = false
                while (i < lines.size) {
                    if (lines[i].trimStart().startsWith(fence)) {
                        closed = true
                        i++
                        break
                    }
                    if (codeBuilder.isNotEmpty()) codeBuilder.append("\n")
                    codeBuilder.append(lines[i])
                    i++
                }
                val splitBlocks = SmartCopyBlockDetector.splitMultiFileCodeBlockIfPresent(
                    language = lang,
                    code = codeBuilder.toString(),
                    isIncompleteStream = !closed
                )
                for (codeBlock in splitBlocks) {
                    if (codeBlock.isCopyable) {
                        blocks.add(codeBlock)
                    } else {
                        blocks.add(MarkdownBlock.Paragraph(codeBlock.code))
                    }
                }
                continue
            }

            // 2. Display Math Block: \[ ... \] or $$ ... $$
            if (trimmed.startsWith("\\[") || trimmed.startsWith("$$")) {
                val closeToken = if (trimmed.startsWith("\\[")) "\\]" else "$$"
                val openToken = if (trimmed.startsWith("\\[")) "\\[" else "$$"
                val afterOpen = trimmed.removePrefix(openToken)
                val sameLineCloseIdx = afterOpen.indexOf(closeToken)
                if (sameLineCloseIdx >= 0) {
                    val expr = afterOpen.substring(0, sameLineCloseIdx).trim()
                    blocks.add(MarkdownBlock.MathBlock(expr))
                    i++
                    continue
                } else {
                    val mathBuilder = StringBuilder(afterOpen)
                    i++
                    while (i < lines.size) {
                        val currLine = lines[i]
                        val closeIdx = currLine.indexOf(closeToken)
                        if (closeIdx >= 0) {
                            if (mathBuilder.isNotEmpty()) mathBuilder.append("\n")
                            mathBuilder.append(currLine.substring(0, closeIdx))
                            i++
                            break
                        }
                        if (mathBuilder.isNotEmpty()) mathBuilder.append("\n")
                        mathBuilder.append(currLine)
                        i++
                    }
                    blocks.add(MarkdownBlock.MathBlock(mathBuilder.toString().trim()))
                    continue
                }
            }

            // 3. Horizontal rule
            if (trimmedBoth == "---" || trimmedBoth == "***" || trimmedBoth == "___") {
                blocks.add(MarkdownBlock.Divider)
                i++
                continue
            }

            // 4. Markdown Table (supports standard pipe tables and alignment separator rows)
            if (isTableHeaderAndSeparator(lines, i)) {
                val headers = parseTableCells(lines[i].trim())
                i += 2 // skip header and separator row
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && isTableRowLine(lines[i].trim())) {
                    val parsedRow = parseTableCells(lines[i].trim())
                    // Normalize column count to match header count
                    val normalizedRow = if (parsedRow.size < headers.size) {
                        parsedRow + List(headers.size - parsedRow.size) { "" }
                    } else {
                        parsedRow.take(headers.size.coerceAtLeast(1))
                    }
                    rows.add(normalizedRow)
                    i++
                }
                blocks.add(MarkdownBlock.TableBlock(headers = headers, rows = rows))
                continue
            }

            // 5. Headings (# through ######)
            if (trimmed.startsWith("#")) {
                val hashes = trimmed.takeWhile { it == '#' }.length
                if (hashes in 1..6 && (trimmed.length == hashes || trimmed[hashes] == ' ' || trimmed[hashes] == '\t')) {
                    val rawHeadingText = trimmed.substring(hashes).trim().trimEnd('#').trim()
                    val cleanHeadingText = stripOuterBoldMarkers(rawHeadingText)
                    if (cleanHeadingText.isNotEmpty()) {
                        blocks.add(MarkdownBlock.Heading(hashes, cleanHeadingText))
                    }
                    i++
                    continue
                }
            }

            // 5b. Setext-style underlined headings (Heading followed by === or ---)
            if (i + 1 < lines.size &&
                trimmed.isNotEmpty() &&
                !trimmed.startsWith(">") &&
                !trimmed.startsWith("|") &&
                !UNORDERED_PREFIX_REGEX.containsMatchIn(trimmed) &&
                !ORDERED_PREFIX_REGEX.containsMatchIn(trimmed)
            ) {
                val nextLineTrimmed = lines[i + 1].trim()
                if (nextLineTrimmed.length >= 3 && nextLineTrimmed.all { it == '=' }) {
                    blocks.add(MarkdownBlock.Heading(1, stripOuterBoldMarkers(trimmed)))
                    i += 2
                    continue
                }
                if (nextLineTrimmed.length >= 3 && nextLineTrimmed.all { it == '-' } && !trimmed.contains("|")) {
                    blocks.add(MarkdownBlock.Heading(2, stripOuterBoldMarkers(trimmed)))
                    i += 2
                    continue
                }
            }

            // 6. Blockquotes (> ...)
            if (trimmed.startsWith(">")) {
                val quoteBuilder = StringBuilder(trimmed.removePrefix(">").trimStart())
                i++
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quoteBuilder.append("\n").append(lines[i].trimStart().removePrefix(">").trimStart())
                    i++
                }
                blocks.add(MarkdownBlock.Blockquote(quoteBuilder.toString()))
                continue
            }

            // 7. Unordered or Ordered Lists (supporting indentation levels)
            val leadingSpaces = line.takeWhile { it == ' ' || it == '\t' }.length
            val indentLevel = (leadingSpaces / 2).coerceIn(0, 4)
            val unorderedMatch = UNORDERED_LIST_REGEX.find(trimmed)
            val orderedMatch = ORDERED_LIST_REGEX.find(trimmed)
            if (unorderedMatch != null) {
                val bulletChar = if (indentLevel > 0) "◦" else "•"
                blocks.add(MarkdownBlock.BulletItem(bulletChar, unorderedMatch.groupValues[2], indentLevel))
                i++
                continue
            }
            if (orderedMatch != null) {
                blocks.add(MarkdownBlock.BulletItem(orderedMatch.groupValues[1], orderedMatch.groupValues[2], indentLevel))
                i++
                continue
            }

            // 8. Blank line
            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // 8b. Unfenced standalone terminal command block (e.g. "$ supabase functions deploy" or "supabase deploy")
            if (SmartCopyBlockDetector.isStandaloneTerminalCommandLine(trimmed)) {
                val cmdBuilder = StringBuilder(line.trim())
                i++
                while (i < lines.size) {
                    val nextTrim = lines[i].trim()
                    if (nextTrim.isNotEmpty() && SmartCopyBlockDetector.isStandaloneTerminalCommandLine(nextTrim)) {
                        cmdBuilder.append("\n").append(nextTrim)
                        i++
                    } else {
                        break
                    }
                }
                val cmdCode = cmdBuilder.toString()
                blocks.add(
                    MarkdownBlock.CodeBlock(
                        language = SmartCopyBlockDetector.inferLanguageLabel("terminal", cmdCode),
                        code = cmdCode,
                        isIncompleteStream = false,
                        isCopyable = true
                    )
                )
                continue
            }

            // 8c. Unfenced labeled reusable prompt / copy-paste block
            val labeledPromptMatch = LABELED_PROMPT_BLOCK_REGEX.find(trimmed)
            if (labeledPromptMatch != null) {
                val labelKind = labeledPromptMatch.groupValues[1].trim()
                val inlineRemainder = labeledPromptMatch.groupValues[2].trim()
                val promptBuilder = StringBuilder()
                if (inlineRemainder.isNotEmpty()) {
                    promptBuilder.append(inlineRemainder.removeSurrounding("\""))
                }
                i++
                while (i < lines.size) {
                    val nextLine = lines[i]
                    val nextTrim = nextLine.trim()
                    if (nextTrim.isEmpty() ||
                        nextTrim.startsWith("#") ||
                        nextTrim.startsWith("```") ||
                        nextTrim.startsWith("~~~")
                    ) {
                        break
                    }
                    if (promptBuilder.isNotEmpty()) promptBuilder.append("\n")
                    promptBuilder.append(nextLine)
                    i++
                }
                val promptBody = promptBuilder.toString().trim()
                if (promptBody.length >= 20) {
                    val inferredLang = when {
                        labelKind.contains("image", ignoreCase = true) -> "image-prompt"
                        labelKind.contains("video", ignoreCase = true) -> "video-prompt"
                        labelKind.contains("ai studio", ignoreCase = true) ||
                            labelKind.contains("system", ignoreCase = true) -> "ai-studio-prompt"
                        labelKind.contains("copy", ignoreCase = true) -> "copy-paste"
                        else -> "prompt"
                    }
                    blocks.add(
                        MarkdownBlock.CodeBlock(
                            language = inferredLang,
                            code = promptBody,
                            isIncompleteStream = false,
                            isCopyable = true
                        )
                    )
                    continue
                } else {
                    val fallbackText = if (promptBody.isNotEmpty()) "$labelKind: $promptBody" else line
                    blocks.add(MarkdownBlock.Paragraph(fallbackText))
                    continue
                }
            }

            // 9. Standard Paragraph (merge consecutive plain lines)
            val paraBuilder = StringBuilder(line)
            i++
            while (i < lines.size) {
                val nextTrimmed = lines[i].trimStart()
                if (nextTrimmed.isEmpty() ||
                    nextTrimmed.startsWith("```") ||
                    nextTrimmed.startsWith("~~~") ||
                    nextTrimmed.startsWith("\\[") ||
                    nextTrimmed.startsWith("$$") ||
                    nextTrimmed.startsWith("#") ||
                    nextTrimmed.startsWith(">") ||
                    nextTrimmed == "---" ||
                    nextTrimmed == "***" ||
                    nextTrimmed == "___" ||
                    UNORDERED_PREFIX_REGEX.containsMatchIn(nextTrimmed) ||
                    ORDERED_PREFIX_REGEX.containsMatchIn(nextTrimmed) ||
                    SmartCopyBlockDetector.isStandaloneTerminalCommandLine(nextTrimmed) ||
                    LABELED_PROMPT_BLOCK_REGEX.containsMatchIn(nextTrimmed) ||
                    isTableHeaderAndSeparator(lines, i)
                ) {
                    break
                }
                paraBuilder.append("\n").append(lines[i])
                i++
            }
            val paraText = paraBuilder.toString()
            if (paraText.contains("\n") && SmartCopyBlockDetector.looksLikeStructuredCodeOrCommand(paraText)) {
                blocks.add(
                    MarkdownBlock.CodeBlock(
                        language = SmartCopyBlockDetector.inferLanguageLabel("code", paraText),
                        code = paraText,
                        isIncompleteStream = false,
                        isCopyable = true
                    )
                )
            } else {
                blocks.add(MarkdownBlock.Paragraph(paraText))
            }
        }

        return blocks
    }

    private val LABELED_PROMPT_BLOCK_REGEX = Regex(
        """^(?:\*\*)?(Image Generation Prompt|Video Generation Prompt|Google AI Studio Prompt|AI Studio Prompt|System Prompt|Ready-to-Use Prompt|Copy-Paste Format|Copy-Paste Prompt)(?:\*\*)?\s*:\s*(.*)$""",
        RegexOption.IGNORE_CASE
    )

    private fun stripOuterBoldMarkers(heading: String): String {
        val trimmed = heading.trim()
        if (trimmed.length > 4 && trimmed.startsWith("**") && trimmed.endsWith("**") &&
            !trimmed.substring(2, trimmed.length - 2).contains("**")
        ) {
            return trimmed.substring(2, trimmed.length - 2).trim()
        }
        if (trimmed.length > 4 && trimmed.startsWith("__") && trimmed.endsWith("__") &&
            !trimmed.substring(2, trimmed.length - 2).contains("__")
        ) {
            return trimmed.substring(2, trimmed.length - 2).trim()
        }
        return trimmed
    }

    private fun isTableHeaderAndSeparator(lines: List<String>, index: Int): Boolean {
        if (index + 1 >= lines.size) return false
        val headerLine = lines[index].trim()
        val sepLine = lines[index + 1].trim()
        if (!headerLine.contains("|") || !sepLine.contains("|")) return false
        // Separator row must consist of pipes, hyphens, colons, and spaces, with at least 3 hyphens
        val validSepChars = sepLine.all { it == '|' || it == '-' || it == ':' || it == ' ' || it == '\t' }
        val hyphenCount = sepLine.count { it == '-' }
        return validSepChars && hyphenCount >= 3
    }

    private fun isTableRowLine(trimmedLine: String): Boolean {
        if (trimmedLine.isEmpty()) return false
        return trimmedLine.startsWith("|") || (trimmedLine.contains("|") && !trimmedLine.startsWith("#"))
    }

    internal fun parseTableCells(row: String): List<String> {
        val stripped = row.trim()
            .removePrefix("|")
            .removeSuffix("|")
        // Split on unescaped pipes
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var idx = 0
        while (idx < stripped.length) {
            if (stripped[idx] == '\\' && idx + 1 < stripped.length && stripped[idx + 1] == '|') {
                current.append('|')
                idx += 2
            } else if (stripped[idx] == '|') {
                cells.add(current.toString().trim())
                current.clear()
                idx++
            } else {
                current.append(stripped[idx])
                idx++
            }
        }
        cells.add(current.toString().trim())
        return cells
    }

    /**
     * Converts inline Markdown and LaTeX syntax (`**bold**`, `*italic*`, `\(math\)`, `\[math\]`,
     * `` `code` ``, `[label](url)`) into an AnnotatedString suitable for both Compose UI rendering
     * and deterministic unit testing.
     */
    fun buildInlineAnnotatedString(
        text: String,
        codeBackground: Color = Color.Unspecified,
        mathBackground: Color = Color.Unspecified,
        linkColor: Color = Color.Unspecified,
        baseFontWeight: FontWeight? = null,
        onOpenUri: ((String) -> Unit)? = null
    ): AnnotatedString {
        return buildAnnotatedString {
            if (baseFontWeight != null) {
                withStyle(SpanStyle(fontWeight = baseFontWeight)) {
                    appendInlineSegments(
                        text = text,
                        codeBackground = codeBackground,
                        mathBackground = mathBackground,
                        linkColor = linkColor,
                        onOpenUri = onOpenUri
                    )
                }
            } else {
                appendInlineSegments(
                    text = text,
                    codeBackground = codeBackground,
                    mathBackground = mathBackground,
                    linkColor = linkColor,
                    onOpenUri = onOpenUri
                )
            }
        }
    }

    private fun AnnotatedString.Builder.appendInlineSegments(
        text: String,
        codeBackground: Color,
        mathBackground: Color,
        linkColor: Color,
        onOpenUri: ((String) -> Unit)?
    ) {
        var i = 0
        val n = text.length
        while (i < n) {
            // 1. Inline Code `...` (preserves literal characters like *, \, _ inside code)
            if (text[i] == '`') {
                val tickCount = text.substring(i).takeWhile { it == '`' }.length
                val delimiter = "`".repeat(tickCount)
                val closeIdx = text.indexOf(delimiter, i + tickCount)
                if (closeIdx > i + tickCount) {
                    val codeContent = text.substring(i + tickCount, closeIdx)
                    withStyle(
                        SpanStyle(
                            fontFamily = JetBrainsMonoFontFamily,
                            background = codeBackground,
                            fontSize = 13.sp
                        )
                    ) {
                        append(" $codeContent ")
                    }
                    i = closeIdx + tickCount
                    continue
                }
            }

            // 2. LaTeX Inline Math \( ... \) or Display Math \[ ... \]
            if (i + 1 < n && text[i] == '\\' && (text[i + 1] == '(' || text[i + 1] == '[')) {
                val closeDelimiter = if (text[i + 1] == '(') "\\)" else "\\]"
                val closeIdx = text.indexOf(closeDelimiter, i + 2)
                if (closeIdx >= i + 2) {
                    val rawMath = text.substring(i + 2, closeIdx)
                    val formattedMath = MathNotationFormatter.formatMathExpression(rawMath)
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            fontWeight = FontWeight.Medium,
                            background = mathBackground
                        )
                    ) {
                        append(formattedMath)
                    }
                    i = closeIdx + 2
                    continue
                } else {
                    // Unclosed \( during live token streaming: strip the raw \( delimiter
                    val partialMath = text.substring(i + 2)
                    val formattedMath = MathNotationFormatter.formatMathExpression(partialMath)
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic
                        )
                    ) {
                        append(formattedMath)
                    }
                    break
                }
            }

            // 3. Dollar-delimited Math $$...$$ or $...$ (avoiding currency like $100)
            if (i + 1 < n && text[i] == '$' && text[i + 1] == '$') {
                val closeIdx = text.indexOf("$$", i + 2)
                if (closeIdx > i + 2) {
                    val rawMath = text.substring(i + 2, closeIdx)
                    val formattedMath = MathNotationFormatter.formatMathExpression(rawMath)
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            fontWeight = FontWeight.Medium,
                            background = mathBackground
                        )
                    ) {
                        append(formattedMath)
                    }
                    i = closeIdx + 2
                    continue
                }
            }
            if (text[i] == '$' && i + 1 < n && !text[i + 1].isWhitespace() && !text[i + 1].isDigit()) {
                val closeIdx = text.indexOf('$', i + 1)
                if (closeIdx > i + 1 && !text[closeIdx - 1].isWhitespace()) {
                    val candidate = text.substring(i + 1, closeIdx)
                    if (!candidate.contains("\n")) {
                        val formattedMath = MathNotationFormatter.formatMathExpression(candidate)
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Serif,
                                fontStyle = FontStyle.Italic,
                                fontWeight = FontWeight.Medium
                            )
                        ) {
                            append(formattedMath)
                        }
                        i = closeIdx + 1
                        continue
                    }
                }
            }

            // 4. Standalone inline LaTeX commands outside delimiters (e.g. \cdot, \mod, \pi, \leq)
            if (text[i] == '\\' && i + 1 < n && text[i + 1].isLetter()) {
                val cmdMatch = LATEX_CMD_PREFIX_REGEX.find(text.substring(i))
                if (cmdMatch != null) {
                    val cmd = cmdMatch.value
                    val converted = MathNotationFormatter.formatMathExpression(cmd)
                    if (converted != cmd.removePrefix("\\")) {
                        append(converted)
                        i += cmd.length
                        continue
                    }
                }
            }

            // 5. Escaped Markdown punctuation (\*, \_, \`, \\, \[, \], \|, \$)
            if (text[i] == '\\' && i + 1 < n && text[i + 1] in "*_`\\[]|~$#+-!()") {
                append(text[i + 1])
                i += 2
                continue
            }

            // 6. Bold + Italic ***...***
            if (i + 2 < n && text.startsWith("***", i)) {
                val closeIdx = text.indexOf("***", i + 3)
                if (closeIdx > i + 3) {
                    val inner = text.substring(i + 3, closeIdx)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                        appendInlineSegments(inner, codeBackground, mathBackground, linkColor, onOpenUri)
                    }
                    i = closeIdx + 3
                    continue
                }
            }

            // 7. Bold **...** or __...__
            if (i + 1 < n && ((text[i] == '*' && text[i + 1] == '*') || (text[i] == '_' && text[i + 1] == '_'))) {
                val delim = text.substring(i, i + 2)
                val closeIdx = text.indexOf(delim, i + 2)
                if (closeIdx > i + 2) {
                    val inner = text.substring(i + 2, closeIdx)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInlineSegments(inner, codeBackground, mathBackground, linkColor, onOpenUri)
                    }
                    i = closeIdx + 2
                    continue
                }
            }

            // 8. Strikethrough ~~...~~
            if (i + 1 < n && text[i] == '~' && text[i + 1] == '~') {
                val closeIdx = text.indexOf("~~", i + 2)
                if (closeIdx > i + 2) {
                    val inner = text.substring(i + 2, closeIdx)
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        appendInlineSegments(inner, codeBackground, mathBackground, linkColor, onOpenUri)
                    }
                    i = closeIdx + 2
                    continue
                }
            }

            // 9. Italic *...* (only when not surrounded by spaces, preserving multiplication like 3 * 4)
            if (text[i] == '*' && i + 1 < n && text[i + 1] != ' ' && text[i + 1] != '*') {
                val closeIdx = text.indexOf('*', i + 1)
                if (closeIdx > i + 1 && text[closeIdx - 1] != ' ') {
                    val inner = text.substring(i + 1, closeIdx)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendInlineSegments(inner, codeBackground, mathBackground, linkColor, onOpenUri)
                    }
                    i = closeIdx + 1
                    continue
                }
            }

            // 10. Italic _..._ (only at word boundaries so snake_case_identifiers are preserved!)
            if (text[i] == '_' &&
                (i == 0 || !text[i - 1].isLetterOrDigit()) &&
                i + 1 < n && text[i + 1] != ' ' && text[i + 1] != '_'
            ) {
                val closeIdx = text.indexOf('_', i + 1)
                if (closeIdx > i + 1 &&
                    text[closeIdx - 1] != ' ' &&
                    (closeIdx + 1 == n || !text[closeIdx + 1].isLetterOrDigit())
                ) {
                    val inner = text.substring(i + 1, closeIdx)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendInlineSegments(inner, codeBackground, mathBackground, linkColor, onOpenUri)
                    }
                    i = closeIdx + 1
                    continue
                }
            }

            // 11. Link [label](url)
            if (text[i] == '[') {
                val closeBracket = text.indexOf(']', i + 1)
                if (closeBracket > i + 1 && closeBracket + 1 < n && text[closeBracket + 1] == '(') {
                    val closeParen = text.indexOf(')', closeBracket + 2)
                    if (closeParen > closeBracket + 2) {
                        val label = text.substring(i + 1, closeBracket)
                        val url = text.substring(closeBracket + 2, closeParen)
                        val link = LinkAnnotation.Clickable(
                            tag = url,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                    fontWeight = FontWeight.Medium
                                )
                            ),
                            linkInteractionListener = {
                                onOpenUri?.invoke(url)
                            }
                        )
                        withLink(link) {
                            appendInlineSegments(label, codeBackground, mathBackground, linkColor, onOpenUri)
                        }
                        i = closeParen + 1
                        continue
                    }
                }
            }

            append(text[i])
            i++
        }
    }
}

@Composable
fun MarkdownMessageContent(
    content: String,
    renderMarkdown: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (!renderMarkdown) {
        SelectionContainer(modifier = Modifier.testTag("selectable_markdown_content")) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = modifier
            )
        }
        return
    }

    val blocks = remember(content) { MarkdownParser.parseBlocks(content) }
    SelectionContainer(modifier = Modifier.testTag("selectable_markdown_content")) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            blocks.forEachIndexed { idx, block ->
                when (block) {
                    is MarkdownBlock.Heading -> {
                        val headingWeight = if (block.level == 1) FontWeight.ExtraBold else FontWeight.Bold
                        val style = when (block.level) {
                            1 -> MaterialTheme.typography.headlineSmall.copy(
                                fontSize = 22.sp,
                                lineHeight = 29.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            2 -> MaterialTheme.typography.titleLarge.copy(
                                fontSize = 19.sp,
                                lineHeight = 26.sp,
                                fontWeight = FontWeight.Bold
                            )
                            3 -> MaterialTheme.typography.titleMedium.copy(
                                fontSize = 17.sp,
                                lineHeight = 24.sp,
                                fontWeight = FontWeight.Bold
                            )
                            else -> MaterialTheme.typography.titleSmall.copy(
                                fontSize = 15.5.sp,
                                lineHeight = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = formatInlineMarkdown(
                                text = block.text,
                                baseFontWeight = headingWeight
                            ),
                            style = style,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier
                                .padding(
                                    top = if (idx > 0) 8.dp else 2.dp,
                                    bottom = 2.dp
                                )
                                .testTag("markdown_heading_h${block.level}")
                        )
                    }
                    is MarkdownBlock.Paragraph -> {
                        Text(
                            text = formatInlineMarkdown(block.text),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    is MarkdownBlock.BulletItem -> {
                        val startPad = (block.indentLevel * 14).dp
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = startPad),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = block.bullet,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .width(26.dp)
                                    .padding(top = 1.dp)
                            )
                            Text(
                                text = formatInlineMarkdown(block.text),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    is MarkdownBlock.Blockquote -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)
                                )
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .fillMaxHeight()
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                                        RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)
                                    )
                            )
                            Text(
                                text = formatInlineMarkdown(block.text),
                                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .weight(1f)
                            )
                        }
                    }
                    is MarkdownBlock.MathBlock -> {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                .testTag("markdown_math_block")
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = MathNotationFormatter.formatMathExpression(block.expression),
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        fontFamily = FontFamily.Serif,
                                        fontStyle = FontStyle.Italic,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    is MarkdownBlock.CodeBlock -> {
                        CodeBlockCard(
                            language = block.language,
                            code = block.code,
                            isStreaming = block.isIncompleteStream,
                            isCopyable = block.isCopyable,
                            blockIndex = idx
                        )
                    }
                    is MarkdownBlock.TableBlock -> {
                        MarkdownTableCard(
                            headers = block.headers,
                            rows = block.rows
                        )
                    }
                    is MarkdownBlock.Divider -> {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlockCard(
    language: String,
    code: String,
    isStreaming: Boolean,
    isCopyable: Boolean = true,
    blockIndex: Int = 0
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val codeBg = if (isDark) CodeBlockBackgroundDark else CodeBlockBackgroundLight
    var copied by remember { mutableStateOf(false) }

    val initialCleanText = remember(language, code) {
        SmartCopyBlockDetector.cleanCopyableContent(language, code)
    }
    var history by rememberSaveable(blockIndex, code) {
        mutableStateOf(listOf(initialCleanText))
    }
    var historyIndex by rememberSaveable(blockIndex, code) {
        mutableStateOf(0)
    }
    val currentText = history.getOrElse(historyIndex) { initialCleanText }
    var textFieldValue by remember(blockIndex, code) {
        mutableStateOf(TextFieldValue(text = initialCleanText))
    }

    LaunchedEffect(currentText) {
        if (textFieldValue.text != currentText) {
            textFieldValue = TextFieldValue(
                text = currentText,
                selection = TextRange(currentText.length)
            )
        }
    }

    val lineCount = remember(currentText) { currentText.count { it == '\n' } + 1 }
    var isExpanded by rememberSaveable(blockIndex) { mutableStateOf(true) }
    val canUndo = historyIndex > 0
    val canRedo = historyIndex < history.lastIndex
    val blockHeaderTitle = remember(language, currentText) {
        SmartCopyBlockDetector.formatBlockHeaderTitle(language, currentText)
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = codeBg,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .testTag("code_block_card_$blockIndex")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = blockHeaderTitle,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("copy_block_title_$blockIndex")
                    )
                    Text(
                        text = if (isStreaming) {
                            "${language.lowercase()} • generating…"
                        } else if (currentText != initialCleanText) {
                            "${language.lowercase()} • edited"
                        } else {
                            "${language.lowercase()} • editable smart block"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isCopyable) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Undo button
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = if (canUndo) 0.75f else 0.3f),
                            modifier = Modifier
                                .clickable(enabled = canUndo) {
                                    if (historyIndex > 0) {
                                        historyIndex -= 1
                                    }
                                }
                                .testTag("copy_block_undo_button_$blockIndex")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Undo,
                                contentDescription = "Undo edit",
                                tint = if (canUndo) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                },
                                modifier = Modifier
                                    .padding(6.dp)
                                    .size(15.dp)
                            )
                        }

                        // Redo button
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = if (canRedo) 0.75f else 0.3f),
                            modifier = Modifier
                                .clickable(enabled = canRedo) {
                                    if (historyIndex < history.lastIndex) {
                                        historyIndex += 1
                                    }
                                }
                                .testTag("copy_block_redo_button_$blockIndex")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Redo,
                                contentDescription = "Redo edit",
                                tint = if (canRedo) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                },
                                modifier = Modifier
                                    .padding(6.dp)
                                    .size(15.dp)
                            )
                        }

                        // Select All button
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                            modifier = Modifier
                                .clickable {
                                    textFieldValue = textFieldValue.copy(
                                        selection = TextRange(0, textFieldValue.text.length)
                                    )
                                }
                                .testTag("copy_block_select_all_button_$blockIndex")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SelectAll,
                                contentDescription = "Select all in block",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(6.dp)
                                    .size(15.dp)
                            )
                        }

                        // Expand / Collapse button for long blocks
                        if (lineCount > 12) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                                modifier = Modifier
                                    .clickable { isExpanded = !isExpanded }
                                    .testTag("copy_block_expand_button_$blockIndex")
                            ) {
                                Icon(
                                    imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    contentDescription = if (isExpanded) "Collapse block" else "Expand block",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .padding(6.dp)
                                        .size(15.dp)
                                )
                            }
                        }

                        // Copy button (always copies the latest edited text)
                        val copyButtonBg by animateColorAsState(
                            targetValue = if (copied) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                            },
                            animationSpec = tween(durationMillis = 180),
                            label = "copyBlockButtonBg"
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = copyButtonBg,
                            modifier = Modifier
                                .clickable {
                                    val latestToCopy = SmartCopyBlockDetector.cleanCopyableContent(language, currentText)
                                    copyTextToClipboard(context, latestToCopy)
                                    copied = true
                                }
                                .testTag("copy_code_block_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                    contentDescription = if (copied) "Copied content" else "Copy block content",
                                    tint = if (copied) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (copied) "Copied!" else "Copy",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (copied) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            if (isStreaming || !isCopyable) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    val highlightedCode = remember(code, isDark) { highlightCodeSyntax(code, isDark) }
                    Text(
                        text = highlightedCode,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    )
                }
            } else if (isExpanded) {
                OutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { updatedValue ->
                        val previousText = textFieldValue.text
                        textFieldValue = updatedValue
                        if (updatedValue.text != previousText) {
                            val truncated = history.take(historyIndex + 1)
                            history = truncated + updatedValue.text
                            historyIndex = history.lastIndex
                        }
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .testTag("copy_block_editor_$blockIndex")
                )
            } else {
                val previewLines = remember(currentText) {
                    currentText.lineSequence().take(5).joinToString("\n") + "\n…"
                }
                Text(
                    text = previewLines,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isExpanded = true }
                        .padding(12.dp)
                )
            }
        }
    }
}

/**
 * Calculates readable mobile column widths based on the longest rendered cell in each column
 * so short key columns (e.g. "Zero-Knowledge") never split mid-word, while description columns
 * expand comfortably up to 270.dp and scroll horizontally when needed.
 */
private fun computeColumnWidths(headers: List<String>, rows: List<List<String>>): List<Dp> {
    val colCount = headers.size.coerceAtLeast(1)
    return (0 until colCount).map { colIdx ->
        val headerLen = MarkdownParser.buildInlineAnnotatedString(headers.getOrElse(colIdx) { "" }).text.length
        val maxCellLen = rows.maxOfOrNull { row ->
            MarkdownParser.buildInlineAnnotatedString(row.getOrElse(colIdx) { "" }).text.length
        } ?: 0
        val longest = maxOf(headerLen, maxCellLen)
        when {
            longest <= 14 -> 136.dp
            longest <= 24 -> 168.dp
            longest <= 45 -> 210.dp
            else -> 264.dp
        }
    }
}

@Composable
private fun MarkdownTableCard(
    headers: List<String>,
    rows: List<List<String>>
) {
    val columnWidths = remember(headers, rows) { computeColumnWidths(headers, rows) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .testTag("markdown_table_card")
    ) {
        Column(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            // Table Header Row
            Row(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                headers.forEachIndexed { colIdx, header ->
                    val colWidth = columnWidths.getOrElse(colIdx) { 160.dp }
                    Text(
                        text = formatInlineMarkdown(header, baseFontWeight = FontWeight.Bold),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(colWidth)
                    )
                }
            }

            // Table Body Rows
            rows.forEachIndexed { rowIdx, row ->
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    modifier = Modifier.padding(vertical = 2.dp)
                )
                val rowBackground = if (rowIdx % 2 == 1) {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)
                } else {
                    Color.Transparent
                }
                Row(
                    modifier = Modifier
                        .background(rowBackground, RoundedCornerShape(6.dp))
                        .padding(vertical = 8.dp, horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    row.forEachIndexed { colIdx, cell ->
                        val colWidth = columnWidths.getOrElse(colIdx) { 160.dp }
                        Text(
                            text = formatInlineMarkdown(cell),
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(colWidth)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun formatInlineMarkdown(
    text: String,
    baseFontWeight: FontWeight? = null
): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val mathBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val primaryColor = MaterialTheme.colorScheme.primary
    val uriHandler = LocalUriHandler.current

    return remember(text, codeBg, mathBg, primaryColor, baseFontWeight) {
        MarkdownParser.buildInlineAnnotatedString(
            text = text,
            codeBackground = codeBg,
            mathBackground = mathBg,
            linkColor = primaryColor,
            baseFontWeight = baseFontWeight,
            onOpenUri = { url -> runCatching { uriHandler.openUri(url) } }
        )
    }
}

private val CODE_KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "return", "if", "else", "when", "for", "while",
    "import", "package", "suspend", "private", "public", "internal", "override", "data", "sealed",
    "const", "let", "async", "await", "function", "export", "from", "def", "select", "where", "insert",
    "into", "create", "table", "policy", "true", "false", "null"
)

private val CODE_TOKEN_REGEX = Regex("""("[^"]*"|'[^']*'|\b[A-Za-z_][A-Za-z0-9_]*\b|\s+|.)""")

private fun highlightCodeSyntax(code: String, isDark: Boolean): AnnotatedString {
    val keywordColor = if (isDark) Color(0xFF93C5FD) else Color(0xFF1D4ED8)
    val stringColor = if (isDark) Color(0xFF86EFAC) else Color(0xFF15803D)
    val commentColor = if (isDark) Color(0xFF6B7280) else Color(0xFF9CA3AF)
    val baseColor = if (isDark) Color(0xFFE5E7EB) else Color(0xFF1F2937)

    return buildAnnotatedString {
        val lines = code.split("\n")
        lines.forEachIndexed { lineIdx, line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("--")) {
                withStyle(SpanStyle(color = commentColor, fontStyle = FontStyle.Italic)) {
                    append(line)
                }
            } else {
                CODE_TOKEN_REGEX.findAll(line).forEach { match ->
                    val token = match.value
                    when {
                        (token.startsWith("\"") && token.endsWith("\"")) ||
                            (token.startsWith("'") && token.endsWith("'")) -> {
                            withStyle(SpanStyle(color = stringColor)) { append(token) }
                        }
                        CODE_KEYWORDS.contains(token) -> {
                            withStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.SemiBold)) {
                                append(token)
                            }
                        }
                        else -> {
                            withStyle(SpanStyle(color = baseColor)) { append(token) }
                        }
                    }
                }
            }
            if (lineIdx < lines.lastIndex) append("\n")
        }
    }
}

fun copyTextToClipboard(context: Context, text: String) {
    LastCopiedClipboardTracker.recordCopy(text)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = ClipData.newPlainText("Smarter AI Code Block", text)
    clipboard?.setPrimaryClip(clip)
}
