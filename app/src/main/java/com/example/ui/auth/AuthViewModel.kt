package com.example.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.domain.models.AuthSession
import com.example.domain.models.OtpChallengeState
import com.example.domain.repositories.AuthRepository
import com.example.domain.usecases.AuthValidationUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthMode {
    LOGIN,
    SIGN_UP,
    VERIFY_OTP,
    FORGOT_PASSWORD
}

data class AuthUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val displayName: String = "",
    val otpCode: String = "",
    val otpChallenge: OtpChallengeState? = null,
    val resendCountdownSeconds: Int = 0,
    val expiryCountdownSeconds: Int = 0,
    val isLoading: Boolean = false,
    val isRestoringSession: Boolean = true,
    val isLiveCloudConfigured: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null
)

class AuthViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AuthUiState(isLiveCloudConfigured = authRepository.isConfiguredInBuild)
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    val sessionFlow: StateFlow<AuthSession?> = authRepository.sessionFlow

    private var countdownJob: Job? = null

    init {
        restoreExistingSession()
    }

    private fun restoreExistingSession() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRestoringSession = true) }
            authRepository.restoreSession()
            _uiState.update {
                it.copy(
                    isRestoringSession = false,
                    isLiveCloudConfigured = authRepository.isConfiguredInBuild
                )
            }
        }
    }

    fun switchMode(newMode: AuthMode) {
        if (newMode != AuthMode.VERIFY_OTP) {
            countdownJob?.cancel()
        }
        _uiState.update {
            it.copy(
                mode = newMode,
                otpCode = if (newMode == AuthMode.VERIFY_OTP) it.otpCode else "",
                errorMessage = null,
                infoMessage = null
            )
        }
    }

    fun updateEmail(value: String) {
        _uiState.update { it.copy(email = value, errorMessage = null, infoMessage = null) }
    }

    fun updatePassword(value: String) {
        _uiState.update { it.copy(password = value, errorMessage = null, infoMessage = null) }
    }

    fun updateConfirmPassword(value: String) {
        _uiState.update { it.copy(confirmPassword = value, errorMessage = null, infoMessage = null) }
    }

    fun updateDisplayName(value: String) {
        _uiState.update { it.copy(displayName = value, errorMessage = null, infoMessage = null) }
    }

    fun updateOtpCode(value: String) {
        val digitsOnly = value.filter { it.isDigit() }.take(6)
        _uiState.update { it.copy(otpCode = digitsOnly, errorMessage = null) }
    }

    fun submitPrimaryAction() {
        val current = _uiState.value
        if (current.isLoading) return

        when (current.mode) {
            AuthMode.LOGIN -> performLogin(current)
            AuthMode.SIGN_UP -> performSignUpWithOtp(current)
            AuthMode.VERIFY_OTP -> verifyOtpCode()
            AuthMode.FORGOT_PASSWORD -> performPasswordReset(current)
        }
    }

    private fun performLogin(state: AuthUiState) {
        val emailValidation = AuthValidationUseCase.validateEmail(state.email)
        if (!emailValidation.isValid) {
            _uiState.update { it.copy(errorMessage = emailValidation.errorMessage) }
            return
        }
        val passwordValidation = AuthValidationUseCase.validatePasswordForLogin(state.password)
        if (!passwordValidation.isValid) {
            _uiState.update { it.copy(errorMessage = passwordValidation.errorMessage) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
            val result = authRepository.signIn(state.email.trim(), state.password)
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            password = "",
                            errorMessage = null
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = sanitizeUserError(
                                error.message,
                                "Unable to sign in. Check your credentials and try again."
                            )
                        )
                    }
                }
            )
        }
    }

    private fun performSignUpWithOtp(state: AuthUiState) {
        val validation = AuthValidationUseCase.validateSignUp(
            email = state.email,
            password = state.password,
            confirmPassword = state.confirmPassword,
            displayName = state.displayName
        )
        if (!validation.isValid) {
            _uiState.update { it.copy(errorMessage = validation.errorMessage) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
            val result = authRepository.initiateSignUpOtp(
                email = state.email.trim(),
                password = state.password,
                displayName = state.displayName.trim()
            )
            result.fold(
                onSuccess = { challenge ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            mode = AuthMode.VERIFY_OTP,
                            otpCode = "",
                            otpChallenge = challenge,
                            errorMessage = null,
                            infoMessage = challenge.notice
                                ?: "We sent a 6-digit verification code to ${challenge.email}."
                        )
                    }
                    startOtpCountdowns(challenge)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = sanitizeUserError(
                                error.message,
                                "We couldn't send your verification code. Please try again shortly."
                            )
                        )
                    }
                }
            )
        }
    }

    fun verifyOtpCode() {
        val state = _uiState.value
        if (state.isLoading) return
        val cleanCode = state.otpCode.trim()
        if (cleanCode.length != 6 || !cleanCode.all { it.isDigit() }) {
            _uiState.update {
                it.copy(errorMessage = "Please enter the 6-digit numeric verification code.")
            }
            return
        }

        val targetEmail = state.otpChallenge?.email?.ifBlank { state.email.trim() } ?: state.email.trim()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
            val result = authRepository.verifySignUpOtp(
                email = targetEmail,
                otpCode = cleanCode
            )
            result.fold(
                onSuccess = {
                    countdownJob?.cancel()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            password = "",
                            confirmPassword = "",
                            otpCode = "",
                            errorMessage = null,
                            infoMessage = "Email verified. Welcome to Smarter AI!"
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = sanitizeUserError(
                                error.message,
                                "Invalid verification code. Please check the code and try again."
                            )
                        )
                    }
                }
            )
        }
    }

    fun resendOtpCode() {
        val state = _uiState.value
        if (state.isLoading) return
        if (state.resendCountdownSeconds > 0) {
            _uiState.update {
                it.copy(
                    errorMessage = "Please wait ${state.resendCountdownSeconds} seconds before requesting a new code."
                )
            }
            return
        }

        val targetEmail = state.otpChallenge?.email?.ifBlank { state.email.trim() } ?: state.email.trim()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
            val result = authRepository.resendSignUpOtp(targetEmail)
            result.fold(
                onSuccess = { challenge ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            otpCode = "",
                            otpChallenge = challenge,
                            errorMessage = null,
                            infoMessage = challenge.notice
                                ?: "A new 6-digit verification code has been sent to ${challenge.email}."
                        )
                    }
                    startOtpCountdowns(challenge)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = sanitizeUserError(
                                error.message,
                                "We couldn't send a new code right now. Please try again shortly."
                            )
                        )
                    }
                }
            )
        }
    }

    private fun startOtpCountdowns(challenge: OtpChallengeState) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val resendRem = ((challenge.resendAvailableAtEpochMs - now + 999L) / 1000L)
                    .toInt()
                    .coerceAtLeast(0)
                val expiryRem = ((challenge.expiresAtEpochMs - now + 999L) / 1000L)
                    .toInt()
                    .coerceAtLeast(0)

                _uiState.update {
                    it.copy(
                        resendCountdownSeconds = resendRem,
                        expiryCountdownSeconds = expiryRem
                    )
                }

                if (resendRem == 0 && expiryRem == 0) {
                    break
                }
                delay(1000L)
            }
        }
    }

    private fun performPasswordReset(state: AuthUiState) {
        val emailValidation = AuthValidationUseCase.validateEmail(state.email)
        if (!emailValidation.isValid) {
            _uiState.update { it.copy(errorMessage = emailValidation.errorMessage) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, infoMessage = null) }
            val result = authRepository.sendPasswordResetEmail(state.email.trim())
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            infoMessage = "Password recovery instructions have been sent to ${state.email.trim()}."
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = sanitizeUserError(
                                error.message,
                                "Unable to send password recovery instructions. Please try again."
                            )
                        )
                    }
                }
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            _uiState.update {
                AuthUiState(
                    mode = AuthMode.LOGIN,
                    isRestoringSession = false,
                    isLiveCloudConfigured = authRepository.isConfiguredInBuild
                )
            }
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            authRepository.deleteAccount()
            _uiState.update {
                AuthUiState(
                    mode = AuthMode.LOGIN,
                    isRestoringSession = false,
                    isLiveCloudConfigured = authRepository.isConfiguredInBuild
                )
            }
        }
    }

    private fun sanitizeUserError(rawMessage: String?, fallback: String): String {
        if (rawMessage.isNullOrBlank()) return fallback
        val lower = rawMessage.lowercase()
        if (lower.contains("supabase") ||
            lower.contains("pollinations") ||
            lower.contains("brevo")
        ) {
            return fallback
        }
        return rawMessage
    }

    companion object {
        fun provideFactory(authRepository: AuthRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AuthViewModel(authRepository) as T
                }
            }
    }
}
