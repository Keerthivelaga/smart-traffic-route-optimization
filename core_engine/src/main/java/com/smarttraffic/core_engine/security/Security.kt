package com.smarttraffic.core_engine.security

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import com.smarttraffic.coreengine.BuildConfig
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Base64
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile
    private var inMemoryToken: String? = null

    private val prefs: SharedPreferences? by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "smart_traffic_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
    }

    fun saveIdToken(token: String, fallbackEmail: String? = null) {
        inMemoryToken = token
        val normalizedFallbackEmail = fallbackEmail
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val tokenEmail = runCatching {
            val payload = decodeJwtPayload(token) ?: return@runCatching null
            extractJsonClaim(payload, "email")
        }.getOrNull()
        val resolvedEmail = tokenEmail ?: normalizedFallbackEmail

        prefs?.edit()?.apply {
            putString(KEY_ID_TOKEN, token)
            if (resolvedEmail != null) {
                putString(KEY_LAST_EMAIL, resolvedEmail)
            } else {
                remove(KEY_LAST_EMAIL)
            }
        }?.apply()
    }

    fun getIdToken(): String? = prefs?.getString(KEY_ID_TOKEN, null) ?: inMemoryToken

    fun isAuthenticated(): Boolean = currentUserId() != null

    fun currentUserId(): String? {
        val token = getIdToken().orEmpty().trim()
        if (token.isBlank()) return null
        return runCatching {
            val payload = decodeJwtPayload(token) ?: return@runCatching null
            if (extractSignInProvider(payload).equals("anonymous", ignoreCase = true)) {
                return@runCatching null
            }
            extractJsonClaim(payload, "user_id")
                ?: extractJsonClaim(payload, "sub")
        }.getOrNull()
    }

    fun currentEmail(): String? {
        val token = getIdToken().orEmpty().trim()
        val parsedEmail = if (token.isBlank()) {
            null
        } else {
            runCatching {
                val payload = decodeJwtPayload(token) ?: return@runCatching null
                if (extractSignInProvider(payload).equals("anonymous", ignoreCase = true)) {
                    return@runCatching null
                }
                extractJsonClaim(payload, "email")
            }.getOrNull()
        }
        return parsedEmail ?: prefs?.getString(KEY_LAST_EMAIL, null)
    }

    fun clear() {
        inMemoryToken = null
        prefs?.edit()?.clear()?.apply()
    }

    private fun decodeJwtPayload(token: String): String? {
        val parts = token.split(".")
        if (parts.size < 2) return null
        val payloadPart = parts[1]
        val normalized = payloadPart
            .replace('-', '+')
            .replace('_', '/')
            .let { raw ->
                val remainder = raw.length % 4
                if (remainder == 0) raw else raw + "=".repeat(4 - remainder)
            }
        val decoded = Base64.getDecoder().decode(normalized)
        return decoded.toString(Charsets.UTF_8)
    }

    private fun extractJsonClaim(payload: String, claim: String): String? {
        val pattern = Pattern.compile("\"$claim\"\\s*:\\s*\"([^\"]+)\"")
        val matcher = pattern.matcher(payload)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun extractSignInProvider(payload: String): String? {
        val pattern = Pattern.compile("\"sign_in_provider\"\\s*:\\s*\"([^\"]+)\"")
        val matcher = pattern.matcher(payload)
        return if (matcher.find()) matcher.group(1) else null
    }

    companion object {
        private const val KEY_ID_TOKEN = "id_token"
        private const val KEY_LAST_EMAIL = "last_email"
    }
}

@Singleton
class TamperGuard @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun isCompromised(): Boolean {
        if (BuildConfig.DEBUG) return false
        return isRootLikely() || isRunningOnUnsafeEmulator() || !verifySignature()
    }

    private fun isRootLikely(): Boolean {
        val suspiciousFiles = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/app/Superuser.apk",
        )
        return suspiciousFiles.any { java.io.File(it).exists() }
    }

    private fun isRunningOnUnsafeEmulator(): Boolean {
        val brand = Build.BRAND.lowercase()
        val model = Build.MODEL.lowercase()
        return brand.contains("generic") || model.contains("sdk") || model.contains("emulator")
    }

    fun verifySignature(): Boolean {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            packageInfo.signingInfo != null
        } catch (_: Exception) {
            false
        }
    }
}

