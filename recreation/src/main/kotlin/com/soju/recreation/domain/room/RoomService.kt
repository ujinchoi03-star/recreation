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
    // 방 관리
    // ============================================

    /**
     * 방 생성 (Host 전용)
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
     * 유저 입장 (QR 스캔 후)
     */
    fun joinRoom(roomId: String, nickname: String): Player {
        val room = getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다: $roomId")

        // 중복 닉네임 체크
        if (room.players.any { it.nickname == nickname }) {
            throw IllegalArgumentException("이미 사용 중인 닉네임입니다: $nickname")
        }

        val newPlayer = Player(
            deviceId = UUID.randomUUID().toString(),
            nickname = nickname
        )

        room.players.add(newPlayer)
        saveRoomInfo(room)

        // Host 화면에 알림
        sseService.broadcast(roomId, "PLAYER_JOINED", mapOf(
            "nickname" to newPlayer.nickname,
            "total" to room.players.size
        ))

        return newPlayer
    }

    /**
     * 방 정보 조회
     */
    fun getRoomInfo(roomId: String): RoomInfo? {
        val key = ROOM_INFO_KEY.format(roomId)
        val json = redisTemplate.opsForValue().get(key) ?: return null
        return objectMapper.readValue(json, RoomInfo::class.java)
    }

    /**
     * 방 정보 저장
     */
    fun saveRoomInfo(room: RoomInfo) {
        val key = ROOM_INFO_KEY.format(room.roomId)
        val json = objectMapper.writeValueAsString(room)
        redisTemplate.opsForValue().set(key, json)
        redisTemplate.expire(key, ROOM_TTL_HOURS, TimeUnit.HOURS)
    }

    // ============================================
    // 게임 상태 관리
    // ============================================

    /**
     * 게임 시작
     */
    fun startGame(roomId: String, gameCode: GameCode) {
        val room = getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다: $roomId")

        room.status = RoomStatus.PLAYING
        room.currentGame = gameCode
        saveRoomInfo(room)

        // Host 화면에 게임 시작 알림
        sseService.broadcast(roomId, "GAME_STARTED", mapOf("game" to gameCode.name))
    }

    /**
     * 게임 상태 삭제
     */
    fun deleteGameState(roomId: String) {
        val key = ROOM_STATE_KEY.format(roomId)
        redisTemplate.delete(key)
    }

    // ============================================
    // 유틸리티
    // ============================================

    private fun generateRoomId(): String {
        // 4자리 영숫자 코드 생성 (충돌 시 재생성)
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 혼동되는 문자 제외 (0,O,1,I)
        var roomId: String
        do {
            roomId = (1..4).map { chars.random() }.joinToString("")
        } while (getRoomInfo(roomId) != null)
        return roomId
    }
}
