package com.soju.recreation.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.soju.recreation.domain.room.*
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class AdminService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val sseService: SseService
) {

    /**
     * 모든 활성 방 목록 조회
     */
    fun getAllActiveRooms(): List<RoomSummary> {
        val keys = redisTemplate.keys("room:*:info") ?: return emptyList()

        return keys.mapNotNull { key ->
            try {
                val json = redisTemplate.opsForValue().get(key) ?: return@mapNotNull null
                val room = objectMapper.readValue<RoomInfo>(json)

                // 현재 게임 상태 확인
                val gameState = getCurrentGameState(room.roomId, room.currentGame)

                RoomSummary(
                    roomId = room.roomId,
                    status = room.status,
                    currentGame = room.currentGame,
                    playerCount = room.players.size,
                    players = room.players.map { PlayerSummary(it.nickname, it.team, it.isAlive) },
                    gamePhase = gameState?.phase,
                    createdAt = getTtlRemaining(key)
                )
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.playerCount }
    }

    /**
     * 특정 방의 상세 정보 조회
     */
    fun getRoomDetail(roomId: String): RoomDetail? {
        val roomKey = "room:$roomId:info"
        val roomJson = redisTemplate.opsForValue().get(roomKey) ?: return null
        val room = objectMapper.readValue<RoomInfo>(roomJson)

        // 게임별 상태 조회
        val mafiaState = getGameStateJson(roomId, "state")
        val marbleState = getGameStateJson(roomId, "marble:state")
        val quizState = getGameStateJson(roomId, "quiz:state")
        val truthState = getGameStateJson(roomId, "truth:state")

        return RoomDetail(
            roomInfo = room,
            mafiaState = mafiaState,
            marbleState = marbleState,
            quizState = quizState,
            truthState = truthState,
            ttlSeconds = getTtlRemaining(roomKey)
        )
    }

    /**
     * 강제 페이즈 전환 (마피아)
     */
    fun forceNextPhase(roomId: String): Boolean {
        val key = "room:$roomId:state"
        val json = redisTemplate.opsForValue().get(key) ?: return false

        try {
            val state = objectMapper.readValue<MafiaGameState>(json)
            val nextPhase = getNextMafiaPhase(state.phase)

            state.phase = nextPhase
            state.timerSec = nextPhase.durationSeconds

            val updatedJson = objectMapper.writeValueAsString(state)
            redisTemplate.opsForValue().set(key, updatedJson)

            // SSE로 알림
            sseService.broadcast(roomId, "ADMIN_FORCE_PHASE", mapOf(
                "newPhase" to nextPhase.name,
                "message" to "관리자가 페이즈를 강제 전환했습니다"
            ))

            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 방 강제 삭제
     */
    fun forceDeleteRoom(roomId: String): Boolean {
        val patterns = listOf(
            "room:$roomId:info",
            "room:$roomId:state",
            "room:$roomId:marble:*",
            "room:$roomId:quiz:*",
            "room:$roomId:truth:*"
        )

        patterns.forEach { pattern ->
            val keys = redisTemplate.keys(pattern)
            keys?.forEach { redisTemplate.delete(it) }
        }

        // 단일 키도 삭제
        redisTemplate.delete("room:$roomId:info")
        redisTemplate.delete("room:$roomId:state")

        return true
    }

    /**
     * 서버 통계
     */
    fun getServerStats(): ServerStats {
        val roomKeys = redisTemplate.keys("room:*:info") ?: emptySet()
        val allRooms = getAllActiveRooms()

        val playingRooms = allRooms.count { it.status == RoomStatus.PLAYING }
        val totalPlayers = allRooms.sumOf { it.playerCount }

        val gameDistribution = allRooms
            .filter { it.currentGame != null }
            .groupBy { it.currentGame }
            .mapValues { it.value.size }

        return ServerStats(
            totalRooms = roomKeys.size,
            playingRooms = playingRooms,
            waitingRooms = roomKeys.size - playingRooms,
            totalPlayers = totalPlayers,
            gameDistribution = gameDistribution
        )
    }

    // ============================================
    // Helper Methods
    // ============================================

    private fun getCurrentGameState(roomId: String, gameCode: com.soju.recreation.domain.game.GameCode?): GameStateInfo? {
        if (gameCode == null) return null

        val key = when (gameCode) {
            com.soju.recreation.domain.game.GameCode.MAFIA -> "room:$roomId:state"
            com.soju.recreation.domain.game.GameCode.MARBLE -> "room:$roomId:marble:state"
            com.soju.recreation.domain.game.GameCode.SPEED_QUIZ -> "room:$roomId:quiz:state"
            com.soju.recreation.domain.game.GameCode.TRUTH -> "room:$roomId:truth:state"
        }

        val json = redisTemplate.opsForValue().get(key) ?: return null

        return try {
            val node = objectMapper.readTree(json)
            GameStateInfo(
                phase = node.get("phase")?.asText()
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun getGameStateJson(roomId: String, suffix: String): String? {
        val key = "room:$roomId:$suffix"
        return redisTemplate.opsForValue().get(key)
    }

    private fun getTtlRemaining(key: String): Long {
        return redisTemplate.getExpire(key) ?: -1
    }

    private fun getNextMafiaPhase(current: MafiaPhase): MafiaPhase {
        return when (current) {
            MafiaPhase.NIGHT -> MafiaPhase.DAY_ANNOUNCEMENT
            MafiaPhase.DAY_ANNOUNCEMENT -> MafiaPhase.DAY_DISCUSSION
            MafiaPhase.DAY_DISCUSSION -> MafiaPhase.VOTE
            MafiaPhase.VOTE -> MafiaPhase.VOTE_RESULT
            MafiaPhase.VOTE_RESULT -> MafiaPhase.NIGHT
            MafiaPhase.FINAL_DEFENSE -> MafiaPhase.FINAL_VOTE
            MafiaPhase.FINAL_VOTE -> MafiaPhase.FINAL_VOTE_RESULT
            MafiaPhase.FINAL_VOTE_RESULT -> MafiaPhase.NIGHT
            MafiaPhase.GAME_END -> MafiaPhase.GAME_END
        }
    }
}

// ============================================
// DTOs
// ============================================

data class RoomSummary(
    val roomId: String,
    val status: RoomStatus,
    val currentGame: com.soju.recreation.domain.game.GameCode?,
    val playerCount: Int,
    val players: List<PlayerSummary>,
    val gamePhase: String?,
    val createdAt: Long  // TTL remaining in seconds
)

data class PlayerSummary(
    val nickname: String,
    val team: String?,
    val isAlive: Boolean
)

data class GameStateInfo(
    val phase: String?
)

data class RoomDetail(
    val roomInfo: RoomInfo,
    val mafiaState: String?,
    val marbleState: String?,
    val quizState: String?,
    val truthState: String?,
    val ttlSeconds: Long
)

data class ServerStats(
    val totalRooms: Int,
    val playingRooms: Int,
    val waitingRooms: Int,
    val totalPlayers: Int,
    val gameDistribution: Map<com.soju.recreation.domain.game.GameCode?, Int>
)
