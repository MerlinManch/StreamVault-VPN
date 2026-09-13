package com.streamvault.app.vpn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class VpnProfile(val id: String, val name: String)

/** All profiles (including names) are encrypted and excluded from Android/app backups. */
internal class WireGuardProfileStore(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "wireguard-profiles.enc"))

    fun list(): List<VpnProfile> = synchronized(lock) {
        val entries = read()
        List(entries.length()) { i ->
            entries.getJSONObject(i).let { VpnProfile(it.getString("id"), it.getString("name")) }
        }
    }

    fun config(id: String) = synchronized(lock) {
        val entries = read()
        val entry = (0 until entries.length()).map { entries.getJSONObject(it) }
            .firstOrNull { it.getString("id") == id }
            ?: throw VpnProfileException(VpnProfileException.Reason.STORAGE)
        WireGuardConfigPolicy.parse(entry.getString("config"), context.packageName)
    }

    fun add(name: String, text: String) = synchronized(lock) {
        require(name.trim().isNotEmpty() && name.trim().length <= 64)
        val config = WireGuardConfigPolicy.parse(text, context.packageName)
        val entries = read()
        require(entries.length() < 32)
        entries.put(JSONObject().put("id", UUID.randomUUID().toString())
            .put("name", name.trim()).put("config", config.toWgQuickString()))
        write(entries)
    }

    fun delete(id: String) = synchronized(lock) {
        val old = read()
        val entries = JSONArray()
        for (i in 0 until old.length()) {
            val entry = old.getJSONObject(i)
            if (entry.getString("id") != id) entries.put(entry)
        }
        write(entries)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private fun read(): JSONArray {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return JSONArray()
        val envelope = file.readFully()
        require(envelope.size >= 29 && envelope[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, envelope.copyOfRange(1, 13)))
        return JSONArray(String(cipher.doFinal(envelope.copyOfRange(13, envelope.size)), Charsets.UTF_8))
    }

    private fun write(entries: JSONArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val envelope = byteArrayOf(1) + cipher.iv + cipher.doFinal(entries.toString().toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try {
            output.write(envelope)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    companion object {
        private const val KEY_ALIAS = "streamvault-wireguard-v1"
        private val lock = Any()
    }
}
