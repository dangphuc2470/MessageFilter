package com.phucdnh.messagefilter.data.local

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ForwardType {
    ALL,      // Forward all messages
    OTP_ONLY  // Forward only when OTP is detected
}

enum class AppAction {
    FORWARD, // Intercept, dismiss original notification, and forward clean alert (Green)
    FILTER,  // Block notifications containing blacklist keywords, keep clean ones (Blue)
    BLOCK,   // Block/dismiss all notifications completely (Red)
    NONE     // Normal: not intercepted (White)
}

object AppFilterPreferences {
    private const val PREF_NAME = "app_filter_prefs"
    private const val KEY_FORWARD_PACKAGES = "key_forward_packages"
    private const val KEY_FORWARD_OTP_ONLY_PACKAGES = "key_forward_otp_only_packages"
    private const val KEY_FILTER_PACKAGES = "key_filter_packages"
    private const val KEY_BLOCKED_PACKAGES = "key_blocked_packages"
    private const val KEY_FILTER_KEYWORDS = "key_filter_keywords"
    private const val KEY_AUTO_DISMISS_ORIGINAL = "key_auto_dismiss_original"

    // Default forward messaging apps (WhatsApp and SMS apps with OTP_ONLY)
    val DEFAULT_FORWARD = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.samsung.android.messaging"
    )
    val DEFAULT_FORWARD_OTP_ONLY = setOf(
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.samsung.android.messaging"
    )
    val DEFAULT_FILTER = emptySet<String>()
    val DEFAULT_BLOCKED = emptySet<String>()

    // Default keywords to block when an app is in FILTER mode
    val DEFAULT_FILTER_KEYWORDS = setOf(
        "quảng cáo",
        "khuyến mãi",
        "ưu đãi",
        "voucher",
        "spam",
        "sale",
        "giảm giá",
        "tiết kiệm",
        "tri ân",
        "vay nhanh",
        "trúng thưởng"
    )

    // Popular messaging apps for quick setup
    val POPULAR_MESSAGING_APPS = listOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.facebook.orca" to "Facebook Messenger",
        "com.facebook.mlite" to "Messenger Lite",
        "org.telegram.messenger" to "Telegram",
        "com.zing.zalo" to "Zalo",
        "com.viber.voip" to "Viber",
        "com.google.android.apps.messaging" to "Google Messages (SMS)",
        "com.instagram.android" to "Instagram Direct"
    )

    private val _forwardFlow = MutableStateFlow<Set<String>>(DEFAULT_FORWARD)
    val forwardFlow: StateFlow<Set<String>> = _forwardFlow.asStateFlow()

    private val _forwardOtpOnlyFlow = MutableStateFlow<Set<String>>(DEFAULT_FORWARD_OTP_ONLY)
    val forwardOtpOnlyFlow: StateFlow<Set<String>> = _forwardOtpOnlyFlow.asStateFlow()

    private val _filterFlow = MutableStateFlow<Set<String>>(DEFAULT_FILTER)
    val filterFlow: StateFlow<Set<String>> = _filterFlow.asStateFlow()

    private val _blockedFlow = MutableStateFlow<Set<String>>(DEFAULT_BLOCKED)
    val blockedFlow: StateFlow<Set<String>> = _blockedFlow.asStateFlow()

    private val _keywordsFlow = MutableStateFlow<Set<String>>(DEFAULT_FILTER_KEYWORDS)
    val keywordsFlow: StateFlow<Set<String>> = _keywordsFlow.asStateFlow()

    private val _autoDismissFlow = MutableStateFlow(true)
    val autoDismissFlow: StateFlow<Boolean> = _autoDismissFlow.asStateFlow()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun initialize(context: Context) {
        val prefs = getPrefs(context)
        // Migration: read old key_included_packages if KEY_FORWARD_PACKAGES not present
        val savedFwd = prefs.getStringSet(KEY_FORWARD_PACKAGES, null)
            ?: prefs.getStringSet("key_included_packages", null)

        val flt = prefs.getStringSet(KEY_FILTER_PACKAGES, DEFAULT_FILTER) ?: DEFAULT_FILTER
        val blk = prefs.getStringSet(KEY_BLOCKED_PACKAGES, DEFAULT_BLOCKED) ?: DEFAULT_BLOCKED

        val fwd = if (savedFwd == null) {
            DEFAULT_FORWARD
        } else {
            val set = savedFwd.toMutableSet()
            // Ensure SMS default apps are included if not present in any list
            for (smsPkg in DEFAULT_FORWARD_OTP_ONLY) {
                if (!set.contains(smsPkg) && !flt.contains(smsPkg) && !blk.contains(smsPkg)) {
                    set.add(smsPkg)
                }
            }
            set
        }

        val otpOnly = prefs.getStringSet(KEY_FORWARD_OTP_ONLY_PACKAGES, DEFAULT_FORWARD_OTP_ONLY) ?: DEFAULT_FORWARD_OTP_ONLY
        val kw = prefs.getStringSet(KEY_FILTER_KEYWORDS, DEFAULT_FILTER_KEYWORDS) ?: DEFAULT_FILTER_KEYWORDS
        val autoDismiss = prefs.getBoolean(KEY_AUTO_DISMISS_ORIGINAL, true)

        _forwardFlow.value = fwd
        _forwardOtpOnlyFlow.value = otpOnly
        _filterFlow.value = flt
        _blockedFlow.value = blk
        _keywordsFlow.value = kw
        _autoDismissFlow.value = autoDismiss
    }

    fun getForwardType(context: Context, packageName: String): ForwardType {
        val prefs = getPrefs(context)
        val otpOnly = prefs.getStringSet(KEY_FORWARD_OTP_ONLY_PACKAGES, null) ?: _forwardOtpOnlyFlow.value
        return if (otpOnly.contains(packageName)) ForwardType.OTP_ONLY else ForwardType.ALL
    }

    fun setForwardType(context: Context, packageName: String, type: ForwardType) {
        val prefs = getPrefs(context)
        val otpOnly = (prefs.getStringSet(KEY_FORWARD_OTP_ONLY_PACKAGES, DEFAULT_FORWARD_OTP_ONLY) ?: DEFAULT_FORWARD_OTP_ONLY).toMutableSet()
        val fwd = _forwardFlow.value.toMutableSet()
        val blk = _blockedFlow.value.toMutableSet()

        if (type == ForwardType.OTP_ONLY) {
            otpOnly.add(packageName)
        } else {
            otpOnly.remove(packageName)
        }

        fwd.add(packageName)
        blk.remove(packageName)

        prefs.edit()
            .putStringSet(KEY_FORWARD_OTP_ONLY_PACKAGES, otpOnly)
            .putStringSet(KEY_FORWARD_PACKAGES, fwd)
            .putStringSet(KEY_BLOCKED_PACKAGES, blk)
            .apply()

        _forwardOtpOnlyFlow.value = otpOnly
        _forwardFlow.value = fwd
        _blockedFlow.value = blk
    }

    fun isForwardEnabled(context: Context, packageName: String): Boolean {
        val prefs = getPrefs(context)
        val fwd = prefs.getStringSet(KEY_FORWARD_PACKAGES, null)
            ?: prefs.getStringSet("key_included_packages", null)
            ?: _forwardFlow.value
        return fwd.contains(packageName)
    }

    fun isFilterEnabled(context: Context, packageName: String): Boolean {
        val prefs = getPrefs(context)
        val flt = prefs.getStringSet(KEY_FILTER_PACKAGES, null) ?: _filterFlow.value
        return flt.contains(packageName)
    }

    fun isBlocked(context: Context, packageName: String): Boolean {
        val prefs = getPrefs(context)
        val blk = prefs.getStringSet(KEY_BLOCKED_PACKAGES, null) ?: _blockedFlow.value
        return blk.contains(packageName)
    }

    fun toggleForward(context: Context, packageName: String) {
        val prefs = getPrefs(context)
        val fwd = _forwardFlow.value.toMutableSet()
        val blk = _blockedFlow.value.toMutableSet()

        if (fwd.contains(packageName)) {
            fwd.remove(packageName)
        } else {
            fwd.add(packageName)
            blk.remove(packageName)
        }

        prefs.edit()
            .putStringSet(KEY_FORWARD_PACKAGES, fwd)
            .putStringSet(KEY_BLOCKED_PACKAGES, blk)
            .apply()

        _forwardFlow.value = fwd
        _blockedFlow.value = blk
    }

    fun toggleFilter(context: Context, packageName: String) {
        val prefs = getPrefs(context)
        val flt = _filterFlow.value.toMutableSet()
        val blk = _blockedFlow.value.toMutableSet()

        if (flt.contains(packageName)) {
            flt.remove(packageName)
        } else {
            flt.add(packageName)
            blk.remove(packageName)
        }

        prefs.edit()
            .putStringSet(KEY_FILTER_PACKAGES, flt)
            .putStringSet(KEY_BLOCKED_PACKAGES, blk)
            .apply()

        _filterFlow.value = flt
        _blockedFlow.value = blk
    }

    fun toggleBlock(context: Context, packageName: String) {
        val prefs = getPrefs(context)
        val blk = _blockedFlow.value.toMutableSet()
        val fwd = _forwardFlow.value.toMutableSet()
        val flt = _filterFlow.value.toMutableSet()

        if (blk.contains(packageName)) {
            blk.remove(packageName)
        } else {
            blk.add(packageName)
            fwd.remove(packageName)
            flt.remove(packageName)
        }

        prefs.edit()
            .putStringSet(KEY_FORWARD_PACKAGES, fwd)
            .putStringSet(KEY_FILTER_PACKAGES, flt)
            .putStringSet(KEY_BLOCKED_PACKAGES, blk)
            .apply()

        _forwardFlow.value = fwd
        _filterFlow.value = flt
        _blockedFlow.value = blk
    }

    fun getPackageAction(context: Context, packageName: String): AppAction {
        val blk = _blockedFlow.value
        if (blk.contains(packageName)) return AppAction.BLOCK

        val fwd = _forwardFlow.value
        val flt = _filterFlow.value
        if (fwd.contains(packageName)) return AppAction.FORWARD
        if (flt.contains(packageName)) return AppAction.FILTER

        return AppAction.NONE
    }

    fun setPackageAction(context: Context, packageName: String, action: AppAction) {
        val prefs = getPrefs(context)
        val fwd = (prefs.getStringSet(KEY_FORWARD_PACKAGES, null)
            ?: prefs.getStringSet("key_included_packages", DEFAULT_FORWARD)
            ?: DEFAULT_FORWARD).toMutableSet()
        val flt = (prefs.getStringSet(KEY_FILTER_PACKAGES, DEFAULT_FILTER) ?: DEFAULT_FILTER).toMutableSet()
        val blk = (prefs.getStringSet(KEY_BLOCKED_PACKAGES, DEFAULT_BLOCKED) ?: DEFAULT_BLOCKED).toMutableSet()

        when (action) {
            AppAction.FORWARD -> {
                fwd.add(packageName)
                blk.remove(packageName)
            }
            AppAction.FILTER -> {
                flt.add(packageName)
                blk.remove(packageName)
            }
            AppAction.BLOCK -> {
                blk.add(packageName)
                fwd.remove(packageName)
                flt.remove(packageName)
            }
            AppAction.NONE -> {
                fwd.remove(packageName)
                flt.remove(packageName)
                blk.remove(packageName)
            }
        }

        prefs.edit()
            .putStringSet(KEY_FORWARD_PACKAGES, fwd)
            .putStringSet(KEY_FILTER_PACKAGES, flt)
            .putStringSet(KEY_BLOCKED_PACKAGES, blk)
            .apply()

        _forwardFlow.value = fwd
        _filterFlow.value = flt
        _blockedFlow.value = blk
    }

    fun getFilterKeywords(context: Context): Set<String> {
        val prefs = getPrefs(context)
        return prefs.getStringSet(KEY_FILTER_KEYWORDS, DEFAULT_FILTER_KEYWORDS) ?: DEFAULT_FILTER_KEYWORDS
    }

    fun addFilterKeyword(context: Context, keyword: String) {
        val clean = keyword.trim().lowercase()
        if (clean.isBlank()) return
        val prefs = getPrefs(context)
        val current = (prefs.getStringSet(KEY_FILTER_KEYWORDS, DEFAULT_FILTER_KEYWORDS) ?: DEFAULT_FILTER_KEYWORDS).toMutableSet()
        current.add(clean)
        prefs.edit().putStringSet(KEY_FILTER_KEYWORDS, current).apply()
        _keywordsFlow.value = current
    }

    fun removeFilterKeyword(context: Context, keyword: String) {
        val prefs = getPrefs(context)
        val current = (prefs.getStringSet(KEY_FILTER_KEYWORDS, DEFAULT_FILTER_KEYWORDS) ?: DEFAULT_FILTER_KEYWORDS).toMutableSet()
        current.remove(keyword.trim().lowercase())
        prefs.edit().putStringSet(KEY_FILTER_KEYWORDS, current).apply()
        _keywordsFlow.value = current
    }

    fun isAutoDismissOriginalEnabled(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_AUTO_DISMISS_ORIGINAL, true)
    }

    fun setAutoDismissOriginal(context: Context, enabled: Boolean) {
        val prefs = getPrefs(context)
        prefs.edit().putBoolean(KEY_AUTO_DISMISS_ORIGINAL, enabled).apply()
        _autoDismissFlow.value = enabled
    }
}
