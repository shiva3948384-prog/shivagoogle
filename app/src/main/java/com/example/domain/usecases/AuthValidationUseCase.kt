package com.example.domain.usecases

object AuthValidationUseCase {

    private val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    data class ValidationResult(
        val isValid: Boolean,
        val errorMessage: String? = null
    )

    fun validateEmail(email: String): ValidationResult {
        val trimmed = email.trim()
        if (trimmed.isEmpty()) {
            return ValidationResult(false, "Email address is required.")
        }
        if (!EMAIL_REGEX.matches(trimmed)) {
            return ValidationResult(false, "Please enter a valid email address.")
        }
        return ValidationResult(true)
    }

    fun validatePasswordForLogin(password: String): ValidationResult {
        if (password.isEmpty()) {
            return ValidationResult(false, "Password is required.")
        }
        if (password.length < 6) {
            return ValidationResult(false, "Password must be at least 6 characters.")
        }
        return ValidationResult(true)
    }

    fun validateSignUp(
        email: String,
        password: String,
        confirmPassword: String,
        displayName: String
    ): ValidationResult {
        if (displayName.trim().length < 2) {
            return ValidationResult(false, "Display name must be at least 2 characters.")
        }
        val emailCheck = validateEmail(email)
        if (!emailCheck.isValid) return emailCheck

        if (password.length < 8) {
            return ValidationResult(false, "Password must be at least 8 characters long.")
        }
        if (!password.any { it.isLetter() } || !password.any { it.isDigit() }) {
            return ValidationResult(false, "Password must contain at least one letter and one number.")
        }
        if (password != confirmPassword) {
            return ValidationResult(false, "Passwords do not match.")
        }
        return ValidationResult(true)
    }
}
