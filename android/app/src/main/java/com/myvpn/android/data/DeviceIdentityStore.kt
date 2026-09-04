package com.myvpn.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.ds by preferencesDataStore("myvpn_device")

/** Keeps legacy device identity and the PHONE-auth session encrypted with Android Keystore. */
class DeviceIdentityStore(private val context: Context) {
    private val install = stringPreferencesKey("install")
    private val secret = stringPreferencesKey("secret")
    private val access = stringPreferencesKey("access")
    private val refresh = stringPreferencesKey("refresh")
    private val accountId = stringPreferencesKey("account_id")
    private val accessStatus = stringPreferencesKey("access_status")
    private val accessExpiresAt = stringPreferencesKey("access_expires_at")
    private val expiresAt = longPreferencesKey("access_expires_at_millis")

    suspend fun identity(): Pair<String, String> {
        val preferences = context.ds.data.first()
        val installId = preferences[install] ?: UUID.randomUUID().toString()
        val deviceSecret = preferences[secret] ?: encrypt(newSecret()).also { encrypted -> context.ds.edit { it[secret] = encrypted } }
        if (preferences[install] == null) context.ds.edit { it[install] = installId }
        return installId to decrypt(deviceSecret)
    }

    suspend fun session(): Session? {
        val preferences = context.ds.data.first()
        val encryptedAccess = preferences[access] ?: return null
        val encryptedRefresh = preferences[refresh] ?: return null
        return Session(decrypt(encryptedAccess), decrypt(encryptedRefresh), 0, preferences[accountId], preferences[accessStatus], preferences[accessExpiresAt], preferences[expiresAt] ?: 0)
    }

    suspend fun save(session: Session) {
        context.ds.edit {
            it[access] = encrypt(session.accessToken)
            it[refresh] = encrypt(session.refreshToken)
            session.accountId?.let { value -> it[accountId] = value } ?: it.remove(accountId)
            session.accessStatus?.let { value -> it[accessStatus] = value } ?: it.remove(accessStatus)
            session.accessExpiresAt?.let { value -> it[accessExpiresAt] = value } ?: it.remove(accessExpiresAt)
            it[expiresAt] = session.expiresAtMillis
        }
    }

    suspend fun clear() = context.ds.edit { it.remove(access); it.remove(refresh); it.remove(accountId); it.remove(accessStatus); it.remove(accessExpiresAt); it.remove(expiresAt) }

    private fun newSecret() = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey("myvpn_aes", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("myvpn_aes", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(value: String): String { val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); val encrypted = cipher.doFinal(value.toByteArray()); return Base64.encodeToString(ByteBuffer.allocate(4 + cipher.iv.size + encrypted.size).putInt(cipher.iv.size).put(cipher.iv).put(encrypted).array(), Base64.NO_WRAP) }
    private fun decrypt(value: String): String { val bytes = Base64.decode(value, Base64.NO_WRAP); val size = ByteBuffer.wrap(bytes).int; val iv = bytes.copyOfRange(4, 4 + size); val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); return String(cipher.doFinal(bytes.copyOfRange(4 + size, bytes.size))) }
}
