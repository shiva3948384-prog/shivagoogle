package com.example.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.di.AppContainer
import com.example.domain.models.ThemeMode
import com.example.ui.audio.AudioStudioScreen
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.AuthViewModel
import com.example.ui.chat.ChatScreen
import com.example.ui.chat.ChatViewModel
import com.example.ui.imagegen.ImageGenerationScreen
import com.example.ui.search.SearchChatsScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.NexusAiTheme

const val ROUTE_AUTH = "auth"
const val ROUTE_CHAT = "chat"
const val ROUTE_SEARCH = "search"
const val ROUTE_IMAGE_STUDIO = "image_studio"
const val ROUTE_AUDIO_STUDIO = "audio_studio"
const val ROUTE_SETTINGS = "settings"

@Composable
fun NexusAiAppRoot(
    appContainer: AppContainer
) {
    val authViewModel: AuthViewModel = viewModel(
        factory = AuthViewModel.provideFactory(appContainer.authRepository)
    )
    val chatViewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.provideFactory(
            authRepository = appContainer.authRepository,
            chatRepository = appContainer.chatRepository,
            modelCatalogRepository = appContainer.modelCatalogRepository,
            preferencesRepository = appContainer.preferencesRepository,
            liveDataService = appContainer.liveDataService
        )
    )

    val authUiState by authViewModel.uiState.collectAsStateWithLifecycle()
    val session by authViewModel.sessionFlow.collectAsStateWithLifecycle()
    val chatUiState by chatViewModel.uiState.collectAsStateWithLifecycle()

    val isDarkTheme = when (chatUiState.preferences.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    NexusAiTheme(
        darkTheme = isDarkTheme,
        reducedAnimations = chatUiState.preferences.reducedAnimations
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            if (authUiState.isRestoringSession && session == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                return@Surface
            }

            val navController = rememberNavController()
            val startDestination = if (session != null) ROUTE_CHAT else ROUTE_AUTH

            LaunchedEffect(session) {
                if (session == null) {
                    navController.navigate(ROUTE_AUTH) {
                        popUpTo(0) { inclusive = true }
                    }
                } else {
                    navController.navigate(ROUTE_CHAT) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }

            val navAnimDuration = if (chatUiState.preferences.reducedAnimations) 0 else 220

            NavHost(
                navController = navController,
                startDestination = startDestination,
                enterTransition = {
                    fadeIn(animationSpec = tween(durationMillis = navAnimDuration, easing = FastOutSlowInEasing))
                },
                exitTransition = {
                    fadeOut(animationSpec = tween(durationMillis = navAnimDuration, easing = FastOutSlowInEasing))
                },
                popEnterTransition = {
                    fadeIn(animationSpec = tween(durationMillis = navAnimDuration, easing = FastOutSlowInEasing))
                },
                popExitTransition = {
                    fadeOut(animationSpec = tween(durationMillis = navAnimDuration, easing = FastOutSlowInEasing))
                }
            ) {
                composable(ROUTE_AUTH) {
                    AuthScreen(
                        uiState = authUiState,
                        onModeChanged = authViewModel::switchMode,
                        onEmailChanged = authViewModel::updateEmail,
                        onPasswordChanged = authViewModel::updatePassword,
                        onConfirmPasswordChanged = authViewModel::updateConfirmPassword,
                        onDisplayNameChanged = authViewModel::updateDisplayName,
                        onOtpCodeChanged = authViewModel::updateOtpCode,
                        onVerifyOtp = authViewModel::verifyOtpCode,
                        onResendOtp = authViewModel::resendOtpCode,
                        onSubmit = authViewModel::submitPrimaryAction
                    )
                }

                composable(ROUTE_CHAT) {
                    ChatScreen(
                        uiState = chatUiState,
                        onStartNewChat = chatViewModel::startNewChat,
                        onSelectConversation = chatViewModel::selectConversation,
                        onRenameConversation = chatViewModel::renameConversation,
                        onDeleteConversation = chatViewModel::deleteConversation,
                        onOpenSearchScreen = {
                            navController.navigate(ROUTE_SEARCH)
                        },
                        onOpenSettingsScreen = {
                            navController.navigate(ROUTE_SETTINGS)
                        },
                        onSignOut = authViewModel::signOut,
                        onOpenModelSelector = chatViewModel::openModelSelectorSheet,
                        onCloseModelSelector = chatViewModel::closeModelSelectorSheet,
                        onModelSearchQueryChanged = chatViewModel::updateModelSearchQuery,
                        onModelCategorySelected = chatViewModel::selectModelCategoryFilter,
                        onRefreshModelCatalog = chatViewModel::refreshModelCatalog,
                        onSelectModel = chatViewModel::selectModel,
                        onComposerTextChanged = chatViewModel::updateComposerText,
                        onApplyPromptSuggestion = chatViewModel::applyPromptSuggestion,
                        onAddAttachment = chatViewModel::addAttachment,
                        onRemoveAttachment = chatViewModel::removeAttachment,
                        onSendMessage = chatViewModel::sendMessage,
                        onStopGeneration = chatViewModel::stopGeneration,
                        onRegenerateResponse = chatViewModel::regenerateLastAssistantResponse,
                        onRetryFailedGeneration = chatViewModel::retryFailedGeneration,
                        onStartEditingMessage = chatViewModel::startEditingUserMessage,
                        onCancelEditingMessage = chatViewModel::cancelEditingUserMessage,
                        onDismissBanners = chatViewModel::dismissBanners,
                        onOpenImageStudio = {
                            navController.navigate(ROUTE_IMAGE_STUDIO)
                        },
                        onOpenAudioStudio = {
                            navController.navigate(ROUTE_AUDIO_STUDIO)
                        },
                        onRefreshLiveMarketData = chatViewModel::refreshLiveMarketDataForMessage,
                        onCancelImageGeneration = chatViewModel::cancelChatImageGeneration,
                        onRetryImageGeneration = chatViewModel::retryChatImageGeneration,
                        onDismissImageGenerationScreen = chatViewModel::dismissDedicatedImageGenerationScreen,
                        onOpenGeneratedImageFromChat = chatViewModel::openGeneratedImageFromChat
                    )
                }

                composable(ROUTE_SEARCH) {
                    SearchChatsScreen(
                        searchQuery = chatUiState.searchQuery,
                        isSearching = chatUiState.isSearching,
                        searchResults = chatUiState.searchResults,
                        onQueryChanged = chatViewModel::updateSearchQuery,
                        onSelectConversation = { convId ->
                            chatViewModel.selectConversation(convId)
                            navController.popBackStack()
                        },
                        onBack = {
                            navController.popBackStack()
                        }
                    )
                }

                composable(ROUTE_IMAGE_STUDIO) {
                    ImageGenerationScreen(
                        accessToken = session?.accessToken,
                        networkClient = appContainer.networkClient,
                        onBack = {
                            navController.popBackStack()
                        }
                    )
                }

                composable(ROUTE_AUDIO_STUDIO) {
                    AudioStudioScreen(
                        accessToken = session?.accessToken,
                        networkClient = appContainer.networkClient,
                        onBack = {
                            navController.popBackStack()
                        }
                    )
                }

                composable(ROUTE_SETTINGS) {
                    SettingsScreen(
                        userDisplayName = chatUiState.activeUserDisplayName,
                        userEmail = chatUiState.activeUserEmail,
                        isLiveCloudConfigured = chatUiState.isLiveCloudConfigured,
                        preferences = chatUiState.preferences,
                        modelCatalog = chatUiState.modelCatalog,
                        userMemories = chatUiState.userMemories,
                        activeProjects = chatUiState.activeProjects,
                        onUpdateDisplayName = chatViewModel::updateUserDisplayName,
                        onThemeModeChanged = chatViewModel::updateThemeMode,
                        onDefaultModelChanged = chatViewModel::updateDefaultModel,
                        onFallbackConfigChanged = chatViewModel::updateFallbackConfiguration,
                        onEnterToSendChanged = chatViewModel::updateEnterToSend,
                        onStreamResponsesChanged = chatViewModel::updateStreamResponses,
                        onRenderMarkdownChanged = chatViewModel::updateRenderMarkdown,
                        onReducedAnimationsChanged = chatViewModel::updateReducedAnimations,
                        onPersonalIntelligenceEnabledChanged = chatViewModel::updatePersonalIntelligenceEnabled,
                        onAddExplicitMemory = chatViewModel::addExplicitUserMemory,
                        onDeleteMemory = chatViewModel::deleteUserMemory,
                        onDeleteProjectContext = chatViewModel::deleteProjectContext,
                        onClearAllPersonalMemory = chatViewModel::clearAllPersonalIntelligenceMemory,
                        onClearAllHistory = chatViewModel::clearAllHistory,
                        onDeleteAccount = authViewModel::deleteAccount,
                        onSignOut = authViewModel::signOut,
                        onBack = {
                            navController.popBackStack()
                        }
                    )
                }
            }
        }
    }
}
