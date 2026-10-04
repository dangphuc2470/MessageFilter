package com.phucdnh.messagefilter.util

object OtpExtractor {
    private val OTP_KEYWORDS = listOf(
        "otp",
        "mã xác minh", "ma xac minh", "xác minh", "xac minh",
        "mã xác thực", "ma xac thuc", "xác thực", "xac thuc",
        "mã xác nhận", "ma xac nhan", "xác nhận", "xac nhan",
        "mã kích hoạt", "ma kich hoat", "kích hoạt", "kich hoat",
        "mã bảo mật", "ma bao mat", "bảo mật", "bao mat",
        "mã khôi phục", "ma khoi phuc",
        "mã đăng ký", "ma dang ky",
        "mã truy cập", "ma truy cap",
        "mã một lần", "ma mot lan",
        "mã otp", "ma otp",
        "mã giao dịch", "ma giao dich",
        "mã đăng nhập", "ma dang nhap",
        "verification code", "security code", "passcode",
        "auth code", "authentication code", "confirmation code",
        "login code", "access code", "secret code",
        "verification", "verify",
        "one-time password", "one time password",
        "security pin"
    )

    private val KEYWORD_PREFIXED_PATTERNS = listOf(
        // e.g. "OTP: 123456", "OTP là 123456", "ma xac thuc: 123456", "code is 123456", "ma xac minh: 123456"
        Regex("""(?:otp|mã|ma|code|passcode|pin)[:\s\-_=làla]+([0-9]{4,8})\b""", RegexOption.IGNORE_CASE),
        // e.g. "G-123456", "FB-12345" -> extracts numeric OTP or full code
        Regex("""\b[A-Za-z]{1,4}-([0-9]{4,8})\b"""),
        // e.g. "123456 là mã", "123456 is your"
        Regex("""\b([0-9]{4,8})\s+(?:là|la|is)\b""", RegexOption.IGNORE_CASE),
        // e.g. "is 123456", "la 123456"
        Regex("""(?:is|la|là)\s+([0-9]{4,8})\b""", RegexOption.IGNORE_CASE)
    )

    private val STANDALONE_DIGIT_REGEX = Regex("""\b([0-9]{4,8})\b""")

    fun extractOtp(messageText: String): String? {
        if (messageText.isBlank()) return null

        val lower = messageText.lowercase()
        val hasKeyword = OTP_KEYWORDS.any { lower.contains(it) }
        if (!hasKeyword) return null

        // 1. Try keyword-prefixed pattern first (highest accuracy)
        for (pattern in KEYWORD_PREFIXED_PATTERNS) {
            val match = pattern.find(messageText)
            if (match != null) {
                val candidate = match.groupValues.getOrNull(1)?.trim() ?: match.value.trim()
                if (candidate.isNotBlank() && !isLikelyYear(candidate)) {
                    return candidate
                }
            }
        }

        // 2. Fallback to extracting standalone 4-8 digits if keyword exists
        val matches = STANDALONE_DIGIT_REGEX.findAll(messageText).toList()
        for (match in matches) {
            val candidate = match.groupValues.getOrNull(1)?.trim() ?: match.value.trim()
            if (!isLikelyYear(candidate)) {
                return candidate
            }
        }

        return null
    }

    private fun isLikelyYear(code: String): Boolean {
        return code.length == 4 && (code.startsWith("202") || code.startsWith("199"))
    }
}
