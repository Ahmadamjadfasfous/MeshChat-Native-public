package com.meshchat.app.domain.mesh

import com.meshchat.app.data.model.MeshPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory, thread-safe routing map used for multi-hop forwarding.
 *
 * "Endpoint" is the transient Nearby Connections link id of a directly connected neighbour,
 * "node" is the stable mesh node id. If node C is only reachable through node B, the table holds
 * `C -> (nextHopEndpoint = link to B, nextHopNode = B, hopCount = 2)`, which is all a relay needs
 * to push C's packets onwards without ever looking at their payload.
 *
 * Observers can collect [routes]; it only emits when the structure of the table changes
 * (route added / removed / re-pointed / hop count changed), not on mere freshness refreshes.
 */
class RoutingTable(
    private val clock: () -> Long = System::currentTimeMillis
) {

    data class RouteEntry(
        val destinationNodeId: String,
        val nextHopEndpointId: String,
        val nextHopNodeId: String,
        val hopCount: Int,
        val isDirect: Boolean
    )

    /** What a node should do with a packet it has to push towards [MeshPacket.finalDestinationId]. */
    sealed class ForwardDecision {
        /** Known route: hand the packet to exactly one neighbour. */
        data class Unicast(val endpointId: String, val nodeId: String) : ForwardDecision()

        /** No usable route (or a broadcast): controlled flood to these neighbours. */
        data class Flood(val targets: List<Pair<String, String>>) : ForwardDecision() // endpointId to nodeId

        /** Nobody to forward to. */
        object NoRoute : ForwardDecision()
    }

    private class Stamped(var entry: RouteEntry, var updatedAt: Long)

    private val lock = Any()
    private val table = HashMap<String, Stamped>() // destination node -> route
    private val endpointToNode = HashMap<String, String>() // direct links only
    private val nodeToEndpoint = HashMap<String, String>()

    private val _routes = MutableStateFlow<List<RouteEntry>>(emptyList())

    /** Live, hop-sorted view of every reachable node (direct neighbours have hopCount 1). */
    val routes: StateFlow<List<RouteEntry>> = _routes.asStateFlow()

    companion object {
        const val ROUTE_TIMEOUT_MS = 90_000L
        const val ROUTE_STALE_MS = 45_000L
    }

    // ---------------------------------------------------------------------------------------------
    // Direct neighbours
    // ---------------------------------------------------------------------------------------------

    fun addDirectNeighbor(endpointId: String, nodeId: String) {
        synchronized(lock) {
            // A node can reconnect over a new link: drop the previous mapping first
            nodeToEndpoint.remove(nodeId)?.let { endpointToNode.remove(it) }
            endpointToNode[endpointId] = nodeId
            nodeToEndpoint[nodeId] = endpointId
            table[nodeId] = Stamped(
                RouteEntry(
                    destinationNodeId = nodeId,
                    nextHopEndpointId = endpointId,
                    nextHopNodeId = nodeId,
                    hopCount = 1,
                    isDirect = true
                ),
                clock()
            )
            publishLocked()
        }
    }

    fun removeDirectNeighbor(endpointId: String) {
        synchronized(lock) {
            val nodeId = endpointToNode.remove(endpointId)
            if (nodeId != null && nodeToEndpoint[nodeId] == endpointId) nodeToEndpoint.remove(nodeId)
            // Everything that was reachable through this link is gone with it
            table.entries.removeIf { it.value.entry.nextHopEndpointId == endpointId }
            publishLocked()
        }
    }

    fun getDirectEndpoints(): Set<String> = synchronized(lock) { endpointToNode.keys.toSet() }

    /** endpointId -> nodeId of every direct neighbour. */
    fun getDirectNeighborsMap(): Map<String, String> = synchronized(lock) { endpointToNode.toMap() }

    fun isDirectEndpoint(endpointId: String): Boolean = synchronized(lock) { endpointToNode.containsKey(endpointId) }

    fun isDirectNode(nodeId: String): Boolean = synchronized(lock) { nodeToEndpoint.containsKey(nodeId) }

    fun nodeForEndpoint(endpointId: String): String? = synchronized(lock) { endpointToNode[endpointId] }

    fun directEndpointFor(nodeId: String): String? = synchronized(lock) { nodeToEndpoint[nodeId] }

    // ---------------------------------------------------------------------------------------------
    // Multi-hop routes
    // ---------------------------------------------------------------------------------------------

    /**
     * Learns (or refreshes) the route to [destinationNodeId] that was observed on the link
     * [viaEndpointId] / [viaNodeId]. Direct routes are never replaced by multi-hop ones.
     * @return true when the structure of the table changed.
     */
    fun learnRoute(destinationNodeId: String, viaEndpointId: String, viaNodeId: String, hopCount: Int): Boolean {
        if (hopCount < 1) return false
        synchronized(lock) {
            val now = clock()
            val existing = table[destinationNodeId]
            if (existing != null && existing.entry.isDirect) {
                // Keep the direct link as long as it exists
                if (nodeToEndpoint.containsKey(destinationNodeId)) return false
            }
            val candidate = RouteEntry(
                destinationNodeId = destinationNodeId,
                nextHopEndpointId = viaEndpointId,
                nextHopNodeId = viaNodeId,
                hopCount = hopCount,
                isDirect = nodeToEndpoint[destinationNodeId] == viaEndpointId && hopCount == 1
            )
            val accept = when {
                existing == null -> true
                existing.entry.nextHopEndpointId == viaEndpointId -> true // same path: refresh / re-measure
                hopCount < existing.entry.hopCount -> true // strictly shorter path
                now - existing.updatedAt > ROUTE_STALE_MS -> true // old route lost its freshness
                else -> false
            }
            if (!accept) return false
            val changed = existing?.entry != candidate
            if (existing == null) {
                table[destinationNodeId] = Stamped(candidate, now)
            } else {
                existing.entry = candidate
                existing.updatedAt = now
            }
            if (changed) publishLocked()
            return changed
        }
    }

    fun getRoute(destinationNodeId: String): RouteEntry? = synchronized(lock) {
        freshRouteLocked(destinationNodeId)
    }

    fun getNextHopEndpoint(destinationNodeId: String): String? = getRoute(destinationNodeId)?.nextHopEndpointId

    fun hopCountTo(destinationNodeId: String): Int? = getRoute(destinationNodeId)?.hopCount

    /** Removes routes that were not refreshed for [ROUTE_TIMEOUT_MS]; direct links never expire. */
    fun purgeExpired() {
        synchronized(lock) {
            val now = clock()
            val removed = table.entries.removeIf { (_, stamped) ->
                !stamped.entry.isDirect && now - stamped.updatedAt > ROUTE_TIMEOUT_MS
            }
            if (removed) publishLocked()
        }
    }

    fun getAllActiveRoutes(): List<RouteEntry> {
        purgeExpired()
        return synchronized(lock) { table.values.map { it.entry }.sortedBy { it.hopCount } }
    }

    /**
     * Forwarding decision for a packet addressed to [finalDestinationId], received on
     * [incomingEndpointId] (null for packets that originate on this node).
     * A packet is never sent back over the link it arrived on.
     */
    fun decideForward(finalDestinationId: String, incomingEndpointId: String?): ForwardDecision {
        synchronized(lock) {
            if (finalDestinationId != MeshPacket.BROADCAST) {
                val route = freshRouteLocked(finalDestinationId)
                if (route != null && route.nextHopEndpointId != incomingEndpointId) {
                    return ForwardDecision.Unicast(route.nextHopEndpointId, route.nextHopNodeId)
                }
            }
            // Broadcast, unknown destination, or a route that would bounce the packet back:
            // controlled flood. TTL (hopCount < maxHops) and packet-id dedup keep it finite.
            val targets = endpointToNode.entries
                .filter { it.key != incomingEndpointId }
                .map { it.key to it.value }
            return if (targets.isEmpty()) ForwardDecision.NoRoute else ForwardDecision.Flood(targets)
        }
    }

    private fun freshRouteLocked(destinationNodeId: String): RouteEntry? {
        val stamped = table[destinationNodeId] ?: return null
        if (!stamped.entry.isDirect && clock() - stamped.updatedAt > ROUTE_TIMEOUT_MS) {
            table.remove(destinationNodeId)
            publishLocked()
            return null
        }
        return stamped.entry
    }

    private fun publishLocked() {
        _routes.value = table.values.map { it.entry }.sortedWith(
            compareBy<RouteEntry> { it.hopCount }.thenBy { it.destinationNodeId }
        )
    }
}
