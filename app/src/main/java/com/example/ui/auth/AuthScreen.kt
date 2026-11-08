package com.example.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

@Composable
fun AuthScreen(
    uiState: AuthUiState,
    onModeChanged: (AuthMode) -> Unit,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onConfirmPasswordChanged: (String) -> Unit,
    onDisplayNameChanged: (String) -> Unit,
    onOtpCodeChanged: (String) -> Unit = {},
    onVerifyOtp: () -> Unit = {},
    onResendOtp: () -> Unit = {},
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("auth_screen_root"),
        contentWindowInsets = WindowInsets.systemBars
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Smarter AI Brand Header
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.secondary
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (uiState.mode == AuthMode.VERIFY_OTP) {
                            Icons.Default.MarkEmailRead
                        } else {
                            Icons.Default.AutoAwesome
                        },
                        contentDescription = stringResource(R.string.cd_app_logo),
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Multi-Model AI Assistant • Cloud Synced Across Devices",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Smarter AI Cloud Status Pill
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.testTag("auth_cloud_status_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = if (uiState.isLiveCloudConfigured) {
                                "Smarter AI Cloud Sync Active"
                            } else {
                                "Smarter AI Account Protection"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                    ) {
                        if (uiState.mode == AuthMode.VERIFY_OTP) {
                            OtpVerificationContent(
                                uiState = uiState,
                                onOtpCodeChanged = onOtpCodeChanged,
                                onVerifyOtp = {
                                    focusManager.clearFocus()
                                    onVerifyOtp()
                                },
                                onResendOtp = onResendOtp,
                                onChangeEmail = { onModeChanged(AuthMode.SIGN_UP) }
                            )
                        } else {
                            if (uiState.mode != AuthMode.FORGOT_PASSWORD) {
                                TabRow(
                                    selectedTabIndex = if (uiState.mode == AuthMode.LOGIN) 0 else 1,
                                    containerColor = Color.Transparent,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Tab(
                                        selected = uiState.mode == AuthMode.LOGIN,
                                        onClick = { onModeChanged(AuthMode.LOGIN) },
                                        modifier = Modifier.testTag("auth_tab_login"),
                                        text = {
                                            Text(
                                                text = "Sign In",
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    )
                                    Tab(
                                        selected = uiState.mode == AuthMode.SIGN_UP,
                                        onClick = { onModeChanged(AuthMode.SIGN_UP) },
                                        modifier = Modifier.testTag("auth_tab_signup"),
                                        text = {
                                            Text(
                                                text = "Create Account",
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    )
                                }
                                Spacer(modifier = Modifier.height(20.dp))
                            } else {
                                Text(
                                    text = "Reset Password",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Enter your email address to receive password recovery instructions.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            AnimatedVisibility(visible = uiState.mode == AuthMode.SIGN_UP) {
                                Column {
                                    OutlinedTextField(
                                        value = uiState.displayName,
                                        onValueChange = onDisplayNameChanged,
                                        label = { Text("Display Name") },
                                        leadingIcon = {
                                            Icon(Icons.Default.Person, contentDescription = null)
                                        },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Text,
                                            imeAction = ImeAction.Next
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("auth_display_name_input")
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                }
                            }

                            OutlinedTextField(
                                value = uiState.email,
                                onValueChange = onEmailChanged,
                                label = { Text("Email Address") },
                                leadingIcon = {
                                    Icon(Icons.Default.Email, contentDescription = null)
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Email,
                                    imeAction = if (uiState.mode == AuthMode.FORGOT_PASSWORD) {
                                        ImeAction.Done
                                    } else {
                                        ImeAction.Next
                                    }
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        focusManager.clearFocus()
                                        onSubmit()
                                    }
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("auth_email_input")
                            )

                            AnimatedVisibility(visible = uiState.mode != AuthMode.FORGOT_PASSWORD) {
                                Column {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    OutlinedTextField(
                                        value = uiState.password,
                                        onValueChange = onPasswordChanged,
                                        label = { Text("Password") },
                                        leadingIcon = {
                                            Icon(Icons.Default.Lock, contentDescription = null)
                                        },
                                        trailingIcon = {
                                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                                Icon(
                                                    imageVector = if (passwordVisible) {
                                                        Icons.Default.VisibilityOff
                                                    } else {
                                                        Icons.Default.Visibility
                                                    },
                                                    contentDescription = "Toggle password visibility"
                                                )
                                            }
                                        },
                                        singleLine = true,
                                        visualTransformation = if (passwordVisible) {
                                            VisualTransformation.None
                                        } else {
                                            PasswordVisualTransformation()
                                        },
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Password,
                                            imeAction = if (uiState.mode == AuthMode.SIGN_UP) {
                                                ImeAction.Next
                                            } else {
                                                ImeAction.Done
                                            }
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onDone = {
                                                focusManager.clearFocus()
                                                onSubmit()
                                            }
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("auth_password_input")
                                    )
                                }
                            }

                            AnimatedVisibility(visible = uiState.mode == AuthMode.SIGN_UP) {
                                Column {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    OutlinedTextField(
                                        value = uiState.confirmPassword,
                                        onValueChange = onConfirmPasswordChanged,
                                        label = { Text("Confirm Password") },
                                        leadingIcon = {
                                            Icon(Icons.Default.Lock, contentDescription = null)
                                        },
                                        singleLine = true,
                                        visualTransformation = if (passwordVisible) {
                                            VisualTransformation.None
                                        } else {
                                            PasswordVisualTransformation()
                                        },
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Password,
                                            imeAction = ImeAction.Done
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onDone = {
                                                focusManager.clearFocus()
                                                onSubmit()
                                            }
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("auth_confirm_password_input")
                                    )
                                }
                            }

                            AnimatedVisibility(visible = !uiState.errorMessage.isNullOrBlank()) {
                                Column {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.errorContainer,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("auth_error_banner")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ErrorOutline,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                            Text(
                                                text = uiState.errorMessage.orEmpty(),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                }
                            }

                            AnimatedVisibility(visible = !uiState.infoMessage.isNullOrBlank()) {
                                Column {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("auth_info_banner")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                            Text(
                                                text = uiState.infoMessage.orEmpty(),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    onSubmit()
                                },
                                enabled = !uiState.isLoading,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("auth_submit_button")
                            ) {
                                if (uiState.isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    val label = when (uiState.mode) {
                                        AuthMode.LOGIN -> "Sign In"
                                        AuthMode.SIGN_UP -> "Create Account"
                                        AuthMode.VERIFY_OTP -> "Verify & Continue"
                                        AuthMode.FORGOT_PASSWORD -> "Send Recovery Email"
                                    }
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            if (uiState.mode == AuthMode.LOGIN) {
                                TextButton(
                                    onClick = { onModeChanged(AuthMode.FORGOT_PASSWORD) },
                                    modifier = Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .testTag("auth_forgot_password_button")
                                ) {
                                    Text("Forgot password?")
                                }
                            } else if (uiState.mode == AuthMode.FORGOT_PASSWORD) {
                                TextButton(
                                    onClick = { onModeChanged(AuthMode.LOGIN) },
                                    modifier = Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .testTag("auth_back_to_login_button")
                                ) {
                                    Text("Back to Sign In")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OtpVerificationContent(
    uiState: AuthUiState,
    onOtpCodeChanged: (String) -> Unit,
    onVerifyOtp: () -> Unit,
    onResendOtp: () -> Unit,
    onChangeEmail: () -> Unit
) {
    val targetEmail = uiState.otpChallenge?.email?.ifBlank { uiState.email } ?: uiState.email
    val minutes = uiState.expiryCountdownSeconds / 60
    val seconds = uiState.expiryCountdownSeconds % 60
    val expiryFormatted = "%d:%02d".format(minutes, seconds)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("auth_otp_verification_section"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Verify your email",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Enter the 6-digit verification code sent to $targetEmail.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(50),
            modifier = Modifier.testTag("auth_otp_expiry_badge")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (uiState.expiryCountdownSeconds > 0) {
                        "Code expires in $expiryFormatted"
                    } else {
                        "Code valid for 5 minutes"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 6-digit visual display boxes
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            for (i in 0 until 6) {
                val digitChar = uiState.otpCode.getOrNull(i)?.toString() ?: ""
                val isFocusedOrFilled = digitChar.isNotEmpty() || uiState.otpCode.length == i
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 54.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .border(
                            width = if (isFocusedOrFilled) 2.dp else 1.dp,
                            color = if (isFocusedOrFilled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                            },
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = digitChar,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = uiState.otpCode,
            onValueChange = onOtpCodeChanged,
            label = { Text("6-Digit Verification Code") },
            placeholder = { Text("123456") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { onVerifyOtp() }
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("auth_otp_input")
        )

        AnimatedVisibility(visible = !uiState.errorMessage.isNullOrBlank()) {
            Column {
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("auth_error_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = uiState.errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = !uiState.infoMessage.isNullOrBlank() && uiState.errorMessage.isNullOrBlank()) {
            Column {
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("auth_info_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = uiState.infoMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onVerifyOtp,
            enabled = !uiState.isLoading && uiState.otpCode.length == 6,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("auth_verify_otp_button")
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(
                    text = "Verify & Continue",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onResendOtp,
            enabled = !uiState.isLoading && uiState.resendCountdownSeconds == 0,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("auth_resend_otp_button")
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = if (uiState.resendCountdownSeconds > 0) {
                    "Resend code in ${uiState.resendCountdownSeconds}s"
                } else {
                    "Resend verification code"
                },
                style = MaterialTheme.typography.labelLarge
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = onChangeEmail,
            modifier = Modifier.testTag("auth_change_email_button")
        ) {
            Text("Use a different email address")
        }
    }
}
