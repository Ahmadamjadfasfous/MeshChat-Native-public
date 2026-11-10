package com.meshchat.app.domain.mesh

import com.meshchat.app.data.local.entity.MessageEntity
import com.meshchat.app.data.local.entity.MessageTypes
import com.meshchat.app.data.local.entity.SecurityStatus

/**
 * Decides when the inline "Verify Peer Profile" banner is shown.
 *
 * The plain-text conversation IS the challenge: Peer A writes first (raw plain text), Peer B answers
 * in plain text, and A - and only A - can then decide that the person on the other end is who they
 * claim to be and activate the asymmetric encryption.
 */
object HandshakePolicy {

    const val BANNER_MESSAGE = "Verify Peer Profile."
    const val BANNER_ACTION = "[Activate Asymmetric ECDH Encryption]"

    /**
     * True when all of the following hold for this conversation (seen from the local device):
     *  1. the conversation is still [SecurityStatus.UNVERIFIED],
     *  2. the first non-system message was written by the local user (local user is "Peer A"),
     *  3. the peer has answered with a plain-text message.
     *
     * [messages] must be ordered oldest first.
     */
    fun shouldOfferVerification(securityStatus: String, messages: List<MessageEntity>): Boolean {
        if (securityStatus != SecurityStatus.UNVERIFIED) return false
        val conversation = messages.filter { it.type != MessageTypes.SYSTEM }
        val first = conversation.firstOrNull() ?: return false
        if (!first.isFromMe) return false
        return conversation.any { !it.isFromMe && it.type == MessageTypes.TEXT_PLAIN }
    }
}
