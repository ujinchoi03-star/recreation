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
        private const val VOTE_DONE_KEY = "room:%s:marble:vote_done"    // 투표 완료한 플레이어
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
     * 플레이어 개인 투표 완료 신호
     */
    fun playerVoteDone(roomId: String, deviceId: String) {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다")
        val player = room.players.find { it.deviceId == deviceId }
            ?: throw IllegalArgumentException("플레이어를 찾을 수 없습니다")

        val key = VOTE_DONE_KEY.format(roomId)
        redisTemplate.opsForSet().add(key, deviceId)
        redisTemplate.expire(key, TTL_HOURS, TimeUnit.HOURS)

        val doneCount = (redisTemplate.opsForSet().size(key) ?: 0).toInt()
        val totalPlayers = room.players.size

        sseService.broadcast(roomId, "MARBLE_PLAYER_VOTE_DONE", mapOf(
            "nickname" to player.nickname,
            "doneCount" to doneCount,
            "totalPlayers" to totalPlayers
        ))
    }

    /**
     * 투표 완료 - 상위 26개 선정
     */
    fun finishVoting(roomId: String): List<String> {
        val penalties = getAllPenalties(roomId)
        val votes = getVotes(roomId)

        // 투표수 기준으로 그룹화
        val penaltyWithVotes = penalties.map { penalty ->
            val voteCount = votes.count { it.penaltyId == penalty.id }
            penalty to voteCount
        }

        // 투표수별로 그룹화
        val groupedByVotes = penaltyWithVotes.groupBy { it.second }
            .toSortedMap(compareByDescending { it }) // 투표수 내림차순

        // 상위 26개 선정 (동률은 랜덤)
        val selectedTexts = mutableListOf<String>()
        for ((voteCount, group) in groupedByVotes) {
            val shuffledGroup = group.shuffled() // 동률인 것들 중 랜덤
            val remaining = TOP_PENALTIES_COUNT - selectedTexts.size

            if (remaining <= 0) break

            val toAdd = shuffledGroup.take(remaining).map { it.first.text }
            selectedTexts.addAll(toAdd)
        }

        // 26개 미만이면 DB 벌칙 또는 기본 벌칙으로 채우기
        if (selectedTexts.size < TOP_PENALTIES_COUNT) {
            val dbPenalties = getDbPenalties()
            val fallbackPenalties = if (dbPenalties.isNotEmpty()) {
                dbPenalties
            } else {
                // DB에 벌칙이 없으면 기본 벌칙 사용
                getDefaultPenalties()
            }

            val needed = TOP_PENALTIES_COUNT - selectedTexts.size
            selectedTexts.addAll(fallbackPenalties.shuffled().take(needed))
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
    // Phase 2.5: 모드 선택 (팀전 / 개인전)
    // ============================================

    /**
     * 게임 모드 선택
     */
    fun selectGameMode(roomId: String, modeStr: String): ModeSelectResult {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다: $roomId")

        val mode = when (modeStr.uppercase()) {
            "TEAM" -> GameMode.TEAM
            "SOLO" -> GameMode.SOLO
            else -> throw IllegalArgumentException("잘못된 모드: $modeStr (TEAM 또는 SOLO만 가능)")
        }

        val selectedPenalties = getSelectedPenalties(roomId)
        if (selectedPenalties.isEmpty()) {
            throw IllegalArgumentException("선정된 벌칙이 없습니다. 먼저 투표를 완료해주세요.")
        }

        val state = when (mode) {
            GameMode.TEAM -> {
                // 팀전: 팀 배정 확인
                val teams = room.players.mapNotNull { it.team }.distinct()
                if (teams.isEmpty()) {
                    throw IllegalArgumentException("팀이 배정되지 않았습니다. 팀전을 선택하려면 먼저 팀을 나눠주세요.")
                }

                MarbleGameState(
                    mode = GameMode.TEAM,
                    teams = teams.toMutableList(),
                    turnTeam = teams.first(),
                    positions = teams.associateWith { 0 }.toMutableMap(),
                    board = generateSquareBoard(selectedPenalties)
                )
            }
            GameMode.SOLO -> {
                // 개인전: 플레이어 순서 랜덤 셔플
                val shuffledPlayers = room.players.shuffled()
                val turnOrder = shuffledPlayers.map { it.deviceId }.toMutableList()
                val playerPositions = shuffledPlayers.associate { it.deviceId to 0 }.toMutableMap()

                MarbleGameState(
                    mode = GameMode.SOLO,
                    turnOrder = turnOrder,
                    currentTurnIndex = 0,
                    playerPositions = playerPositions,
                    board = generateSquareBoard(selectedPenalties)
                )
            }
        }

        saveState(roomId, state)

        // 브로드캐스트
        sseService.broadcast(roomId, "MARBLE_MODE_SELECTED", mapOf(
            "mode" to mode.name,
            "board" to state.board,
            "turnOrder" to (if (mode == GameMode.SOLO) state.turnOrder else emptyList()),
            "teams" to (if (mode == GameMode.TEAM) state.teams else emptyList())
        ))

        return ModeSelectResult(
            mode = mode.name,
            turnOrder = if (mode == GameMode.SOLO) state.turnOrder else null,
            teams = if (mode == GameMode.TEAM) state.teams else null
        )
    }

    // ============================================
    // Phase 3: 게임 초기화
    // ============================================

    /**
     * 게임 초기화 - 정사각형 게임판 생성
     * 팀전: 팀은 미리 TeamService로 배정되어 있어야 함
     * 개인전: selectGameMode에서 이미 초기화됨
     */
    fun initializeGame(roomId: String): MarbleGameState {
        // 이미 게임 상태가 있으면 반환 (개인전 모드 선택 시 이미 초기화됨)
        val existingState = getState(roomId)
        if (existingState != null) {
            // Host에게 게임 초기화 완료 알림
            sseService.broadcast(roomId, "MARBLE_INIT", mapOf(
                "mode" to existingState.mode.name,
                "board" to existingState.board,
                "turnOrder" to (if (existingState.mode == GameMode.SOLO) existingState.turnOrder else existingState.teams)
            ))
            return existingState
        }

        // 팀전 모드로 초기화 (기존 로직)
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
            mode = GameMode.TEAM,
            turnTeam = teams.first(),
            teams = teams.toMutableList(),
            positions = positions,
            board = board
        )

        saveState(roomId, state)

        // Host에게 게임 초기화 완료 알림
        sseService.broadcast(roomId, "MARBLE_INIT", mapOf(
            "mode" to "TEAM",
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
     * 주사위 굴리기 (팀전 / 개인전 분기)
     */
    fun rollDice(roomId: String, deviceId: String): DiceResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다")

        val player = room.players.find { it.deviceId == deviceId }
            ?: throw IllegalArgumentException("플레이어를 찾을 수 없습니다")

        return when (state.mode) {
            GameMode.TEAM -> rollDiceTeamMode(roomId, state, room, player)
            GameMode.SOLO -> rollDiceSoloMode(roomId, state, room, player)
        }
    }

    /**
     * 팀전 모드 주사위 굴리기
     */
    private fun rollDiceTeamMode(roomId: String, state: MarbleGameState, room: RoomInfo, player: Player): DiceResult {
        // 현재 턴인 팀의 플레이어인지 확인
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

        // 현재 팀의 모든 플레이어 deviceId 목록
        val currentTeamPlayers = room.players.filter { it.team == player.team }
        val currentTeamDeviceIds = currentTeamPlayers.map { it.deviceId }

        // 턴 넘기기 (다음 팀으로)
        val currentTeamIndex = state.teams.indexOf(state.turnTeam)
        val nextTeamIndex = (currentTeamIndex + 1) % state.teams.size
        state.turnTeam = state.teams[nextTeamIndex]

        // 다음 팀의 첫 번째 플레이어 찾기
        val nextTeamPlayers = room.players.filter { it.team == state.turnTeam }
        val nextTurnDeviceIds = nextTeamPlayers.map { it.deviceId }

        saveState(roomId, state)

        val result = DiceResult(
            mode = "TEAM",
            diceValue = diceValue,
            player = player.nickname,
            deviceId = player.deviceId,
            team = player.team,
            teamDeviceIds = currentTeamDeviceIds,
            newPosition = newPos,
            cell = landedCell,
            nextTurnTeam = state.turnTeam,
            nextTurnDeviceIds = nextTurnDeviceIds,
            nextTurnDeviceId = nextTurnDeviceIds.firstOrNull()
        )

        // Host에게 결과 전송
        sseService.broadcast(roomId, "MARBLE_DICE_ROLLED", result)

        // 턴 변경 알림 (플레이어 페이지용)
        sseService.broadcast(roomId, "MARBLE_TURN_CHANGE", mapOf(
            "currentDeviceId" to (nextTurnDeviceIds.firstOrNull() ?: ""),
            "currentTeam" to state.turnTeam,
            "teamDeviceIds" to nextTurnDeviceIds
        ))

        return result
    }

    /**
     * 개인전 모드 주사위 굴리기
     */
    private fun rollDiceSoloMode(roomId: String, state: MarbleGameState, room: RoomInfo, player: Player): DiceResult {
        // 현재 턴인 플레이어인지 확인
        val currentTurnDeviceId = state.turnOrder.getOrNull(state.currentTurnIndex)
            ?: throw IllegalStateException("턴 순서가 올바르지 않습니다")

        if (player.deviceId != currentTurnDeviceId) {
            val currentPlayer = room.players.find { it.deviceId == currentTurnDeviceId }
            throw IllegalArgumentException("지금은 ${currentPlayer?.nickname ?: "다른 사람"} 차례입니다")
        }

        // 주사위 굴리기 (1~6)
        val diceValue = Random.nextInt(1, 7)
        state.lastDiceValue = diceValue

        // 말 이동 (순환)
        val currentPos = state.playerPositions[player.deviceId] ?: 0
        val newPos = (currentPos + diceValue) % BOARD_SIZE
        state.playerPositions[player.deviceId] = newPos

        // 도착한 칸 정보
        val landedCell = state.board.getOrNull(newPos)

        // 턴 넘기기 (다음 플레이어로)
        state.currentTurnIndex = (state.currentTurnIndex + 1) % state.turnOrder.size
        val nextTurnDeviceId = state.turnOrder[state.currentTurnIndex]

        saveState(roomId, state)

        val result = DiceResult(
            mode = "SOLO",
            diceValue = diceValue,
            player = player.nickname,
            deviceId = player.deviceId,
            team = null,
            teamDeviceIds = listOf(player.deviceId),  // 개인전에서는 본인만
            newPosition = newPos,
            cell = landedCell,
            nextTurnTeam = null,
            nextTurnDeviceIds = listOf(nextTurnDeviceId),
            nextTurnDeviceId = nextTurnDeviceId
        )

        // Host에게 결과 전송
        sseService.broadcast(roomId, "MARBLE_DICE_ROLLED", result)

        // 턴 변경 알림 (플레이어 페이지용)
        sseService.broadcast(roomId, "MARBLE_TURN_CHANGE", mapOf(
            "currentDeviceId" to nextTurnDeviceId,
            "currentTeam" to null,
            "teamDeviceIds" to listOf(nextTurnDeviceId)
        ))

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

    /**
     * 기본 벌칙 리스트 (DB에 벌칙이 없을 때 사용)
     */
    private fun getDefaultPenalties(): List<String> {
        return listOf(
            "소주 1잔",
            "소주 2잔",
            "폭탄주 만들어 마시기",
            "애교 부리기",
            "랜덤 댄스",
            "노래 한 소절 부르기",
            "박수 10번 치기",
            "윙크하기",
            "팔굽혀펴기 5개",
            "스쿼트 10개",
            "자기소개 다시하기",
            "좋아하는 사람 이름대기",
            "첫사랑 이야기",
            "최근 고민 말하기",
            "가장 웃긴 일 말하기",
            "셀카 찍어서 단톡방에 올리기",
            "폰 배경화면 보여주기",
            "최근 검색어 공개",
            "갤러리 랜덤 사진 보여주기",
            "목소리 톤 바꿔서 말하기",
            "사투리로 말하기",
            "존댓말로만 말하기",
            "눈 감고 코 만지기",
            "혀 내밀고 코 닿게 하기",
            "한 손으로 박수치기",
            "로봇춤 추기",
            "좌우 팔 번갈아 돌리기",
            "머리에 휴지 올리고 10초",
            "옆 사람과 하이파이브 10번"
        )
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
    val mode: String,             // 게임 모드 ("TEAM" / "SOLO")
    val diceValue: Int,           // 주사위 값 (1-6)
    val player: String,           // 주사위 굴린 사람 nickname
    val deviceId: String,         // 주사위 굴린 사람 deviceId
    val team: String?,            // [팀전] 현재 팀 (개인전은 null)
    val teamDeviceIds: List<String>, // [팀전] 팀원 전체 / [개인전] 본인만
    val newPosition: Int,         // 이동한 위치
    val cell: BoardCell?,         // 도착한 칸 정보
    val nextTurnTeam: String?,    // [팀전] 다음 턴 팀 (개인전은 null)
    val nextTurnDeviceIds: List<String>, // 다음 턴 플레이어 deviceId 목록
    val nextTurnDeviceId: String? // [공통] 다음 턴 첫 번째 플레이어 deviceId
)

data class ModeSelectResult(
    val mode: String,              // "TEAM" 또는 "SOLO"
    val turnOrder: List<String>?,  // [개인전] 플레이어 순서 (deviceId)
    val teams: List<String>?       // [팀전] 팀 목록
)
