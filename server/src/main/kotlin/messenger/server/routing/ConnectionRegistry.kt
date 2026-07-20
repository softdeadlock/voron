package messenger.server.routing

import java.util.concurrent.ConcurrentHashMap

/** Tracks currently-online devices by their Noise static public key (hex-encoded). */
class ConnectionRegistry {
    private val connections = ConcurrentHashMap<String, Connection>()

    /**
     * If this device key already has a live connection (e.g. a reconnect racing the old
     * socket's teardown), the replaced [Connection] is closed here rather than just dropped —
     * otherwise its [Connection.outgoing] channel and transport session would leak: nothing
     * else holds a reference to it once the map entry is overwritten, and its underlying
     * socket may never naturally notice the reader is gone.
     */
    fun register(connection: Connection) {
        val previous = connections.put(connection.staticPublicKeyHex, connection)
        if (previous != null && previous !== connection) previous.close()
    }

    fun unregister(connection: Connection) {
        connections.remove(connection.staticPublicKeyHex, connection)
    }

    fun find(staticPublicKeyHex: String): Connection? = connections[staticPublicKeyHex]
}
