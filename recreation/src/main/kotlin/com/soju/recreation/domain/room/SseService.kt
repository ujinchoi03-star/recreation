package com.soju.recreation.domain.room

import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap

@Service
class SseService {
    // Room ID(Key) -> Emitter(Value) (For Host)
    private val emitters = ConcurrentHashMap<String, SseEmitter>()

    // Player Emitters: roomId -> (deviceId -> emitter)
    private val playerEmitters = ConcurrentHashMap<String, ConcurrentHashMap<String, SseEmitter>>()

    // 1. Host Connects
    fun connect(roomId: String): SseEmitter {
        // Timeout: 1 hour
        val emitter = SseEmitter(60 * 60 * 1000L)

        emitters[roomId] = emitter

        // Remove on completion/timeout
        emitter.onCompletion { emitters.remove(roomId) }
        emitter.onTimeout { emitters.remove(roomId) }

        // Send "connected" event
        try {
            emitter.send(SseEmitter.event().name("CONNECT").data("connected"))
        } catch (e: Exception) {
            emitters.remove(roomId)
        }

        return emitter
    }

    // 2. Broadcast to specific room (Host)
    fun broadcast(roomId: String, eventName: String, data: Any) {
        val emitter = emitters[roomId]
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data))
            } catch (e: Exception) {
                emitters.remove(roomId)
            }
        }
    }

    // 3. Player Connects
    fun connectPlayer(roomId: String, deviceId: String): SseEmitter {
        val emitter = SseEmitter(60 * 60 * 1000L)

        playerEmitters.computeIfAbsent(roomId) { ConcurrentHashMap() }[deviceId] = emitter

        emitter.onCompletion { playerEmitters[roomId]?.remove(deviceId) }
        emitter.onTimeout { playerEmitters[roomId]?.remove(deviceId) }

        try {
            emitter.send(SseEmitter.event().name("CONNECT").data("connected"))
        } catch (e: Exception) {
            playerEmitters[roomId]?.remove(deviceId)
        }

        return emitter
    }

    // 4. Broadcast to all players in room
    fun broadcastToPlayers(roomId: String, eventName: String, data: Any) {
        playerEmitters[roomId]?.forEach { (deviceId, emitter) ->
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data))
            } catch (e: Exception) {
                playerEmitters[roomId]?.remove(deviceId)
            }
        }
    }

    // 5. Broadcast to Host AND All Players
    fun broadcastToAll(roomId: String, eventName: String, data: Any) {
        broadcast(roomId, eventName, data)
        broadcastToPlayers(roomId, eventName, data)
    }
}