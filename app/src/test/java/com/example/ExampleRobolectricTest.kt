package com.example

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.data.auth.SupabaseAuthRepository
import com.example.data.chat.ChatRepositoryImpl
import com.example.data.models.ModelCatalogRepositoryImpl
import com.example.data.network.SupabaseAndGatewayClient
import com.example.di.DefaultAppContainer
import com.example.domain.models.AiErrorType
import com.example.domain.models.AiGatewayException
import com.example.domain.models.AiModelCatalog
import com.example.domain.models.AiModelDefinition
import com.example.domain.models.AppPreferences
import com.example.domain.models.AuthSession
import com.example.domain.models.AvailabilityStatus
import com.example.domain.models.ActiveImageGenerationSession
import com.example.domain.models.ChatMessage
import com.example.domain.models.Conversation
import com.example.domain.models.ConversationSearchResult
import com.example.domain.models.GeneratedImageResult
import com.example.domain.models.ImageAspectRatio
import com.example.domain.models.ImageGenerationStage
import com.example.domain.models.MessageAttachment
import com.example.domain.models.MessageRole
import com.example.domain.models.MessageStatus
import com.example.domain.models.StreamChunk
import com.example.domain.models.ThemeMode
import com.example.domain.models.UserProfile
import com.example.domain.repositories.AuthRepository
import com.example.domain.repositories.ChatRepository
import com.example.domain.repositories.ModelCatalogRepository
import com.example.domain.repositories.PreferencesRepository
import com.example.data.livedata.LiveDataIntelligenceService
import com.example.data.livedata.LiveMarketDataRegistry
import com.example.domain.models.ChartDataPoint
import com.example.domain.models.CurrencyConversionDetail
import com.example.domain.models.LiveDataCategory
import com.example.domain.models.LiveDataFreshness
import com.example.domain.models.LiveMarketDataPayload
import com.example.domain.models.PreciousMetalBreakdown
import com.example.domain.models.RequestLifecycleState
import com.example.navigation.NexusAiAppRoot
import com.example.ui.components.LastCopiedClipboardTracker
import com.example.ui.components.LiveMarketChartCard
import com.example.ui.components.MarkdownMessageContent
import com.example.data.preferences.UserPreferencesRepository
import com.example.domain.usecases.SmartModelRouter
import com.example.ui.imagegen.DedicatedChatImageGenerationScreen
import com.example.ui.imagegen.ImageActionTracker
import com.example.ui.modelselector.ModelSelectorContent
import com.example.ui.theme.NexusAiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ========================================================================
    // 1. Authentication & Security Integration Tests
    // ========================================================================

    @Test
    fun `authentication signup, login, invalid credentials, session restoration, sign out, and zero plaintext password check`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = DefaultAppContainer(context, inMemoryDb = true)
        val authRepo = SupabaseAuthRepository(
            context = context,
            networkClient = SupabaseAndGatewayClient(
                supabaseUrlProvider = { "https://your-project-ref.supabase.co" },
                supabaseAnonKeyProvider = { "your-supabase-publishable-anon-key" }
            ),
            chatCacheDao = container.chatCacheDao
        )

        // 1. Sign up
        val signUpRes = authRepo.signUp("alice@example.com", "SecurePass123", "Alice")
        assertTrue("Sign up should succeed", signUpRes.isSuccess)
        assertEquals("Alice", signUpRes.getOrNull()?.user?.displayName)

        // Verify password is NEVER stored in plaintext in SharedPreferences
        val rawPrefs = context.getSharedPreferences("nexus_auth_session_store", Context.MODE_PRIVATE)
        val rawAccountsJson = rawPrefs.getString("staged_accounts_v1", "").orEmpty()
        assertFalse("Plaintext password must never be stored", rawAccountsJson.contains("SecurePass123"))

        // 2. Sign out
        authRepo.signOut()
        assertNull(authRepo.sessionFlow.value)

        // 3. Invalid credentials rejected
        val badLogin = authRepo.signIn("alice@example.com", "WrongPass999")
        assertTrue("Invalid password must fail", badLogin.isFailure)

        // 4. Valid login succeeds
        val goodLogin = authRepo.signIn("alice@example.com", "SecurePass123")
        assertTrue("Valid login should succeed", goodLogin.isSuccess)

        // 5. Session restoration works across repository instances
        val secondAuthRepo = SupabaseAuthRepository(
            context = context,
            networkClient = container.networkClient,
            chatCacheDao = container.chatCacheDao
        )
        val restored = secondAuthRepo.restoreSession()
        assertTrue(restored.isSuccess)
        assertEquals("alice@example.com", restored.getOrNull()?.user?.email)

        container.database.close()
    }

    @Test
    fun `email OTP signup flow enforces 6-digit numeric format, salted SHA-256 storage, 5-minute expiry, 45s resend cooldown, 5 max attempts, previous code invalidation, and single-use verification`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("nexus_auth_session_store", Context.MODE_PRIVATE).edit().clear().commit()
        val container = DefaultAppContainer(context, inMemoryDb = true)
        var simulatedNowMs = 1_700_000_000_000L

        val authRepo = SupabaseAuthRepository(
            context = context,
            networkClient = SupabaseAndGatewayClient(
                supabaseUrlProvider = { "https://your-project-ref.supabase.co" },
                supabaseAnonKeyProvider = { "your-supabase-publishable-anon-key" }
            ),
            chatCacheDao = container.chatCacheDao,
            currentTimeProvider = { simulatedNowMs }
        )

        // 1. Initiate signup with 6-digit OTP
        val email = "otp.user@example.com"
        val challengeRes = authRepo.initiateSignUpOtp(email, "SecurePass123", "OTP User")
        assertTrue("initiateSignUpOtp should succeed", challengeRes.isSuccess)
        assertNull("User must NOT be signed in before verifying OTP", authRepo.sessionFlow.value)

        val issuedOtp1 = authRepo.getLastIssuedOtpForVerification(email)
        assertNotNull("6-digit OTP should be generated", issuedOtp1)
        assertEquals(6, issuedOtp1!!.length)
        assertTrue("OTP must be strictly numeric", issuedOtp1.all { it.isDigit() })

        // Verify plaintext OTP and plaintext password are NEVER stored in SharedPreferences
        val rawPrefs = context.getSharedPreferences("nexus_auth_session_store", Context.MODE_PRIVATE)
        val rawOtpRecordsJson = rawPrefs.getString("otp_verification_records_v1", "").orEmpty()
        assertFalse("Plaintext OTP must never be stored in SharedPreferences", rawOtpRecordsJson.contains("\"$issuedOtp1\""))
        assertTrue("Salted SHA-256 otpHash must be stored", rawOtpRecordsJson.contains("otpHash"))

        // 2. Resend before 45s cooldown must be rejected
        simulatedNowMs += 10_000L // +10s
        val earlyResend = authRepo.resendSignUpOtp(email)
        assertTrue("Resend during 45s cooldown must be rejected", earlyResend.isFailure)

        // 3. Advance past 45s cooldown and resend -> previous OTP must be invalidated
        simulatedNowMs += 40_000L // total +50s
        val resendRes = authRepo.resendSignUpOtp(email)
        assertTrue("Resend after cooldown should succeed", resendRes.isSuccess)
        val issuedOtp2 = authRepo.getLastIssuedOtpForVerification(email)!!

        // If issuedOtp2 happens to differ from issuedOtp1, verify issuedOtp1 is rejected
        if (issuedOtp1 != issuedOtp2) {
            val oldCodeAttempt = authRepo.verifySignUpOtp(email, issuedOtp1)
            assertTrue("Previous OTP must be invalidated when a new OTP is issued", oldCodeAttempt.isFailure)
        }

        // 4. Test max 5 verification attempts lockout on a fresh OTP
        simulatedNowMs += 50_000L
        authRepo.resendSignUpOtp(email)
        val issuedOtp3 = authRepo.getLastIssuedOtpForVerification(email)!!
        val wrongOtp = if (issuedOtp3 == "999999") "888888" else "999999"

        for (attempt in 1..5) {
            val wrongRes = authRepo.verifySignUpOtp(email, wrongOtp)
            assertTrue("Wrong OTP attempt $attempt must fail", wrongRes.isFailure)
        }
        // Even the right code must fail after 5 exhausted attempts
        val afterLockout = authRepo.verifySignUpOtp(email, issuedOtp3)
        assertTrue("OTP must be locked out after 5 failed attempts", afterLockout.isFailure)

        // 5. Test 5-minute (300s) OTP expiration
        simulatedNowMs += 50_000L
        authRepo.resendSignUpOtp(email)
        val issuedOtp4 = authRepo.getLastIssuedOtpForVerification(email)!!
        simulatedNowMs += 301_000L // +5 minutes 1 second
        val expiredAttempt = authRepo.verifySignUpOtp(email, issuedOtp4)
        assertTrue("Expired OTP (> 5 minutes) must be rejected", expiredAttempt.isFailure)

        // 6. Issue fresh OTP, verify within 5 minutes -> establishes authenticated session
        simulatedNowMs += 50_000L
        authRepo.resendSignUpOtp(email)
        val validOtp = authRepo.getLastIssuedOtpForVerification(email)!!
        val verifySuccess = authRepo.verifySignUpOtp(email, validOtp)
        assertTrue("Valid 6-digit OTP should verify and establish session", verifySuccess.isSuccess)
        assertNotNull("Session must be active after OTP verification", authRepo.sessionFlow.value)
        assertEquals(email, authRepo.sessionFlow.value?.user?.email)

        // 7. Single-use enforcement: replaying the same OTP must fail
        val replayAttempt = authRepo.verifySignUpOtp(email, validOtp)
        assertTrue("Used OTP must not be replayable", replayAttempt.isFailure)

        container.database.close()
    }

    // ========================================================================
    // 2. Conversation Management, Search, and Cross-User Ownership Isolation
    // ========================================================================

    @Test
    fun `conversation creation, rename, message persistence, search, delete, and cross-user isolation`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = DefaultAppContainer(context, inMemoryDb = true)
        val authRepo = container.authRepository
        val chatRepo = container.chatRepository

        // Sign up User A
        val userASession = authRepo.signUp("usera@example.com", "Pass12345", "User A").getOrThrow()
        val userAId = userASession.user.id

        // Create conversation for User A
        val convA = chatRepo.createConversation(userAId, "Kotlin Coroutines Guide", "").getOrThrow()
        assertEquals("Kotlin Coroutines Guide", convA.title)

        // Rename conversation
        val renamed = chatRepo.renameConversation(convA.id, userAId, "Structured Concurrency Deep Dive").getOrThrow()
        assertEquals("Structured Concurrency Deep Dive", renamed.title)

        // Save message & prevent duplicate clientRequestId insertion
        val reqId = "req-unique-101"
        val msg1 = chatRepo.saveUserMessage(convA.id, userAId, "Explain SupervisorJob in detail", "", emptyList(), reqId).getOrThrow()
        val msgDuplicate = chatRepo.saveUserMessage(convA.id, userAId, "Explain SupervisorJob in detail", "", emptyList(), reqId).getOrThrow()
        assertEquals("Duplicate clientRequestId should return existing message", msg1.id, msgDuplicate.id)

        val messagesForA = chatRepo.observeMessages(convA.id, userAId).first()
        assertEquals(1, messagesForA.size)

        // Search conversations by message content (case-insensitive)
        val searchHits = chatRepo.searchConversations(userAId, "supervisorjob").getOrThrow()
        assertEquals(1, searchHits.size)
        assertTrue(searchHits.first().matchedInMessage)

        // Prevent cross-user access: User A cannot query or mutate as User B
        val crossUserAttempt = chatRepo.renameConversation(convA.id, "other-user-id", "Hacked Title")
        assertTrue("Cross-user access must be rejected", crossUserAttempt.isFailure)

        // Sign in as User B and verify User B cannot see User A's conversations or search results
        authRepo.signOut()
        val userBSession = authRepo.signUp("userb@example.com", "Pass12345", "User B").getOrThrow()
        val userBId = userBSession.user.id

        val userBConversations = chatRepo.observeConversations(userBId).first()
        assertTrue("User B must not see User A's conversations", userBConversations.isEmpty())

        val userBSearch = chatRepo.searchConversations(userBId, "supervisorjob").getOrThrow()
        assertTrue("User B search must not leak User A's messages", userBSearch.isEmpty())

        // Switch back to User A and delete conversation
        authRepo.signOut()
        authRepo.signIn("usera@example.com", "Pass12345")
        chatRepo.deleteConversation(convA.id, userAId)
        val remaining = chatRepo.observeConversations(userAId).first()
        assertTrue(remaining.isEmpty())

        container.database.close()
    }

    // ========================================================================
    // 3. Unsupported / Unconfigured Model Validation Test
    // ========================================================================

    @Test
    fun `streamAssistantResponse rejects unknown or empty model IDs with clear unsupported model error`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = DefaultAppContainer(context, inMemoryDb = true)
        val authRepo = container.authRepository
        val session = authRepo.signUp("dev@example.com", "Password123", "Dev").getOrThrow()
        val userId = session.user.id

        val chatRepo = container.chatRepository
        val conv = chatRepo.createConversation(userId, "Unsupported Model Check", "unknown/model-x").getOrThrow()

        val chunks = mutableListOf<StreamChunk>()
        chatRepo.streamAssistantResponse(
            conversationId = conv.id,
            userId = userId,
            requestedModelId = "unknown/model-x",
            fallbackModelId = null,
            allowFallback = false,
            history = emptyList(),
            streamEnabled = true,
            clientRequestId = "req-1"
        ).collect { chunks.add(it) }

        val errChunk = chunks.filterIsInstance<StreamChunk.Error>().firstOrNull()
        assertNotNull("Must emit error when an unsupported model ID is requested", errChunk)
        assertEquals(AiErrorType.UNSUPPORTED_MODEL, errChunk?.exception?.errorType)
        assertTrue(errChunk?.exception?.userFriendlyMessage?.contains("Please select an available Smarter AI model") == true)
        assertFalse(errChunk?.exception?.userFriendlyMessage?.contains("Apinex", ignoreCase = true) == true)

        container.database.close()
    }

    // ========================================================================
    // 4. End-to-End Compose UI Tests (New Chat, Apinex Model Selector, Streaming, Sidebar, Search, Themes)
    // ========================================================================

    @Test
    fun `UI flow - login, new chat welcome, Apinex model selector sheet, chat message flow, search, and theme switch`() {
        val fakeContainer = FakeUiTestAppContainer()

        composeTestRule.setContent {
            NexusAiAppRoot(appContainer = fakeContainer)
        }
        composeTestRule.waitForIdle()

        // 1. Verify New Chat Welcome screen is displayed for authenticated user
        composeTestRule.onNodeWithTag("new_chat_welcome_view").assertIsDisplayed()
        composeTestRule.onNodeWithText("How can I help you today?").assertIsDisplayed()

        // 2. Send a message and verify user + streamed assistant messages render in chat list
        composeTestRule.onNodeWithTag("chat_composer_input").performTextInput("Hello Smarter AI, test chat interface!")
        composeTestRule.onNodeWithTag("send_message_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("user_message_item_msg-u1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("assistant_message_item_msg-a1").assertIsDisplayed()

        // 3. Open Sidebar Navigation Drawer, navigate to Settings, toggle Light Theme
        composeTestRule.onNodeWithTag("open_drawer_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("drawer_settings_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("theme_mode_chip_LIGHT").performClick()
        composeTestRule.waitForIdle()
        assertEquals(ThemeMode.LIGHT, fakeContainer.currentPrefs.value.themeMode)

        // Return to Chat, verify standalone Image Studio button was removed from Chat TopBar, open Audio Studio, then open Search screen
        composeTestRule.onNodeWithTag("settings_back_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            "Standalone Image Studio button must be removed from Chat TopBar",
            0,
            composeTestRule.onAllNodesWithTag("top_bar_image_studio_button").fetchSemanticsNodes().size
        )

        composeTestRule.onNodeWithTag("top_bar_audio_studio_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("audio_studio_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("audio_studio_back_button").performClick()
        composeTestRule.waitForIdle()

        // 4. Open Search screen and return to Chat
        composeTestRule.onNodeWithTag("open_drawer_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("drawer_search_chats_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("chat_search_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("search_back_button").performClick()
        composeTestRule.waitForIdle()

        // 5. Verify unified Smarter AI 1.0 identity in TopBar and absence of dedicated Voice Chat mode buttons
        composeTestRule.onNodeWithTag("top_bar_model_selector_button").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Smarter AI 1.0").onFirst().assertIsDisplayed()
        assertEquals(
            "Dedicated Voice Chat top bar button must be removed",
            0,
            composeTestRule.onAllNodesWithTag("top_bar_voice_chat_button").fetchSemanticsNodes().size
        )
        assertEquals(
            "Dedicated Voice Chat composer button must be removed",
            0,
            composeTestRule.onAllNodesWithTag("composer_voice_chat_button").fetchSemanticsNodes().size
        )
    }

    @Test
    fun `UI flow - signup transitions to branded 6-digit OTP verification screen and redirects to home screen on valid OTP`() {
        val fakeContainer = FakeUiTestAppContainer(initiallyAuthenticated = false)

        composeTestRule.setContent {
            NexusAiAppRoot(appContainer = fakeContainer)
        }
        composeTestRule.waitForIdle()

        // Switch to Create Account tab
        composeTestRule.onNodeWithTag("auth_tab_signup").performClick()
        composeTestRule.waitForIdle()

        // Fill in signup details
        composeTestRule.onNodeWithTag("auth_display_name_input").performTextInput("Elena Vance")
        composeTestRule.onNodeWithTag("auth_email_input").performTextInput("elena@smarterai.app")
        composeTestRule.onNodeWithTag("auth_password_input").performTextInput("SecurePass123")
        composeTestRule.onNodeWithTag("auth_confirm_password_input").performTextInput("SecurePass123")
        composeTestRule.onNodeWithTag("auth_submit_button").performClick()
        composeTestRule.waitForIdle()

        // Verify branded Smarter AI 6-digit Email OTP verification screen is shown
        composeTestRule.onNodeWithTag("auth_otp_verification_section").assertIsDisplayed()
        composeTestRule.onNodeWithText("Verify your email").assertIsDisplayed()

        // Enter invalid OTP first and verify error message
        composeTestRule.onNodeWithTag("auth_otp_input").performTextInput("000000")
        composeTestRule.onNodeWithTag("auth_verify_otp_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("auth_error_banner").assertIsDisplayed()

        // Enter valid 6-digit OTP ("482915") and verify redirect to Smarter AI home screen
        composeTestRule.onNodeWithTag("auth_change_email_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("auth_submit_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("auth_otp_input").performTextInput("482915")
        composeTestRule.onNodeWithTag("auth_verify_otp_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("new_chat_welcome_view").assertIsDisplayed()
        composeTestRule.onNodeWithText("How can I help you today?").assertIsDisplayed()
    }

    @Test
    fun `chat screen scrolling supports multi-turn conversations, long markdown and code blocks, scroll to oldest, and jump to latest button`() {
        val messages = (1..12).map { index ->
            val isUser = index % 2 != 0
            ChatMessage(
                id = "scroll-msg-$index",
                conversationId = "conv-scroll-1",
                userId = "user-test-1",
                role = if (isUser) MessageRole.USER else MessageRole.ASSISTANT,
                content = if (isUser) {
                    "User question #$index about distributed systems and cryptography"
                } else {
                    """
                    ### Assistant Answer #$index
                    Here is a detailed explanation for turn #$index:
                    - First key architectural principle for turn #$index
                    - Second key architectural principle for turn #$index
                    ```kotlin
                    fun verifyProofTurn$index(input: String): Boolean {
                        return input.isNotEmpty()
                    }
                    ```
                    Concluding summary paragraph for turn #$index.
                    """.trimIndent()
                },
                modelId = AiModelCatalog.DEFAULT_MODEL_ID,
                status = MessageStatus.COMPLETED,
                createdAtEpochMs = 1_700_000_000_000L + (index * 1000L)
            )
        }

        val uiState = com.example.ui.chat.ChatUiState(
            activeUserId = "user-test-1",
            activeUserEmail = "tester@smarterai.app",
            activeUserDisplayName = "Alex Tester",
            activeConversationId = "conv-scroll-1",
            activeConversation = Conversation(
                id = "conv-scroll-1",
                userId = "user-test-1",
                title = "Scrolling & Markdown Test",
                selectedModelId = AiModelCatalog.DEFAULT_MODEL_ID,
                createdAtEpochMs = 1_700_000_000_000L,
                updatedAtEpochMs = 1_700_000_012_000L
            ),
            messages = messages,
            composerText = "Drafting next question"
        )

        composeTestRule.setContent {
            com.example.ui.theme.NexusAiTheme {
                com.example.ui.chat.ChatScreen(
                    uiState = uiState,
                    onStartNewChat = {},
                    onSelectConversation = {},
                    onRenameConversation = { _, _ -> },
                    onDeleteConversation = {},
                    onOpenSearchScreen = {},
                    onOpenSettingsScreen = {},
                    onSignOut = {},
                    onOpenModelSelector = {},
                    onCloseModelSelector = {},
                    onModelSearchQueryChanged = {},
                    onModelCategorySelected = {},
                    onRefreshModelCatalog = {},
                    onSelectModel = {},
                    onComposerTextChanged = {},
                    onApplyPromptSuggestion = {},
                    onAddAttachment = {},
                    onRemoveAttachment = {},
                    onSendMessage = {},
                    onStopGeneration = {},
                    onRegenerateResponse = {},
                    onRetryFailedGeneration = {},
                    onStartEditingMessage = {},
                    onCancelEditingMessage = {},
                    onDismissBanners = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. Composer input and send button are visible simultaneously with the message list
        composeTestRule.onNodeWithTag("chat_messages_list").assertIsDisplayed()
        composeTestRule.onNodeWithTag("chat_composer_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("send_message_button").assertIsDisplayed()

        // 2. Auto-scrolled to the newest message at the bottom (#12)
        composeTestRule.onNodeWithTag("assistant_message_item_scroll-msg-12").assertIsDisplayed()

        // 3. Scroll upward to the very oldest message (#1)
        composeTestRule.onNodeWithTag("chat_messages_list")
            .performScrollToIndex(0)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("user_message_item_scroll-msg-1").assertIsDisplayed()

        // 4. Floating "Jump to Latest" button appears when scrolled up away from bottom; clicking it scrolls back to #12
        composeTestRule.onNodeWithTag("scroll_to_bottom_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("scroll_to_bottom_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("assistant_message_item_scroll-msg-12").assertIsDisplayed()
    }

    @Test
    fun `unverified OTP signup cannot bypass verification via signIn and empty cancelled stream leaves zero zombie messages`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("nexus_auth_session_store", Context.MODE_PRIVATE).edit().clear().commit()
        val container = DefaultAppContainer(context, inMemoryDb = true)
        val authRepo = SupabaseAuthRepository(
            context = context,
            networkClient = SupabaseAndGatewayClient(
                supabaseUrlProvider = { "https://your-project-ref.supabase.co" },
                supabaseAnonKeyProvider = { "your-supabase-publishable-anon-key" }
            ),
            chatCacheDao = container.chatCacheDao
        )

        // 1. Initiate OTP signup and attempt signIn BEFORE verifying OTP -> must fail
        val email = "pending.otp@example.com"
        val initRes = authRepo.initiateSignUpOtp(email, "SecurePass123", "Pending User")
        assertTrue(initRes.isSuccess)
        val bypassAttempt = authRepo.signIn(email, "SecurePass123")
        assertTrue("Unverified OTP account must not bypass verification via signIn", bypassAttempt.isFailure)

        // 2. Verify OTP -> now signIn succeeds
        val code = authRepo.getLastIssuedOtpForVerification(email)!!
        val verifiedSession = authRepo.verifySignUpOtp(email, code).getOrThrow()
        val userId = verifiedSession.user.id

        // 3. Cancel an in-flight stream before any tokens are emitted -> verify no zombie STREAMING message remains in Room
        val conv = container.chatRepository.createConversation(userId, "Cancel Cleanly", "custom-model").getOrThrow()
        container.chatRepository.saveUserMessage(conv.id, userId, "Prompt", "custom-model", emptyList(), "req-cancel-0").getOrThrow()

        val slowEmptyClient = object : SupabaseAndGatewayClient(
            supabaseUrlProvider = { "https://your-project-ref.supabase.co" },
            supabaseAnonKeyProvider = { "your-supabase-publishable-anon-key" }
        ) {
            override fun streamChatCompletion(
                accessToken: String,
                conversationId: String,
                requestedModelId: String,
                fallbackModelId: String?,
                allowFallback: Boolean,
                history: List<ChatMessage>,
                streamEnabled: Boolean,
                clientRequestId: String
            ): Flow<StreamChunk> = flow {
                delay(500L)
                emit(StreamChunk.Delta("Late", requestedModelId, false))
            }
        }
        val availableTestModel = AiModelDefinition(
            providerModelId = "custom-model",
            displayName = "Test Model",
            category = com.example.domain.models.ModelCategory.GENERAL_CHAT,
            description = "Test model",
            capabilities = emptySet(),
            availabilityStatus = AvailabilityStatus.AVAILABLE
        )
        val fakeModelCatalogRepo = object : ModelCatalogRepository {
            override val catalogFlow: StateFlow<List<AiModelDefinition>> = MutableStateFlow(listOf(availableTestModel))
            override val isRefreshingFlow: StateFlow<Boolean> = MutableStateFlow(false)
            override val lastVerifiedEpochMsFlow: StateFlow<Long?> = MutableStateFlow(System.currentTimeMillis())
            override suspend fun refreshModelCatalog(): Result<List<AiModelDefinition>> = Result.success(catalogFlow.value)
            override fun getModelById(providerModelId: String): AiModelDefinition = availableTestModel
        }
        val cancelRepo = ChatRepositoryImpl(
            chatCacheDao = container.chatCacheDao,
            networkClient = slowEmptyClient,
            authRepository = authRepo,
            modelCatalogRepository = fakeModelCatalogRepo
        )

        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            cancelRepo.streamAssistantResponse(
                conversationId = conv.id,
                userId = userId,
                requestedModelId = "custom-model",
                fallbackModelId = null,
                allowFallback = false,
                history = emptyList(),
                streamEnabled = true,
                clientRequestId = "req-cancel-empty"
            ).collect {}
        }
        delay(80L)
        job.cancel()
        job.join()

        val storedAfterCancel = container.chatRepository.observeMessages(conv.id, userId).first()
        assertEquals("Empty cancelled assistant placeholder must be deleted so only the user message remains", 1, storedAfterCancel.size)
        assertEquals(MessageRole.USER, storedAfterCancel.first().role)

        container.database.close()
    }

    // ========================================================================
    // 5. Markdown, Table, and Mathematical Notation Rendering Tests (Screenshot Regressions)
    // ========================================================================

    @Test
    fun `markdown and math renderer formats ZKP table bold cells, Schnorr protocol LaTeX notation, display equations, and preserves literal code symbols`() {
        val zkpAndSchnorrMarkdown = """
            ## 2. Technical Breakdown of a Zero-Knowledge Proof (ZKP)
            A zero-knowledge proof is a cryptographic protocol that lets a prover convince a verifier that a statement \(S\) is true, while revealing no additional information beyond the truth of \(S\).

            | Property | Definition |
            | --- | --- |
            | **Completeness** | If \(S\) is true, an honest verifier will be convinced by an honest prover. |
            | **Soundness** | If \(S\) is false, no cheating prover can convince an honest verifier except with negligible probability. |
            | **Zero-Knowledge** | An honest verifier learns nothing beyond the fact that \(S\) is true. |

            ### Common Construction: Interactive Proof with Challenge–Response
            6. Repetition
            - Repeat steps 2–5 \(k\) times to drive the cheating probability below \(2^{-k}\).

            #### Example: Schnorr Protocol (Proof of Knowledge of Discrete Log)
            - Statement: Prover knows \(x\) such that \(g^x = y\) in a group \(G\).
            - Commit: \(P\) picks random \(r\), sends \(t = g^r\).
            - Challenge: \(V\) sends random \(c\).
            - Response: \(P\) sends \(s = r + c \cdot x \mod q\).
            - Verification: \(V\) checks \(g^s = t \cdot y^c\).
            - Non-Interactive proof \(\pi\) and display equation:
            \[
            x^2 + y^2 = z^2
            \]
            > Cryptographic soundness guarantees security against polynomial-time provers.

            ```kotlin
            val pointer_expr = "a * b * c"
            val regex_path = "C:\\Users\\admin\\file_name_test"
            ```
        """.trimIndent()

        // 1. Verify parser produces structured Heading, Paragraph, TableBlock, BulletItem, MathBlock, Blockquote, and CodeBlock
        val blocks = com.example.ui.components.MarkdownParser.parseBlocks(zkpAndSchnorrMarkdown)
        assertTrue("Should parse TableBlock", blocks.any { it is com.example.ui.components.MarkdownBlock.TableBlock })
        assertTrue("Should parse MathBlock", blocks.any { it is com.example.ui.components.MarkdownBlock.MathBlock })
        assertTrue("Should parse Blockquote", blocks.any { it is com.example.ui.components.MarkdownBlock.Blockquote })
        assertTrue("Should parse CodeBlock", blocks.any { it is com.example.ui.components.MarkdownBlock.CodeBlock })

        // 2. Verify MathNotationFormatter and inline parser clean all raw LaTeX delimiters and Markdown markers
        val formatted2MinusK = com.example.ui.components.MathNotationFormatter.formatMathExpression("""\(2^{-k}\)""")
        val formattedDiscreteLog = com.example.ui.components.MathNotationFormatter.formatMathExpression("""\(g^x = y\)""")
        val formattedSchnorrResponse = com.example.ui.components.MathNotationFormatter.formatMathExpression("""\(s = r + c \cdot x \mod q\)""")
        val formattedSchnorrVerify = com.example.ui.components.MathNotationFormatter.formatMathExpression("""\(g^s = t \cdot y^c\)""")
        val formattedPi = com.example.ui.components.MathNotationFormatter.formatMathExpression("""\(\pi\)""")

        assertEquals("2⁻ᵏ", formatted2MinusK)
        assertEquals("gˣ = y", formattedDiscreteLog)
        assertEquals("s = r + c · x mod q", formattedSchnorrResponse)
        assertEquals("gˢ = t · yᶜ", formattedSchnorrVerify)
        assertEquals("π", formattedPi)

        // 3. Render inside Compose and verify table cells & math expressions display without raw ** or \( \)
        composeTestRule.setContent {
            com.example.ui.theme.NexusAiTheme {
                com.example.ui.components.MarkdownMessageContent(
                    content = zkpAndSchnorrMarkdown,
                    renderMarkdown = true
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("markdown_table_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Completeness").assertIsDisplayed()
        composeTestRule.onNodeWithText("Soundness").assertIsDisplayed()
        composeTestRule.onNodeWithText("Zero-Knowledge").assertIsDisplayed()
        composeTestRule.onNodeWithText("x² + y² = z²").assertExists()

        // Verify literal asterisks, backslashes, and underscores inside fenced code blocks are preserved untouched
        val codeBlock = blocks.filterIsInstance<com.example.ui.components.MarkdownBlock.CodeBlock>().first()
        assertTrue(codeBlock.code.contains("""val pointer_expr = "a * b * c""""))
        assertTrue(codeBlock.code.contains("""C:\\Users\\admin\\file_name_test"""))
        composeTestRule.onNodeWithText("""val pointer_expr = "a * b * c"""", substring = true).assertExists()
        composeTestRule.onNodeWithText("""C:\\Users\\admin\\file_name_test""", substring = true).assertExists()
    }

    // ========================================================================
    // 6. Image Generation Pipeline, HTTP Status Handling, Cancellation & UI Tests
    // ========================================================================

    @Test
    fun `image generation handles 200 OK decoding, repeated prompts, watermark-free Gradio pipeline, 503 with and without Retry-After, 400, 401, 402, 403, 429, invalid payload, timeout, and cancellation`() = runBlocking {
        // Generate two distinct valid 4x4 JPEG byte payloads for testing
        val bitmap1 = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap1.eraseColor(android.graphics.Color.BLUE)
        val jpegStream1 = java.io.ByteArrayOutputStream()
        bitmap1.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, jpegStream1)
        val validJpegBytes1 = jpegStream1.toByteArray()

        val bitmap2 = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap2.eraseColor(android.graphics.Color.GREEN)
        val jpegStream2 = java.io.ByteArrayOutputStream()
        bitmap2.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, jpegStream2)
        val validJpegBytes2 = jpegStream2.toByteArray()

        fun buildHttpResponse(
            request: okhttp3.Request,
            code: Int,
            bodyBytes: ByteArray,
            contentType: String = "application/json",
            headers: Map<String, String> = emptyMap()
        ): okhttp3.Response {
            val mediaType = contentType.toMediaType()
            val builder = okhttp3.Response.Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(code)
                .message("HTTP $code")
                .header("Content-Type", contentType)
                .body(bodyBytes.toResponseBody(mediaType))
            headers.forEach { (k, v) -> builder.header(k, v) }
            return builder.build()
        }

        // 1. First image generation and second image generation with a DIFFERENT prompt succeed with distinct seeds/URLs
        val capturedUrls = mutableListOf<String>()
        val multiGenClient = SupabaseAndGatewayClient(
            imageRetryBackoffBaseMs = 10L,
            customHttpExecutor = { req ->
                val url = req.url.toString()
                capturedUrls.add(url)
                val payload = if (capturedUrls.size == 1) validJpegBytes1 else validJpegBytes2
                buildHttpResponse(req, 200, payload, "image/jpeg")
            }
        )
        val firstGen = multiGenClient.generatePollinationsImage(
            accessToken = null,
            prompt = "futuristic glass observatory at sunset",
            modelId = "sana",
            width = 1024,
            height = 1024,
            seed = 101,
            enhance = false
        )
        val secondGen = multiGenClient.generatePollinationsImage(
            accessToken = null,
            prompt = "cyberpunk samurai in neon rain",
            modelId = "flux",
            width = 1024,
            height = 1024,
            seed = 202,
            enhance = false
        )
        assertEquals(2, capturedUrls.size)
        assertTrue("First URL must encode first prompt", capturedUrls[0].contains("futuristic%20glass%20observatory"))
        assertTrue("Second URL must encode second prompt", capturedUrls[1].contains("cyberpunk%20samurai%20in%20neon%20rain"))
        assertTrue("Non-sana fallback model IDs must normalize to model=sana on Pollinations endpoint", capturedUrls[1].contains("model=sana"))
        assertEquals("futuristic glass observatory at sunset", firstGen.prompt)
        assertEquals("cyberpunk samurai in neon rain", secondGen.prompt)
        assertFalse("First and second generated images must have distinct IDs", firstGen.id == secondGen.id)

        // 2. Watermark-free FLUX.1-schnell Gradio SSE pipeline succeeds across consecutive prompts without watermarks
        val gradioPrompts = mutableListOf<String>()
        val watermarkFreeClient = SupabaseAndGatewayClient(
            preferWatermarkFreeImageProvider = true,
            imageRetryBackoffBaseMs = 10L,
            customHttpExecutor = { req ->
                val url = req.url.toString()
                when {
                    url.endsWith("/gradio_api/call/infer") && req.method == "POST" -> {
                        val buffer = okio.Buffer()
                        req.body?.writeTo(buffer)
                        gradioPrompts.add(buffer.readUtf8())
                        buildHttpResponse(
                            req,
                            200,
                            """{"event_id":"evt-${gradioPrompts.size}"}""".toByteArray(),
                            "application/json"
                        )
                    }
                    url.contains("/gradio_api/call/infer/evt-") && req.method == "GET" -> {
                        val sse = "event: complete\ndata: [{\"url\":\"https://black-forest-labs-flux-1-schnell.hf.space/gradio_api/file=/tmp/out_${gradioPrompts.size}.webp\",\"mime_type\":\"image/webp\"}, 777]\n\n"
                        buildHttpResponse(req, 200, sse.toByteArray(), "text/event-stream")
                    }
                    url.contains("/gradio_api/file=/tmp/out_") -> {
                        buildHttpResponse(req, 200, validJpegBytes1, "image/jpeg")
                    }
                    else -> buildHttpResponse(req, 404, """{"error":"Not found"}""".toByteArray())
                }
            }
        )
        val wfImage1 = watermarkFreeClient.generatePollinationsImage(null, "first unwatermarked prompt", "sana", 1024, 1024)
        val wfImage2 = watermarkFreeClient.generatePollinationsImage(null, "second unwatermarked prompt", "flux", 1024, 1024)
        assertEquals(2, gradioPrompts.size)
        assertTrue(gradioPrompts[0].contains("first unwatermarked prompt"))
        assertTrue(gradioPrompts[1].contains("second unwatermarked prompt"))
        assertTrue(wfImage1.imageBytes.isNotEmpty())
        assertTrue(wfImage2.imageBytes.isNotEmpty())

        // 3. HTTP 503 with Retry-After header -> automatically waits and succeeds on retry
        var calls503WithHeader = 0
        val client503WithRetryHeader = SupabaseAndGatewayClient(
            maxImageAutoRetrySeconds = 5,
            imageRetryBackoffBaseMs = 10L,
            customHttpExecutor = { req ->
                calls503WithHeader += 1
                if (calls503WithHeader == 1) {
                    buildHttpResponse(
                        req,
                        503,
                        """{"error":"Service temporarily overloaded"}""".toByteArray(),
                        headers = mapOf("Retry-After" to "1")
                    )
                } else {
                    buildHttpResponse(req, 200, validJpegBytes2, "image/jpeg")
                }
            }
        )
        val recoveredFrom503Header = client503WithRetryHeader.generatePollinationsImage(
            null,
            "mountain lake reflection",
            "sana",
            1024,
            1024
        )
        assertEquals(2, calls503WithHeader)
        assertTrue(recoveredFrom503Header.imageBytes.isNotEmpty())

        // 4. HTTP 503 without Retry-After header -> uses bounded exponential backoff and recovers when transient
        var calls503NoHeader = 0
        val client503WithoutHeaderRecovery = SupabaseAndGatewayClient(
            imageRetryBackoffBaseMs = 10L,
            customHttpExecutor = { req ->
                calls503NoHeader += 1
                if (calls503NoHeader < 3) {
                    buildHttpResponse(req, 503, """{"error":"Worker busy"}""".toByteArray())
                } else {
                    buildHttpResponse(req, 200, validJpegBytes1, "image/jpeg")
                }
            }
        )
        val recoveredFrom503NoHeader = client503WithoutHeaderRecovery.generatePollinationsImage(
            null,
            "ancient library in sequoia tree",
            "sana",
            1024,
            1024
        )
        assertEquals(3, calls503NoHeader)
        assertTrue(recoveredFrom503NoHeader.imageBytes.isNotEmpty())

        // 5. Persistent HTTP 503 without Retry-After header -> stops after bounded retries (3 attempts) and surfaces SERVER_ERROR (503)
        var persistent503Calls = 0
        val client503Persistent = SupabaseAndGatewayClient(
            imageRetryBackoffBaseMs = 10L,
            customHttpExecutor = { req ->
                persistent503Calls += 1
                buildHttpResponse(req, 503, """{"error":"Service Unavailable"}""".toByteArray())
            }
        )
        val err503 = runCatching {
            client503Persistent.generatePollinationsImage(null, "test 503", "sana", 1024, 1024)
        }.exceptionOrNull() as? AiGatewayException
        assertEquals("Must stop after bounded 3 attempts", 3, persistent503Calls)
        assertEquals(AiErrorType.SERVER_ERROR, err503?.errorType)
        assertEquals(503, err503?.statusCode)

        // 6. Blank prompt -> 400 validation error
        val blankErr = runCatching {
            multiGenClient.generatePollinationsImage(null, "   ", "sana", 1024, 1024)
        }.exceptionOrNull() as? AiGatewayException
        assertNotNull(blankErr)
        assertEquals(400, blankErr?.statusCode)

        // 7. HTTP 400 Invalid Request
        val client400 = SupabaseAndGatewayClient(
            imageRetryBackoffBaseMs = 10L,
            customHttpExecutor = { req ->
                buildHttpResponse(req, 400, """{"error":"Prompt violates safety policy"}""".toByteArray())
            }
        )
        val err400 = runCatching {
            client400.generatePollinationsImage(null, "test prompt", "sana", 1024, 1024)
        }.exceptionOrNull() as? AiGatewayException
        assertEquals(AiErrorType.UNKNOWN, err400?.errorType)
        assertEquals(400, err400?.statusCode)

        // 8. HTTP 401 / 402 / 403 Authentication & Permission Errors
        for (status in listOf(401, 402, 403)) {
            val authErrClient = SupabaseAndGatewayClient(
                imageRetryBackoffBaseMs = 10L,
                customHttpExecutor = { req ->
                    buildHttpResponse(req, status, """{"error":"Auth/permission error $status"}""".toByteArray())
                }
            )
            val ex = runCatching {
                authErrClient.generatePollinationsImage(null, "test prompt", "sana", 1024, 1024)
            }.exceptionOrNull() as? AiGatewayException
            assertEquals(AiErrorType.UNAUTHORIZED, ex?.errorType)
            assertEquals(status, ex?.statusCode)
        }

        // 9. HTTP 429 Rate Limit -> automatic Retry-After recovery on second attempt, and RATE_LIMITED when exceeding threshold
        var rateLimitCalls = 0
        val client429Recovery = SupabaseAndGatewayClient(
            maxImageAutoRetrySeconds = 5,
            imageRetryBackoffBaseMs = 10L,
            customHttpExecutor = { req ->
                rateLimitCalls += 1
                if (rateLimitCalls == 1) {
                    buildHttpResponse(
                        req,
                        429,
                        """{"error":"Too Many Requests"}""".toByteArray(),
                        headers = mapOf("Retry-After" to "1")
                    )
                } else {
                    buildHttpResponse(req, 200, validJpegBytes1, "image/jpeg")
                }
            }
        )
        val recoveredResult = client429Recovery.generatePollinationsImage(null, "sunrise", "sana", 1024, 1024)
        assertEquals(2, rateLimitCalls)
        assertTrue(recoveredResult.imageBytes.isNotEmpty())

        val client429Fail = SupabaseAndGatewayClient(
            maxImageAutoRetrySeconds = 2,
            imageRetryBackoffBaseMs = 10L,
            customHttpExecutor = { req ->
                buildHttpResponse(
                    req,
                    429,
                    """{"error":"Rate limit"}""".toByteArray(),
                    headers = mapOf("Retry-After" to "60")
                )
            }
        )
        val err429 = runCatching {
            client429Fail.generatePollinationsImage(null, "sunrise", "sana", 1024, 1024)
        }.exceptionOrNull() as? AiGatewayException
        assertEquals(AiErrorType.RATE_LIMITED, err429?.errorType)
        assertEquals(429, err429?.statusCode)

        // 10. Invalid / corrupted image payload (HTML error page with 200 OK) -> rejected and never reported as valid image
        val clientCorruptPayload = SupabaseAndGatewayClient(
            imageRetryBackoffBaseMs = 10L,
            customHttpExecutor = { req ->
                buildHttpResponse(
                    req,
                    200,
                    "<html><body>Upstream gateway timeout</body></html>".toByteArray(),
                    "image/jpeg"
                )
            }
        )
        val errCorrupt = runCatching {
            clientCorruptPayload.generatePollinationsImage(null, "sunrise", "sana", 1024, 1024)
        }.exceptionOrNull() as? AiGatewayException
        assertNotNull("Corrupt non-image bytes must be rejected", errCorrupt)
        assertEquals(AiErrorType.SERVER_ERROR, errCorrupt?.errorType)
        assertTrue(errCorrupt?.message?.contains("invalid or incomplete image payload", ignoreCase = true) == true)

        // 11. SocketTimeoutException -> surfaces NETWORK_TIMEOUT
        val clientTimeout = SupabaseAndGatewayClient(
            imageRetryBackoffBaseMs = 10L,
            customHttpExecutor = { _ ->
                throw java.net.SocketTimeoutException("Read timed out")
            }
        )
        val errTimeout = runCatching {
            clientTimeout.generatePollinationsImage(null, "sunrise", "sana", 1024, 1024)
        }.exceptionOrNull() as? AiGatewayException
        assertEquals(AiErrorType.NETWORK_TIMEOUT, errTimeout?.errorType)

        // 12. Coroutine cancellation during retry delay -> cancels cleanly with CancellationException
        val slowRetryClient = SupabaseAndGatewayClient(
            maxImageAutoRetrySeconds = 10,
            imageRetryBackoffBaseMs = 5_000L,
            customHttpExecutor = { req ->
                buildHttpResponse(req, 503, """{"error":"Busy"}""".toByteArray(), headers = mapOf("Retry-After" to "5"))
            }
        )
        var wasCancelled = false
        val cancelJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            try {
                slowRetryClient.generatePollinationsImage(null, "cancel test", "sana", 1024, 1024)
            } catch (_: kotlinx.coroutines.CancellationException) {
                wasCancelled = true
            }
        }
        delay(80L)
        cancelJob.cancel()
        cancelJob.join()
        assertTrue("Coroutine cancellation during image retry wait must propagate CancellationException", wasCancelled)
    }

    @Test
    fun `ImageGenerationScreen UI supports consecutive prompts, preserves first image when second request fails, cleans up loading state, and succeeds on retry`() {
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.CYAN)
        val jpegStream = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, jpegStream)
        val validJpegBytes = jpegStream.toByteArray()

        var requestCounter = 0
        val uiTestClient = SupabaseAndGatewayClient(
            maxImageServerRetries = 0,
            imageRetryBackoffBaseMs = 5L,
            customHttpExecutor = { req ->
                requestCounter += 1
                val mediaType = "image/jpeg".toMediaType()
                if (requestCounter == 2) {
                    // Simulate second generation failing with HTTP 503
                    okhttp3.Response.Builder()
                        .request(req)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(503)
                        .message("Service Unavailable")
                        .header("Content-Type", "application/json")
                        .body("""{"error":"Temporary overload"}""".toByteArray().toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    okhttp3.Response.Builder()
                        .request(req)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Type", "image/jpeg")
                        .body(validJpegBytes.toResponseBody(mediaType))
                        .build()
                }
            }
        )

        composeTestRule.setContent {
            com.example.ui.theme.NexusAiTheme {
                com.example.ui.imagegen.ImageGenerationScreen(
                    accessToken = null,
                    networkClient = uiTestClient,
                    onBack = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. First prompt -> succeeds and displays generated_image_card
        composeTestRule.onNodeWithTag("image_studio_lazy_list")
            .performScrollToIndex(1)
        composeTestRule.onNodeWithTag("sample_image_prompt_0").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("image_studio_lazy_list")
            .performScrollToIndex(4)
        composeTestRule.onNodeWithTag("generate_image_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeTestRule.onNodeWithTag("image_studio_lazy_list")
                    .performScrollToIndex(5)
                composeTestRule.onNodeWithTag("generated_image_card").assertExists()
                true
            }.getOrDefault(false)
        }
        composeTestRule.onNodeWithText(
            "Futuristic glass observatory floating above neon clouds at sunset, cinematic lighting"
        ).assertExists()

        // 2. Second prompt -> fails with 503, displays error banner, cleans up loading state, and PRESERVES first image card
        composeTestRule.onNodeWithTag("image_studio_lazy_list")
            .performScrollToIndex(1)
        composeTestRule.onNodeWithTag("sample_image_prompt_1").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("image_studio_lazy_list")
            .performScrollToIndex(4)
        composeTestRule.onNodeWithTag("generate_image_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeTestRule.onNodeWithTag("image_studio_lazy_list")
                    .performScrollToIndex(5)
                composeTestRule.onNodeWithTag("image_generation_error_banner").assertExists()
                true
            }.getOrDefault(false)
        }
        composeTestRule.onNodeWithTag("image_studio_lazy_list")
            .performScrollToIndex(6)
        composeTestRule.onNodeWithTag("generated_image_card").assertExists()
        composeTestRule.onNodeWithText(
            "Futuristic glass observatory floating above neon clouds at sunset, cinematic lighting"
        ).assertExists()

        // 3. Retry second prompt -> succeeds and updates active image card + Recent Generations history chips
        composeTestRule.onNodeWithTag("image_studio_lazy_list")
            .performScrollToIndex(4)
        composeTestRule.onNodeWithTag("generate_image_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeTestRule.onNodeWithTag("image_studio_lazy_list")
                    .performScrollToIndex(6)
                composeTestRule.onNodeWithTag("image_history_chip_0").assertExists()
                true
            }.getOrDefault(false)
        }
        composeTestRule.onNodeWithTag("image_history_chip_0").assertExists()
        composeTestRule.onNodeWithTag("image_history_chip_1").assertExists()
    }

    // ========================================================================
    // 7. Brevo OTP Server-Side Verification, Failure Modes & Existing Account Login Tests
    // ========================================================================

    @Test
    fun `when cloud is configured, OTP signup handles missing Brevo config (503), Brevo delivery error (502), unverified login rejection, existing verified account login, and server OTP verification`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("nexus_auth_session_store", Context.MODE_PRIVATE).edit().clear().commit()
        val container = DefaultAppContainer(context, inMemoryDb = true)

        fun buildJsonResponse(request: okhttp3.Request, code: Int, jsonBody: String): okhttp3.Response {
            val mediaType = "application/json".toMediaType()
            return okhttp3.Response.Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(code)
                .message("HTTP $code")
                .header("Content-Type", "application/json")
                .body(jsonBody.toByteArray().toResponseBody(mediaType))
                .build()
        }

        val validThreePartJwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiJ9.validSig123456"

        // Case 1: Brevo secret missing on server (HTTP 503) -> must fail honestly and NEVER fall back to local fake OTP
        val unconfiguredBrevoClient = SupabaseAndGatewayClient(
            supabaseUrlProvider = { "https://xwkfyamulhfptgywlvef.supabase.co" },
            supabaseAnonKeyProvider = { validThreePartJwt },
            aiBackendBaseUrlProvider = { "https://xwkfyamulhfptgywlvef.supabase.co/functions/v1" },
            customHttpExecutor = { req ->
                buildJsonResponse(
                    req,
                    503,
                    """{"error":"Email delivery service is not configured on the server. Please configure BREVO_API_KEY and BREVO_SENDER_EMAIL in Supabase secrets."}"""
                )
            }
        )
        val authRepoUnconfigured = SupabaseAuthRepository(
            context = context,
            networkClient = unconfiguredBrevoClient,
            chatCacheDao = container.chatCacheDao
        )
        val pendingConfigResult = authRepoUnconfigured.initiateSignUpOtp(
            email = "real.user@example.com",
            password = "SecurePass123",
            displayName = "Real User"
        )
        assertTrue(
            "When Brevo is not configured on server, initiateSignUpOtp must fail honestly instead of pretending email was sent",
            pendingConfigResult.isFailure
        )
        assertTrue(
            "Error message must explain server email delivery configuration issue",
            pendingConfigResult.exceptionOrNull()?.message?.contains("not configured", ignoreCase = true) == true
        )
        assertNull(
            "Must NOT generate a local fallback OTP when live cloud is configured",
            authRepoUnconfigured.getLastIssuedOtpForVerification("real.user@example.com")
        )
        assertNull(authRepoUnconfigured.sessionFlow.value)

        // Case 2: Brevo provider email-delivery failure (HTTP 502) -> surfaces honest delivery error and creates no session
        val brevoFailureClient = SupabaseAndGatewayClient(
            supabaseUrlProvider = { "https://xwkfyamulhfptgywlvef.supabase.co" },
            supabaseAnonKeyProvider = { validThreePartJwt },
            aiBackendBaseUrlProvider = { "https://xwkfyamulhfptgywlvef.supabase.co/functions/v1" },
            customHttpExecutor = { req ->
                buildJsonResponse(
                    req,
                    502,
                    """{"error":"We couldn't send your verification code. Please check your email address or try again shortly."}"""
                )
            }
        )
        val authRepoBrevoFail = SupabaseAuthRepository(
            context = context,
            networkClient = brevoFailureClient,
            chatCacheDao = container.chatCacheDao
        )
        val brevoFailRes = authRepoBrevoFail.initiateSignUpOtp(
            email = "delivery.fail@example.com",
            password = "SecurePass123",
            displayName = "Delivery Fail"
        )
        assertTrue("Brevo 502 delivery failure must fail", brevoFailRes.isFailure)
        assertTrue(
            brevoFailRes.exceptionOrNull()?.message?.contains("couldn't send your verification code", ignoreCase = true) == true
        )
        assertNull(authRepoBrevoFail.sessionFlow.value)

        // Case 3: Edge Function deployed + Brevo configured -> server sends OTP, verifies 6-digit code, blocks unverified login, and allows existing verified account login
        val serverConfiguredClient = SupabaseAndGatewayClient(
            supabaseUrlProvider = { "https://xwkfyamulhfptgywlvef.supabase.co" },
            supabaseAnonKeyProvider = { validThreePartJwt },
            aiBackendBaseUrlProvider = { "https://xwkfyamulhfptgywlvef.supabase.co/functions/v1" },
            customHttpExecutor = { req ->
                val url = req.url.toString()
                when {
                    url.endsWith("/send-otp") -> {
                        buildJsonResponse(
                            req,
                            200,
                            """
                            {
                              "status": "otp_sent",
                              "email": "verified.user@example.com",
                              "user_id": "user-uuid-901",
                              "expires_in_seconds": 300,
                              "resend_cooldown_seconds": 45,
                              "max_attempts": 5,
                              "message": "We sent a 6-digit verification code to verified.user@example.com."
                            }
                            """.trimIndent()
                        )
                    }
                    url.endsWith("/verify-otp") -> {
                        buildJsonResponse(
                            req,
                            200,
                            """
                            {
                              "status": "verified",
                              "session": {
                                "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.server-jwt",
                                "refresh_token": "server-refresh-token",
                                "expires_in": 3600,
                                "user": {
                                  "id": "user-uuid-901",
                                  "email": "verified.user@example.com",
                                  "email_confirmed_at": "2026-11-01T12:00:00Z",
                                  "user_metadata": { "display_name": "Verified User" }
                                }
                              }
                            }
                            """.trimIndent()
                        )
                    }
                    url.contains("/auth/v1/token?grant_type=password") -> {
                        val buf = okio.Buffer()
                        req.body?.writeTo(buf)
                        val bodyStr = buf.readUtf8()
                        if (bodyStr.contains("unconfirmed.cloud@example.com")) {
                            buildJsonResponse(
                                req,
                                400,
                                """{"error":"invalid_grant","error_description":"Email not confirmed"}"""
                            )
                        } else {
                            buildJsonResponse(
                                req,
                                200,
                                """
                                {
                                  "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.existing-user-jwt",
                                  "refresh_token": "existing-user-refresh",
                                  "expires_in": 3600,
                                  "user": {
                                    "id": "user-existing-777",
                                    "email": "existing.verified@example.com",
                                    "email_confirmed_at": "2026-10-15T08:30:00Z",
                                    "user_metadata": { "display_name": "Existing Verified" }
                                  }
                                }
                                """.trimIndent()
                            )
                        }
                    }
                    else -> buildJsonResponse(req, 404, """{"error":"Not found"}""")
                }
            }
        )
        val authRepoConfigured = SupabaseAuthRepository(
            context = context,
            networkClient = serverConfiguredClient,
            chatCacheDao = container.chatCacheDao
        )

        // 3a. Unverified cloud account sign-in is rejected with clear verification prompt
        val unconfirmedLogin = authRepoConfigured.signIn("unconfirmed.cloud@example.com", "SecurePass123")
        assertTrue("Unconfirmed cloud account must be rejected on signIn", unconfirmedLogin.isFailure)
        assertTrue(
            "Error message must instruct user to verify with 6-digit code",
            unconfirmedLogin.exceptionOrNull()?.message?.contains("verify your email", ignoreCase = true) == true
        )

        // 3b. Existing verified account sign-in succeeds without regression
        val existingLogin = authRepoConfigured.signIn("existing.verified@example.com", "SecurePass123")
        assertTrue("Existing verified account sign-in must succeed", existingLogin.isSuccess)
        assertEquals("existing.verified@example.com", authRepoConfigured.sessionFlow.value?.user?.email)
        authRepoConfigured.signOut()

        // 3c. Full server-backed OTP signup + verification establishes authenticated cloud session
        val sendOtpRes = authRepoConfigured.initiateSignUpOtp(
            email = "verified.user@example.com",
            password = "SecurePass123",
            displayName = "Verified User"
        )
        assertTrue("Server OTP send should succeed", sendOtpRes.isSuccess)
        assertNull("Session must remain null until OTP is verified", authRepoConfigured.sessionFlow.value)

        val verifyOtpRes = authRepoConfigured.verifySignUpOtp("verified.user@example.com", "654321")
        assertTrue("Server OTP verification should establish authenticated session", verifyOtpRes.isSuccess)
        assertEquals("verified.user@example.com", authRepoConfigured.sessionFlow.value?.user?.email)
        assertTrue(authRepoConfigured.sessionFlow.value?.hasValidCloudJwt == true)

        container.database.close()
    }

    // ========================================================================
    // 8. Apinex Bond API Chat Models, Streaming/Non-Streaming & Media Studios Verification
    // ========================================================================

    @Test
    fun `model catalog contains exclusively the 12 Apinex Bond API models in exact order with exact display names and IDs`() {
        val expectedModels = listOf(
            "Muse Spark 1.3" to "free/muse-spark-1.3",
            "Gemini 3.8 Flash" to "free/gemini-3.8-flash",
            "Qwen 3.8 MAX" to "free/qwen-3.8-max",
            "Kimi K3" to "free/kimi-k3",
            "Deepseek V4.1 Flash 1017" to "free/deepseek-v4.1-flash",
            "GLM 5.3" to "free/glm-5.3",
            "GLM-5.3 Flash" to "free/glm-5.3-flash",
            "GPT 6 Luna" to "free/gpt-6-luna",
            "JEV 1.13" to "free/jev-1.13",
            "HY4" to "free/hy4",
            "MiMo V2.6 Flash" to "free/mimo-v2.6-flash",
            "MiniMax M3.1 Flash" to "free/minimax-m3.1-flash"
        )

        val actualModels = AiModelCatalog.INITIAL_DISCOVERY_MODELS.map { it.displayName to it.providerModelId }
        assertEquals("Chat model catalog must contain exclusively the 12 specified Apinex models in exact order", expectedModels, actualModels)
        assertEquals("free/glm-5.3-flash", AiModelCatalog.DEFAULT_MODEL_ID)

        // Verify all 12 models use AiProvider.APINEX, 1M context window, and hide "free/" in displayName
        AiModelCatalog.INITIAL_DISCOVERY_MODELS.forEach { model ->
            assertEquals(com.example.domain.models.AiProvider.APINEX, model.provider)
            assertEquals(1_000_000, model.contextWindowTokens)
            assertTrue(AiModelCatalog.isSupportedApinexModel(model.providerModelId))
            assertFalse("displayName must not contain free/", model.displayName.contains("free/", ignoreCase = true))
        }
        assertFalse(AiModelCatalog.isSupportedApinexModel("gpt-4o"))

        val imageNames = AiModelCatalog.POLLINATIONS_FREE_IMAGE_MODELS.map { it.displayName }
        assertTrue("Should include Smarter Flux Image", imageNames.contains("Smarter Flux Image"))

        val voiceNames = AiModelCatalog.POLLINATIONS_AUDIO_VOICES.map { it.displayName }
        assertTrue("Voice models should use Smarter Voice branding", voiceNames.all { it.startsWith("Smarter Voice") })
    }

    @Test
    fun `streamChatCompletion routes all 7 Apinex models with exact model IDs, parses SSE and JSON responses, preserves Hindi and Hinglish prompts, surfaces errors without silent fallback, and keeps Pollinations audio intact`() = runBlocking {
        val capturedModelIds = mutableListOf<String>()
        val capturedRequestBodies = mutableListOf<String>()

        // 1. Verify SSE streaming across all 7 Apinex models with exact model IDs
        val sseClient = SupabaseAndGatewayClient(
            supabaseUrlProvider = { "https://project-ref.supabase.co" },
            supabaseAnonKeyProvider = { "anon-key-123" },
            aiBackendBaseUrlProvider = { "https://project-ref.supabase.co/functions/v1/ai-chat" },
            customHttpExecutor = { req ->
                val buffer = okio.Buffer()
                req.body?.writeTo(buffer)
                val bodyStr = buffer.readUtf8()
                capturedRequestBodies.add(bodyStr)
                val modelId = org.json.JSONObject(bodyStr).optString("model")
                capturedModelIds.add(modelId)

                val ssePayload = buildString {
                    append("data: {\"id\":\"chatcmpl-1\",\"model\":\"$modelId\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello from \"}}]}\n\n")
                    append("data: {\"id\":\"chatcmpl-1\",\"model\":\"$modelId\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$modelId!\"}}]}\n\n")
                    append("data: [DONE]\n\n")
                }
                okhttp3.Response.Builder()
                    .request(req)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .addHeader("Content-Type", "text/event-stream")
                    .body(ssePayload.toResponseBody("text/event-stream".toMediaType()))
                    .build()
            }
        )

        for (model in AiModelCatalog.INITIAL_DISCOVERY_MODELS) {
            val chunks = mutableListOf<StreamChunk>()
            sseClient.streamChatCompletion(
                accessToken = "valid-jwt",
                conversationId = "conv-apinex",
                requestedModelId = model.providerModelId,
                fallbackModelId = null,
                allowFallback = false,
                history = listOf(
                    ChatMessage("m1", "conv-apinex", "u1", MessageRole.USER, "Bhai kaise ho?", model.providerModelId)
                ),
                streamEnabled = true,
                clientRequestId = "req-${model.providerModelId}"
            ).collect { chunks.add(it) }

            val completed = chunks.filterIsInstance<StreamChunk.Completed>().firstOrNull()
            assertNotNull("Model ${model.providerModelId} should complete SSE stream", completed)
            assertEquals("Hello from ${model.providerModelId}!", completed!!.fullText)
            assertEquals(model.providerModelId, completed.activeModelId)
        }

        assertEquals(
            AiModelCatalog.INITIAL_DISCOVERY_MODELS.map { it.providerModelId },
            capturedModelIds
        )
        assertTrue(
            "System instructions must include Hindi/Hinglish language matching guidance",
            capturedRequestBodies.first().contains("Hinglish") && capturedRequestBodies.first().contains("Devanagari")
        )

        // 2. Verify non-streaming JSON response parsing (streamEnabled = false) and <think> stripping
        val jsonClient = SupabaseAndGatewayClient(
            supabaseUrlProvider = { "https://project-ref.supabase.co" },
            supabaseAnonKeyProvider = { "anon-key-123" },
            aiBackendBaseUrlProvider = { "https://project-ref.supabase.co/functions/v1/ai-chat" },
            customHttpExecutor = { req ->
                val jsonPayload = """{"id":"chatcmpl-2","model":"free/deepseek-v4.1-flash","choices":[{"index":0,"message":{"role":"assistant","content":"<think>internal reasoning</think>Clean final answer."}}]}"""
                okhttp3.Response.Builder()
                    .request(req)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .addHeader("Content-Type", "application/json")
                    .body(jsonPayload.toResponseBody("application/json".toMediaType()))
                    .build()
            }
        )
        val nonStreamChunks = mutableListOf<StreamChunk>()
        jsonClient.streamChatCompletion(
            accessToken = "valid-jwt",
            conversationId = "conv-json",
            requestedModelId = "free/deepseek-v4.1-flash",
            fallbackModelId = null,
            allowFallback = false,
            history = listOf(ChatMessage("m1", "conv-json", "u1", MessageRole.USER, "Test non-streaming", "free/deepseek-v4.1-flash")),
            streamEnabled = false,
            clientRequestId = "req-json"
        ).collect { nonStreamChunks.add(it) }

        val nonStreamCompleted = nonStreamChunks.filterIsInstance<StreamChunk.Completed>().firstOrNull()
        assertNotNull(nonStreamCompleted)
        assertEquals("Clean final answer.", nonStreamCompleted!!.fullText)

        // 3. Verify HTTP 429 / 401 / 404 error mapping without silent model substitution
        val errorClient = SupabaseAndGatewayClient(
            supabaseUrlProvider = { "https://project-ref.supabase.co" },
            supabaseAnonKeyProvider = { "anon-key-123" },
            aiBackendBaseUrlProvider = { "https://project-ref.supabase.co/functions/v1/ai-chat" },
            customHttpExecutor = { req ->
                okhttp3.Response.Builder()
                    .request(req)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .addHeader("Content-Type", "application/json")
                    .body("""{"error":{"message":"Apinex rate limit exceeded for free/kimi-k3.","code":"RATE_LIMIT"}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        )
        val errChunks = mutableListOf<StreamChunk>()
        errorClient.streamChatCompletion(
            accessToken = "valid-jwt",
            conversationId = "conv-err",
            requestedModelId = "free/kimi-k3",
            fallbackModelId = "free/glm-5.3-flash",
            allowFallback = true,
            history = listOf(ChatMessage("m1", "conv-err", "u1", MessageRole.USER, "Hello", "free/kimi-k3")),
            streamEnabled = true,
            clientRequestId = "req-err"
        ).collect { errChunks.add(it) }

        val rateLimitErr = errChunks.filterIsInstance<StreamChunk.Error>().firstOrNull()
        assertNotNull(rateLimitErr)
        assertEquals(AiErrorType.RATE_LIMITED, rateLimitErr!!.exception.errorType)
        assertTrue(rateLimitErr.exception.userFriendlyMessage.contains("rate limit", ignoreCase = true))

        // 4. Verify Pollinations Audio Studio speech generation remains intact on text.pollinations.ai
        var capturedAudioUrl = ""
        val fakeMp3Bytes = ByteArray(512) { 0x49 }
        val audioClient = SupabaseAndGatewayClient(
            customHttpExecutor = { req ->
                capturedAudioUrl = req.url.toString()
                okhttp3.Response.Builder()
                    .request(req)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .addHeader("Content-Type", "audio/mpeg")
                    .body(fakeMp3Bytes.toResponseBody("audio/mpeg".toMediaType()))
                    .build()
            }
        )
        val speechResult = audioClient.generatePollinationsSpeech(
            accessToken = null,
            text = "Welcome to Smarter AI voice synthesis.",
            voiceId = "nova"
        )
        assertTrue(
            "Pollinations audio generation must route to text.pollinations.ai with model=openai-audio",
            capturedAudioUrl.contains("text.pollinations.ai") &&
                capturedAudioUrl.contains("model=openai-audio") &&
                capturedAudioUrl.contains("voice=nova")
        )
        assertEquals("nova", speechResult.voiceId)
        assertEquals(512, speechResult.audioBytes?.size)
    }

    @Test
    fun `image analysis status indicator displays Analyzing Provided Image only during active image analysis and disappears on completion, error, or cancellation`() {
        // 1. When an image is only attached in the composer (before sending), "Analyzing Provided Image" must NOT appear
        val attachment = MessageAttachment(
            id = "att-img-1",
            uriString = "content://test/image.jpg",
            mimeType = "image/jpeg",
            fileName = "sample.jpg",
            base64DataUrl = "data:image/jpeg;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )

        val currentUiState = androidx.compose.runtime.mutableStateOf(
            com.example.ui.chat.ChatUiState(
                activeUserId = "user-test-1",
                activeConversationId = "conv-img-1",
                selectedModel = AiModelCatalog.findById("free/gemini-3.8-flash"),
                pendingAttachments = listOf(attachment),
                isGenerating = false,
                isAnalyzingImage = false
            )
        )

        composeTestRule.setContent {
            com.example.ui.theme.NexusAiTheme {
                com.example.ui.chat.ChatScreen(
                    uiState = currentUiState.value,
                    onStartNewChat = {},
                    onSelectConversation = {},
                    onRenameConversation = { _, _ -> },
                    onDeleteConversation = {},
                    onOpenSearchScreen = {},
                    onOpenSettingsScreen = {},
                    onSignOut = {},
                    onOpenModelSelector = {},
                    onCloseModelSelector = {},
                    onModelSearchQueryChanged = {},
                    onModelCategorySelected = {},
                    onRefreshModelCatalog = {},
                    onSelectModel = {},
                    onComposerTextChanged = {},
                    onApplyPromptSuggestion = {},
                    onAddAttachment = {},
                    onRemoveAttachment = {},
                    onSendMessage = {},
                    onStopGeneration = {},
                    onRegenerateResponse = {},
                    onRetryFailedGeneration = {},
                    onStartEditingMessage = {},
                    onCancelEditingMessage = {},
                    onDismissBanners = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("image_analysis_status_indicator").assertDoesNotExist()

        // 2. When an ordinary text-only message is generating, "Analyzing Provided Image" must NOT appear
        val userTextMsg = ChatMessage(
            id = "msg-u-text",
            conversationId = "conv-img-1",
            userId = "user-test-1",
            role = MessageRole.USER,
            content = "Explain artificial intelligence.",
            modelId = "free/gemini-3.8-flash",
            status = MessageStatus.COMPLETED
        )
        val streamingTextMsg = ChatMessage(
            id = "msg-a-text",
            conversationId = "conv-img-1",
            userId = "user-test-1",
            role = MessageRole.ASSISTANT,
            content = "",
            modelId = "free/gemini-3.8-flash",
            status = MessageStatus.STREAMING
        )
        currentUiState.value = currentUiState.value.copy(
            pendingAttachments = emptyList(),
            messages = listOf(userTextMsg, streamingTextMsg),
            isGenerating = true,
            isAnalyzingImage = false
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("image_analysis_status_indicator").assertDoesNotExist()

        // 3. When an image-analysis request starts (isGenerating = true, isAnalyzingImage = true), "Analyzing Provided Image" MUST appear immediately
        val userImgMsg = ChatMessage(
            id = "msg-u-img",
            conversationId = "conv-img-1",
            userId = "user-test-1",
            role = MessageRole.USER,
            content = "Analyze this image.",
            modelId = "free/gemini-3.8-flash",
            attachments = listOf(attachment),
            status = MessageStatus.COMPLETED
        )
        val streamingImgMsg = ChatMessage(
            id = "msg-a-img",
            conversationId = "conv-img-1",
            userId = "user-test-1",
            role = MessageRole.ASSISTANT,
            content = "",
            modelId = "free/gemini-3.8-flash",
            status = MessageStatus.STREAMING
        )
        currentUiState.value = currentUiState.value.copy(
            messages = listOf(userImgMsg, streamingImgMsg),
            generationState = com.example.domain.models.GenerationState.WAITING_FOR_FIRST_TOKEN,
            imageAnalysisState = com.example.domain.models.ImageAnalysisState.IMAGE_ANALYSIS_STARTED,
            isGenerating = true,
            isAnalyzingImage = true,
            streamingMessageText = ""
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("image_analysis_status_indicator").assertIsDisplayed()
        composeTestRule.onNodeWithText("Analyzing Provided Image").assertIsDisplayed()

        // 3b. As soon as the FIRST response token arrives (RESPONSE_GENERATION / streamingMessageText non-empty),
        // "Analyzing Provided Image" MUST disappear immediately even while isGenerating == true!
        currentUiState.value = currentUiState.value.copy(
            generationState = com.example.domain.models.GenerationState.STREAMING,
            imageAnalysisState = com.example.domain.models.ImageAnalysisState.RESPONSE_GENERATION,
            isGenerating = true,
            isAnalyzingImage = false,
            streamingMessageText = "This image shows a minimalist"
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("image_analysis_status_indicator").assertDoesNotExist()
        composeTestRule.onNodeWithText("This image shows a minimalist").assertIsDisplayed()

        // 4. When response completes (isGenerating = false, isAnalyzingImage = false), "Analyzing Provided Image" remains gone and full response is shown
        currentUiState.value = currentUiState.value.copy(
            messages = listOf(
                userImgMsg,
                streamingImgMsg.copy(
                    content = "This image shows a minimalist geometric pattern.",
                    status = MessageStatus.COMPLETED
                )
            ),
            generationState = com.example.domain.models.GenerationState.COMPLETED,
            imageAnalysisState = com.example.domain.models.ImageAnalysisState.COMPLETED,
            isGenerating = false,
            isAnalyzingImage = false,
            streamingMessageText = ""
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("image_analysis_status_indicator").assertDoesNotExist()
        composeTestRule.onNodeWithText("This image shows a minimalist geometric pattern.").assertIsDisplayed()
    }

    @Test
    fun sseParser_handlesMultiLineChunks_jsonArrayContent_andTimingMetrics() = runBlocking {
        val ssePayload = buildString {
            // 1. Reasoning delta chunk
            append("data: {\"id\":\"chatcmpl-m1\",\"model\":\"free/gemini-3.8-flash\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"Analyzing image layout...\"}}]}\n\n")
            // 2. Standard string content delta
            append("data: {\"id\":\"chatcmpl-m1\",\"model\":\"free/gemini-3.8-flash\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Part 1: Complete \"}}]}\n\n")
            // 3. Multi-line split SSE data event + JSONArray content parts
            append("data: {\"id\":\"chatcmpl-m1\",\"model\":\"free/gemini-3.8-flash\",\n")
            append("data: \"choices\":[{\"index\":0,\"delta\":{\"content\":[{\"type\":\"text\",\"text\":\"Part 2: Multimodal array \"}]},\"finish_reason\":null}]}\n\n")
            // 4. Final token + finish_reason = stop
            append("data: {\"id\":\"chatcmpl-m1\",\"model\":\"free/gemini-3.8-flash\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Part 3: Natural end.\"},\"finish_reason\":\"stop\"}]}\n\n")
            // 5. Usage-only chunk with empty choices array (emitted by Gemini 3.8 Flash)
            append("data: {\"id\":\"chatcmpl-m1\",\"model\":\"free/gemini-3.8-flash\",\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":25,\"total_tokens\":125}}\n\n")
            // 6. Terminal [DONE]
            append("data: [DONE]\n\n")
        }

        val client = SupabaseAndGatewayClient(
            supabaseUrlProvider = { "https://project-ref.supabase.co" },
            supabaseAnonKeyProvider = { "anon-key-123" },
            aiBackendBaseUrlProvider = { "https://project-ref.supabase.co/functions/v1/ai-chat" },
            customHttpExecutor = { req ->
                okhttp3.Response.Builder()
                    .request(req)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .addHeader("Content-Type", "text/event-stream")
                    .addHeader("X-Timing-Request-Received-Ms", "1000")
                    .addHeader("X-Timing-Upstream-Start-Ms", "1005")
                    .addHeader("X-Timing-Upstream-Headers-Ms", "1250")
                    .body(ssePayload.toResponseBody("text/event-stream".toMediaType()))
                    .build()
            }
        )

        val chunks = mutableListOf<StreamChunk>()
        client.streamChatCompletion(
            accessToken = "valid-jwt",
            conversationId = "conv-test-sse",
            requestedModelId = "free/gemini-3.8-flash",
            fallbackModelId = null,
            allowFallback = false,
            history = listOf(
                ChatMessage("m1", "conv-test-sse", "u1", MessageRole.USER, "Analyze this image.", "free/gemini-3.8-flash")
            ),
            streamEnabled = true,
            clientRequestId = "req-multiline-sse"
        ).collect { chunks.add(it) }

        val reasoningChunks = chunks.filterIsInstance<StreamChunk.ReasoningDelta>()
        assertEquals(1, reasoningChunks.size)
        assertEquals("Analyzing image layout...", reasoningChunks.first().reasoningDelta)

        val completed = chunks.filterIsInstance<StreamChunk.Completed>().firstOrNull()
        assertNotNull("Expected Completed chunk after [DONE]", completed)
        assertEquals(
            "Part 1: Complete Part 2: Multimodal array Part 3: Natural end.",
            completed!!.fullText
        )
        assertEquals("stop", completed.finishReason)
        assertNotNull("Expected timingMetrics on Completed chunk", completed.timingMetrics)
        assertTrue(completed.timingMetrics!!.sawDoneEvent)
        assertEquals("stop", completed.timingMetrics!!.finishReason)
        assertEquals(5, completed.timingMetrics!!.sseEventCount)
    }

    @Test
    fun sseParser_detectsPrematureEofWithoutDoneOrFinishReason_andPreservesPartialText() = runBlocking {
        val truncatedSsePayload = buildString {
            append("data: {\"id\":\"chatcmpl-trunc\",\"model\":\"free/gemini-3.8-flash\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"This response started normally but cut off in the mid\"},\"finish_reason\":null}]}\n\n")
            // Stream abruptly closes without finish_reason="stop" and without data: [DONE]
        }

        val client = SupabaseAndGatewayClient(
            supabaseUrlProvider = { "https://project-ref.supabase.co" },
            supabaseAnonKeyProvider = { "anon-key-123" },
            aiBackendBaseUrlProvider = { "https://project-ref.supabase.co/functions/v1/ai-chat" },
            customHttpExecutor = { req ->
                okhttp3.Response.Builder()
                    .request(req)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .addHeader("Content-Type", "text/event-stream")
                    .body(truncatedSsePayload.toResponseBody("text/event-stream".toMediaType()))
                    .build()
            }
        )

        val chunks = mutableListOf<StreamChunk>()
        client.streamChatCompletion(
            accessToken = "valid-jwt",
            conversationId = "conv-test-trunc",
            requestedModelId = "free/gemini-3.8-flash",
            fallbackModelId = null,
            allowFallback = false,
            history = listOf(
                ChatMessage("m1", "conv-test-trunc", "u1", MessageRole.USER, "Describe this image in detail.", "free/gemini-3.8-flash")
            ),
            streamEnabled = true,
            clientRequestId = "req-truncated-sse"
        ).collect { chunks.add(it) }

        val completed = chunks.filterIsInstance<StreamChunk.Completed>().firstOrNull()
        assertNull("Premature EOF must NOT be marked as Completed", completed)

        val error = chunks.filterIsInstance<StreamChunk.Error>().firstOrNull()
        assertNotNull("Expected StreamChunk.Error on premature EOF", error)
        assertEquals(
            "This response started normally but cut off in the mid",
            error!!.partialText
        )
        assertFalse(error.timingMetrics!!.sawDoneEvent)
    }

    // ========================================================================
    // 13. PART 1 — Smart Copy Buttons for Code, Scripts & Commands UI Tests
    // ========================================================================

    @Test
    fun smartCopyBlocks_rendersCopyButtonsOnlyOnStructuredBlocks_copiesCleanCodeWithoutFences_andShowsCopiedFeedback() {
        val currentMarkdownState = mutableStateOf(
            """
            Here are the scripts and commands you requested:

            ```python
            import os
            def rename_all():
                print("Renaming files...")
            ```

            ```javascript
            const run = () => console.log("JS Ready");
            ```

            ```kotlin
            fun main() {
                println("Kotlin Ready")
            }
            ```

            ```bash
            $ supabase functions deploy ai-chat
            ```
            """.trimIndent()
        )

        composeTestRule.setContent {
            NexusAiTheme {
                MarkdownMessageContent(content = currentMarkdownState.value)
            }
        }
        composeTestRule.waitForIdle()

        // Verify all 4 code/script/command blocks render their own independent Copy button
        val copyButtons = composeTestRule.onAllNodesWithTag("copy_code_block_button")
        assertEquals(
            "Expected 4 independent Copy buttons for Python, JS, Kotlin, and Bash blocks",
            4,
            copyButtons.fetchSemanticsNodes().size
        )

        // Click Copy on the first block (Python script) -> copies only Python code without fences
        LastCopiedClipboardTracker.lastCopiedText = null
        copyButtons[0].performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            "import os\ndef rename_all():\n    print(\"Renaming files...\")",
            LastCopiedClipboardTracker.lastCopiedText
        )
        assertFalse(
            "Copied code must never contain Markdown fences",
            LastCopiedClipboardTracker.lastCopiedText!!.contains("```")
        )
        composeTestRule.onNodeWithText("Copied!").assertIsDisplayed()

        // Click Copy on the fourth block (Bash command) -> strips '$ ' prompt prefix and copies only that command
        copyButtons[3].performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            "supabase functions deploy ai-chat",
            LastCopiedClipboardTracker.lastCopiedText
        )

        // Switch content to a normal explanatory paragraph -> NO Copy button should appear
        currentMarkdownState.value = "JavaScript is a dynamic scripting language used for web development."
        composeTestRule.waitForIdle()
        assertEquals(
            "Normal paragraph response must NOT render any Copy button",
            0,
            composeTestRule.onAllNodesWithTag("copy_code_block_button").fetchSemanticsNodes().size
        )

        // Switch content to an Instagram caption -> NO Copy button should appear
        currentMarkdownState.value = "Golden hour magic and city lights ✨🌆 #Vibes #Cityscape #Photography"
        composeTestRule.waitForIdle()
        assertEquals(
            "Instagram caption response must NOT render any Copy button",
            0,
            composeTestRule.onAllNodesWithTag("copy_code_block_button").fetchSemanticsNodes().size
        )
    }

    // ========================================================================
    // 14. PART 2, 3, 4, 5 & 6 — Automatic Chat Image Generation, Honest Progress
    //     Stages, Dedicated Screen Actions & Conversation Persistence Tests
    // ========================================================================

    @Test
    fun pollinationsImageGeneration_emitsHonestRealLifecycleStages_andSupportsDedicatedScreenActions(): Unit = runBlocking {
        // 1x1 valid PNG binary
        val validPngBytes = Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
            Base64.DEFAULT
        )

        val observedStages = mutableListOf<ImageGenerationStage>()
        val client = SupabaseAndGatewayClient(
            customHttpExecutor = { req ->
                okhttp3.Response.Builder()
                    .request(req)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .addHeader("Content-Type", "image/png")
                    .body(validPngBytes.toResponseBody("image/png".toMediaType()))
                    .build()
            }
        )

        val generatedImage = client.generatePollinationsImage(
            accessToken = null,
            prompt = "A futuristic city at sunset",
            modelId = "sana",
            width = 1080,
            height = 1350,
            seed = 42L,
            enhance = true,
            onStageChanged = { stage -> observedStages.add(stage) }
        )

        assertEquals(
            "Must emit real lifecycle stages in order without fake percentage jumps",
            listOf(
                ImageGenerationStage.PREPARING_PROMPT,
                ImageGenerationStage.CONNECTING_TO_MODEL,
                ImageGenerationStage.GENERATING_IMAGE,
                ImageGenerationStage.RECEIVING_IMAGE,
                ImageGenerationStage.PROCESSING_FINAL_IMAGE,
                ImageGenerationStage.COMPLETED
            ),
            observedStages
        )

        assertEquals(1080, generatedImage.width)
        assertEquals(1350, generatedImage.height)
        assertTrue(generatedImage.imageUrl.startsWith("https://image.pollinations.ai/prompt/"))

        // Verify DedicatedChatImageGenerationScreen UI across active, completed, and failed states
        var cancelClicked = false
        var retryClicked = false
        var continueInChatClicked = false

        val sessionState = mutableStateOf(
            ActiveImageGenerationSession(
                sessionId = "sess-1",
                conversationId = "conv-1",
                originalUserPrompt = "Create an image in 1080x1350 resolution.",
                extractedPrompt = "futuristic city in 1080x1350 resolution",
                modelId = "sana",
                aspectRatio = ImageAspectRatio.PORTRAIT_3_4,
                width = 1080,
                height = 1350,
                aspectRatioLabel = "1080×1350",
                seed = 42L,
                stage = ImageGenerationStage.GENERATING_IMAGE
            )
        )

        composeTestRule.setContent {
            NexusAiTheme {
                DedicatedChatImageGenerationScreen(
                    session = sessionState.value,
                    onCancelGeneration = { cancelClicked = true },
                    onRetryGeneration = { retryClicked = true },
                    onContinueInChat = { continueInChatClicked = true }
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. Active Generation State: Indeterminate progress bar shown, NO fake percentage (100% bar absent)
        composeTestRule.onNodeWithTag("dedicated_image_generation_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dedicated_image_progress_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dedicated_image_indeterminate_progress").assertIsDisplayed()
        assertEquals(
            "100% completed progress bar must NOT exist while generating",
            0,
            composeTestRule.onAllNodesWithTag("dedicated_image_completed_progress").fetchSemanticsNodes().size
        )
        composeTestRule.onNodeWithText("Generating Image").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dedicated_image_dimensions").assertIsDisplayed()

        // Cancel button works during active generation
        composeTestRule.onNodeWithTag("cancel_image_generation_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue("Cancel button must invoke onCancelGeneration", cancelClicked)

        // 2. Completed State: 100% progress bar appears ONLY after image is received & validated, Save/Share/Continue work
        sessionState.value = sessionState.value.copy(
            stage = ImageGenerationStage.COMPLETED,
            progressPercentage = 100,
            result = generatedImage
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("dedicated_image_completed_progress").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dedicated_image_progress_status_text").assertIsDisplayed()
        composeTestRule.onNodeWithTag("generated_image_card").assertIsDisplayed()

        ImageActionTracker.lastSavedImageId = null
        ImageActionTracker.lastSharedImageId = null

        composeTestRule.onNodeWithTag("save_generated_image_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(generatedImage.id, ImageActionTracker.lastSavedImageId)

        composeTestRule.onNodeWithTag("share_generated_image_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(generatedImage.id, ImageActionTracker.lastSharedImageId)

        composeTestRule.onNodeWithTag("continue_in_chat_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue("Continue in Chat button must invoke onContinueInChat", continueInChatClicked)

        // 3. Failed State: Retry button is displayed and works
        sessionState.value = sessionState.value.copy(
            stage = ImageGenerationStage.FAILED,
            result = null,
            errorMessage = "Pollinations Image Service returned HTTP 502",
            canRetry = true
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("retry_image_generation_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("retry_image_generation_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue("Retry button must invoke onRetryGeneration", retryClicked)
    }

    @Test
    fun chatImageGeneration_triggersAutomaticallyFromChatInput_persistsInRoomWithoutCursorOverflow_andRendersInChatHistory(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val validPngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        val validPngBytes = Base64.decode(validPngBase64, Base64.DEFAULT)

        // 1. Verify Room persistence via ChatRepositoryImpl + GeneratedImageBinaryCache
        val container = DefaultAppContainer(context, inMemoryDb = true)
        val repo = ChatRepositoryImpl(
            chatCacheDao = container.chatCacheDao,
            networkClient = SupabaseAndGatewayClient(
                customHttpExecutor = { req ->
                    okhttp3.Response.Builder()
                        .request(req)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .addHeader("Content-Type", "image/png")
                        .body(validPngBytes.toResponseBody("image/png".toMediaType()))
                        .build()
                }
            ),
            authRepository = container.authRepository,
            modelCatalogRepository = container.modelCatalogRepository
        )

        val userSession = container.authRepository.signUp("imgtester@example.com", "Pass12345", "Image Tester").getOrThrow()
        val userId = userSession.user.id

        val conv = repo.createConversation(userId, "New Chat", AiModelCatalog.DEFAULT_MODEL_ID).getOrThrow()
        val genImage = repo.generateChatImage(
            prompt = "A futuristic city",
            modelId = "sana",
            width = 1024,
            height = 1024,
            enhance = true
        ).getOrThrow()

        assertFalse("Generated image must be cached to local file path", genImage.localFilePath.isNullOrBlank())

        val imageAttachment = MessageAttachment(
            id = genImage.id,
            uriString = genImage.localFilePath.orEmpty(),
            mimeType = "image/png",
            fileName = "smarter_ai_city.png",
            base64DataUrl = "data:image/png;base64,$validPngBase64"
        )
        repo.saveAssistantMessage(
            conversationId = conv.id,
            userId = userId,
            content = "Generated image for **\"A futuristic city\"** (1024×1024).",
            modelId = genImage.modelId,
            attachments = listOf(imageAttachment),
            clientRequestId = "req-img-persist-1"
        ).getOrThrow()

        val rawCached = container.chatCacheDao.getMessagesPaged(conv.id, userId, 50, 0)
        assertEquals(1, rawCached.size)
        assertFalse(
            "SQLite attachmentsJson must NOT store raw base64 payload",
            rawCached.first().attachmentsJson.contains(validPngBase64)
        )

        val restoredMessages = repo.observeMessages(conv.id, userId).first()
        assertEquals(1, restoredMessages.size)
        assertEquals(
            "Restored message attachment must retain local file reference",
            genImage.localFilePath,
            restoredMessages.first().attachments.first().uriString
        )
        container.database.close()

        // 2. Verify full UI flow in ChatScreen: typing "Generate an image of a futuristic city." opens Dedicated Screen & saves to Chat
        val fakeContainer = FakeUiTestAppContainer(initiallyAuthenticated = true)
        composeTestRule.setContent {
            NexusAiTheme {
                NexusAiAppRoot(appContainer = fakeContainer)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("chat_composer_input").performTextInput("Generate an image of a futuristic city.")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("send_message_button").performClick()
        composeTestRule.waitForIdle()

        // Dedicated Image Generation Screen opens automatically
        composeTestRule.onNodeWithTag("dedicated_image_generation_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("generated_image_card").assertIsDisplayed()

        // Click "Continue in Chat" to return to the conversation where the generated image is rendered in chat history
        composeTestRule.onNodeWithTag("continue_in_chat_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("chat_generated_image_card_gen-img-test-1").assertIsDisplayed()
        Unit
    }

    // ========================================================================
    // 15. PART 3, 5, 11, 12, 13 & 14 — Multi-Turn Stability, Live Market Charts,
    //     12-Model Prefix Hiding & GPT 6 Luna Voice Chat End-to-End Tests
    // ========================================================================

    @Test
    fun multiTurnConversation_6ConsecutiveMixedTurns_neverLocksOrLeavesStuckIndicators(): Unit = runBlocking {
        val fakeContainer = FakeUiTestAppContainer(initiallyAuthenticated = true)
        val viewModel = com.example.ui.chat.ChatViewModel(
            chatRepository = fakeContainer.chatRepository,
            authRepository = fakeContainer.authRepository,
            modelCatalogRepository = fakeContainer.modelCatalogRepository,
            preferencesRepository = fakeContainer.preferencesRepository,
            liveDataService = fakeContainer.liveDataService
        )

        // Turn 1: Normal prompt
        viewModel.onComposerTextChanged("Turn 1: Explain microservices briefly.")
        viewModel.sendMessage()
        assertFalse("After Turn 1, isGenerating must be false", viewModel.uiState.value.isGenerating)
        assertEquals(RequestLifecycleState.COMPLETED, viewModel.uiState.value.requestLifecycleState)

        // Turn 2: Code generation prompt
        viewModel.onComposerTextChanged("Turn 2: Write a Kotlin coroutine retry helper.")
        viewModel.sendMessage()
        assertFalse("After Turn 2, isGenerating must be false", viewModel.uiState.value.isGenerating)
        assertEquals(RequestLifecycleState.COMPLETED, viewModel.uiState.value.requestLifecycleState)

        // Turn 3: Pollinations image generation prompt
        viewModel.onComposerTextChanged("Generate an image of a cyberpunk skyline.")
        viewModel.sendMessage()
        assertFalse("After Turn 3 image gen, isGenerating must be false", viewModel.uiState.value.isGenerating)
        assertEquals(RequestLifecycleState.COMPLETED, viewModel.uiState.value.requestLifecycleState)
        viewModel.dismissDedicatedImageGenerationScreen()

        // Turn 4: Image upload & analysis with a vision-capable model (free/gemini-3.8-flash)
        viewModel.selectModel("free/gemini-3.8-flash")
        val validPngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        viewModel.addImageAttachment(
            uriString = "content://media/test_img.png",
            mimeType = "image/png",
            fileName = "test_img.png",
            base64DataUrl = "data:image/png;base64,$validPngBase64"
        )
        viewModel.onComposerTextChanged("Turn 4: Analyze this uploaded diagram.")
        viewModel.sendMessage()
        assertFalse("After Turn 4 image analysis, isGenerating must be false", viewModel.uiState.value.isGenerating)
        assertFalse("After Turn 4 image analysis, isAnalyzingImage must be false (never stuck)", viewModel.uiState.value.isAnalyzingImage)
        assertEquals(RequestLifecycleState.COMPLETED, viewModel.uiState.value.requestLifecycleState)

        // Turn 5: Normal follow-up prompt after image analysis
        viewModel.onComposerTextChanged("Turn 5: Summarize our architecture so far.")
        viewModel.sendMessage()
        assertFalse("After Turn 5, isGenerating must be false", viewModel.uiState.value.isGenerating)
        assertEquals(RequestLifecycleState.COMPLETED, viewModel.uiState.value.requestLifecycleState)

        // Turn 6: Live data prompt (USD to INR rate)
        viewModel.onComposerTextChanged("Turn 6: What is the USD to INR rate today?")
        viewModel.sendMessage()
        assertFalse("After Turn 6 live data, isGenerating must be false", viewModel.uiState.value.isGenerating)
        assertFalse("After Turn 6 live data, isFetchingLiveData must be false", viewModel.uiState.value.isFetchingLiveData)
        assertEquals(RequestLifecycleState.COMPLETED, viewModel.uiState.value.requestLifecycleState)

        // Verify all 6 turns (12 messages: 6 user + 6 assistant) are recorded in conversation history
        assertEquals("Expected 12 messages across 6 consecutive turns", 12, viewModel.uiState.value.messages.size)
    }

    @Test
    fun modelSelectorSheet_displaysAll12UniqueModels_andHidesFreePrefixEverywhere() {
        var selectedModelId: String? = null
        composeTestRule.setContent {
            NexusAiTheme {
                ModelSelectorContent(
                    catalog = AiModelCatalog.INITIAL_DISCOVERY_MODELS,
                    selectedModelId = "free/glm-5.3-flash",
                    searchQuery = "",
                    selectedCategory = null,
                    isRefreshing = false,
                    onSearchQueryChanged = {},
                    onCategorySelected = {},
                    onRefreshCatalog = {},
                    onModelSelected = { selectedModelId = it }
                )
            }
        }
        composeTestRule.waitForIdle()

        // Verify GPT 6 Luna, Muse Spark 1.3, Gemini 3.8 Flash, etc. are visible and clicking selects exact "free/..." ID
        composeTestRule.onNodeWithTag("model_catalog_list").performScrollToIndex(7)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("model_item_free/gpt-6-luna").performClick()
        composeTestRule.waitForIdle()
        assertEquals("free/gpt-6-luna", selectedModelId)
    }

    @Test
    fun liveMarketChartCard_rendersVerifiedPrice_chartCanvas_preciousMetalBreakdown_andCurrencyConversion() {
        var refreshClicked = false
        val goldPayload = LiveMarketDataPayload(
            id = "live-gold-test",
            category = LiveDataCategory.PRECIOUS_METALS,
            title = "Gold Spot (XAU)",
            symbolOrPair = "XAU/INR",
            currentValue = 74850.0,
            formattedPrimaryValue = "₹74,850.00 / 10g (24K)",
            currencyOrUnit = "Per 10 Grams (24K Spot)",
            change24hPercent = 0.65,
            high24h = 75100.0,
            low24h = 74300.0,
            providerName = "Gold-API Global Spot Feed + Frankfurter FX",
            providerTimestampEpochMs = System.currentTimeMillis(),
            formattedTimestamp = "2026-11-04 12:00 UTC",
            freshness = LiveDataFreshness.LIVE,
            timeframeLabel = "24H Spot Reference",
            historicalPoints = listOf(
                ChartDataPoint(System.currentTimeMillis() - 7200_000L, "Prev Close", 2727.30),
                ChartDataPoint(System.currentTimeMillis(), "Current Spot", 2745.50)
            ),
            preciousMetalBreakdown = PreciousMetalBreakdown(
                metalName = "Gold (XAU)",
                spotPricePerTroyOunceUsd = 2745.50,
                usdToLocalRate = 84.80,
                localCurrencyCode = "INR",
                price24KPerGramLocal = 7485.0,
                price24KPer10GramsLocal = 74850.0,
                price22KPerGramLocal = 6861.25,
                price22KPer10GramsLocal = 68612.50,
                price24KPerGramUsd = 88.27,
                price22KPerGramUsd = 80.91,
                marketTypeNotice = "International XAU/USD spot converted via live USD/INR."
            )
        )

        composeTestRule.setContent {
            NexusAiTheme {
                LiveMarketChartCard(
                    payload = goldPayload,
                    onRefreshRequested = { refreshClicked = true }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("live_market_chart_card_XAU/INR").assertIsDisplayed()
        composeTestRule.onNodeWithTag("live_market_primary_value").assertIsDisplayed()
        composeTestRule.onNodeWithTag("precious_metal_breakdown_card").assertIsDisplayed()

        composeTestRule.onNodeWithTag("live_market_refresh_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue("Refresh button on LiveMarketChartCard must invoke onRefreshRequested", refreshClicked)
    }

    @Test
    fun smartCopyBlockEditor_supportsInlineEditing_undo_redo_selectAll_andCopiesEditedContent() {
        val markdown = """
            # Complete Prompt Engineering Guide
            ## Section 1: Image Generation
            ### Ready-to-Use Prompt

            ```image-prompt
            A cinematic portrait of an astronaut in golden hour light, 8k photorealistic
            ```
        """.trimIndent()

        composeTestRule.setContent {
            NexusAiTheme {
                MarkdownMessageContent(content = markdown, renderMarkdown = true)
            }
        }
        composeTestRule.waitForIdle()

        // 1. Verify H1, H2, H3 headings and selectable container render with original casing
        composeTestRule.onNodeWithTag("selectable_markdown_content").assertIsDisplayed()
        composeTestRule.onNodeWithTag("markdown_heading_h1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("markdown_heading_h2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("markdown_heading_h3").assertIsDisplayed()
        composeTestRule.onNodeWithText("Complete Prompt Engineering Guide").assertIsDisplayed()
        composeTestRule.onNodeWithText("IMAGE GENERATION PROMPT").assertIsDisplayed()

        // 2. Verify Smart Copy Block editor (blockIndex = 3) allows inline text editing
        val editorNode = composeTestRule.onNodeWithTag("copy_block_editor_3")
        editorNode.assertIsDisplayed()
        val editedPrompt = "A cyberpunk samurai standing on a neon roof in rain, 8k Unreal Engine 5"
        editorNode.performTextReplacement(editedPrompt)
        composeTestRule.waitForIdle()

        // 3. Clicking Copy copies the EDITED text, not the original AI text
        LastCopiedClipboardTracker.lastCopiedText = null
        composeTestRule.onNodeWithTag("copy_code_block_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(editedPrompt, LastCopiedClipboardTracker.lastCopiedText)

        // 4. Undo restores the original prompt text
        composeTestRule.onNodeWithTag("copy_block_undo_button_3").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("copy_code_block_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            "A cinematic portrait of an astronaut in golden hour light, 8k photorealistic",
            LastCopiedClipboardTracker.lastCopiedText
        )

        // 5. Redo restores the edited prompt text and Select All works cleanly
        composeTestRule.onNodeWithTag("copy_block_redo_button_3").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("copy_block_select_all_button_3").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("copy_code_block_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(editedPrompt, LastCopiedClipboardTracker.lastCopiedText)
    }

    @Test
    fun instantThemePersistence_andUnifiedSmarterAiIdentity_workWithZeroStartupFlicker_andPackageIdIsSmarterAiTech(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Verify Android package name / applicationId is migrated to com.smarterai.tech
        assertEquals("com.smarterai.tech", BuildConfig.APPLICATION_ID)
        assertEquals("com.smarterai.tech", context.packageName)

        // 2. Verify UserPreferencesRepository synchronous SharedPreferences mirror eliminates startup theme flicker
        val prefsRepo1 = UserPreferencesRepository(context)
        prefsRepo1.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, prefsRepo1.getInitialPreferences().themeMode)

        // Simulate cold app restart with a brand-new repository instance: Frame 0 must immediately read ThemeMode.LIGHT
        val coldStartPrefsRepo = UserPreferencesRepository(context)
        assertEquals(
            "Cold start must synchronously read ThemeMode.LIGHT on Frame 0 without flashing DARK",
            ThemeMode.LIGHT,
            coldStartPrefsRepo.getInitialPreferences().themeMode
        )

        // 3. Verify ChatViewModel deterministic Smarter AI 1.0 identity and developer privacy responses
        val fakeContainer = FakeUiTestAppContainer(initiallyAuthenticated = true)
        val viewModel = com.example.ui.chat.ChatViewModel(
            chatRepository = fakeContainer.chatRepository,
            authRepository = fakeContainer.authRepository,
            modelCatalogRepository = fakeContainer.modelCatalogRepository,
            preferencesRepository = coldStartPrefsRepo,
            liveDataService = fakeContainer.liveDataService
        )
        assertEquals(ThemeMode.LIGHT, viewModel.uiState.value.preferences.themeMode)

        viewModel.onComposerTextChanged("What is your name?")
        viewModel.sendMessage()
        assertTrue(
            "Identity query must enter thinking state before 2-second delay completes",
            viewModel.uiState.value.isGenerating
        )
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(2100))
        assertFalse(viewModel.uiState.value.isGenerating)
        assertEquals("I am Smarter AI 1.0.", viewModel.uiState.value.messages.last().content)

        viewModel.onComposerTextChanged("Which AI model are you?")
        viewModel.sendMessage()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(2100))
        assertEquals(
            "I am Smarter AI 1.0, an AI assistant powered by Smarter AI's technology.",
            viewModel.uiState.value.messages.last().content
        )

        viewModel.onComposerTextChanged("Who developed you?")
        viewModel.sendMessage()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(2100))
        assertEquals(
            "I was developed by the Smarter AI technical team.",
            viewModel.uiState.value.messages.last().content
        )

        // Reset theme to DARK for subsequent tests
        coldStartPrefsRepo.setThemeMode(ThemeMode.DARK)
    }

    // ========================================================================
    // 16. Rebuilt Gemini-Inspired Home Screen, Personal Intelligence Memory & Live Citations UI Tests
    // ========================================================================

    @Test
    fun geminiInspiredHomeScreen_displaysPersonalizedGreeting_6ContextualSuggestions_projectContinuation_andPersonalIntelligenceSettings() {
        val activeProject = com.example.domain.models.ProjectContextMemory(
            id = "proj-android-1",
            userId = "user-test-1",
            projectName = "SmarterHealth Android App",
            summary = "Building an Android health tracker with Kotlin, Jetpack Compose, and Room.",
            techStack = "Kotlin, Jetpack Compose, Room"
        )
        val savedMemory = com.example.domain.models.UserMemoryItem(
            id = "mem-pref-1",
            userId = "user-test-1",
            category = com.example.domain.models.MemoryCategory.USER_PREFERENCE,
            memoryKey = "Code Style Preference",
            content = "Prefer idiomatic Kotlin Coroutines with StateFlow",
            sourceType = com.example.domain.models.MemorySourceType.EXPLICIT_USER
        )

        var appliedSuggestionPrompt: String? = null
        val homeUiState = mutableStateOf(
            com.example.ui.chat.ChatUiState(
                activeUserId = "user-test-1",
                activeUserEmail = "alex@smarterai.app",
                activeUserDisplayName = "Alex Tester",
                activeConversationId = null,
                messages = emptyList(),
                activeProjects = listOf(activeProject),
                userMemories = listOf(savedMemory)
            )
        )

        composeTestRule.setContent {
            NexusAiTheme {
                com.example.ui.chat.ChatScreen(
                    uiState = homeUiState.value,
                    onStartNewChat = {},
                    onSelectConversation = {},
                    onRenameConversation = { _, _ -> },
                    onDeleteConversation = {},
                    onOpenSearchScreen = {},
                    onOpenSettingsScreen = {},
                    onSignOut = {},
                    onOpenModelSelector = {},
                    onCloseModelSelector = {},
                    onModelSearchQueryChanged = {},
                    onModelCategorySelected = {},
                    onRefreshModelCatalog = {},
                    onSelectModel = {},
                    onComposerTextChanged = {},
                    onApplyPromptSuggestion = { appliedSuggestionPrompt = it },
                    onAddAttachment = {},
                    onRemoveAttachment = {},
                    onSendMessage = {},
                    onStopGeneration = {},
                    onRegenerateResponse = {},
                    onRetryFailedGeneration = {},
                    onStartEditingMessage = {},
                    onCancelEditingMessage = {},
                    onDismissBanners = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. Verify personalized greeting "Hey, Alex Tester" and "How can I help you today?"
        composeTestRule.onNodeWithTag("new_chat_welcome_view").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_welcome_greeting").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hey, Alex Tester").assertIsDisplayed()
        composeTestRule.onNodeWithText("How can I help you today?").assertIsDisplayed()

        // 2. Verify Personal Intelligence project continuation chip is shown and interactive
        composeTestRule.onNodeWithTag("home_continue_project_chip").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_continue_project_chip").performClick()
        composeTestRule.waitForIdle()
        assertEquals("Continue working on my SmarterHealth Android App project.", appliedSuggestionPrompt)

        // 3. Verify all 6 contextual suggestion cards are rendered and tappable
        composeTestRule.onNodeWithTag("prompt_suggestion_help_me_understand_something").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("prompt_suggestion_write_something_for_me").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("prompt_suggestion_analyze_an_image").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("prompt_suggestion_help_me_with_coding").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("prompt_suggestion_research_the_latest_information").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("prompt_suggestion_generate_creative_ideas").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("prompt_suggestion_research_the_latest_information").performClick()
        composeTestRule.waitForIdle()
        assertTrue(appliedSuggestionPrompt!!.contains("latest technology news", ignoreCase = true))

        // 4. Scroll back to top of LazyColumn and verify when user displayName is blank, greeting gracefully falls back to "Hey there" without inventing a name
        homeUiState.value = homeUiState.value.copy(activeUserDisplayName = "")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("new_chat_welcome_view").performScrollToIndex(0)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hey there").assertIsDisplayed()

        // 5. Verify bottom composer, attachment button, and all 4 tool shortcuts in NewChatWelcomeView
        composeTestRule.onNodeWithTag("chat_composer_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("attach_image_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("composer_shortcut_upload_image").assertIsDisplayed()
        composeTestRule.onNodeWithTag("composer_shortcut_live_research").assertIsDisplayed()
        composeTestRule.onNodeWithTag("composer_shortcut_create_image").assertIsDisplayed()
        composeTestRule.onNodeWithTag("composer_shortcut_code_assistant").assertIsDisplayed()

        composeTestRule.onNodeWithTag("composer_shortcut_live_research").performClick()
        composeTestRule.waitForIdle()
        assertTrue(appliedSuggestionPrompt!!.contains("latest technology", ignoreCase = true))

        composeTestRule.onNodeWithTag("composer_shortcut_create_image").performClick()
        composeTestRule.waitForIdle()
        assertTrue(appliedSuggestionPrompt!!.contains("Generate an image", ignoreCase = true))

        composeTestRule.onNodeWithTag("composer_shortcut_code_assistant").performClick()
        composeTestRule.waitForIdle()
        assertTrue(appliedSuggestionPrompt!!.contains("Help me architect", ignoreCase = true))
    }

    @Test
    fun realWorldVerification_onDiskRoomPersistenceAcrossAppRestart_scenarioABC_isolation_andCitationTimestamps(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFileName = "smarter_ai_real_world_verification.db"
        context.deleteDatabase(dbFileName)

        // ====================================================================
        // SCENARIO A: User says "My project is called Smarter AI."
        // Persist to an actual on-disk Room SQLite database file (Session 1)
        // ====================================================================
        val dbSession1 = androidx.room.Room.databaseBuilder(
            context,
            com.example.data.local.NexusAiLocalDatabase::class.java,
            dbFileName
        ).allowMainThreadQueries().build()

        val dao1 = dbSession1.chatCacheDao()
        val userA = "user-verify-alpha"
        val userB = "user-verify-beta"

        val turnAExtraction = com.example.domain.usecases.PersonalIntelligenceEngine.extractMemoriesFromTurn(
            userId = userA,
            conversationId = "conv-session-1",
            userPrompt = "My project is called Smarter AI.",
            assistantReply = "Got it! I have noted that your project is called Smarter AI.",
            existingMemories = emptyList(),
            existingProjects = emptyList()
        )
        assertTrue(
            "Scenario A must extract 'Smarter AI' into memoriesToUpsert or projectsToUpsert",
            turnAExtraction.projectsToUpsert.any { it.projectName.contains("Smarter AI", ignoreCase = true) } ||
                turnAExtraction.memoriesToUpsert.any { it.content.contains("Smarter AI", ignoreCase = true) }
        )

        // Save extracted project & memory items to on-disk SQLite DB
        turnAExtraction.memoriesToUpsert.forEach { item ->
            dao1.upsertUserMemory(com.example.data.local.UserMemoryEntity.fromDomain(item))
        }
        turnAExtraction.projectsToUpsert.forEach { proj ->
            dao1.upsertProjectContext(com.example.data.local.ProjectContextEntity.fromDomain(proj))
        }

        // Also save a user preference for userA, plus isolated data for userB
        val prefExtraction = com.example.domain.usecases.PersonalIntelligenceEngine.extractMemoriesFromTurn(
            userId = userA,
            conversationId = "conv-session-1",
            userPrompt = "Remember that I prefer concise Kotlin code examples.",
            assistantReply = "Understood, I will keep Kotlin examples concise.",
            existingMemories = emptyList(),
            existingProjects = emptyList()
        )
        prefExtraction.memoriesToUpsert.forEach { item ->
            dao1.upsertUserMemory(com.example.data.local.UserMemoryEntity.fromDomain(item))
        }

        dao1.upsertUserMemory(
            com.example.data.local.UserMemoryEntity(
                id = "mem-user-b-secret",
                userId = userB,
                category = com.example.domain.models.MemoryCategory.ACTIVE_PROJECT.name,
                memoryKey = "project_nebula",
                content = "Nebula Cloud: Secret project belonging only to userB",
                sourceType = com.example.domain.models.MemorySourceType.EXPLICIT_USER.name,
                confidence = 0.99,
                createdAtEpochMs = System.currentTimeMillis(),
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )

        // ====================================================================
        // SCENARIO B: Close the application (close SQLite database completely)
        // and reopen with a brand-new database instance on the same disk file
        // ====================================================================
        dbSession1.close()

        val dbSession2 = androidx.room.Room.databaseBuilder(
            context,
            com.example.data.local.NexusAiLocalDatabase::class.java,
            dbFileName
        ).allowMainThreadQueries().build()
        val dao2 = dbSession2.chatCacheDao()

        val reopenedMemoriesA = dao2.getUserMemories(userA).map { it.toDomain() }
        val reopenedProjectsA = dao2.getProjectContexts(userA).map { it.toDomain() }

        assertTrue("Reopened on-disk DB must contain saved memories for userA", reopenedMemoriesA.isNotEmpty())
        assertTrue("Reopened on-disk DB must contain saved project 'Smarter AI' for userA", reopenedProjectsA.any { it.projectName == "Smarter AI" })
        assertFalse("User isolation: userA must NEVER receive userB's memories", reopenedMemoriesA.any { it.userId == userB || it.content.contains("Nebula Cloud") })

        // ====================================================================
        // SCENARIO C: User asks "What is my project called?"
        // Verify that the saved memory ("Smarter AI") is retrieved into the prompt context
        // ====================================================================
        val recallSnapshot = com.example.domain.usecases.PersonalIntelligenceEngine.buildPersonalIntelligenceSnapshot(
            userDisplayName = "Alex Tester",
            personalIntelligenceEnabled = true,
            userPrompt = "What is my project called?",
            allMemories = reopenedMemoriesA,
            allProjects = reopenedProjectsA
        )
        assertTrue(
            "Scenario C ('What is my project called?') must retrieve 'Smarter AI' in formattedSystemContext",
            recallSnapshot.formattedSystemContext.contains("Smarter AI", ignoreCase = true)
        )
        assertEquals("Smarter AI", recallSnapshot.matchedProjectForContinuation?.projectName)

        // Verify explicit memory opt-out ("Don't remember this...")
        val optOutTurn = com.example.domain.usecases.PersonalIntelligenceEngine.extractMemoriesFromTurn(
            userId = userA,
            conversationId = "conv-session-2",
            userPrompt = "Don't remember this: my temporary test project is called EphemeralX.",
            assistantReply = "Understood, I won't save this to memory.",
            existingMemories = reopenedMemoriesA,
            existingProjects = reopenedProjectsA
        )
        assertTrue("Explicit opt-out must not persist any memory or project", optOutTurn.memoriesToUpsert.isEmpty() && optOutTurn.projectsToUpsert.isEmpty())

        // Verify single memory deletion & full clear on disk
        val firstMemId = reopenedMemoriesA.first().id
        dao2.deleteUserMemoryById(firstMemId, userA)
        assertFalse(dao2.getUserMemories(userA).any { it.id == firstMemId })

        dao2.deleteAllMemoriesForUser(userA)
        dao2.deleteAllProjectContextsForUser(userA)
        assertTrue(dao2.getUserMemories(userA).isEmpty())
        assertTrue(dao2.getProjectContexts(userA).isEmpty())
        // User B's memory must still remain untouched after clearing User A
        assertEquals(1, dao2.getUserMemories(userB).size)

        dbSession2.close()
        context.deleteDatabase(dbFileName)

        // ====================================================================
        // SOURCE CITATION TIMESTAMP DISTINCTION VERIFICATION (UI)
        // Verify LiveMarketChartCard distinguishes Publication Time vs Retrieval Time
        // ====================================================================
        val researchPayload = LiveMarketDataPayload(
            id = "verify-citations-1",
            category = LiveDataCategory.TECH_RESEARCH,
            title = "Live Tech Research • AI",
            symbolOrPair = "AI",
            currentValue = 2.0,
            formattedPrimaryValue = "2 Verified Live Sources",
            currencyOrUnit = "Live Sources",
            providerName = "Hacker News Algolia & Live Tech Feed",
            sourceAttributionUrl = "https://hn.algolia.com",
            providerTimestampEpochMs = 1794048000000L,
            formattedTimestamp = "2026-11-07 10:00 UTC",
            freshness = LiveDataFreshness.LIVE,
            timeframeLabel = "Real-Time Feed",
            sourceCitations = listOf(
                com.example.domain.models.LiveSourceCitation(
                    title = "Verified Story With PubDate",
                    sourceName = "Hacker News",
                    url = "https://kotlinlang.org/news",
                    publishedAtLabel = "2026-11-07 08:30 UTC"
                ),
                com.example.domain.models.LiveSourceCitation(
                    title = "Reference Entry Without PubDate",
                    sourceName = "Wikipedia",
                    url = "https://en.wikipedia.org/wiki/Artificial_intelligence",
                    publishedAtLabel = ""
                )
            )
        )

        composeTestRule.setContent {
            NexusAiTheme {
                LiveMarketChartCard(payload = researchPayload)
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("live_research_citations_card").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Hacker News • Published: 2026-11-07 08:30 UTC • Retrieved: 2026-11-07 10:00 UTC • kotlinlang.org"
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "Wikipedia • Published time not provided • Retrieved: 2026-11-07 10:00 UTC • en.wikipedia.org"
        ).assertIsDisplayed()
    }

    // ========================================================================
    // 14. Phase 2 — Production Supabase Memory Sync End-to-End Integration Test
    // ========================================================================

    @Test
    fun phase2_supabaseMemorySync_endToEndRoomToCloud_offlineQueue_conflictResolution_deletion_accountIsolation_andAuthRefresh(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbNameDevice1 = "phase2_sync_device1.db"
        val dbNameDevice2 = "phase2_sync_device2_fresh_install.db"
        context.deleteDatabase(dbNameDevice1)
        context.deleteDatabase(dbNameDevice2)

        val userA = "aaaaaaaa-1111-2222-3333-444444444444"
        val userB = "bbbbbbbb-5555-6666-7777-888888888888"

        fun makeJwt(sub: String, role: String = "authenticated", tag: String = "v1"): String {
            val h = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
            val p = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("""{"sub":"$sub","role":"$role","jti":"$tag"}""".toByteArray())
            return "$h.$p.sig_$tag"
        }

        val anonKey = makeJwt(sub = "anon", role = "anon", tag = "anon")
        val jwtUserAInitial = makeJwt(sub = userA, tag = "initial")
        val jwtUserARefreshed = makeJwt(sub = userA, tag = "refreshed")
        val jwtUserB = makeJwt(sub = userB, tag = "userB")

        // Stateful simulated Supabase PostgREST cloud store enforcing RLS (auth.uid() == user_id)
        val cloudUserMemory = mutableListOf<String>()
        val cloudProjectContext = mutableListOf<String>()
        val cloudConversationSummaries = mutableListOf<String>()
        var isNetworkOffline = false
        var forceHttpStatus: Int? = null
        var rejectInitialJwtWith401 = false

        val postgrestClient = SupabaseAndGatewayClient(
            supabaseUrlProvider = { "https://smarterai-phase2-test.supabase.co" },
            supabaseAnonKeyProvider = { anonKey },
            aiBackendBaseUrlProvider = { "https://smarterai-phase2-test.supabase.co/functions/v1/ai-chat" },
            customHttpExecutor = { req ->
                if (isNetworkOffline) {
                    throw java.io.IOException("Simulated offline network disconnect")
                }
                val authHeader = req.header("Authorization").orEmpty().removePrefix("Bearer ").trim()
                if (rejectInitialJwtWith401 && authHeader == jwtUserAInitial) {
                    return@SupabaseAndGatewayClient okhttp3.Response.Builder()
                        .request(req)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(401)
                        .message("Unauthorized")
                        .body("""{"message":"JWT expired"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                forceHttpStatus?.let { forcedCode ->
                    return@SupabaseAndGatewayClient okhttp3.Response.Builder()
                        .request(req)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(forcedCode)
                        .message("HTTP $forcedCode")
                        .body("""{"code":"42501","message":"Simulated HTTP $forcedCode"}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }

                val jwtUser = SupabaseAndGatewayClient.extractJwtSubjectClaim(authHeader).orEmpty()
                val urlStr = req.url.toString()
                val path = req.url.encodedPath
                val bodyBuffer = okio.Buffer()
                req.body?.writeTo(bodyBuffer)
                val reqBody = bodyBuffer.readUtf8()

                val targetStore = when {
                    path.endsWith("/user_memory") -> cloudUserMemory
                    path.endsWith("/project_context") -> cloudProjectContext
                    path.endsWith("/conversation_summaries") -> cloudConversationSummaries
                    else -> mutableListOf()
                }

                val responseBody = when (req.method) {
                    "GET" -> {
                        val userItems = targetStore.filter { it.contains(""""user_id":"$jwtUser"""") }
                        "[${userItems.joinToString(",")}]"
                    }
                    "POST" -> {
                        // Enforce RLS: user_id in body must match jwtUser
                        if (!reqBody.contains(""""user_id":"$jwtUser"""")) {
                            return@SupabaseAndGatewayClient okhttp3.Response.Builder()
                                .request(req)
                                .protocol(okhttp3.Protocol.HTTP_1_1)
                                .code(403)
                                .message("Forbidden")
                                .body("""{"code":"42501","message":"new row violates row-level security policy"}""".toResponseBody("application/json".toMediaType()))
                                .build()
                        }
                        // Upsert deduplication by natural key
                        when {
                            path.endsWith("/user_memory") -> {
                                val cat = Regex(""""category":"([^"]+)"""").find(reqBody)?.groupValues?.getOrNull(1).orEmpty()
                                val key = Regex(""""memory_key":"([^"]+)"""").find(reqBody)?.groupValues?.getOrNull(1).orEmpty()
                                targetStore.removeAll {
                                    it.contains(""""user_id":"$jwtUser"""") &&
                                        it.contains(""""category":"$cat"""") &&
                                        it.contains(""""memory_key":"$key"""")
                                }
                            }
                            path.endsWith("/project_context") -> {
                                val pName = Regex(""""project_name":"([^"]+)"""").find(reqBody)?.groupValues?.getOrNull(1).orEmpty()
                                targetStore.removeAll {
                                    it.contains(""""user_id":"$jwtUser"""") &&
                                        it.contains(""""project_name":"$pName"""")
                                }
                            }
                            path.endsWith("/conversation_summaries") -> {
                                val cId = Regex(""""conversation_id":"([^"]+)"""").find(reqBody)?.groupValues?.getOrNull(1).orEmpty()
                                targetStore.removeAll {
                                    it.contains(""""user_id":"$jwtUser"""") &&
                                        it.contains(""""conversation_id":"$cId"""")
                                }
                            }
                        }
                        targetStore.add(reqBody)
                        "[$reqBody]"
                    }
                    "DELETE" -> {
                        val query = req.url.query.orEmpty()
                        val keyParam = req.url.queryParameter("memory_key")?.removePrefix("eq.")
                        val projParam = req.url.queryParameter("project_name")?.removePrefix("eq.")
                        val convParam = req.url.queryParameter("conversation_id")?.removePrefix("eq.")
                        val idParam = req.url.queryParameter("id")?.removePrefix("eq.")
                        when {
                            keyParam != null -> targetStore.removeAll {
                                it.contains(""""user_id":"$jwtUser"""") && it.contains(""""memory_key":"$keyParam"""")
                            }
                            projParam != null -> targetStore.removeAll {
                                it.contains(""""user_id":"$jwtUser"""") && it.contains(""""project_name":"$projParam"""")
                            }
                            convParam != null -> targetStore.removeAll {
                                it.contains(""""user_id":"$jwtUser"""") && it.contains(""""conversation_id":"$convParam"""")
                            }
                            idParam != null -> targetStore.removeAll {
                                it.contains(""""user_id":"$jwtUser"""") && it.contains(""""id":"$idParam"""")
                            }
                            query.contains("user_id=eq.$jwtUser") -> targetStore.removeAll {
                                it.contains(""""user_id":"$jwtUser"""")
                            }
                        }
                        "[]"
                    }
                    else -> "[]"
                }

                okhttp3.Response.Builder()
                    .request(req)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .addHeader("Content-Type", "application/json")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
        )

        val sessionFlow = MutableStateFlow<AuthSession?>(
            AuthSession(
                accessToken = jwtUserAInitial,
                refreshToken = "refresh-a",
                expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
                user = UserProfile(id = userA, email = "alpha@smarterai.app", displayName = "Alpha", isEmailVerified = true),
                hasValidCloudJwt = true
            )
        )
        var refreshCallCount = 0

        val fakeAuthRepo = object : AuthRepository {
            override val sessionFlow: StateFlow<AuthSession?> = sessionFlow
            override val isConfiguredInBuild: Boolean = true
            override suspend fun restoreSession() = Result.success(sessionFlow.value)
            override suspend fun signUp(email: String, password: String, displayName: String) = Result.success(sessionFlow.value!!)
            override suspend fun signIn(email: String, password: String) = Result.success(sessionFlow.value!!)
            override suspend fun sendPasswordResetEmail(email: String) = Result.success(Unit)
            override suspend fun refreshSessionIfNeeded(): Result<AuthSession?> {
                refreshCallCount++
                val current = sessionFlow.value ?: return Result.success(null)
                val refreshed = current.copy(
                    accessToken = jwtUserARefreshed,
                    expiresAtEpochMs = System.currentTimeMillis() + 7_200_000L
                )
                sessionFlow.value = refreshed
                return Result.success(refreshed)
            }
            override suspend fun updateProfileName(displayName: String) = Result.success(sessionFlow.value!!.user)
            override suspend fun signOut(): Result<Unit> {
                sessionFlow.value = null
                return Result.success(Unit)
            }
            override suspend fun deleteAccount() = signOut()
        }

        val fakeCatalogRepo = object : ModelCatalogRepository {
            override val catalogFlow = MutableStateFlow(AiModelCatalog.INITIAL_DISCOVERY_MODELS)
            override val isRefreshingFlow = MutableStateFlow(false)
            override val lastVerifiedEpochMsFlow = MutableStateFlow<Long?>(System.currentTimeMillis())
            override suspend fun refreshModelCatalog() = Result.success(catalogFlow.value)
            override fun getModelById(providerModelId: String) = AiModelCatalog.findById(providerModelId)
        }

        val db1 = androidx.room.Room.databaseBuilder(context, com.example.data.local.NexusAiLocalDatabase::class.java, dbNameDevice1)
            .addMigrations(com.example.data.local.NexusAiLocalDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        val dao1 = db1.chatCacheDao()
        val repo1 = com.example.data.chat.ChatRepositoryImpl(dao1, postgrestClient, fakeAuthRepo, fakeCatalogRepo)

        // ====================================================================
        // 1. Authenticated Cloud Memory Upload & Duplicate Prevention across all 3 tables
        // ====================================================================
        val mem1 = com.example.domain.models.UserMemoryItem(
            id = "mem-a-1",
            userId = userA,
            category = com.example.domain.models.MemoryCategory.USER_PREFERENCE,
            memoryKey = "pref_coding_lang",
            content = "I prefer Kotlin v1",
            sourceType = com.example.domain.models.MemorySourceType.EXPLICIT_USER,
            confidence = 0.98,
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 2_000L
        )
        assertTrue(repo1.saveUserMemory(mem1).isSuccess)
        assertEquals(1, cloudUserMemory.size)
        assertTrue("Saved memory must be marked synced in Room", dao1.getUnsyncedUserMemories(userA).isEmpty())

        // Duplicate prevention: saving another memory with same (userId, category, memoryKey) but newer timestamp updates existing row instead of duplicating
        val mem1Updated = mem1.copy(
            id = "mem-a-duplicate-id",
            content = "I prefer Kotlin and Jetpack Compose v2",
            updatedAtEpochMs = 5_000L
        )
        assertTrue(repo1.saveUserMemory(mem1Updated).isSuccess)
        assertEquals("Room must not create duplicate rows for same natural key", 1, repo1.getUserMemoriesSnapshot(userA).size)
        assertEquals("Cloud must not create duplicate rows for same natural key", 1, cloudUserMemory.size)
        assertEquals("I prefer Kotlin and Jetpack Compose v2", repo1.getUserMemoriesSnapshot(userA).first().content)

        // Save ProjectContext and ConversationSummary
        val proj1 = com.example.domain.models.ProjectContextMemory(
            id = "proj-a-1",
            userId = userA,
            projectName = "Smarter AI",
            summary = "Gemini-inspired AI assistant with Personal Intelligence",
            techStack = "Kotlin, Jetpack Compose, Room, Supabase",
            recentDecisions = "Offline-first PostgREST sync",
            openTasks = "Run verification suite",
            lastConversationId = "conv-1",
            updatedAtEpochMs = 6_000L
        )
        assertTrue(repo1.saveProjectContext(proj1).isSuccess)
        assertEquals(1, cloudProjectContext.size)

        val sum1 = com.example.domain.models.ConversationSummaryMemory(
            id = "sum-a-1",
            userId = userA,
            conversationId = "conv-1",
            summaryText = "Discussed Smarter AI Phase 2 cloud memory architecture",
            keyTopics = "supabase, room, rls, sync",
            updatedAtEpochMs = 7_000L
        )
        assertTrue(repo1.saveConversationSummary(sum1).isSuccess)
        assertEquals(1, cloudConversationSummaries.size)

        // ====================================================================
        // 2. Offline Queue & Automatic Retry when Connectivity Returns
        // ====================================================================
        isNetworkOffline = true
        val offlineMem = com.example.domain.models.UserMemoryItem(
            id = "mem-a-offline",
            userId = userA,
            category = com.example.domain.models.MemoryCategory.COMMUNICATION_STYLE,
            memoryKey = "pref_concise_tone",
            content = "Keep explanations concise and structured",
            sourceType = com.example.domain.models.MemorySourceType.EXPLICIT_USER,
            confidence = 0.99,
            createdAtEpochMs = 8_000L,
            updatedAtEpochMs = 8_500L
        )
        assertTrue("Offline save must succeed locally", repo1.saveUserMemory(offlineMem).isSuccess)
        assertEquals("Local Room must immediately have 2 memories", 2, repo1.getUserMemoriesSnapshot(userA).size)
        assertEquals("Cloud still has 1 memory while offline", 1, cloudUserMemory.size)
        assertEquals("Offline queue must hold 1 pending UPSERT", 1, repo1.getPendingMemorySyncOperations(userA).size)

        // Reconnect network and sync -> queue flushes automatically to cloud
        isNetworkOffline = false
        val reconnectReport = repo1.syncPersonalIntelligenceWithCloud(userA).getOrThrow()
        assertTrue("Pending queue must be empty after reconnect sync", reconnectReport.pendingQueueCount == 0)
        assertEquals("Cloud must now have both memories", 2, cloudUserMemory.size)
        assertTrue("All local memories must be marked synced", dao1.getUnsyncedUserMemories(userA).isEmpty())

        // ====================================================================
        // 3. Expired JWT (HTTP 401) -> Automatic Token Refresh & Retry
        // ====================================================================
        sessionFlow.value = sessionFlow.value!!.copy(accessToken = jwtUserAInitial)
        rejectInitialJwtWith401 = true
        val refreshSyncResult = repo1.syncPersonalIntelligenceWithCloud(userA)
        assertTrue("401 expired token must trigger refreshSessionIfNeeded and succeed", refreshSyncResult.isSuccess)
        assertTrue("refreshSessionIfNeeded must have been called", refreshCallCount >= 1)
        assertEquals(jwtUserARefreshed, sessionFlow.value?.accessToken)
        rejectInitialJwtWith401 = false

        // ====================================================================
        // 4. Fresh Installation / Login on Device 2 (Cloud-to-Room Sync & Conflict Resolution)
        // ====================================================================
        val db2 = androidx.room.Room.databaseBuilder(context, com.example.data.local.NexusAiLocalDatabase::class.java, dbNameDevice2)
            .addMigrations(com.example.data.local.NexusAiLocalDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        val dao2 = db2.chatCacheDao()
        val repo2 = com.example.data.chat.ChatRepositoryImpl(dao2, postgrestClient, fakeAuthRepo, fakeCatalogRepo)

        // Pre-seed Device 2 with an OLDER conflicting local memory and a NEWER local project update
        dao2.upsertUserMemory(
            com.example.data.local.UserMemoryEntity.fromDomain(
                mem1.copy(content = "Stale older local preference", updatedAtEpochMs = 1_500L),
                isSynced = false
            )
        )
        dao2.upsertProjectContext(
            com.example.data.local.ProjectContextEntity.fromDomain(
                proj1.copy(
                    summary = "Updated on Device 2 with GraphQL support",
                    techStack = "Kotlin, GraphQL",
                    updatedAtEpochMs = 15_000L
                ),
                isSynced = false
            )
        )

        val device2SyncReport = repo2.syncPersonalIntelligenceWithCloud(userA).getOrThrow()
        assertTrue("Device 2 must resolve conflicts", device2SyncReport.conflictsResolvedCount >= 2)

        val device2Memories = repo2.getUserMemoriesSnapshot(userA)
        assertEquals("Device 2 must have all 2 userA memories from cloud", 2, device2Memories.size)
        assertEquals(
            "Newer cloud memory (5000ms) must win over stale Device 2 memory (1500ms)",
            "I prefer Kotlin and Jetpack Compose v2",
            device2Memories.first { it.memoryKey == "pref_coding_lang" }.content
        )

        val device2Projects = repo2.getProjectContextsSnapshot(userA)
        assertEquals(1, device2Projects.size)
        assertEquals(
            "Newer Device 2 project summary (15000ms) must win and merge techStack",
            "Updated on Device 2 with GraphQL support",
            device2Projects.first().summary
        )
        assertTrue(device2Projects.first().techStack.contains("GraphQL"))
        assertTrue(device2Projects.first().techStack.contains("Supabase"))

        val device2Summaries = repo2.getConversationSummariesSnapshot(userA)
        assertEquals("Device 2 must download conversation summary from cloud", 1, device2Summaries.size)
        assertEquals("Discussed Smarter AI Phase 2 cloud memory architecture", device2Summaries.first().summaryText)

        // ====================================================================
        // 5. Offline & Online Memory Deletion Synchronization (Single & Clear All)
        // ====================================================================
        isNetworkOffline = true
        assertTrue(repo2.deleteUserMemory(offlineMem.id, userA).isSuccess)
        assertEquals(1, repo2.getUserMemoriesSnapshot(userA).size)
        assertEquals("Delete tombstone must be queued while offline", 1, repo2.getPendingMemorySyncOperations(userA).size)

        // Reconnect and sync -> tombstone deletes record from cloud and does NOT resurrect it
        isNetworkOffline = false
        assertTrue(repo2.syncPersonalIntelligenceWithCloud(userA).isSuccess)
        assertEquals("Deleted memory must be removed from cloud", 1, cloudUserMemory.size)
        assertEquals("Deleted memory must not be resurrected locally", 1, repo2.getUserMemoriesSnapshot(userA).size)

        // ====================================================================
        // 6. Account Switching & User Isolation (User A -> SignOut -> User B)
        // ====================================================================
        fakeAuthRepo.signOut()
        assertNull(fakeAuthRepo.sessionFlow.value)

        // Sign in as User B
        sessionFlow.value = AuthSession(
            accessToken = jwtUserB,
            refreshToken = "refresh-b",
            expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
            user = UserProfile(id = userB, email = "beta@smarterai.app", displayName = "Beta", isEmailVerified = true),
            hasValidCloudJwt = true
        )

        // User B must not be able to read, write, or delete User A's memories
        val crossUserWrite = repo2.saveUserMemory(mem1.copy(userId = userA))
        assertTrue("Cross-user write while logged in as User B must be rejected", crossUserWrite.isFailure)
        val crossUserSync = repo2.syncPersonalIntelligenceWithCloud(userA)
        assertTrue("Cross-user sync for User A while logged in as User B must be rejected", crossUserSync.isFailure)

        // Sync User B with cloud -> User B has 0 memories and never receives User A's cloud data
        val userBSync = repo2.syncPersonalIntelligenceWithCloud(userB).getOrThrow()
        assertEquals(0, userBSync.downloadedMemoriesCount)
        assertTrue("User B local memories must be empty", repo2.getUserMemoriesSnapshot(userB).isEmpty())

        // Switch back to User A and test clearAllUserMemories syncs full deletion to cloud
        sessionFlow.value = AuthSession(
            accessToken = jwtUserARefreshed,
            refreshToken = "refresh-a",
            expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
            user = UserProfile(id = userA, email = "alpha@smarterai.app", displayName = "Alpha", isEmailVerified = true),
            hasValidCloudJwt = true
        )
        assertTrue(repo2.clearAllUserMemories(userA).isSuccess)
        assertTrue("All User A local memories must be cleared", repo2.getUserMemoriesSnapshot(userA).isEmpty())
        assertTrue("All User A cloud user_memory rows must be cleared", cloudUserMemory.isEmpty())
        assertTrue("All User A cloud project_context rows must be cleared", cloudProjectContext.isEmpty())
        assertTrue("All User A cloud conversation_summaries rows must be cleared", cloudConversationSummaries.isEmpty())

        // ====================================================================
        // 7. RLS 403 & Supabase 503 Unavailable Error Handling
        // ====================================================================
        forceHttpStatus = 403
        val rlsFail = repo2.syncPersonalIntelligenceWithCloud(userA)
        assertTrue("HTTP 403 RLS error must surface as failure on explicit sync", rlsFail.isFailure)
        assertEquals(403, (rlsFail.exceptionOrNull() as? AiGatewayException)?.statusCode)

        forceHttpStatus = 503
        val unavailableSave = repo2.saveUserMemory(
            mem1.copy(id = "mem-503-test", content = "Saved while Supabase returned 503", updatedAtEpochMs = 20_000L)
        )
        assertTrue("Local save must still succeed when Supabase returns 503", unavailableSave.isSuccess)
        assertEquals(1, repo2.getUserMemoriesSnapshot(userA).size)
        assertEquals("503 failure must queue item in pending_memory_sync_queue", 1, repo2.getPendingMemorySyncOperations(userA).size)

        db1.close()
        db2.close()
        context.deleteDatabase(dbNameDevice1)
        context.deleteDatabase(dbNameDevice2)
    }

    @Test
    fun liveSupabaseProductionCloudSync_realWorldVerification(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbLiveDevice1 = "live_supabase_verify_device1.db"
        val dbLiveDevice2 = "live_supabase_verify_device2_restore.db"
        context.deleteDatabase(dbLiveDevice1)
        context.deleteDatabase(dbLiveDevice2)

        val supabaseUrl = BuildConfig.SUPABASE_URL.ifBlank { "https://xwkfyamulhfptgywlvef.supabase.co" }.trim().removeSurrounding("\"").trimEnd('/')
        val rawAnonKey = BuildConfig.SUPABASE_ANON_KEY.ifBlank {
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inh3a2Z5YW11bGhmcHRneXdsdmVmIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzU0MDQxNDcsImV4cCI6MjA5MDk4MDE0N30.X39G-G-G72N2wH2yIksX37n1d8wM9j16m6k5Y7qI9E0"
        }
        val realHttp = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        var simulateOffline = false
        val realNetworkClient = SupabaseAndGatewayClient(
            supabaseUrlProvider = { supabaseUrl },
            supabaseAnonKeyProvider = { rawAnonKey },
            aiBackendBaseUrlProvider = { "$supabaseUrl/functions/v1/ai-chat" },
            customHttpExecutor = { req ->
                if (simulateOffline) {
                    throw java.io.IOException("Simulated temporary network disconnect for offline queue verification")
                }
                val resp = realHttp.newCall(req).execute()
                val rawPeek = resp.peekBody(512).string().replace("\n", " ")
                val sanitizedPath = req.url.encodedPath + (req.url.encodedQuery?.let { "?$it" } ?: "")
                val sanitizedPreview = if (sanitizedPath.contains("/auth/v1/")) {
                    """{"status":"authenticated_session_issued","tokens":"<REDACTED>"}"""
                } else {
                    rawPeek.take(220)
                }
                println("LIVE_SUPABASE_HTTP | method=${req.method} | endpoint=$sanitizedPath | status=${resp.code} | bodyPreview=$sanitizedPreview")
                resp
            }
        )

        println("=== [FINAL PRODUCTION CLOUD SYNC VERIFICATION START] ===")
        println("LIVE_SUPABASE_ENV | projectHost=${java.net.URI(supabaseUrl).host} | isLiveCloudConfigured=${realNetworkClient.isLiveCloudConfigured}")

        // ====================================================================
        // SECTION 1: Verify Supabase Connectivity & Remote Table Availability
        // ====================================================================
        val anonKey = realNetworkClient.supabaseAnonKey
        for (table in listOf("user_memory", "project_context", "conversation_summaries")) {
            val probeReq = okhttp3.Request.Builder()
                .url("$supabaseUrl/rest/v1/$table?select=id&limit=1")
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .get()
                .build()
            realHttp.newCall(probeReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                println("SECTION1_TABLE_PROBE | table=public.$table | status=${resp.code} | body=$body")
                assertEquals("Remote table public.$table must exist and return HTTP 200 (not 404 PGRST205)", 200, resp.code)
                assertFalse("PGRST205 error must be resolved for public.$table", body.contains("PGRST205"))
            }
        }

        // ====================================================================
        // Authenticate two dedicated test accounts (User A & User B) on Live Supabase
        // ====================================================================
        suspend fun authenticateOrCreateLiveTestUser(emailPrefix: String, password: String, displayName: String): AuthSession {
            val fixedEmail = "$emailPrefix@smarterai.tech"
            val signInAttempt = runCatching {
                realNetworkClient.signInWithEmail(fixedEmail, password)
            }
            if (signInAttempt.isSuccess && signInAttempt.getOrThrow().hasValidCloudJwt) {
                val s = signInAttempt.getOrThrow()
                println("LIVE_AUTH | account=$emailPrefix | mode=signIn | hasValidJwt=${s.hasValidCloudJwt} | userId=${s.user.id}")
                return s
            }
            val signUpFixed = runCatching {
                realNetworkClient.signUpWithEmail(fixedEmail, password, displayName)
            }
            if (signUpFixed.isSuccess && signUpFixed.getOrThrow().hasValidCloudJwt) {
                val s = signUpFixed.getOrThrow()
                println("LIVE_AUTH | account=$emailPrefix | mode=signUpFixed | hasValidJwt=${s.hasValidCloudJwt} | userId=${s.user.id}")
                return s
            }
            // If fixedEmail existed with different state or required fresh signup, try unique timestamped email
            val uniqueEmail = "${emailPrefix}.${System.currentTimeMillis()}@smarterai.tech"
            val signUpUnique = runCatching {
                realNetworkClient.signUpWithEmail(uniqueEmail, password, displayName)
            }
            if (signUpUnique.isSuccess) {
                val s = signUpUnique.getOrThrow()
                println("LIVE_AUTH | account=$emailPrefix | mode=signUpUnique | hasValidJwt=${s.hasValidCloudJwt} | userId=${s.user.id}")
                if (s.hasValidCloudJwt) return s
                // Attempt signIn immediately after signUp
                val signInAfter = runCatching { realNetworkClient.signInWithEmail(uniqueEmail, password) }
                if (signInAfter.isSuccess && signInAfter.getOrThrow().hasValidCloudJwt) {
                    return signInAfter.getOrThrow()
                }
                return s
            } else {
                val err = signUpUnique.exceptionOrNull()
                println("LIVE_AUTH_ERROR | account=$emailPrefix | error=${err?.javaClass?.simpleName}: ${err?.message}")
                throw err ?: IllegalStateException("Failed to authenticate live test account $emailPrefix")
            }
        }

        val sessionA = authenticateOrCreateLiveTestUser(
            emailPrefix = "verify.sync.alpha",
            password = "SmarterAiVerify#2026A",
            displayName = "Verify Alpha"
        )
        val sessionB = authenticateOrCreateLiveTestUser(
            emailPrefix = "verify.sync.beta",
            password = "SmarterAiVerify#2026B",
            displayName = "Verify Beta"
        )

        println("LIVE_AUTH_SUMMARY | userA=${sessionA.user.id} (jwt=${sessionA.hasValidCloudJwt}) | userB=${sessionB.user.id} (jwt=${sessionB.hasValidCloudJwt})")
        assertTrue("Live Supabase Auth must issue a valid JWT for User A", sessionA.hasValidCloudJwt)
        assertTrue("Live Supabase Auth must issue a valid JWT for User B", sessionB.hasValidCloudJwt)

        val userA = sessionA.user.id
        val userB = sessionB.user.id
        assertEquals("JWT sub must match User A ID", userA, SupabaseAndGatewayClient.extractJwtSubjectClaim(sessionA.accessToken))
        assertEquals("JWT sub must match User B ID", userB, SupabaseAndGatewayClient.extractJwtSubjectClaim(sessionB.accessToken))

        val activeSessionFlow = MutableStateFlow<AuthSession?>(sessionA)
        val liveAuthRepo = object : AuthRepository {
            override val sessionFlow: StateFlow<AuthSession?> = activeSessionFlow
            override val isConfiguredInBuild: Boolean = true
            override suspend fun restoreSession() = Result.success(activeSessionFlow.value)
            override suspend fun signUp(email: String, password: String, displayName: String) = Result.success(activeSessionFlow.value!!)
            override suspend fun signIn(email: String, password: String) = Result.success(activeSessionFlow.value!!)
            override suspend fun sendPasswordResetEmail(email: String) = Result.success(Unit)
            override suspend fun refreshSessionIfNeeded(): Result<AuthSession?> {
                val cur = activeSessionFlow.value ?: return Result.success(null)
                if (cur.refreshToken.isNotBlank()) {
                    val refreshed = runCatching { realNetworkClient.refreshSession(cur.refreshToken) }.getOrNull()
                    if (refreshed != null) {
                        activeSessionFlow.value = refreshed
                        return Result.success(refreshed)
                    }
                }
                return Result.success(cur)
            }
            override suspend fun updateProfileName(displayName: String) = Result.success(activeSessionFlow.value!!.user)
            override suspend fun signOut(): Result<Unit> {
                activeSessionFlow.value = null
                return Result.success(Unit)
            }
            override suspend fun deleteAccount() = signOut()
        }

        val liveCatalogRepo = object : ModelCatalogRepository {
            override val catalogFlow = MutableStateFlow(AiModelCatalog.INITIAL_DISCOVERY_MODELS)
            override val isRefreshingFlow = MutableStateFlow(false)
            override val lastVerifiedEpochMsFlow = MutableStateFlow<Long?>(System.currentTimeMillis())
            override suspend fun refreshModelCatalog() = Result.success(catalogFlow.value)
            override fun getModelById(providerModelId: String) = AiModelCatalog.findById(providerModelId)
        }

        val db1 = androidx.room.Room.databaseBuilder(context, com.example.data.local.NexusAiLocalDatabase::class.java, dbLiveDevice1)
            .addMigrations(com.example.data.local.NexusAiLocalDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        val dao1 = db1.chatCacheDao()
        val repo1 = com.example.data.chat.ChatRepositoryImpl(dao1, realNetworkClient, liveAuthRepo, liveCatalogRepo)

        // Clean any prior test data for User A and User B before starting verification
        repo1.clearAllUserMemories(userA)
        activeSessionFlow.value = sessionB
        repo1.clearAllUserMemories(userB)
        activeSessionFlow.value = sessionA

        // Create a real conversation row in public.conversations for User A so conversation_summaries FK (if any) or reference is valid
        val testConvId = java.util.UUID.randomUUID().toString()
        runCatching {
            realNetworkClient.insertConversation(
                accessToken = sessionA.accessToken,
                conversation = Conversation(
                    id = testConvId,
                    userId = userA,
                    title = "Phase 2 Cloud Sync Verification",
                    selectedModelId = AiModelCatalog.DEFAULT_MODEL_ID
                )
            )
        }

        // ====================================================================
        // SECTION 2: Real Authenticated Cloud Upload (user_memory, project_context, conversation_summaries)
        // ====================================================================
        val nowMs = System.currentTimeMillis()
        val liveMemId = java.util.UUID.randomUUID().toString()
        val liveMem = com.example.domain.models.UserMemoryItem(
            id = liveMemId,
            userId = userA,
            category = com.example.domain.models.MemoryCategory.USER_PREFERENCE,
            memoryKey = "pref_architecture_pattern",
            content = "Prefer Clean Architecture with Kotlin Coroutines and Room offline-first sync",
            sourceType = com.example.domain.models.MemorySourceType.EXPLICIT_USER,
            confidence = 0.98,
            conversationId = null,
            isUserApproved = true,
            createdAtEpochMs = nowMs - 5_000L,
            updatedAtEpochMs = nowMs
        )
        val saveMemRes = repo1.saveUserMemory(liveMem)
        assertTrue("saveUserMemory must succeed", saveMemRes.isSuccess)
        val localMemRow = dao1.getUserMemories(userA).firstOrNull { it.memoryKey == "pref_architecture_pattern" }
        assertNotNull("user_memory must be saved in Room immediately", localMemRow)
        assertTrue("user_memory isSyncedWithCloud must become true after live upload", localMemRow!!.isSyncedWithCloud)

        val remoteMemoriesAfterUpload = realNetworkClient.fetchUserMemories(sessionA.accessToken, userA)
        println("SECTION2_UPLOAD_VERIFY | table=user_memory | remoteCount=${remoteMemoriesAfterUpload.size} | localSynced=${localMemRow.isSyncedWithCloud}")
        assertEquals("Remote public.user_memory must contain the uploaded record", 1, remoteMemoriesAfterUpload.size)
        assertEquals("pref_architecture_pattern", remoteMemoriesAfterUpload.first().memoryKey)
        assertEquals(liveMem.content, remoteMemoriesAfterUpload.first().content)

        val liveProjId = java.util.UUID.randomUUID().toString()
        val liveProj = com.example.domain.models.ProjectContextMemory(
            id = liveProjId,
            userId = userA,
            projectName = "Smarter AI 1.0 Cloud Sync",
            summary = "Production Personal Intelligence synchronization with Supabase PostgREST and Room",
            techStack = "Kotlin, Jetpack Compose, Room, Supabase PostgREST",
            recentDecisions = "Enforce RLS and deterministic conflict resolution",
            openTasks = "Complete real-world verification",
            lastConversationId = null,
            updatedAtEpochMs = nowMs + 1_000L
        )
        val saveProjRes = repo1.saveProjectContext(liveProj)
        assertTrue("saveProjectContext must succeed", saveProjRes.isSuccess)
        val localProjRow = dao1.getProjectContexts(userA).firstOrNull { it.projectName == "Smarter AI 1.0 Cloud Sync" }
        assertNotNull("project_context must be saved in Room immediately", localProjRow)
        assertTrue("project_context isSyncedWithCloud must become true after live upload", localProjRow!!.isSyncedWithCloud)

        val remoteProjectsAfterUpload = realNetworkClient.fetchProjectContexts(sessionA.accessToken, userA)
        println("SECTION2_UPLOAD_VERIFY | table=project_context | remoteCount=${remoteProjectsAfterUpload.size} | localSynced=${localProjRow.isSyncedWithCloud}")
        assertEquals("Remote public.project_context must contain the uploaded record", 1, remoteProjectsAfterUpload.size)
        assertEquals("Smarter AI 1.0 Cloud Sync", remoteProjectsAfterUpload.first().projectName)

        val liveSumId = java.util.UUID.randomUUID().toString()
        val liveSum = com.example.domain.models.ConversationSummaryMemory(
            id = liveSumId,
            userId = userA,
            conversationId = testConvId,
            summaryText = "Verified live upload and download across all three Personal Intelligence tables.",
            keyTopics = "supabase, postgrest, rls, room",
            updatedAtEpochMs = nowMs + 2_000L
        )
        val saveSumRes = repo1.saveConversationSummary(liveSum)
        assertTrue("saveConversationSummary must succeed", saveSumRes.isSuccess)
        val localSumRow = dao1.getConversationSummaries(userA).firstOrNull { it.conversationId == testConvId }
        assertNotNull("conversation_summaries must be saved in Room immediately", localSumRow)
        assertTrue("conversation_summaries isSyncedWithCloud must become true after live upload", localSumRow!!.isSyncedWithCloud)

        val remoteSummariesAfterUpload = realNetworkClient.fetchConversationSummaries(sessionA.accessToken, userA)
        println("SECTION2_UPLOAD_VERIFY | table=conversation_summaries | remoteCount=${remoteSummariesAfterUpload.size} | localSynced=${localSumRow.isSyncedWithCloud}")
        assertEquals("Remote public.conversation_summaries must contain the uploaded record", 1, remoteSummariesAfterUpload.size)
        assertEquals(testConvId, remoteSummariesAfterUpload.first().conversationId)

        // ====================================================================
        // SECTION 3: Real Cloud Download & Restoration into Fresh Empty Room DB
        // ====================================================================
        val db2 = androidx.room.Room.databaseBuilder(context, com.example.data.local.NexusAiLocalDatabase::class.java, dbLiveDevice2)
            .addMigrations(com.example.data.local.NexusAiLocalDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        val dao2 = db2.chatCacheDao()
        val repo2 = com.example.data.chat.ChatRepositoryImpl(dao2, realNetworkClient, liveAuthRepo, liveCatalogRepo)

        assertTrue("Fresh Room DB must start empty", dao2.getUserMemories(userA).isEmpty())
        assertTrue("Fresh Room DB projects must start empty", dao2.getProjectContexts(userA).isEmpty())
        assertTrue("Fresh Room DB summaries must start empty", dao2.getConversationSummaries(userA).isEmpty())

        val restoreReport = repo2.syncPersonalIntelligenceWithCloud(userA).getOrThrow()
        println(
            "SECTION3_RESTORE_REPORT | downloadedMemories=${restoreReport.downloadedMemoriesCount} | " +
                "downloadedProjects=${restoreReport.downloadedProjectsCount} | " +
                "downloadedSummaries=${restoreReport.downloadedSummariesCount}"
        )
        val restoredMemories = repo2.getUserMemoriesSnapshot(userA)
        val restoredProjects = repo2.getProjectContextsSnapshot(userA)
        val restoredSummaries = repo2.getConversationSummariesSnapshot(userA)

        assertEquals(1, restoredMemories.size)
        assertEquals(liveMem.id, restoredMemories.first().id)
        assertEquals(liveMem.memoryKey, restoredMemories.first().memoryKey)
        assertEquals(liveMem.sourceType, restoredMemories.first().sourceType)
        assertEquals(userA, restoredMemories.first().userId)

        assertEquals(1, restoredProjects.size)
        assertEquals(liveProj.id, restoredProjects.first().id)
        assertEquals(liveProj.projectName, restoredProjects.first().projectName)
        assertEquals(userA, restoredProjects.first().userId)

        assertEquals(1, restoredSummaries.size)
        assertEquals(liveSum.id, restoredSummaries.first().id)
        assertEquals(liveSum.conversationId, restoredSummaries.first().conversationId)
        assertEquals(userA, restoredSummaries.first().userId)

        // Verify newer local data is NOT overwritten by older cloud record during sync
        val newerLocalMem = restoredMemories.first().copy(
            content = "Prefer Clean Architecture with Kotlin Coroutines, Room, and newer local update",
            updatedAtEpochMs = nowMs + 60_000L
        )
        dao2.upsertUserMemory(com.example.data.local.UserMemoryEntity.fromDomain(newerLocalMem, isSynced = false))
        val conflictReport = repo2.syncPersonalIntelligenceWithCloud(userA).getOrThrow()
        assertEquals(
            "Newer local memory must NOT be overwritten by older cloud record",
            newerLocalMem.content,
            repo2.getUserMemoriesSnapshot(userA).first().content
        )
        assertEquals(
            "Newer local memory must be pushed to Supabase during conflict resolution",
            newerLocalMem.content,
            realNetworkClient.fetchUserMemories(sessionA.accessToken, userA).first().content
        )
        println("SECTION3_CONFLICT_VERIFY | conflictsResolved=${conflictReport.conflictsResolvedCount} | uploadedMemories=${conflictReport.uploadedMemoriesCount}")

        // ====================================================================
        // SECTION 5: Offline Queue Verification against Live Supabase
        // ====================================================================
        simulateOffline = true
        val offlineQueueMemId = java.util.UUID.randomUUID().toString()
        val offlineQueueMem = com.example.domain.models.UserMemoryItem(
            id = offlineQueueMemId,
            userId = userA,
            category = com.example.domain.models.MemoryCategory.WORKFLOW,
            memoryKey = "workflow_gradle_verification",
            content = "Always run Robolectric and live PostgREST verification before release",
            sourceType = com.example.domain.models.MemorySourceType.EXPLICIT_USER,
            confidence = 0.97,
            isUserApproved = true,
            createdAtEpochMs = nowMs + 70_000L,
            updatedAtEpochMs = nowMs + 70_000L
        )
        assertTrue("Offline saveUserMemory must succeed locally", repo2.saveUserMemory(offlineQueueMem).isSuccess)
        val localOfflineRow = dao2.getUserMemories(userA).firstOrNull { it.memoryKey == "workflow_gradle_verification" }
        assertNotNull("Offline memory must be immediately persisted in Room", localOfflineRow)
        assertFalse("Offline memory must have isSyncedWithCloud=false while offline", localOfflineRow!!.isSyncedWithCloud)

        val pendingQueueBeforeFlush = repo2.getPendingMemorySyncOperations(userA)
        println("SECTION5_OFFLINE_QUEUE | pendingBeforeFlush=${pendingQueueBeforeFlush.size} | naturalKey=${pendingQueueBeforeFlush.firstOrNull()?.naturalKey}")
        assertEquals("pending_memory_sync_queue must contain the offline UPSERT operation", 1, pendingQueueBeforeFlush.size)

        // Restore network connectivity and flush real queue to live Supabase
        simulateOffline = false
        val flushReport = repo2.flushPendingMemorySyncQueue(userA).getOrThrow()
        val pendingQueueAfterFlush = repo2.getPendingMemorySyncOperations(userA)
        val remoteMemoriesAfterFlush = realNetworkClient.fetchUserMemories(sessionA.accessToken, userA)
        println(
            "SECTION5_FLUSH_RESULT | uploadedMemories=${flushReport.uploadedMemoriesCount} | " +
                "pendingAfterFlush=${pendingQueueAfterFlush.size} | remoteTotal=${remoteMemoriesAfterFlush.size}"
        )
        assertTrue("pending_memory_sync_queue must be empty after successful flush", pendingQueueAfterFlush.isEmpty())
        assertTrue(
            "Queued memory must reach live Supabase after flush",
            remoteMemoriesAfterFlush.any { it.memoryKey == "workflow_gradle_verification" }
        )

        // ====================================================================
        // SECTION 6: Live RLS & Cross-User Security Verification (User A vs User B)
        // ====================================================================
        // 1. User B queries public.user_memory with User B's JWT -> RLS must return 0 rows (cannot read User A's 2 rows)
        val userBReadUserA = realNetworkClient.fetchUserMemories(sessionB.accessToken, userB)
        println("SECTION6_RLS_READ | userB_ownRows=${userBReadUserA.size}")
        assertTrue("User B must see 0 rows", userBReadUserA.isEmpty())

        // Direct HTTP query where User B tries to filter ?user_id=eq.<userA> using User B's JWT:
        val crossReadReq = okhttp3.Request.Builder()
            .url("$supabaseUrl/rest/v1/user_memory?user_id=eq.$userA")
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer ${sessionB.accessToken}")
            .get()
            .build()
        realHttp.newCall(crossReadReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            println("SECTION6_RLS_CROSS_READ | endpoint=/rest/v1/user_memory?user_id=eq.<userA> | status=${resp.code} | body=$body")
            assertEquals(200, resp.code)
            assertEquals("RLS must return empty array [] when User B queries User A's user_id", "[]", body.trim())
        }

        // 2. Direct HTTP POST where User B tries to insert/update a row with user_id = userA using User B's JWT:
        val crossWriteBody = """{"id":"${java.util.UUID.randomUUID()}","user_id":"$userA","category":"USER_PREFERENCE","memory_key":"rls_hijack","content":"Hijack attempt","source_type":"EXPLICIT_USER","confidence":1.0,"is_user_approved":true}"""
        val crossWriteReq = okhttp3.Request.Builder()
            .url("$supabaseUrl/rest/v1/user_memory")
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer ${sessionB.accessToken}")
            .addHeader("Content-Type", "application/json")
            .post(okhttp3.RequestBody.create("application/json".toMediaType(), crossWriteBody))
            .build()
        realHttp.newCall(crossWriteReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            println("SECTION6_RLS_CROSS_WRITE | endpoint=/rest/v1/user_memory | status=${resp.code} | body=$body")
            assertTrue("RLS must reject User B inserting a row with user_id=userA (HTTP 403 / 42501)", resp.code == 403 || body.contains("42501") || body.contains("row-level security"))
        }

        // 3. Direct HTTP DELETE where User B tries to delete User A's rows using User B's JWT:
        val crossDeleteReq = okhttp3.Request.Builder()
            .url("$supabaseUrl/rest/v1/user_memory?user_id=eq.$userA")
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer ${sessionB.accessToken}")
            .addHeader("Prefer", "return=representation")
            .delete()
            .build()
        realHttp.newCall(crossDeleteReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            println("SECTION6_RLS_CROSS_DELETE | endpoint=/rest/v1/user_memory?user_id=eq.<userA> | status=${resp.code} | deletedRows=$body")
            assertEquals("RLS must prevent User B from deleting User A's records", "[]", body.trim())
        }
        // Verify User A's 2 memories are still intact in Supabase after User B's delete attempt!
        assertEquals(2, realNetworkClient.fetchUserMemories(sessionA.accessToken, userA).size)

        // ====================================================================
        // SECTION 4: Actual Deletion Verification (Single Deletions, Offline Tombstone, and clearAllUserMemories)
        // ====================================================================
        // 1. Offline tombstone deletion test: delete `offlineQueueMemId` while offline, then sync online
        simulateOffline = true
        assertTrue(repo2.deleteUserMemory(offlineQueueMemId, userA).isSuccess)
        assertEquals("Offline delete must enqueue DELETE tombstone", 1, repo2.getPendingMemorySyncOperations(userA).size)
        simulateOffline = false
        val tombstoneSyncReport = repo2.syncPersonalIntelligenceWithCloud(userA).getOrThrow()
        val remoteMemoriesAfterTombstone = realNetworkClient.fetchUserMemories(sessionA.accessToken, userA)
        println("SECTION4_TOMBSTONE_DELETE | deletedCount=${tombstoneSyncReport.deletedRecordsCount} | remainingRemote=${remoteMemoriesAfterTombstone.size}")
        assertEquals("Offline tombstone must delete record from Supabase and prevent resurrection", 1, remoteMemoriesAfterTombstone.size)
        assertFalse(remoteMemoriesAfterTombstone.any { it.id == offlineQueueMemId })
        assertFalse(repo2.getUserMemoriesSnapshot(userA).any { it.id == offlineQueueMemId })

        // 2. Delete single user_memory online
        assertTrue(repo2.deleteUserMemory(liveMemId, userA).isSuccess)
        val remoteMemAfterSingleDelete = realNetworkClient.fetchUserMemories(sessionA.accessToken, userA)
        println("SECTION4_SINGLE_DELETE | table=user_memory | remainingRemote=${remoteMemAfterSingleDelete.size}")
        assertTrue("public.user_memory must be empty after deleting remaining memory", remoteMemAfterSingleDelete.isEmpty())

        // 3. Delete single project_context online
        assertTrue(repo2.deleteProjectContext(liveProjId, userA).isSuccess)
        val remoteProjAfterSingleDelete = realNetworkClient.fetchProjectContexts(sessionA.accessToken, userA)
        println("SECTION4_SINGLE_DELETE | table=project_context | remainingRemote=${remoteProjAfterSingleDelete.size}")
        assertTrue("public.project_context must be empty after deleting project", remoteProjAfterSingleDelete.isEmpty())

        // 4. Delete single conversation_summary online
        assertTrue(repo2.deleteConversationSummary(liveSumId, userA).isSuccess)
        val remoteSumAfterSingleDelete = realNetworkClient.fetchConversationSummaries(sessionA.accessToken, userA)
        println("SECTION4_SINGLE_DELETE | table=conversation_summaries | remainingRemote=${remoteSumAfterSingleDelete.size}")
        assertTrue("public.conversation_summaries must be empty after deleting summary", remoteSumAfterSingleDelete.isEmpty())

        // 5. Re-populate all 3 tables and test clearAllUserMemories()
        repo2.saveUserMemory(liveMem.copy(id = java.util.UUID.randomUUID().toString(), updatedAtEpochMs = System.currentTimeMillis()))
        repo2.saveProjectContext(liveProj.copy(id = java.util.UUID.randomUUID().toString(), updatedAtEpochMs = System.currentTimeMillis()))
        repo2.saveConversationSummary(liveSum.copy(id = java.util.UUID.randomUUID().toString(), updatedAtEpochMs = System.currentTimeMillis()))
        assertEquals(1, realNetworkClient.fetchUserMemories(sessionA.accessToken, userA).size)
        assertEquals(1, realNetworkClient.fetchProjectContexts(sessionA.accessToken, userA).size)
        assertEquals(1, realNetworkClient.fetchConversationSummaries(sessionA.accessToken, userA).size)

        assertTrue("clearAllUserMemories must succeed", repo2.clearAllUserMemories(userA).isSuccess)
        val finalRemoteMem = realNetworkClient.fetchUserMemories(sessionA.accessToken, userA)
        val finalRemoteProj = realNetworkClient.fetchProjectContexts(sessionA.accessToken, userA)
        val finalRemoteSum = realNetworkClient.fetchConversationSummaries(sessionA.accessToken, userA)
        println(
            "SECTION4_CLEAR_ALL_VERIFY | remoteMem=${finalRemoteMem.size} | " +
                "remoteProj=${finalRemoteProj.size} | remoteSum=${finalRemoteSum.size}"
        )
        assertTrue("clearAllUserMemories must delete all remote user_memory rows", finalRemoteMem.isEmpty())
        assertTrue("clearAllUserMemories must delete all remote project_context rows", finalRemoteProj.isEmpty())
        assertTrue("clearAllUserMemories must delete all remote conversation_summaries rows", finalRemoteSum.isEmpty())

        // Clean up test conversation
        runCatching { realNetworkClient.deleteConversation(sessionA.accessToken, testConvId, userA) }

        db1.close()
        db2.close()
        context.deleteDatabase(dbLiveDevice1)
        context.deleteDatabase(dbLiveDevice2)
        println("=== [FINAL PRODUCTION CLOUD SYNC VERIFICATION END] ===")
    }

    // ========================================================================
    // 18. Phase 6: Intelligence Restoration, 2-Second Identity Thinking, Cancellation,
    //     Multi-Model Failover & Animated Thinking Indicator Verification
    // ========================================================================

    @Test
    fun phase6_intelligenceRestoration_2SecondIdentityThinking_cancellation_multiModelFallback_andAnimatedThinkingIndicatorUi() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // 1. Verify 2-second identity thinking state & cancellation before completion
        val fakeContainer = FakeUiTestAppContainer(initiallyAuthenticated = true)
        val viewModel = com.example.ui.chat.ChatViewModel(
            chatRepository = fakeContainer.chatRepository,
            authRepository = fakeContainer.authRepository,
            modelCatalogRepository = fakeContainer.modelCatalogRepository,
            preferencesRepository = fakeContainer.preferencesRepository,
            liveDataService = fakeContainer.liveDataService
        )

        // Start an identity request ("Who created Smarter AI?") and verify it remains in thinking state at 500ms
        viewModel.updateComposerText("Who created Smarter AI?")
        viewModel.sendMessage()
        assertTrue("Identity request must immediately enter isGenerating=true thinking state", viewModel.uiState.value.isGenerating)
        assertEquals(
            com.example.domain.models.GenerationState.WAITING_FOR_FIRST_TOKEN,
            viewModel.uiState.value.generationState
        )
        assertEquals(
            "Only the user message should be present while thinking indicator is active",
            1,
            viewModel.uiState.value.messages.size
        )
        assertEquals(MessageRole.USER, viewModel.uiState.value.messages.first().role)

        // Advance 500ms (still < 2000ms): assistant answer must NOT be shown yet
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(500))
        assertTrue("Thinking indicator must still be active at 500ms", viewModel.uiState.value.isGenerating)
        assertEquals(1, viewModel.uiState.value.messages.size)

        // Cancel during the 2-second thinking state and verify no assistant message is appended even after 2500ms
        viewModel.stopGeneration()
        assertFalse("isGenerating must become false immediately on cancellation", viewModel.uiState.value.isGenerating)
        assertEquals(
            com.example.domain.models.GenerationState.CANCELLED,
            viewModel.uiState.value.generationState
        )
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(2500))
        assertEquals(
            "Cancelled identity request must never append an assistant response after timer expiry",
            1,
            viewModel.uiState.value.messages.size
        )

        // Now send a new identity request ("Who built this application?") and allow the full 2-second thinking state to complete
        viewModel.updateComposerText("Who built this application?")
        viewModel.sendMessage()
        assertTrue(viewModel.uiState.value.isGenerating)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(2100))
        assertFalse(viewModel.uiState.value.isGenerating)
        assertEquals(
            com.example.domain.models.GenerationState.COMPLETED,
            viewModel.uiState.value.generationState
        )
        assertEquals(
            "I was developed by the Smarter AI technical team.",
            viewModel.uiState.value.messages.last().content
        )

        // 2. Verify AnimatedThinkingIndicator renders in ChatScreen with 3 animated dots when waiting for first token
        val thinkingUiState = mutableStateOf(
            com.example.ui.chat.ChatUiState(
                activeUserId = "user-test-1",
                activeUserDisplayName = "Shiva",
                activeConversationId = "conv-thinking-1",
                messages = listOf(
                    ChatMessage(
                        id = "u-msg-1",
                        conversationId = "conv-thinking-1",
                        userId = "user-test-1",
                        role = MessageRole.USER,
                        content = "What is your name?",
                        modelId = "free/glm-5.3-flash"
                    )
                ),
                isGenerating = true,
                generationState = com.example.domain.models.GenerationState.WAITING_FOR_FIRST_TOKEN,
                requestLifecycleState = RequestLifecycleState.PROCESSING
            )
        )

        composeTestRule.setContent {
            NexusAiTheme(darkTheme = true, reducedAnimations = false) {
                com.example.ui.chat.ChatScreen(
                    uiState = thinkingUiState.value,
                    onStartNewChat = {},
                    onSelectConversation = {},
                    onRenameConversation = { _, _ -> },
                    onDeleteConversation = {},
                    onOpenSearchScreen = {},
                    onOpenSettingsScreen = {},
                    onSignOut = {},
                    onOpenModelSelector = {},
                    onCloseModelSelector = {},
                    onModelSearchQueryChanged = {},
                    onModelCategorySelected = {},
                    onRefreshModelCatalog = {},
                    onSelectModel = {},
                    onComposerTextChanged = {},
                    onApplyPromptSuggestion = {},
                    onAddAttachment = {},
                    onRemoveAttachment = {},
                    onSendMessage = {},
                    onStopGeneration = {},
                    onRegenerateResponse = {},
                    onRetryFailedGeneration = {},
                    onStartEditingMessage = {},
                    onCancelEditingMessage = {},
                    onDismissBanners = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("assistant_thinking_indicator").assertIsDisplayed()
        composeTestRule.onNodeWithTag("thinking_dot_0", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("thinking_dot_1", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("thinking_dot_2", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Thinking…").assertIsDisplayed()

        // 3. Verify ChatRepositoryImpl multi-model automatic failover when primary model fails before emitting tokens
        val attemptedModels = mutableListOf<String>()
        val failoverNetworkClient = SupabaseAndGatewayClient(
            supabaseUrlProvider = { "https://test-project.supabase.co" },
            supabaseAnonKeyProvider = { "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiJ9.sig" },
            aiBackendBaseUrlProvider = { "https://test-project.supabase.co/functions/v1/ai-chat" },
            customHttpExecutor = { req ->
                val bodyStr = okio.Buffer().also { req.body?.writeTo(it) }.readUtf8()
                val reqJson = org.json.JSONObject(bodyStr)
                val modelId = reqJson.getString("model")
                attemptedModels.add(modelId)

                if (modelId == "free/qwen-3.8-max") {
                    // Simulate primary model 503 failure
                    okhttp3.Response.Builder()
                        .request(req)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(503)
                        .message("Service Unavailable")
                        .addHeader("Content-Type", "application/json")
                        .body("""{"error":{"message":"Primary model overloaded"}}""".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    // Fallback model succeeds with SSE stream
                    val ssePayload = """
                        data: {"choices":[{"delta":{"content":"Kotlin is a modern, concise, cross-platform programming language."},"finish_reason":null}]}

                        data: [DONE]

                    """.trimIndent()
                    okhttp3.Response.Builder()
                        .request(req)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .addHeader("Content-Type", "text/event-stream")
                        .body(ssePayload.toResponseBody("text/event-stream".toMediaType()))
                        .build()
                }
            }
        )

        val memContainer = DefaultAppContainer(context, inMemoryDb = true)
        val failoverAuthRepo = SupabaseAuthRepository(
            context = context,
            networkClient = failoverNetworkClient,
            chatCacheDao = memContainer.chatCacheDao
        )
        val session = failoverAuthRepo.signUp("failover@smarterai.app", "Pass1234!", "Failover Tester").getOrThrow()
        val failoverChatRepo = ChatRepositoryImpl(
            chatCacheDao = memContainer.chatCacheDao,
            networkClient = failoverNetworkClient,
            authRepository = failoverAuthRepo,
            modelCatalogRepository = ModelCatalogRepositoryImpl(failoverNetworkClient, failoverAuthRepo)
        )
        val conv = failoverChatRepo.createConversation(session.user.id, "Kotlin Question", "free/qwen-3.8-max").getOrThrow()
        val userMsg = failoverChatRepo.saveUserMessage(
            conversationId = conv.id,
            userId = session.user.id,
            content = "What is Kotlin?",
            modelId = "free/qwen-3.8-max",
            attachments = emptyList(),
            clientRequestId = "req-kotlin-1"
        ).getOrThrow()

        val emittedChunks = mutableListOf<StreamChunk>()
        failoverChatRepo.streamAssistantResponse(
            conversationId = conv.id,
            userId = session.user.id,
            requestedModelId = "free/qwen-3.8-max",
            fallbackModelId = "free/glm-5.3-flash",
            allowFallback = true,
            history = listOf(userMsg),
            streamEnabled = true,
            clientRequestId = "req-kotlin-stream"
        ).collect { emittedChunks.add(it) }

        assertEquals(listOf("free/qwen-3.8-max", "free/glm-5.3-flash"), attemptedModels)
        val completedChunk = emittedChunks.filterIsInstance<StreamChunk.Completed>().firstOrNull()
        assertNotNull("Failover to fallback model must complete successfully", completedChunk)
        assertTrue("fallbackUsed flag must be true after automatic failover", completedChunk!!.fallbackUsed)
        assertEquals(
            "Kotlin is a modern, concise, cross-platform programming language.",
            completedChunk.fullText
        )

        // 4. Verify buildMessagesJsonArray merges MessageRole.SYSTEM Personal Intelligence context into role="system" and never pollutes role="user"
        val historyWithSystemMemory = listOf(
            ChatMessage("sys-1", conv.id, session.user.id, MessageRole.SYSTEM, "[SMARTER AI 1.0 — PERSONAL INTELLIGENCE & MEMORY CONTEXT]\nProject: Smarter AI", "free/glm-5.3-flash"),
            ChatMessage("u-1", conv.id, session.user.id, MessageRole.USER, "What is my project called?", "free/glm-5.3-flash")
        )
        val messagesJson = SupabaseAndGatewayClient.buildMessagesJsonArray("free/glm-5.3-flash", historyWithSystemMemory)
        assertEquals(2, messagesJson.length())
        assertEquals("system", messagesJson.getJSONObject(0).getString("role"))
        assertTrue(messagesJson.getJSONObject(0).getString("content").contains("PRIMARY DIRECTIVE — ANSWER THE ACTUAL QUESTION"))
        assertTrue(messagesJson.getJSONObject(0).getString("content").contains("Project: Smarter AI"))
        assertEquals("user", messagesJson.getJSONObject(1).getString("role"))
        assertEquals("What is my project called?", messagesJson.getJSONObject(1).getString("content"))

        // 5. Verify no fallback retry occurs after partial response text has already been delivered
        val midStreamAttemptedModels = mutableListOf<String>()
        val midStreamErrorClient = SupabaseAndGatewayClient(
            supabaseUrlProvider = { "https://test-project.supabase.co" },
            supabaseAnonKeyProvider = { "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiJ9.sig" },
            aiBackendBaseUrlProvider = { "https://test-project.supabase.co/functions/v1/ai-chat" },
            customHttpExecutor = { req ->
                val bodyStr = okio.Buffer().also { req.body?.writeTo(it) }.readUtf8()
                val modelId = org.json.JSONObject(bodyStr).getString("model")
                midStreamAttemptedModels.add(modelId)

                val sseWithMidStreamError = """
                    data: {"choices":[{"delta":{"content":"Partial answer before interruption."},"finish_reason":null}]}

                    event: error
                    data: {"error":{"message":"Upstream stream interrupted mid-response","code":"SERVER_ERROR"}}

                """.trimIndent()
                okhttp3.Response.Builder()
                    .request(req)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .addHeader("Content-Type", "text/event-stream")
                    .body(sseWithMidStreamError.toResponseBody("text/event-stream".toMediaType()))
                    .build()
            }
        )
        val midStreamRepo = ChatRepositoryImpl(
            chatCacheDao = memContainer.chatCacheDao,
            networkClient = midStreamErrorClient,
            authRepository = failoverAuthRepo,
            modelCatalogRepository = ModelCatalogRepositoryImpl(midStreamErrorClient, failoverAuthRepo)
        )
        val midStreamChunks = mutableListOf<StreamChunk>()
        midStreamRepo.streamAssistantResponse(
            conversationId = conv.id,
            userId = session.user.id,
            requestedModelId = "free/qwen-3.8-max",
            fallbackModelId = "free/glm-5.3-flash",
            allowFallback = true,
            history = listOf(userMsg),
            streamEnabled = true,
            clientRequestId = "req-partial-no-retry"
        ).collect { midStreamChunks.add(it) }

        assertEquals(
            "Must NOT retry fallback model when partial text has already been delivered",
            listOf("free/qwen-3.8-max"),
            midStreamAttemptedModels
        )
        val midStreamErr = midStreamChunks.filterIsInstance<StreamChunk.Error>().firstOrNull()
        assertNotNull(midStreamErr)
        assertEquals("Partial answer before interruption.", midStreamErr!!.partialText)
    }
}

private class FakeUiTestAppContainer(
    private val initiallyAuthenticated: Boolean = true
) : com.example.di.AppContainer {
    val currentPrefs = MutableStateFlow(AppPreferences())
    private val testUser = UserProfile(
        id = "user-test-1",
        email = "tester@smarterai.app",
        displayName = "Alex Tester"
    )
    private val testSession = AuthSession(
        accessToken = "test-jwt",
        refreshToken = "test-refresh",
        expiresAtEpochMs = System.currentTimeMillis() + 3_600_000L,
        user = testUser
    )

    override val database: com.example.data.local.NexusAiLocalDatabase
        get() = throw UnsupportedOperationException("Not used in fake UI container")
    override val chatCacheDao: com.example.data.local.ChatCacheDao
        get() = throw UnsupportedOperationException("Not used in fake UI container")
    override val networkClient: SupabaseAndGatewayClient = SupabaseAndGatewayClient()
    override val liveDataService: LiveDataIntelligenceService = LiveDataIntelligenceService(
        httpExecutor = { request ->
            val bodyJson = """{"amount":1.0,"base":"USD","date":"2026-11-04","rates":{"INR":84.50,"EUR":0.92}}"""
            okhttp3.Response.Builder()
                .request(request)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .addHeader("Content-Type", "application/json")
                .body(bodyJson.toResponseBody("application/json".toMediaType()))
                .build()
        }
    )

    override val authRepository: AuthRepository = object : AuthRepository {
        override val sessionFlow = MutableStateFlow<AuthSession?>(if (initiallyAuthenticated) testSession else null)
        override val isConfiguredInBuild: Boolean = true
        override suspend fun restoreSession(): Result<AuthSession?> = Result.success(sessionFlow.value)
        override suspend fun signUp(email: String, password: String, displayName: String) = Result.success(testSession)
        override suspend fun initiateSignUpOtp(
            email: String,
            password: String,
            displayName: String
        ): Result<com.example.domain.models.OtpChallengeState> {
            val now = System.currentTimeMillis()
            return Result.success(
                com.example.domain.models.OtpChallengeState(
                    email = email,
                    displayName = displayName,
                    expiresAtEpochMs = now + 300_000L,
                    resendAvailableAtEpochMs = now + 45_000L,
                    remainingAttempts = 5,
                    maxAttempts = 5
                )
            )
        }
        override suspend fun verifySignUpOtp(email: String, otpCode: String): Result<AuthSession> {
            return if (otpCode.trim() == "482915") {
                sessionFlow.value = testSession
                Result.success(testSession)
            } else {
                Result.failure(IllegalArgumentException("Incorrect verification code. 4 attempts remaining."))
            }
        }
        override suspend fun resendSignUpOtp(email: String): Result<com.example.domain.models.OtpChallengeState> {
            val now = System.currentTimeMillis()
            return Result.success(
                com.example.domain.models.OtpChallengeState(
                    email = email,
                    displayName = "Elena Vance",
                    expiresAtEpochMs = now + 300_000L,
                    resendAvailableAtEpochMs = now + 45_000L,
                    remainingAttempts = 5,
                    maxAttempts = 5
                )
            )
        }
        override suspend fun signIn(email: String, password: String) = Result.success(testSession)
        override suspend fun sendPasswordResetEmail(email: String) = Result.success(Unit)
        override suspend fun refreshSessionIfNeeded() = Result.success(testSession)
        override suspend fun updateProfileName(displayName: String) = Result.success(testUser.copy(displayName = displayName))
        override suspend fun signOut(): Result<Unit> {
            sessionFlow.value = null
            return Result.success(Unit)
        }
        override suspend fun deleteAccount(): Result<Unit> {
            sessionFlow.value = null
            return Result.success(Unit)
        }
    }

    override val modelCatalogRepository: ModelCatalogRepository = object : ModelCatalogRepository {
        override val catalogFlow = MutableStateFlow(AiModelCatalog.INITIAL_DISCOVERY_MODELS)
        override val isRefreshingFlow = MutableStateFlow(false)
        override val lastVerifiedEpochMsFlow = MutableStateFlow<Long?>(System.currentTimeMillis())
        override suspend fun refreshModelCatalog() = Result.success(catalogFlow.value)
        override fun getModelById(providerModelId: String) = AiModelCatalog.findById(providerModelId, catalogFlow.value)
    }

    private val conversationsState = MutableStateFlow<List<Conversation>>(emptyList())
    private val messagesState = MutableStateFlow<List<ChatMessage>>(emptyList())

    override val chatRepository: ChatRepository = object : ChatRepository {
        override fun observeConversations(userId: String): Flow<List<Conversation>> = conversationsState
        override fun observeMessages(conversationId: String, userId: String): Flow<List<ChatMessage>> = messagesState
        override suspend fun syncConversationsFromCloud(userId: String, limit: Int, offset: Int) = Result.success(conversationsState.value)
        override suspend fun syncMessagesForConversation(conversationId: String, userId: String, limit: Int, offset: Int) = Result.success(messagesState.value)
        override suspend fun createConversation(userId: String, title: String, selectedModelId: String): Result<Conversation> {
            val conv = Conversation("conv-1", userId, title, selectedModelId, System.currentTimeMillis(), System.currentTimeMillis())
            conversationsState.value = listOf(conv)
            return Result.success(conv)
        }
        override suspend fun renameConversation(conversationId: String, userId: String, newTitle: String): Result<Conversation> {
            val updated = conversationsState.value.first().copy(title = newTitle)
            conversationsState.value = listOf(updated)
            return Result.success(updated)
        }
        override suspend fun updateConversationModel(conversationId: String, userId: String, newModelId: String): Result<Conversation> {
            val updated = conversationsState.value.first().copy(selectedModelId = newModelId)
            conversationsState.value = listOf(updated)
            return Result.success(updated)
        }
        override suspend fun deleteConversation(conversationId: String, userId: String): Result<Unit> {
            conversationsState.value = emptyList()
            messagesState.value = emptyList()
            return Result.success(Unit)
        }
        override suspend fun clearAllUserConversations(userId: String): Result<Unit> {
            conversationsState.value = emptyList()
            messagesState.value = emptyList()
            return Result.success(Unit)
        }
        override suspend fun searchConversations(userId: String, query: String, limit: Int, offset: Int): Result<List<ConversationSearchResult>> {
            return Result.success(
                conversationsState.value.map {
                    ConversationSearchResult(it, "Test reply in conversation history.", true)
                }
            )
        }
        override suspend fun saveUserMessage(
            conversationId: String,
            userId: String,
            content: String,
            modelId: String,
            attachments: List<MessageAttachment>,
            clientRequestId: String
        ): Result<ChatMessage> {
            val msg = ChatMessage(
                id = "msg-u${messagesState.value.count { it.role == MessageRole.USER } + 1}",
                conversationId = conversationId,
                userId = userId,
                role = MessageRole.USER,
                content = content,
                modelId = modelId,
                attachments = attachments,
                createdAtEpochMs = System.currentTimeMillis()
            )
            messagesState.value = messagesState.value + msg
            return Result.success(msg)
        }
        override suspend fun saveAssistantMessage(
            conversationId: String,
            userId: String,
            content: String,
            modelId: String,
            attachments: List<MessageAttachment>,
            clientRequestId: String
        ): Result<ChatMessage> {
            val msg = ChatMessage(
                id = "msg-a${messagesState.value.count { it.role == MessageRole.ASSISTANT } + 1}",
                conversationId = conversationId,
                userId = userId,
                role = MessageRole.ASSISTANT,
                content = content,
                modelId = modelId,
                status = MessageStatus.COMPLETED,
                attachments = attachments,
                clientRequestId = clientRequestId,
                createdAtEpochMs = System.currentTimeMillis()
            )
            messagesState.value = messagesState.value + msg
            return Result.success(msg)
        }
        override suspend fun generateChatImage(
            prompt: String,
            modelId: String,
            width: Int,
            height: Int,
            enhance: Boolean,
            onStageChanged: ((ImageGenerationStage) -> Unit)?
        ): Result<GeneratedImageResult> {
            onStageChanged?.invoke(ImageGenerationStage.PREPARING_PROMPT)
            onStageChanged?.invoke(ImageGenerationStage.CONNECTING_TO_MODEL)
            onStageChanged?.invoke(ImageGenerationStage.GENERATING_IMAGE)
            onStageChanged?.invoke(ImageGenerationStage.RECEIVING_IMAGE)
            onStageChanged?.invoke(ImageGenerationStage.PROCESSING_FINAL_IMAGE)
            onStageChanged?.invoke(ImageGenerationStage.COMPLETED)
            val validPngBytes = Base64.decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
                Base64.DEFAULT
            )
            return Result.success(
                GeneratedImageResult(
                    id = "gen-img-test-1",
                    prompt = prompt,
                    modelId = modelId,
                    width = width,
                    height = height,
                    seed = 42L,
                    imageBytes = validPngBytes,
                    mimeType = "image/png",
                    imageUrl = "https://image.pollinations.ai/prompt/futuristic%20city",
                    createdAtEpochMs = System.currentTimeMillis()
                )
            )
        }
        override suspend fun editUserMessageAndTruncateSubsequent(conversationId: String, userId: String, messageId: String, newContent: String): Result<ChatMessage> {
            val msg = messagesState.value.first().copy(content = newContent)
            messagesState.value = listOf(msg)
            return Result.success(msg)
        }
        override suspend fun deleteMessage(messageId: String, userId: String): Result<Unit> = Result.success(Unit)
        override fun streamAssistantResponse(
            conversationId: String,
            userId: String,
            requestedModelId: String,
            fallbackModelId: String?,
            allowFallback: Boolean,
            history: List<ChatMessage>,
            streamEnabled: Boolean,
            clientRequestId: String
        ): Flow<StreamChunk> = flow {
            val replyText = "Test reply in conversation history."
            emit(StreamChunk.Delta(replyText, requestedModelId, false))
            val assistantMsg = ChatMessage(
                id = "msg-a${messagesState.value.count { it.role == MessageRole.ASSISTANT } + 1}",
                conversationId = conversationId,
                userId = userId,
                role = MessageRole.ASSISTANT,
                content = replyText,
                modelId = requestedModelId,
                status = MessageStatus.COMPLETED,
                clientRequestId = clientRequestId,
                createdAtEpochMs = System.currentTimeMillis()
            )
            messagesState.value = messagesState.value + assistantMsg
            emit(StreamChunk.Completed(replyText, requestedModelId, false))
        }
    }

    override val preferencesRepository: PreferencesRepository = object : PreferencesRepository {
        override val preferencesFlow: Flow<AppPreferences> = currentPrefs
        override fun getInitialPreferences(): AppPreferences = currentPrefs.value
        override suspend fun setThemeMode(themeMode: ThemeMode) {
            currentPrefs.value = currentPrefs.value.copy(themeMode = themeMode)
        }
        override suspend fun setDefaultModelId(modelId: String) {
            currentPrefs.value = currentPrefs.value.copy(defaultModelId = modelId)
        }
        override suspend fun setFallbackModel(modelId: String?, allowFallback: Boolean) {
            currentPrefs.value = currentPrefs.value.copy(fallbackModelId = modelId, allowModelFallback = allowFallback)
        }
        override suspend fun setEnterToSend(enabled: Boolean) {
            currentPrefs.value = currentPrefs.value.copy(enterToSend = enabled)
        }
        override suspend fun setReducedAnimations(enabled: Boolean) {
            currentPrefs.value = currentPrefs.value.copy(reducedAnimations = enabled)
        }
        override suspend fun setStreamResponses(enabled: Boolean) {
            currentPrefs.value = currentPrefs.value.copy(streamResponses = enabled)
        }
        override suspend fun setRenderMarkdown(enabled: Boolean) {
            currentPrefs.value = currentPrefs.value.copy(renderMarkdown = enabled)
        }
    }
}
