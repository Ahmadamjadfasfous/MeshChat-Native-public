package com.meshchat.app.domain.mesh

import com.meshchat.app.data.local.entity.PeerEntity
import java.util.Locale

/**
 * The standardized three-field username template of a mesh peer:
 *
 *     <DisplayName> / <DeviceModel> / <NodeId>        e.g.  Samsung / SM-A546B / 1a2b3c4d
 *
 * - DisplayName : user chosen name (default: device manufacturer)
 * - DeviceModel : hardware model reported by the device
 * - NodeId      : the stable mesh node id (without its `node_` prefix)
 *
 * The same three fields travel inside the Nearby advertisement as `name|model|nodeId`.
 */
object PeerUsername {

    private const val ADVERT_DELIMITER = '|'
    private const val NODE_PREFIX = "node_"
    private const val FIELD_SEPARATORS = "/·|"
    const val MAX_FIELD_CHARS = 32

    /** Parsed advertisement of a nearby endpoint. */
    data class Advert(val displayName: String, val deviceModel: String, val nodeId: String)

    fun shortId(nodeId: String): String = nodeId.removePrefix(NODE_PREFIX)

    /** Removes the advert delimiter / control characters and bounds the length of one field. */
    fun sanitizeField(raw: String): String =
        raw.filter { it != ADVERT_DELIMITER && !it.isISOControl() && it != '/' && it != '·' }
            .trim()
            .take(MAX_FIELD_CHARS)

    /** Human readable form shown in the UI. */
    fun display(displayName: String, deviceModel: String, nodeId: String): String =
        "$displayName · $deviceModel · ${shortId(nodeId)}"

    /** Form users can type into the search bar. */
    fun canonical(displayName: String, deviceModel: String, nodeId: String): String =
        "$displayName/$deviceModel/${shortId(nodeId)}"

    fun encodeAdvert(displayName: String, deviceModel: String, nodeId: String): String =
        listOf(sanitizeField(displayName), sanitizeField(deviceModel), nodeId).joinToString(ADVERT_DELIMITER.toString())

    /** Returns null if the advertisement does not carry the three fields (foreign / old app). */
    fun decodeAdvert(raw: String): Advert? {
        val parts = raw.split(ADVERT_DELIMITER)
        if (parts.size != 3 || parts[2].isBlank()) return null
        return Advert(
            displayName = sanitizeField(parts[0]).ifBlank { "Mesh User" },
            deviceModel = sanitizeField(parts[1]).ifBlank { "Unknown" },
            nodeId = parts[2].trim()
        )
    }

    fun initialsOf(displayName: String): String =
        displayName.trim().take(2).uppercase(Locale.ROOT).ifBlank { "??" }

    /**
     * Instant, case-insensitive directory filter.
     *
     *  - Blank query                     -> everything matches.
     *  - Query with a field separator    -> positional: `/`, `·` or `|` split it into
     *                                       name / model / id segments; every non-blank segment must
     *                                       be contained in its own field (`//1a2b`, `sam/sm-a`, ...).
     *  - Query without field separators  -> every whitespace separated token must appear in at least
     *                                       one of the three fields (or the full node id).
     */
    fun matches(query: String, displayName: String, deviceModel: String, nodeId: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true

        val name = displayName.lowercase(Locale.ROOT)
        val model = deviceModel.lowercase(Locale.ROOT)
        val shortId = shortId(nodeId).lowercase(Locale.ROOT)
        val fullId = nodeId.lowercase(Locale.ROOT)

        if (q.any { it in FIELD_SEPARATORS }) {
            val segments = q.lowercase(Locale.ROOT).split('/', '·', '|').map { it.trim() }
            if (segments.size > 3) return false
            val fields = listOf(name, model, shortId)
            return segments.withIndex().all { (index, segment) ->
                segment.isEmpty() || fields[index].contains(segment) ||
                    (index == 2 && fullId.contains(segment))
            }
        }

        return q.lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .all { token -> name.contains(token) || model.contains(token) || fullId.contains(token) }
    }

    fun matches(query: String, peer: PeerEntity): Boolean =
        matches(query, peer.displayName, peer.deviceModel, peer.id)
}

/** Username in its UI form. */
fun PeerEntity.username(): String = PeerUsername.display(displayName, deviceModel, id)
