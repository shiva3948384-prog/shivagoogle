package com.example.data.auth

import android.content.Context
import android.content.SharedPreferences
import com.example.data.local.ChatCacheDao
import com.example.data.network.SupabaseAndGatewayClient
import com.example.domain.models.AuthSession
import com.example.domain.models.OtpChallengeState
import com.example.domain.models.UserProfile
import com.example.domain.repositories.AuthRepository
import com.example.domain.usecases.AuthValidationUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SupabaseAuthRepository(
    private val context: Context,
    private val networkClient: SupabaseAndGatewayClient,
    private val chatCacheDao: ChatCacheDao,
    var currentTimeProvider: () -> Long = { System.currentTimeMillis() },
    private val allowLocalFallbackWhenUnconfigured: Boolean = !networkClient.isLiveCloudConfigured
) : AuthRepository {

    companion object {
        private const val PREFS_NAME = "nexus_auth_session_store"
        private const val KEY_ACTIVE_SESSION = "active_session_v1"
        private const val KEY_STAGED_ACCOUNTS = "staged_accounts_v1"
        private const val KEY_OTP_RECORDS = "otp_verification_records_v1"

        const val OTP_EXPIRY_MS = 5 * 60 * 1000L // 5 minutes
        const val OTP_RESEND_COOLDOWN_MS = 45 * 1000L // 45 seconds (within 30-60s requirement)
        const val OTP_MAX_ATTEMPTS = 5
        private const val RATE_LIMIT_WINDOW_MS = 15 * 60 * 1000L // 15 minutes
        private const val MAX_OTP_SENDS_PER_WINDOW = 6
        private const val MAX_OTP_VERIFIES_PER_WINDOW = 15
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val secureRandom = SecureRandom()

    private val _sessionFlow = MutableStateFlow<AuthSession?>(null)
    override val sessionFlow: StateFlow<AuthSession?> = _sessionFlow.asStateFlow()

    override val isConfiguredInBuild: Boolean
        get() = networkClient.isLiveCloudConfigured

    private data class RateWindow(var count: Int, var windowStartMs: Long)
    private val rateLimits = ConcurrentHashMap<String, RateWindow>()

    // Transient in-memory pending passwords for establishing session after OTP verification
    private val transientPendingPasswords = ConcurrentHashMap<String, String>()

    // Volatile in-memory last generated OTP code (never persisted to SharedPreferences)
    private val transientIssuedOtpCodes = ConcurrentHashMap<String, String>()

    init {
        loadPersistedSessionSynchronously()
    }

    /**
     * Returns the most recently generated 6-digit numeric OTP from volatile memory (never stored on disk).
     */
    fun getLastIssuedOtpForVerification(email: String): String? {
        return transientIssuedOtpCodes[email.trim().lowercase()]
    }

    private fun loadPersistedSessionSynchronously() {
        val raw = prefs.getString(KEY_ACTIVE_SESSION, null) ?: return
        runCatching {
            val session = deserializeSession(JSONObject(raw))
            _sessionFlow.value = session
        }
    }

    override suspend fun restoreSession(): Result<AuthSession?> = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_ACTIVE_SESSION, null)
        if (raw.isNullOrBlank()) {
            _sessionFlow.value = null
            return@withContext Result.success(null)
        }
        return@withContext try {
            val saved = deserializeSession(JSONObject(raw))
            chatCacheDao.markInFlightMessagesInterrupted(saved.user.id)

            if (isConfiguredInBuild &&
                saved.hasValidCloudJwt &&
                saved.refreshToken.isNotBlank() &&
                currentTimeProvider() >= saved.expiresAtEpochMs - 60_000L
            ) {
                val refreshed = runCatching {
                    networkClient.refreshSession(saved.refreshToken)
                }.getOrNull()
                if (refreshed != null) {
                    persistSession(refreshed)
                    _sessionFlow.value = refreshed
                    return@withContext Result.success(refreshed)
                }
            }

            _sessionFlow.value = saved
            Result.success(saved)
        } catch (e: Exception) {
            _sessionFlow.value = null
            Result.failure(e)
        }
    }

    // ========================================================================
    // 11-Step Signup + Brevo 6-Digit Email OTP Verification Flow
    // ========================================================================

    override suspend fun initiateSignUpOtp(
        email: String,
        password: String,
        displayName: String
    ): Result<OtpChallengeState> = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase()
        val cleanName = displayName.trim().ifBlank {
            cleanEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
        }

        val validation = AuthValidationUseCase.validateSignUp(
            email = cleanEmail,
            password = password,
            confirmPassword = password,
            displayName = cleanName
        )
        if (!validation.isValid) {
            return@withContext Result.failure(
                IllegalArgumentException(validation.errorMessage ?: "Invalid signup details.")
            )
        }

        val now = currentTimeProvider()

        // 1. Rate-limit signup / OTP send requests
        if (!checkRateLimit("send:$cleanEmail", MAX_OTP_SENDS_PER_WINDOW, now)) {
            return@withContext Result.failure(
                IllegalStateException("Too many verification codes requested. Please wait a few minutes and try again.")
            )
        }

        // 2. Prevent duplicate verified accounts
        val accountsObj = loadAccountsJson()
        val existingAccount = accountsObj.optJSONObject(cleanEmail)
        if (existingAccount != null && existingAccount.optBoolean("isVerified", true)) {
            return@withContext Result.failure(
                IllegalArgumentException("An account with this email already exists. Please sign in instead.")
            )
        }

        // 3. Enforce 45-second resend cooldown if an active OTP already exists
        val otpRecordsObj = loadOtpRecordsJson()
        val existingOtp = otpRecordsObj.optJSONObject(cleanEmail)
        if (existingOtp != null &&
            !existingOtp.optBoolean("invalidated", false) &&
            !existingOtp.optBoolean("used", false)
        ) {
            val resendAvailableAt = existingOtp.optLong("resendAvailableAtEpochMs", 0L)
            if (now < resendAvailableAt) {
                val waitSec = ((resendAvailableAt - now + 999L) / 1000L).coerceAtLeast(1L)
                return@withContext Result.failure(
                    IllegalStateException("Please wait $waitSec seconds before requesting a new code.")
                )
            }
        }

        // 4. Invalidate any previous OTP for this email
        invalidateOtpRecord(cleanEmail)
        transientPendingPasswords[cleanEmail] = password

        // 5. If cloud backend is configured, request Brevo 6-digit OTP delivery from Edge Function
        if (isConfiguredInBuild) {
            val remoteSend = runCatching {
                networkClient.sendSignupOtp(
                    email = cleanEmail,
                    password = password,
                    displayName = cleanName,
                    isResend = false
                )
            }
            if (remoteSend.isSuccess) {
                val dto = remoteSend.getOrThrow()
                val expiresAt = now + (dto.expiresInSeconds * 1000L)
                val resendAt = now + (dto.resendCooldownSeconds * 1000L)
                val userId = dto.userId ?: UUID.nameUUIDFromBytes(cleanEmail.toByteArray()).toString()
                val salt = generateHexSalt()
                val pwHash = hashWithSalt(password, salt)

                saveOtpRecordJson(
                    cleanEmail,
                    JSONObject().apply {
                        put("id", UUID.randomUUID().toString())
                        put("email", cleanEmail)
                        put("displayName", cleanName)
                        put("userId", userId)
                        put("passwordSalt", salt)
                        put("passwordHash", pwHash)
                        put("otpSalt", generateHexSalt())
                        put("otpHash", "")
                        put("attempts", 0)
                        put("maxAttempts", dto.maxAttempts)
                        put("expiresAtEpochMs", expiresAt)
                        put("resendAvailableAtEpochMs", resendAt)
                        put("used", false)
                        put("invalidated", false)
                        put("isRemoteEdgeOtp", true)
                        put("createdAtEpochMs", now)
                    }
                )

                return@withContext Result.success(
                    OtpChallengeState(
                        email = cleanEmail,
                        displayName = cleanName,
                        userId = userId,
                        expiresAtEpochMs = expiresAt,
                        resendAvailableAtEpochMs = resendAt,
                        maxAttempts = dto.maxAttempts,
                        remainingAttempts = dto.maxAttempts,
                        notice = dto.message ?: "We sent a 6-digit verification code to $cleanEmail."
                    )
                )
            } else {
                val err = remoteSend.exceptionOrNull()
                val errMsg = err?.message.orEmpty()
                if (errMsg.contains("already exists", ignoreCase = true) ||
                    errMsg.contains("already registered", ignoreCase = true)
                ) {
                    return@withContext Result.failure(
                        IllegalArgumentException("An account with this email already exists. Please sign in instead.")
                    )
                }
                return@withContext Result.failure(
                    IllegalStateException(
                        errMsg.ifBlank {
                            "Email verification service is not configured or unavailable on the server. Please complete the Brevo & Supabase OTP Edge Function setup."
                        }
                    )
                )
            }
        }

        if (!allowLocalFallbackWhenUnconfigured) {
            return@withContext Result.failure(
                IllegalStateException(
                    "Email verification backend is not configured. Please configure the Supabase and Brevo OTP server credentials."
                )
            )
        }

        // 6. Generate cryptographically secure 6-digit numeric OTP and store salted SHA-256 hash
        val otpCode = generateSixDigitNumericOtp()
        val otpSalt = generateHexSalt()
        val otpHash = computeSaltedOtpHash(otpSalt, cleanEmail, otpCode)
        val pwSalt = generateHexSalt()
        val pwHash = hashWithSalt(password, pwSalt)
        val userId = UUID.nameUUIDFromBytes(cleanEmail.toByteArray()).toString()
        val expiresAt = now + OTP_EXPIRY_MS
        val resendAt = now + OTP_RESEND_COOLDOWN_MS

        saveOtpRecordJson(
            cleanEmail,
            JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("email", cleanEmail)
                put("displayName", cleanName)
                put("userId", userId)
                put("passwordSalt", pwSalt)
                put("passwordHash", pwHash)
                put("otpSalt", otpSalt)
                put("otpHash", otpHash)
                put("attempts", 0)
                put("maxAttempts", OTP_MAX_ATTEMPTS)
                put("expiresAtEpochMs", expiresAt)
                put("resendAvailableAtEpochMs", resendAt)
                put("used", false)
                put("invalidated", false)
                put("isRemoteEdgeOtp", false)
                put("createdAtEpochMs", now)
            }
        )
        transientIssuedOtpCodes[cleanEmail] = otpCode

        Result.success(
            OtpChallengeState(
                email = cleanEmail,
                displayName = cleanName,
                userId = userId,
                expiresAtEpochMs = expiresAt,
                resendAvailableAtEpochMs = resendAt,
                maxAttempts = OTP_MAX_ATTEMPTS,
                remainingAttempts = OTP_MAX_ATTEMPTS,
                notice = "We sent a 6-digit verification code to $cleanEmail."
            )
        )
    }

    override suspend fun verifySignUpOtp(
        email: String,
        otpCode: String
    ): Result<AuthSession> = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase()
        val cleanCode = otpCode.trim()

        if (!cleanCode.matches(Regex("^\\d{6}$"))) {
            return@withContext Result.failure(
                IllegalArgumentException("Please enter a valid 6-digit numeric verification code.")
            )
        }

        val now = currentTimeProvider()
        if (!checkRateLimit("verify:$cleanEmail", MAX_OTP_VERIFIES_PER_WINDOW, now)) {
            return@withContext Result.failure(
                IllegalStateException("Too many verification attempts. Please wait a few minutes and try again.")
            )
        }

        val otpRecordsObj = loadOtpRecordsJson()
        val record = otpRecordsObj.optJSONObject(cleanEmail)
            ?: return@withContext Result.failure(
                IllegalArgumentException("This code is no longer valid. Please request a new code.")
            )

        if (record.optBoolean("used", false) || record.optBoolean("invalidated", false)) {
            return@withContext Result.failure(
                IllegalArgumentException("This code has already been used or invalidated. Request a new one.")
            )
        }

        val expiresAt = record.optLong("expiresAtEpochMs", 0L)
        if (now > expiresAt) {
            record.put("invalidated", true)
            saveOtpRecordJson(cleanEmail, record)
            return@withContext Result.failure(
                IllegalArgumentException("This code has expired. Request a new one.")
            )
        }

        val attempts = record.optInt("attempts", 0)
        val maxAttempts = record.optInt("maxAttempts", OTP_MAX_ATTEMPTS)
        if (attempts >= maxAttempts) {
            record.put("invalidated", true)
            saveOtpRecordJson(cleanEmail, record)
            return@withContext Result.failure(
                IllegalStateException("Maximum verification attempts reached. Please request a new code.")
            )
        }

        val displayName = record.optString("displayName", cleanEmail.substringBefore("@"))
        val userId = record.optString("userId", UUID.nameUUIDFromBytes(cleanEmail.toByteArray()).toString())
        val pwSalt = record.optString("passwordSalt")
        val pwHash = record.optString("passwordHash")
        val pendingPassword = transientPendingPasswords[cleanEmail].orEmpty()

        // If issued by the remote Edge Function, verify on the backend first
        if (record.optBoolean("isRemoteEdgeOtp", false) && isConfiguredInBuild) {
            val nextAttempts = attempts + 1
            record.put("attempts", nextAttempts)
            saveOtpRecordJson(cleanEmail, record)

            val remoteVerify = runCatching {
                networkClient.verifySignupOtp(
                    email = cleanEmail,
                    otpCode = cleanCode,
                    password = pendingPassword
                )
            }
            if (remoteVerify.isFailure) {
                val err = remoteVerify.exceptionOrNull()
                val errMsg = err?.message.orEmpty()
                if (nextAttempts >= maxAttempts ||
                    errMsg.contains("Maximum verification attempts", ignoreCase = true) ||
                    errMsg.contains("expired", ignoreCase = true)
                ) {
                    record.put("invalidated", true)
                    saveOtpRecordJson(cleanEmail, record)
                }
                return@withContext Result.failure(
                    IllegalArgumentException(
                        errMsg.ifBlank { "Invalid verification code. Please check the code and try again." }
                    )
                )
            }

            val verifyDto = remoteVerify.getOrThrow()
            record.put("used", true)
            record.put("invalidated", true)
            saveOtpRecordJson(cleanEmail, record)
            transientPendingPasswords.remove(cleanEmail)
            transientIssuedOtpCodes.remove(cleanEmail)

            val session = verifyDto.session ?: buildVerifiedLocalSession(
                userId = verifyDto.userId ?: userId,
                email = cleanEmail,
                displayName = displayName
            )
            saveVerifiedStagedAccount(
                email = cleanEmail,
                userId = session.user.id,
                displayName = session.user.displayName,
                passwordSalt = pwSalt,
                passwordHash = pwHash
            )
            persistSession(session)
            _sessionFlow.value = session
            return@withContext Result.success(session)
        }

        // Verify against salted SHA-256 hash in constant time
        val nextAttempts = attempts + 1
        record.put("attempts", nextAttempts)
        val otpSalt = record.optString("otpSalt")
        val expectedHash = record.optString("otpHash")
        val candidateHash = computeSaltedOtpHash(otpSalt, cleanEmail, cleanCode)

        val isMatch = constantTimeHashEquals(candidateHash, expectedHash)
        if (!isMatch) {
            val remaining = (maxAttempts - nextAttempts).coerceAtLeast(0)
            if (remaining == 0) {
                record.put("invalidated", true)
                saveOtpRecordJson(cleanEmail, record)
                return@withContext Result.failure(
                    IllegalStateException("Maximum verification attempts reached. Please request a new code.")
                )
            } else {
                saveOtpRecordJson(cleanEmail, record)
                return@withContext Result.failure(
                    IllegalArgumentException(
                        "Incorrect verification code. $remaining ${if (remaining == 1) "attempt" else "attempts"} remaining."
                    )
                )
            }
        }

        // Mark single-use OTP consumed & invalidated
        record.put("used", true)
        record.put("invalidated", true)
        saveOtpRecordJson(cleanEmail, record)
        transientIssuedOtpCodes.remove(cleanEmail)

        var cloudSession: AuthSession? = null
        if (isConfiguredInBuild && pendingPassword.isNotBlank()) {
            cloudSession = runCatching {
                networkClient.signUpWithEmail(cleanEmail, pendingPassword, displayName)
            }.getOrNull() ?: runCatching {
                networkClient.signInWithEmail(cleanEmail, pendingPassword)
            }.getOrNull()
        }
        transientPendingPasswords.remove(cleanEmail)

        val finalSession = cloudSession ?: buildVerifiedLocalSession(
            userId = userId,
            email = cleanEmail,
            displayName = displayName
        )

        saveVerifiedStagedAccount(
            email = cleanEmail,
            userId = finalSession.user.id,
            displayName = finalSession.user.displayName,
            passwordSalt = pwSalt,
            passwordHash = pwHash
        )
        persistSession(finalSession)
        _sessionFlow.value = finalSession
        Result.success(finalSession)
    }

    override suspend fun resendSignUpOtp(
        email: String
    ): Result<OtpChallengeState> = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase()
        val now = currentTimeProvider()

        if (!checkRateLimit("send:$cleanEmail", MAX_OTP_SENDS_PER_WINDOW, now)) {
            return@withContext Result.failure(
                IllegalStateException("Too many verification codes requested. Please wait a few minutes and try again.")
            )
        }

        val otpRecordsObj = loadOtpRecordsJson()
        val prevRecord = otpRecordsObj.optJSONObject(cleanEmail)
            ?: return@withContext Result.failure(
                IllegalStateException("Please start signup again to request a verification code.")
            )

        val resendAvailableAt = prevRecord.optLong("resendAvailableAtEpochMs", 0L)
        if (now < resendAvailableAt) {
            val waitSec = ((resendAvailableAt - now + 999L) / 1000L).coerceAtLeast(1L)
            return@withContext Result.failure(
                IllegalStateException("Please wait $waitSec seconds before requesting a new code.")
            )
        }

        val displayName = prevRecord.optString("displayName", cleanEmail.substringBefore("@"))
        val userId = prevRecord.optString("userId", UUID.nameUUIDFromBytes(cleanEmail.toByteArray()).toString())
        val pwSalt = prevRecord.optString("passwordSalt")
        val pwHash = prevRecord.optString("passwordHash")
        val wasRemote = prevRecord.optBoolean("isRemoteEdgeOtp", false)
        val pendingPassword = transientPendingPasswords[cleanEmail].orEmpty()

        // Invalidate previous OTP before issuing a new one
        invalidateOtpRecord(cleanEmail)

        if (wasRemote || isConfiguredInBuild) {
            val remoteResend = runCatching {
                networkClient.sendSignupOtp(
                    email = cleanEmail,
                    password = pendingPassword,
                    displayName = displayName,
                    isResend = true
                )
            }
            if (remoteResend.isSuccess) {
                val dto = remoteResend.getOrThrow()
                val expiresAt = now + (dto.expiresInSeconds * 1000L)
                val resendAt = now + (dto.resendCooldownSeconds * 1000L)
                saveOtpRecordJson(
                    cleanEmail,
                    JSONObject().apply {
                        put("id", UUID.randomUUID().toString())
                        put("email", cleanEmail)
                        put("displayName", displayName)
                        put("userId", userId)
                        put("passwordSalt", pwSalt)
                        put("passwordHash", pwHash)
                        put("otpSalt", generateHexSalt())
                        put("otpHash", "")
                        put("attempts", 0)
                        put("maxAttempts", dto.maxAttempts)
                        put("expiresAtEpochMs", expiresAt)
                        put("resendAvailableAtEpochMs", resendAt)
                        put("used", false)
                        put("invalidated", false)
                        put("isRemoteEdgeOtp", true)
                        put("createdAtEpochMs", now)
                    }
                )
                return@withContext Result.success(
                    OtpChallengeState(
                        email = cleanEmail,
                        displayName = displayName,
                        userId = userId,
                        expiresAtEpochMs = expiresAt,
                        resendAvailableAtEpochMs = resendAt,
                        maxAttempts = dto.maxAttempts,
                        remainingAttempts = dto.maxAttempts,
                        notice = "A new 6-digit verification code has been sent to $cleanEmail."
                    )
                )
            } else {
                val err = remoteResend.exceptionOrNull()
                return@withContext Result.failure(
                    IllegalStateException(
                        err?.message ?: "We couldn't send a new verification code right now. Please try again shortly."
                    )
                )
            }
        }

        if (!allowLocalFallbackWhenUnconfigured) {
            return@withContext Result.failure(
                IllegalStateException(
                    "Email verification backend is not configured. Please configure the Supabase and Brevo OTP server credentials."
                )
            )
        }

        val newOtpCode = generateSixDigitNumericOtp()
        val newOtpSalt = generateHexSalt()
        val newOtpHash = computeSaltedOtpHash(newOtpSalt, cleanEmail, newOtpCode)
        val expiresAt = now + OTP_EXPIRY_MS
        val resendAt = now + OTP_RESEND_COOLDOWN_MS

        saveOtpRecordJson(
            cleanEmail,
            JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("email", cleanEmail)
                put("displayName", displayName)
                put("userId", userId)
                put("passwordSalt", pwSalt)
                put("passwordHash", pwHash)
                put("otpSalt", newOtpSalt)
                put("otpHash", newOtpHash)
                put("attempts", 0)
                put("maxAttempts", OTP_MAX_ATTEMPTS)
                put("expiresAtEpochMs", expiresAt)
                put("resendAvailableAtEpochMs", resendAt)
                put("used", false)
                put("invalidated", false)
                put("isRemoteEdgeOtp", false)
                put("createdAtEpochMs", now)
            }
        )
        transientIssuedOtpCodes[cleanEmail] = newOtpCode

        Result.success(
            OtpChallengeState(
                email = cleanEmail,
                displayName = displayName,
                userId = userId,
                expiresAtEpochMs = expiresAt,
                resendAvailableAtEpochMs = resendAt,
                maxAttempts = OTP_MAX_ATTEMPTS,
                remainingAttempts = OTP_MAX_ATTEMPTS,
                notice = "A new 6-digit verification code has been sent to $cleanEmail."
            )
        )
    }

    // ========================================================================
    // Direct Signup & Sign-In Methods
    // ========================================================================

    override suspend fun signUp(
        email: String,
        password: String,
        displayName: String
    ): Result<AuthSession> = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase()
        val cleanName = displayName.trim().ifBlank { cleanEmail.substringBefore("@") }

        val check = AuthValidationUseCase.validateSignUp(cleanEmail, password, password, cleanName)
        if (!check.isValid) {
            return@withContext Result.failure(IllegalArgumentException(check.errorMessage ?: "Invalid signup details."))
        }

        if (isConfiguredInBuild) {
            try {
                val cloudSession = networkClient.signUpWithEmail(cleanEmail, password, cleanName)
                saveLocalAccountWithPassword(cleanEmail, password, cloudSession.user.id, cloudSession.user.displayName)
                persistSession(cloudSession)
                _sessionFlow.value = cloudSession
                return@withContext Result.success(cloudSession)
            } catch (e: Exception) {
                val msg = e.message.orEmpty().lowercase()
                if (msg.contains("already registered") || msg.contains("already exists")) {
                    return@withContext Result.failure(
                        IllegalArgumentException("An account with this email already exists. Please sign in instead.")
                    )
                }
            }
        }

        // Local staged account creation
        val accountsObj = loadAccountsJson()
        if (accountsObj.has(cleanEmail)) {
            return@withContext Result.failure(
                IllegalArgumentException("An account with this email already exists. Please sign in instead.")
            )
        }

        val userId = UUID.nameUUIDFromBytes(cleanEmail.toByteArray()).toString()
        saveLocalAccountWithPassword(cleanEmail, password, userId, cleanName)

        val session = buildVerifiedLocalSession(
            userId = userId,
            email = cleanEmail,
            displayName = cleanName
        )
        persistSession(session)
        _sessionFlow.value = session
        Result.success(session)
    }

    override suspend fun signIn(
        email: String,
        password: String
    ): Result<AuthSession> = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim().lowercase()
        val emailCheck = AuthValidationUseCase.validateEmail(cleanEmail)
        if (!emailCheck.isValid) {
            return@withContext Result.failure(IllegalArgumentException(emailCheck.errorMessage))
        }
        val passCheck = AuthValidationUseCase.validatePasswordForLogin(password)
        if (!passCheck.isValid) {
            return@withContext Result.failure(IllegalArgumentException(passCheck.errorMessage))
        }

        // Prevent bypassing a pending unverified 6-digit OTP signup challenge
        val accountsObj = loadAccountsJson()
        val existingAccount = accountsObj.optJSONObject(cleanEmail)
        val pendingOtp = loadOtpRecordsJson().optJSONObject(cleanEmail)
        if (pendingOtp != null &&
            !pendingOtp.optBoolean("used", false) &&
            (existingAccount == null || !existingAccount.optBoolean("isVerified", false))
        ) {
            return@withContext Result.failure(
                IllegalStateException("Please verify your email with the 6-digit verification code sent to $cleanEmail before signing in.")
            )
        }

        if (isConfiguredInBuild) {
            try {
                val cloudSession = networkClient.signInWithEmail(cleanEmail, password)
                if (!cloudSession.user.isEmailVerified) {
                    return@withContext Result.failure(
                        IllegalStateException("Please verify your email address with your 6-digit code before signing in.")
                    )
                }
                saveLocalAccountWithPassword(cleanEmail, password, cloudSession.user.id, cloudSession.user.displayName)
                persistSession(cloudSession)
                _sessionFlow.value = cloudSession
                return@withContext Result.success(cloudSession)
            } catch (e: Exception) {
                val errMsg = e.message.orEmpty()
                if (errMsg.contains("verify your email", ignoreCase = true) ||
                    errMsg.contains("not confirmed", ignoreCase = true)
                ) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            errMsg.ifBlank {
                                "Please verify your email address with your 6-digit code before signing in."
                            }
                        )
                    )
                }
                // Fall back to checking local verified staged account if network is unreachable
                val localCheck = verifyStagedAccount(cleanEmail, password)
                if (localCheck != null) {
                    persistSession(localCheck)
                    _sessionFlow.value = localCheck
                    return@withContext Result.success(localCheck)
                }
                return@withContext Result.failure(
                    IllegalArgumentException(
                        errMsg.ifBlank { "Unable to sign in. Check your credentials and try again." }
                    )
                )
            }
        }

        val localSession = verifyStagedAccount(cleanEmail, password)
            ?: return@withContext Result.failure(
                IllegalArgumentException("Unable to sign in. Check your credentials and try again.")
            )
        persistSession(localSession)
        _sessionFlow.value = localSession
        Result.success(localSession)
    }

    override suspend fun sendPasswordResetEmail(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        val check = AuthValidationUseCase.validateEmail(email)
        if (!check.isValid) {
            return@withContext Result.failure(IllegalArgumentException(check.errorMessage))
        }
        if (isConfiguredInBuild) {
            runCatching { networkClient.sendPasswordResetEmail(email.trim()) }
        }
        Result.success(Unit)
    }

    override suspend fun refreshSessionIfNeeded(): Result<AuthSession?> = withContext(Dispatchers.IO) {
        val current = _sessionFlow.value ?: return@withContext Result.success(null)
        if (isConfiguredInBuild &&
            current.hasValidCloudJwt &&
            current.refreshToken.isNotBlank() &&
            currentTimeProvider() >= current.expiresAtEpochMs - 60_000L
        ) {
            val refreshed = runCatching {
                networkClient.refreshSession(current.refreshToken)
            }.getOrNull()
            if (refreshed != null) {
                persistSession(refreshed)
                _sessionFlow.value = refreshed
                return@withContext Result.success(refreshed)
            }
        }
        Result.success(current)
    }

    override suspend fun updateProfileName(displayName: String): Result<UserProfile> =
        withContext(Dispatchers.IO) {
            val current = _sessionFlow.value
                ?: return@withContext Result.failure(IllegalStateException("Not signed in."))
            val clean = displayName.trim()
            if (clean.length < 2) {
                return@withContext Result.failure(IllegalArgumentException("Display name must be at least 2 characters."))
            }
            if (isConfiguredInBuild && current.hasValidCloudJwt) {
                runCatching {
                    networkClient.updateUserProfile(current.accessToken, current.user.id, clean)
                }
            }
            val updatedUser = current.user.copy(displayName = clean)
            val updatedSession = current.copy(user = updatedUser)
            persistSession(updatedSession)
            _sessionFlow.value = updatedSession
            Result.success(updatedUser)
        }

    override suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_ACTIVE_SESSION).apply()
        _sessionFlow.value = null
        Result.success(Unit)
    }

    override suspend fun deleteAccount(): Result<Unit> = withContext(Dispatchers.IO) {
        val current = _sessionFlow.value
        if (current != null) {
            chatCacheDao.deleteAllMessagesForUser(current.user.id)
            chatCacheDao.deleteAllConversationsForUser(current.user.id)
            val accountsObj = loadAccountsJson()
            accountsObj.remove(current.user.email.lowercase())
            prefs.edit().putString(KEY_STAGED_ACCOUNTS, accountsObj.toString()).apply()
        }
        prefs.edit().remove(KEY_ACTIVE_SESSION).apply()
        _sessionFlow.value = null
        Result.success(Unit)
    }

    // ========================================================================
    // Cryptographic & Persistence Helpers
    // ========================================================================

    fun generateSixDigitNumericOtp(): String {
        val value = 100000 + secureRandom.nextInt(900000)
        return value.toString()
    }

    fun generateHexSalt(byteLen: Int = 16): String {
        val bytes = ByteArray(byteLen)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun computeSaltedOtpHash(salt: String, email: String, otpCode: String): String {
        val normalized = "$salt:${email.trim().lowercase()}:${otpCode.trim()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun hashWithSalt(secret: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$salt:$secret".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeHashEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8)
        )
    }

    private fun checkRateLimit(key: String, maxAllowed: Int, nowMs: Long): Boolean {
        val window = rateLimits.compute(key) { _, existing ->
            if (existing == null || nowMs - existing.windowStartMs > RATE_LIMIT_WINDOW_MS) {
                RateWindow(count = 1, windowStartMs = nowMs)
            } else {
                existing.count += 1
                existing
            }
        }!!
        return window.count <= maxAllowed
    }

    private fun loadOtpRecordsJson(): JSONObject {
        val raw = prefs.getString(KEY_OTP_RECORDS, null)
        if (raw.isNullOrBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun saveOtpRecordJson(email: String, recordObj: JSONObject) {
        val all = loadOtpRecordsJson()
        all.put(email.trim().lowercase(), recordObj)
        prefs.edit().putString(KEY_OTP_RECORDS, all.toString()).apply()
    }

    private fun invalidateOtpRecord(email: String) {
        val clean = email.trim().lowercase()
        val all = loadOtpRecordsJson()
        val existing = all.optJSONObject(clean) ?: return
        existing.put("invalidated", true)
        all.put(clean, existing)
        prefs.edit().putString(KEY_OTP_RECORDS, all.toString()).apply()
        transientIssuedOtpCodes.remove(clean)
    }

    private fun loadAccountsJson(): JSONObject {
        val raw = prefs.getString(KEY_STAGED_ACCOUNTS, null)
        if (raw.isNullOrBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun saveLocalAccountWithPassword(
        email: String,
        password: String,
        userId: String,
        displayName: String
    ) {
        val salt = generateHexSalt()
        val passwordHash = hashWithSalt(password, salt)
        saveVerifiedStagedAccount(email, userId, displayName, salt, passwordHash)
    }

    private fun saveVerifiedStagedAccount(
        email: String,
        userId: String,
        displayName: String,
        passwordSalt: String,
        passwordHash: String
    ) {
        val cleanEmail = email.trim().lowercase()
        val accountsObj = loadAccountsJson()
        val userRecord = JSONObject().apply {
            put("userId", userId)
            put("email", cleanEmail)
            put("displayName", displayName)
            put("salt", passwordSalt)
            put("passwordHash", passwordHash)
            put("isVerified", true)
            put("createdAtEpochMs", currentTimeProvider())
        }
        accountsObj.put(cleanEmail, userRecord)
        prefs.edit().putString(KEY_STAGED_ACCOUNTS, accountsObj.toString()).apply()
    }

    private fun verifyStagedAccount(email: String, password: String): AuthSession? {
        val cleanEmail = email.trim().lowercase()
        val accountsObj = loadAccountsJson()
        val record = accountsObj.optJSONObject(cleanEmail) ?: return null
        val salt = record.optString("salt")
        val storedHash = record.optString("passwordHash")
        val candidateHash = hashWithSalt(password, salt)
        if (!constantTimeHashEquals(candidateHash, storedHash)) return null

        val userId = record.optString("userId", UUID.nameUUIDFromBytes(cleanEmail.toByteArray()).toString())
        val displayName = record.optString("displayName", cleanEmail.substringBefore("@"))
        return buildVerifiedLocalSession(userId, cleanEmail, displayName)
    }

    private fun buildVerifiedLocalSession(
        userId: String,
        email: String,
        displayName: String
    ): AuthSession {
        val now = currentTimeProvider()
        return AuthSession(
            accessToken = "local-session-$userId",
            refreshToken = "local-refresh-$userId",
            expiresAtEpochMs = now + 30L * 24L * 3600L * 1000L,
            user = UserProfile(
                id = userId,
                email = email,
                displayName = displayName,
                isEmailVerified = true,
                createdAtEpochMs = now
            ),
            hasValidCloudJwt = false
        )
    }

    private fun persistSession(session: AuthSession) {
        val json = JSONObject().apply {
            put("accessToken", session.accessToken)
            put("refreshToken", session.refreshToken)
            put("expiresAtEpochMs", session.expiresAtEpochMs)
            put("hasValidCloudJwt", session.hasValidCloudJwt)
            put(
                "user",
                JSONObject().apply {
                    put("id", session.user.id)
                    put("email", session.user.email)
                    put("displayName", session.user.displayName)
                    put("isEmailVerified", session.user.isEmailVerified)
                    put("createdAtEpochMs", session.user.createdAtEpochMs)
                }
            )
        }
        prefs.edit().putString(KEY_ACTIVE_SESSION, json.toString()).apply()
    }

    private fun deserializeSession(json: JSONObject): AuthSession {
        val userObj = json.getJSONObject("user")
        return AuthSession(
            accessToken = json.optString("accessToken"),
            refreshToken = json.optString("refreshToken"),
            expiresAtEpochMs = json.optLong("expiresAtEpochMs"),
            hasValidCloudJwt = json.optBoolean("hasValidCloudJwt", false),
            user = UserProfile(
                id = userObj.optString("id"),
                email = userObj.optString("email"),
                displayName = userObj.optString("displayName"),
                isEmailVerified = userObj.optBoolean("isEmailVerified", true),
                createdAtEpochMs = userObj.optLong("createdAtEpochMs", System.currentTimeMillis())
            )
        )
    }
}
