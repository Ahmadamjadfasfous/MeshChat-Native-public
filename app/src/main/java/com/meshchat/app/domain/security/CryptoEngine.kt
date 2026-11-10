package com.meshchat.app.domain.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.security.crypto.MasterKeys
import com.meshchat.app.data.local.dao.TrustDao
import com.meshchat.app.data.local.entity.TrustEntity
import com.meshchat.app.data.model.HandshakeMeta
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Key pair of one ECDH handshake. On Android 12+ the private half lives inside the Android
 * Keystore ([keystoreAlias] != null) and can only be used for key agreement, never exported.
 * Older devices fall back to a software ephemeral key that exists in memory only.
 */
class HandshakeKeys internal constructor(
    val publicKeyBytes: ByteArray,
    internal val privateKey: PrivateKey,
    internal val keystoreAlias: String?
)

@Singleton
class CryptoEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trustDao: TrustDao
) {
    companion object {
        private const val TAG = "CryptoEngine"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS_IDENTITY = "meshchat_identity_ec"
        private const val KEY_ALIAS_HANDSHAKE_PREFIX = "meshchat_kx_"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val REPLAY_WINDOW_MS = 60_000L // accepted clock drift between two nodes
        private const val HANDSHAKE_CONTEXT = "MeshChat-KX-v1"
        private const val HKDF_INFO = "meshchat/v1/aes-gcm"

        const val ROLE_REQUEST = "REQ"
        const val ROLE_RESPONSE = "RES"
    }

    private val secureRandom = SecureRandom()
    private val activeSessionKeys = ConcurrentHashMap<String, SecretKey>()

    @Volatile
    private var keystoreAgreementBroken = false

    // Anti-replay cache: set of recently processed nonces with LRU eviction
    private val processedNonces = Collections.newSetFromMap(
        object : java.util.LinkedHashMap<Long, Boolean>(500, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?): Boolean {
                return size > 1000
            }
        }
    )

    init {
        ensureIdentityKeyPair()
    }

    // ---------------------------------------------------------------------------------------------
    // Long-term identity key (ECDSA, Android Keystore)
    // ---------------------------------------------------------------------------------------------

    /** Generates or loads the asymmetric identity KeyPair (ECDSA secp256r1) inside Android KeyStore. */
    private fun ensureIdentityKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS_IDENTITY)) {
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE
            )
            val parameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS_IDENTITY,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .build()

            kpg.initialize(parameterSpec)
            return kpg.generateKeyPair()
        }

        val entry = keyStore.getEntry(KEY_ALIAS_IDENTITY, null) as KeyStore.PrivateKeyEntry
        return KeyPair(entry.certificate.publicKey, entry.privateKey)
    }

    fun getLocalPublicKey(): PublicKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getCertificate(KEY_ALIAS_IDENTITY).publicKey
    }

    fun getLocalPublicKeyBytes(): ByteArray = getLocalPublicKey().encoded

    /** Signs data using the Keystore-backed identity key via ECDSA. */
    fun sign(data: ByteArray): ByteArray {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS_IDENTITY, null) as PrivateKey
        val dsa = Signature.getInstance("SHA256withECDSA")
        dsa.initSign(privateKey)
        dsa.update(data)
        return dsa.sign()
    }

    /** Verifies an ECDSA signature against a peer's X.509 encoded public key. */
    fun verifySignature(peerPublicKeyBytes: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        return try {
            val keyFactory = KeyFactory.getInstance("EC")
            val pubKey = keyFactory.generatePublic(X509EncodedKeySpec(peerPublicKeyBytes))
            val dsa = Signature.getInstance("SHA256withECDSA")
            dsa.initVerify(pubKey)
            dsa.update(data)
            dsa.verify(signature)
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Anti replay
    // ---------------------------------------------------------------------------------------------

    /** Validates anti-replay nonce and timestamp drift. */
    @Synchronized
    fun validateAntiReplay(nonce: Long, timestamp: Long): Boolean {
        val now = System.currentTimeMillis()
        if (Math.abs(now - timestamp) > REPLAY_WINDOW_MS) {
            return false // Packet timestamp outside acceptable drift window
        }
        if (processedNonces.contains(nonce)) {
            return false // Duplicate nonce detected: replay attempt blocked
        }
        processedNonces.add(nonce)
        return true
    }

    fun generateNonce(): Long = secureRandom.nextLong()

    // ---------------------------------------------------------------------------------------------
    // Asymmetric ECDH key exchange
    // ---------------------------------------------------------------------------------------------

    /**
     * Creates the key pair for one handshake. Preferred: an EC key generated inside the Android
     * Keystore with the key-agreement purpose (API 31+). Otherwise (or if the device refuses):
     * a software ephemeral key.
     */
    fun createHandshakeKeys(): HandshakeKeys {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !keystoreAgreementBroken) {
            try {
                val alias = KEY_ALIAS_HANDSHAKE_PREFIX + UUID.randomUUID()
                val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
                kpg.initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .build()
                )
                val pair = kpg.generateKeyPair()
                return HandshakeKeys(pair.public.encoded, pair.private, alias)
            } catch (e: Exception) {
                Log.w(TAG, "Keystore ECDH key unavailable, using software ephemeral key", e)
                keystoreAgreementBroken = true
            }
        }
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        val pair = kpg.generateKeyPair()
        return HandshakeKeys(pair.public.encoded, pair.private, null)
    }

    /** Destroys the handshake key pair (deletes the Keystore entry if there is one). */
    fun discardHandshakeKeys(keys: HandshakeKeys) {
        val alias = keys.keystoreAlias ?: return
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete handshake key $alias", e)
        }
    }

    /**
     * Builds the signed handshake blob for [ephemeralPublicKey]. The signature binds the ephemeral
     * key to the sender, the receiver and the role, so it cannot be replayed into another exchange.
     */
    fun buildHandshakeMeta(
        role: String,
        sourceNodeId: String,
        destinationNodeId: String,
        ephemeralPublicKey: ByteArray
    ): HandshakeMeta = HandshakeMeta(
        ephemeralPublicKey = ephemeralPublicKey,
        identityPublicKey = getLocalPublicKeyBytes(),
        signature = sign(handshakeContext(role, sourceNodeId, destinationNodeId, ephemeralPublicKey))
    )

    fun verifyHandshakeMeta(
        role: String,
        meta: HandshakeMeta,
        sourceNodeId: String,
        destinationNodeId: String
    ): Boolean = verifySignature(
        meta.identityPublicKey,
        handshakeContext(role, sourceNodeId, destinationNodeId, meta.ephemeralPublicKey),
        meta.signature
    )

    private fun handshakeContext(
        role: String,
        sourceNodeId: String,
        destinationNodeId: String,
        ephemeralPublicKey: ByteArray
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(HANDSHAKE_CONTEXT.toByteArray(Charsets.UTF_8)); out.write(0)
        out.write(role.toByteArray(Charsets.UTF_8)); out.write(0)
        out.write(sourceNodeId.toByteArray(Charsets.UTF_8)); out.write(0)
        out.write(destinationNodeId.toByteArray(Charsets.UTF_8)); out.write(0)
        out.write(ephemeralPublicKey)
        return out.toByteArray()
    }

    /**
     * ECDH agreement + HKDF-SHA256 -> 256-bit AES-GCM session key. Both sides feed the same,
     * order-independent salt/info (sorted ephemeral keys and node ids), so they derive the same key.
     */
    fun deriveSessionKey(
        keys: HandshakeKeys,
        peerEphemeralPublicKey: ByteArray,
        localNodeId: String,
        peerNodeId: String
    ): SecretKey {
        val peerPub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerEphemeralPublicKey))

        val agreement = if (keys.keystoreAlias != null) {
            KeyAgreement.getInstance("ECDH", ANDROID_KEYSTORE)
        } else {
            KeyAgreement.getInstance("ECDH")
        }
        val rawSecret = try {
            agreement.init(keys.privateKey)
            agreement.doPhase(peerPub, true)
            agreement.generateSecret()
        } catch (e: Exception) {
            if (keys.keystoreAlias != null) keystoreAgreementBroken = true // next handshake uses software keys
            throw e
        }

        val orderedKeys = listOf(keys.publicKeyBytes, peerEphemeralPublicKey).sortedBy { it.toHex() }
        val salt = MessageDigest.getInstance("SHA-256").run {
            orderedKeys.forEach { update(it) }
            digest()
        }
        val info = (HKDF_INFO + "|" + listOf(localNodeId, peerNodeId).sorted().joinToString("|"))
            .toByteArray(Charsets.UTF_8)
        return SecretKeySpec(hkdfSha256(rawSecret, salt, info), "AES")
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val prk = hmacSha256(salt, ikm)
        return hmacSha256(prk, info + byteArrayOf(1)) // one 32-byte output block is all we need
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------------------------------------
    // AES-GCM
    // ---------------------------------------------------------------------------------------------

    /** AES-GCM authenticated encryption with random 12-byte IV and 128-bit tag. */
    fun encryptAesGcm(plainText: ByteArray, secretKey: SecretKey): Pair<ByteArray, ByteArray> {
        val iv = ByteArray(GCM_IV_LENGTH).apply { secureRandom.nextBytes(this) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return Pair(cipher.doFinal(plainText), iv)
    }

    fun decryptAesGcm(cipherText: ByteArray, iv: ByteArray, secretKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(cipherText)
    }

    // ---------------------------------------------------------------------------------------------
    // Session key registry + persistence
    // ---------------------------------------------------------------------------------------------

    fun registerActiveSessionKey(peerId: String, key: SecretKey) {
        activeSessionKeys[peerId] = key
    }

    fun getActiveSessionKey(peerId: String): SecretKey? = activeSessionKeys[peerId]

    fun clearSessionKeys() {
        activeSessionKeys.clear()
    }

    private fun getKeystoreMasterKey(): SecretKey {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(masterKeyAlias, null) as SecretKey
    }

    /**
     * Android Keystore keys require randomized encryption: the caller must NOT supply the IV,
     * the cipher generates it. Output layout: IV (12 bytes) || ciphertext+tag.
     */
    private fun encryptWithMasterKey(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKeystoreMasterKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decryptWithMasterKey(combined: ByteArray): ByteArray {
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val cipherText = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getKeystoreMasterKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(cipherText)
    }

    /** Identity key pinned for [peerId] on first use, or null when the peer was never trusted. */
    suspend fun getPinnedIdentityKey(peerId: String): ByteArray? = trustDao.getTrustForPeer(peerId)?.publicKeyBytes

    /**
     * Persists the peer's identity key and the AES-GCM session key (wrapped by the Keystore master
     * key) and activates the key in memory.
     */
    suspend fun savePeerTrust(peerId: String, peerIdentityKey: ByteArray, sessionKey: SecretKey) {
        registerActiveSessionKey(peerId, sessionKey)
        trustDao.saveTrustRecord(
            TrustEntity(
                peerId = peerId,
                publicKeyBytes = peerIdentityKey,
                sessionKeyEncrypted = encryptWithMasterKey(sessionKey.encoded),
                trustLevel = 1
            )
        )
    }

    suspend fun loadPeerTrust(peerId: String): Boolean {
        val trust = trustDao.getTrustForPeer(peerId) ?: return false
        return try {
            val decryptedRaw = decryptWithMasterKey(trust.sessionKeyEncrypted)
            activeSessionKeys[peerId] = SecretKeySpec(decryptedRaw, 0, 32, "AES")
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun revokePeerTrust(peerId: String) {
        activeSessionKeys.remove(peerId)
        trustDao.revokeTrust(peerId)
    }

    suspend fun purgeAllTrust() {
        clearSessionKeys()
        trustDao.purgeAllTrust()
    }
}
