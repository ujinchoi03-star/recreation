package com.soju.recreation.domain.room

import com.fasterxml.jackson.databind.ObjectMapper
import com.soju.recreation.domain.game.GameCode
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.TimeUnit

@Service
class RoomService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val sseService: SseService
) {
    companion object {
        private const val ROOM_INFO_KEY = "room:%s:info"
        private const val ROOM_STATE_KEY = "room:%s:state"
        private const val ROOM_TTL_HOURS = 6L
    }

    // ============================================
    // Room Management
    // ============================================

    /**
     * Create Room (Host only)
     */
    fun createRoom(): RoomInfo {
        val roomId = generateRoomId()
        val hostSessionId = UUID.randomUUID().toString()

        val newRoom = RoomInfo(
            roomId = roomId,
            hostSessionId = hostSessionId
        )
        saveRoomInfo(newRoom)
        return newRoom
    }

    /**
     * Join Room (QR Scan)
     */
    fun joinRoom(roomId: String, nickname: String): Player {
        val room = getRoomInfo(roomId)
            ?: throw IllegalArgumentException("Room not found: $roomId")

        // Check duplicate nickname
        if (room.players.any { it.nickname == nickname }) {
            throw IllegalArgumentException("Nickname already taken: $nickname")
        }

        val newPlayer = Player(
            deviceId = UUID.randomUUID().toString(),
            nickname = nickname
        )

        room.players.add(newPlayer)
        saveRoomInfo(room)

        // Broadcast to Host Screen
        sseService.broadcast(roomId, "PLAYER_JOINED", mapOf(
            "nickname" to newPlayer.nickname,
            "deviceId" to newPlayer.deviceId,
            "total" to room.players.size
        ))

        return newPlayer
    }

    /**
     * Get Room Info
     */
    fun getRoomInfo(roomId: String): RoomInfo? {
        val key = ROOM_INFO_KEY.format(roomId)
        val json = redisTemplate.opsForValue().get(key) ?: return null
        return objectMapper.readValue(json, RoomInfo::class.java)
    }

    /**
     * Save Room Info
     */
    fun saveRoomInfo(room: RoomInfo) {
        val key = ROOM_INFO_KEY.format(room.roomId)
        val json = objectMapper.writeValueAsString(room)
        redisTemplate.opsForValue().set(key, json)
        redisTemplate.expire(key, ROOM_TTL_HOURS, TimeUnit.HOURS)
    }

    // ============================================
    // Game State Management
    // ============================================

    /**
     * Start Game
     */
    fun startGame(roomId: String, gameCode: GameCode) {
        val room = getRoomInfo(roomId)
            ?: throw IllegalArgumentException("Room not found: $roomId")

        room.status = RoomStatus.PLAYING
        room.currentGame = gameCode
        saveRoomInfo(room)

        // Broadcast Game Start to Host
        sseService.broadcast(roomId, "GAME_STARTED", mapOf("game" to gameCode.name))
    }

    /**
     * Delete Game State
     */
    fun deleteGameState(roomId: String) {
        val key = ROOM_STATE_KEY.format(roomId)
        redisTemplate.delete(key)
    }

    // ============================================
    // Utility
    // ============================================

    private fun generateRoomId(): String {
        // Generate 4-char alphanumeric code
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // Exclude confusing chars (0,O,1,I)
        var roomId: String
        do {
            roomId = (1..4).map { chars.random() }.joinToString("")
        } while (getRoomInfo(roomId) != null)
        return roomId
    }
}
