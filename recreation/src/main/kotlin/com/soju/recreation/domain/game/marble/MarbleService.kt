package com.soju.recreation.domain.game.marble

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.soju.recreation.domain.game.*
import com.soju.recreation.domain.room.*
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@Service
class MarbleService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val roomService: RoomService,
    private val sseService: SseService,
    private val gameContentRepository: GameContentRepository,
    private val categoryRepository: CategoryRepository
) {
    companion object {
        private const val STATE_KEY = "room:%s:marble:state"
        private const val PENALTY_KEY = "room:%s:marble:penalties"      // 제출된 벌칙
        private const val VOTE_KEY = "room:%s:marble:votes"             // 투표 현황
        private const val SELECTED_KEY = "room:%s:marble:selected"      // 선정된 벌칙
        private const val TTL_HOURS = 6L
        private const val BOARD_SIZE = 28                               // 정사각형 판 (26벌칙 + 2의리주)
        private const val MAX_PENALTIES_PER_PLAYER = 2                  // 인당 벌칙 제출 개수
        private const val TOP_PENALTIES_COUNT = 26                      // 선정할 벌칙 개수
    }

    // ============================================
    // Phase 1: 벌칙 제출
    // ============================================

    /**
     * 벌칙 제출 (인당 2개 제한)
     */
    fun submitPenalty(roomId: String, deviceId: String, penalty: String): PenaltySubmitResult {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다")

        val player = room.players.find { it.deviceId == deviceId }
            ?: throw IllegalArgumentException("플레이어를 찾을 수 없습니다")

        val key = PENALTY_KEY.format(roomId)

        // 현재 제출된 벌칙 조회
        val allPenalties = getAllPenalties(roomId)

        // 해당 플레이어가 제출한 개수 확인
        val playerSubmitCount = allPenalties.count { it.deviceId == deviceId }
        if (playerSubmitCount >= MAX_PENALTIES_PER_PLAYER) {
            throw IllegalArgumentException("이미 ${MAX_PENALTIES_PER_PLAYER}개의 벌칙을 제출했습니다")
        }

        // 벌칙 저장
        val penaltyItem = PenaltyItem(
            id = "${deviceId}_${System.currentTimeMillis()}",
            deviceId = deviceId,
            nickname = player.nickname,
            text = penalty,
            votes = 0
        )
        val json = objectMapper.writeValueAsString(penaltyItem)
        redisTemplate.opsForList().rightPush(key, json)
        redisTemplate.expire(key, TTL_HOURS, TimeUnit.HOURS)

        val totalCount = (redisTemplate.opsForList().size(key) ?: 0).toInt()
        val expectedTotal = room.players.size * MAX_PENALTIES_PER_PLAYER
        val isAllSubmitted = totalCount >= expectedTotal

        // 제출 현황 알림
        sseService.broadcast(roomId, "MARBLE_PENALTY_SUBMITTED", mapOf(
            "from" to player.nickname,
            "totalCount" to totalCount,
            "expectedCount" to expectedTotal,
            "isAllSubmitted" to isAllSubmitted
        ))

        return PenaltySubmitResult(
            submittedCount = playerSubmitCount + 1,
            maxCount = MAX_PENALTIES_PER_PLAYER,
            totalSubmitted = totalCount,
            isAllSubmitted = isAllSubmitted
        )
    }

    /**
     * 제출 현황 조회
     */
    fun getSubmitStatus(roomId: String): PenaltySubmitStatus {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다")

        val allPenalties = getAllPenalties(roomId)
        val expectedTotal = room.players.size * MAX_PENALTIES_PER_PLAYER

        // 플레이어별 제출 현황
        val playerStatus = room.players.map { player ->
            val count = allPenalties.count { it.deviceId == player.deviceId }
            PlayerSubmitStatus(
                nickname = player.nickname,
                deviceId = player.deviceId,
                submittedCount = count,
                isComplete = count >= MAX_PENALTIES_PER_PLAYER
            )
        }

        return PenaltySubmitStatus(
            totalSubmitted = allPenalties.size,
            expectedTotal = expectedTotal,
            isAllSubmitted = allPenalties.size >= expectedTotal,
            players = playerStatus
        )
    }

    // ============================================
    // Phase 2: 벌칙 투표
    // ============================================

    /**
     * 모든 벌칙 목록 조회 (투표용)
     */
    fun getAllPenaltiesForVote(roomId: String): List<PenaltyForVote> {
        val penalties = getAllPenalties(roomId)
        val votes = getVotes(roomId)

        return penalties.map { penalty ->
            val voteCount = votes.count { it.penaltyId == penalty.id }
            PenaltyForVote(
                id = penalty.id,
                text = penalty.text,
                submittedBy = penalty.nickname,
                voteCount = voteCount
            )
        }.sortedByDescending { it.voteCount }
    }

    /**
     * 벌칙 투표 (개수 무제한)
     */
    fun votePenalty(roomId: String, deviceId: String, penaltyId: String): VoteResult {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다")

        val player = room.players.find { it.deviceId == deviceId }
            ?: throw IllegalArgumentException("플레이어를 찾을 수 없습니다")

        val key = VOTE_KEY.format(roomId)

        // 이미 투표했는지 확인
        val existingVotes = getVotes(roomId)
        val alreadyVoted = existingVotes.any { it.deviceId == deviceId && it.penaltyId == penaltyId }

        if (alreadyVoted) {
            // 투표 취소
            val updatedVotes = existingVotes.filter { !(it.deviceId == deviceId && it.penaltyId == penaltyId) }
            saveVotes(roomId, updatedVotes)
        } else {
            // 투표 추가
            val vote = VoteItem(deviceId = deviceId, penaltyId = penaltyId)
            val updatedVotes = existingVotes + vote
            saveVotes(roomId, updatedVotes)
        }

        // 투표 현황 브로드캐스트 (메인 화면용)
        val voteStatus = getVoteStatus(roomId)
        sseService.broadcast(roomId, "MARBLE_VOTE_UPDATE", voteStatus)

        return VoteResult(
            penaltyId = penaltyId,
            voted = !alreadyVoted,
            currentVoteStatus = voteStatus
        )
    }

    /**
     * 투표 현황 조회 (메인 화면용)
     */
    fun getVoteStatus(roomId: String): VoteStatus {
        val penalties = getAllPenalties(roomId)
        val votes = getVotes(roomId)

        val penaltyVotes = penalties.map { penalty ->
            val voteCount = votes.count { it.penaltyId == penalty.id }
            PenaltyVoteInfo(
                id = penalty.id,
                text = penalty.text,
                submittedBy = penalty.nickname,
                voteCount = voteCount
            )
        }.sortedByDescending { it.voteCount }

        return VoteStatus(
            totalVotes = votes.size,
            penalties = penaltyVotes
        )
    }

    /**
     * 투표 완료 - 상위 26개 선정
     */
    fun finishVoting(roomId: String): List<String> {
        val penalties = getAllPenalties(roomId)
        val votes = getVotes(roomId)

        // 투표수 기준 정렬
        val sortedPenalties = penalties.map { penalty ->
            val voteCount = votes.count { it.penaltyId == penalty.id }
            penalty to voteCount
        }.sortedByDescending { it.second }

        // 상위 26개 선정 (부족하면 DB 벌칙으로 채움)
        val selectedTexts = sortedPenalties.take(TOP_PENALTIES_COUNT).map { it.first.text }.toMutableList()

        // 26개 미만이면 DB 벌칙으로 채우기
        if (selectedTexts.size < TOP_PENALTIES_COUNT) {
            val dbPenalties = getDbPenalties()
            val needed = TOP_PENALTIES_COUNT - selectedTexts.size
            selectedTexts.addAll(dbPenalties.shuffled().take(needed))
        }

        // 선정된 벌칙 저장
        val key = SELECTED_KEY.format(roomId)
        redisTemplate.delete(key)
        selectedTexts.forEach { text ->
            redisTemplate.opsForList().rightPush(key, text)
        }
        redisTemplate.expire(key, TTL_HOURS, TimeUnit.HOURS)

        sseService.broadcast(roomId, "MARBLE_VOTE_FINISHED", mapOf(
            "selectedPenalties" to selectedTexts,
            "message" to "투표가 완료되었습니다! 상위 ${selectedTexts.size}개 벌칙이 선정되었습니다."
        ))

        return selectedTexts
    }

    // ============================================
    // Phase 3: 게임 초기화
    // ============================================

    /**
     * 게임 초기화 - 정사각형 게임판 생성
     * 팀은 미리 TeamService로 배정되어 있어야 함
     */
    fun initializeGame(roomId: String): MarbleGameState {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다: $roomId")

        // 팀 배정 확인
        val teams = room.players.mapNotNull { it.team }.distinct()
        if (teams.isEmpty()) {
            throw IllegalArgumentException("팀이 배정되지 않았습니다. 먼저 팀을 나눠주세요.")
        }

        // 선정된 벌칙 가져오기
        val selectedPenalties = getSelectedPenalties(roomId)
        if (selectedPenalties.isEmpty()) {
            throw IllegalArgumentException("선정된 벌칙이 없습니다. 먼저 투표를 완료해주세요.")
        }

        // 게임판 생성
        val board = generateSquareBoard(selectedPenalties)

        // 팀별 시작 위치 초기화
        val positions = teams.associateWith { 0 }.toMutableMap()

        val state = MarbleGameState(
            turnTeam = teams.first(),
            teams = teams.toMutableList(),
            positions = positions,
            board = board
        )

        saveState(roomId, state)

        // Host에게 게임 초기화 완료 알림
        sseService.broadcast(roomId, "MARBLE_INIT", mapOf(
            "board" to board,
            "teams" to room.players.groupBy { it.team },
            "turnOrder" to teams
        ))

        return state
    }

    /**
     * 정사각형 게임판 생성 (28칸)
     * 7번: 의리주 채우기
     * 21번: 의리주 마시기
     * 나머지: 벌칙
     */
    private fun generateSquareBoard(penalties: List<String>): MutableList<BoardCell> {
        val board = mutableListOf<BoardCell>()
        val penaltyQueue = penalties.shuffled().toMutableList()

        for (i in 0 until BOARD_SIZE) {
            val cell = when (i) {
                0 -> BoardCell(i, CellType.START, "출발")
                7 -> BoardCell(i, CellType.UIRIJU_FILL, "의리주 채우기")
                21 -> BoardCell(i, CellType.UIRIJU_DRINK, "의리주 마시기")
                else -> {
                    val penaltyText = if (penaltyQueue.isNotEmpty()) {
                        penaltyQueue.removeAt(0)
                    } else {
                        "소주 한 잔"
                    }
                    BoardCell(i, CellType.PENALTY, penaltyText)
                }
            }
            board.add(cell)
        }

        return board
    }

    // ============================================
    // Phase 4: 게임 진행
    // ============================================

    /**
     * 주사위 굴리기
     */
    fun rollDice(roomId: String, deviceId: String): DiceResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다")

        // 현재 턴인 팀의 플레이어인지 확인
        val player = room.players.find { it.deviceId == deviceId }
            ?: throw IllegalArgumentException("플레이어를 찾을 수 없습니다")

        if (player.team != state.turnTeam) {
            throw IllegalArgumentException("지금은 ${state.turnTeam}팀 차례입니다")
        }

        // 주사위 굴리기 (1~6)
        val diceValue = Random.nextInt(1, 7)
        state.lastDiceValue = diceValue

        // 말 이동 (순환)
        val currentPos = state.positions[state.turnTeam] ?: 0
        val newPos = (currentPos + diceValue) % BOARD_SIZE

        state.positions[state.turnTeam] = newPos

        // 도착한 칸 정보
        val landedCell = state.board.getOrNull(newPos)

        // 턴 넘기기 (다음 팀으로)
        val currentTeamIndex = state.teams.indexOf(state.turnTeam)
        val nextTeamIndex = (currentTeamIndex + 1) % state.teams.size
        state.turnTeam = state.teams[nextTeamIndex]

        saveState(roomId, state)

        val result = DiceResult(
            diceValue = diceValue,
            player = player.nickname,
            team = player.team!!,
            newPosition = newPos,
            cell = landedCell,
            nextTurnTeam = state.turnTeam
        )

        // Host에게 결과 전송
        sseService.broadcast(roomId, "MARBLE_DICE_ROLLED", result)

        return result
    }

    /**
     * 게임 종료
     */
    fun endGame(roomId: String) {
        // 모든 관련 데이터 삭제
        listOf(STATE_KEY, PENALTY_KEY, VOTE_KEY, SELECTED_KEY).forEach { keyPattern ->
            val key = keyPattern.format(roomId)
            redisTemplate.delete(key)
        }

        sseService.broadcast(roomId, "MARBLE_GAME_END", mapOf(
            "message" to "주루마블 게임이 종료되었습니다!"
        ))
    }

    /**
     * 현재 게임 상태 조회
     */
    fun getGameState(roomId: String): MarbleGameState? {
        return getState(roomId)
    }

    // ============================================
    // Redis 헬퍼 메서드
    // ============================================

    private fun getAllPenalties(roomId: String): List<PenaltyItem> {
        val key = PENALTY_KEY.format(roomId)
        val jsonList = redisTemplate.opsForList().range(key, 0, -1) ?: return emptyList()
        return jsonList.map { objectMapper.readValue(it, PenaltyItem::class.java) }
    }

    private fun getVotes(roomId: String): List<VoteItem> {
        val key = VOTE_KEY.format(roomId)
        val json = redisTemplate.opsForValue().get(key) ?: return emptyList()
        return objectMapper.readValue(json)
    }

    private fun saveVotes(roomId: String, votes: List<VoteItem>) {
        val key = VOTE_KEY.format(roomId)
        val json = objectMapper.writeValueAsString(votes)
        redisTemplate.opsForValue().set(key, json)
        redisTemplate.expire(key, TTL_HOURS, TimeUnit.HOURS)
    }

    private fun getSelectedPenalties(roomId: String): List<String> {
        val key = SELECTED_KEY.format(roomId)
        return redisTemplate.opsForList().range(key, 0, -1) ?: emptyList()
    }

    private fun getDbPenalties(): List<String> {
        return try {
            val categories = categoryRepository.findByGameCode(GameCode.MARBLE)
            val penaltyCategory = categories.find { it.name == "기본 벌칙" }
            penaltyCategory?.let {
                gameContentRepository.findByCategory(it).map { c -> c.textContent }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveState(roomId: String, state: MarbleGameState) {
        val key = STATE_KEY.format(roomId)
        val json = objectMapper.writeValueAsString(state)
        redisTemplate.opsForValue().set(key, json)
        redisTemplate.expire(key, TTL_HOURS, TimeUnit.HOURS)
    }

    private fun getState(roomId: String): MarbleGameState? {
        val key = STATE_KEY.format(roomId)
        val json = redisTemplate.opsForValue().get(key) ?: return null
        return objectMapper.readValue(json, MarbleGameState::class.java)
    }
}

// ============================================
// DTOs
// ============================================

data class PenaltyItem(
    val id: String,
    val deviceId: String,
    val nickname: String,
    val text: String,
    val votes: Int = 0
)

data class VoteItem(
    val deviceId: String,
    val penaltyId: String
)

data class PenaltySubmitResult(
    val submittedCount: Int,
    val maxCount: Int,
    val totalSubmitted: Int,
    val isAllSubmitted: Boolean
)

data class PenaltySubmitStatus(
    val totalSubmitted: Int,
    val expectedTotal: Int,
    val isAllSubmitted: Boolean,
    val players: List<PlayerSubmitStatus>
)

data class PlayerSubmitStatus(
    val nickname: String,
    val deviceId: String,
    val submittedCount: Int,
    val isComplete: Boolean
)

data class PenaltyForVote(
    val id: String,
    val text: String,
    val submittedBy: String,
    val voteCount: Int
)

data class VoteResult(
    val penaltyId: String,
    val voted: Boolean,
    val currentVoteStatus: VoteStatus
)

data class VoteStatus(
    val totalVotes: Int,
    val penalties: List<PenaltyVoteInfo>
)

data class PenaltyVoteInfo(
    val id: String,
    val text: String,
    val submittedBy: String,
    val voteCount: Int
)

data class DiceResult(
    val diceValue: Int,
    val player: String,
    val team: String,
    val newPosition: Int,
    val cell: BoardCell?,
    val nextTurnTeam: String
)
