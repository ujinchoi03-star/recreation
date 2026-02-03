package com.soju.recreation.domain.game.liar

import com.fasterxml.jackson.databind.ObjectMapper
import com.soju.recreation.domain.game.CategoryRepository
import com.soju.recreation.domain.game.GameCode
import com.soju.recreation.domain.game.GameContentRepository
import com.soju.recreation.domain.game.GameRepository
import com.soju.recreation.domain.room.LiarGameState
import com.soju.recreation.domain.room.LiarPhase
import com.soju.recreation.domain.room.LiarWinner
import com.soju.recreation.domain.room.RoomService
import com.soju.recreation.domain.room.SseService
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class LiarService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val roomService: RoomService,
    private val sseService: SseService,
    private val timerService: LiarTimerService,
    private val gameRepository: GameRepository,
    private val categoryRepository: CategoryRepository,
    private val gameContentRepository: GameContentRepository
) {
    private val logger = LoggerFactory.getLogger(LiarService::class.java)

    companion object {
        private const val STATE_KEY = "room:%s:liar:state"
        private const val TTL_HOURS = 6L
    }

    // ============================================
    // 카테고리 조회
    // ============================================

    fun getCategories(): List<CategoryInfo> {
        val game = gameRepository.findByCode(GameCode.LIAR)
            ?: return emptyList()

        return categoryRepository.findByGame(game).map { category ->
            CategoryInfo(
                categoryId = category.categoryId!!,
                name = category.name
            )
        }
    }

    // ============================================
    // 게임 초기화
    // ============================================

    fun initializeGame(roomId: String, categoryId: Long): LiarGameState {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다: $roomId")

        val playerCount = room.players.size
        if (playerCount < 3) {
            throw IllegalArgumentException("라이어 게임은 최소 3명이 필요합니다")
        }

        // 카테고리에서 랜덤 제시어 선택
        val category = categoryRepository.findById(categoryId)
            .orElseThrow { IllegalArgumentException("카테고리를 찾을 수 없습니다: $categoryId") }

        val contents = gameContentRepository.findByCategory(category)
        if (contents.isEmpty()) {
            throw IllegalArgumentException("해당 카테고리에 제시어가 없습니다")
        }

        val keyword = contents.random().textContent

        // 랜덤으로 라이어 선정
        val liar = room.players.random()

        // 설명 순서 랜덤 배정
        val shuffledPlayers = room.players.shuffled()

        val state = LiarGameState(
            phase = LiarPhase.ROLE_REVEAL,
            timerSec = LiarPhase.ROLE_REVEAL.durationSeconds,
            keyword = keyword,
            categoryName = category.name,
            liarDeviceId = liar.deviceId,
            explanationOrder = shuffledPlayers.map { it.deviceId }.toMutableList(),
            currentExplainerIndex = 0,
            roundCount = 1,
            hasSecondRound = false
        )

        saveState(roomId, state)

        // Host에게 게임 시작 알림 (제시어는 숨김)
        sseService.broadcast(roomId, "LIAR_INIT", mapOf(
            "phase" to state.phase.name,
            "timer" to state.timerSec,
            "categoryName" to state.categoryName,
            "playerCount" to playerCount,
            "players" to room.players.map { mapOf("nickname" to it.nickname, "deviceId" to it.deviceId) }
        ))

        // 역할 확인 타이머 시작
        startPhaseTimer(roomId, state.phase.durationSeconds)

        return state
    }

    // ============================================
    // 역할 조회 (개인 컨트롤러용)
    // ============================================

    fun getMyRole(roomId: String, deviceId: String): LiarRoleInfo {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        val isLiar = state.liarDeviceId == deviceId

        return LiarRoleInfo(
            isLiar = isLiar,
            keyword = if (isLiar) null else state.keyword,
            categoryName = state.categoryName
        )
    }

    // ============================================
    // 페이즈 전환
    // ============================================

    private fun transitionToExplanation(roomId: String) {
        val state = getState(roomId) ?: return
        val room = roomService.getRoomInfo(roomId) ?: return

        state.phase = LiarPhase.EXPLANATION
        state.timerSec = LiarPhase.EXPLANATION.durationSeconds
        state.currentExplainerIndex = 0

        saveState(roomId, state)

        val currentExplainerDeviceId = state.explanationOrder[state.currentExplainerIndex]
        val currentExplainer = room.players.find { it.deviceId == currentExplainerDeviceId }

        sseService.broadcast(roomId, "LIAR_EXPLANATION_START", mapOf(
            "phase" to state.phase.name,
            "timer" to state.timerSec,
            "currentExplainer" to mapOf(
                "deviceId" to currentExplainerDeviceId,
                "nickname" to (currentExplainer?.nickname ?: "?")
            ),
            "currentIndex" to state.currentExplainerIndex,
            "totalPlayers" to state.explanationOrder.size,
            "roundCount" to state.roundCount,
            "message" to "${currentExplainer?.nickname}님부터 설명하겠습니다"
        ))

        startPhaseTimer(roomId, state.timerSec)
    }

    private fun moveToNextExplainer(roomId: String) {
        val state = getState(roomId) ?: return
        val room = roomService.getRoomInfo(roomId) ?: return

        state.currentExplainerIndex++

        // 모든 참가자가 설명을 완료했는지 확인
        if (state.currentExplainerIndex >= state.explanationOrder.size) {
            // 이미 2라운드였거나 1라운드 종료
            if (state.roundCount >= 2 || state.hasSecondRound) {
                // 바로 지목 시간으로
                transitionToPointing(roomId)
            } else {
                // 한바퀴 더 투표로
                transitionToVoteMoreRound(roomId)
            }
            return
        }

        // 다음 설명자로
        state.timerSec = LiarPhase.EXPLANATION.durationSeconds
        saveState(roomId, state)

        val currentExplainerDeviceId = state.explanationOrder[state.currentExplainerIndex]
        val currentExplainer = room.players.find { it.deviceId == currentExplainerDeviceId }

        sseService.broadcast(roomId, "LIAR_NEXT_EXPLAINER", mapOf(
            "phase" to state.phase.name,
            "timer" to state.timerSec,
            "currentExplainer" to mapOf(
                "deviceId" to currentExplainerDeviceId,
                "nickname" to (currentExplainer?.nickname ?: "?")
            ),
            "currentIndex" to state.currentExplainerIndex,
            "totalPlayers" to state.explanationOrder.size,
            "roundCount" to state.roundCount,
            "message" to "다음은 ${currentExplainer?.nickname}님입니다"
        ))

        startPhaseTimer(roomId, state.timerSec)
    }

    private fun transitionToVoteMoreRound(roomId: String) {
        val state = getState(roomId) ?: return

        state.phase = LiarPhase.VOTE_MORE_ROUND
        state.timerSec = LiarPhase.VOTE_MORE_ROUND.durationSeconds
        state.moreRoundVotes.clear()

        saveState(roomId, state)

        sseService.broadcast(roomId, "LIAR_VOTE_MORE_ROUND", mapOf(
            "phase" to state.phase.name,
            "timer" to state.timerSec,
            "message" to "한바퀴 더 하시겠습니까?"
        ))

        startPhaseTimer(roomId, state.timerSec)
    }

    fun voteMoreRound(roomId: String, deviceId: String, wantMore: Boolean) {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        if (state.phase != LiarPhase.VOTE_MORE_ROUND) {
            throw IllegalArgumentException("투표 시간이 아닙니다")
        }

        val room = roomService.getRoomInfo(roomId)!!
        val player = room.players.find { it.deviceId == deviceId }
            ?: throw IllegalArgumentException("플레이어를 찾을 수 없습니다")

        state.moreRoundVotes[deviceId] = wantMore
        saveState(roomId, state)

        // 실시간 투표 현황 브로드캐스트
        val moreCount = state.moreRoundVotes.count { it.value }
        val stopCount = state.moreRoundVotes.count { !it.value }

        sseService.broadcast(roomId, "LIAR_VOTE_UPDATE", mapOf(
            "voterNickname" to player.nickname,
            "vote" to if (wantMore) "한바퀴 더" else "STOP",
            "moreCount" to moreCount,
            "stopCount" to stopCount,
            "votedCount" to state.moreRoundVotes.size,
            "totalPlayers" to room.players.size
        ))
    }

    private fun processVoteResult(roomId: String) {
        val state = getState(roomId) ?: return

        val moreCount = state.moreRoundVotes.count { it.value }
        val stopCount = state.moreRoundVotes.count { !it.value }

        if (moreCount > stopCount) {
            // 한바퀴 더
            state.roundCount = 2
            state.hasSecondRound = true
            state.currentExplainerIndex = 0

            saveState(roomId, state)

            sseService.broadcast(roomId, "LIAR_VOTE_RESULT", mapOf(
                "result" to "MORE",
                "moreCount" to moreCount,
                "stopCount" to stopCount,
                "message" to "한바퀴 더 진행합니다!"
            ))

            // 약간의 딜레이 후 설명 시작 (비동기)
            timerService.scheduleDelayed(roomId, 2000) {
                transitionToExplanation(roomId)
            }
        } else {
            // STOP - 지목 시간으로
            sseService.broadcast(roomId, "LIAR_VOTE_RESULT", mapOf(
                "result" to "STOP",
                "moreCount" to moreCount,
                "stopCount" to stopCount,
                "message" to "지목 시간으로 넘어갑니다!"
            ))

            timerService.scheduleDelayed(roomId, 2000) {
                transitionToPointing(roomId)
            }
        }
    }

    private fun transitionToPointing(roomId: String) {
        val state = getState(roomId) ?: return

        state.phase = LiarPhase.POINTING
        state.timerSec = 0  // 시간 제한 없음 (토론 시간)

        saveState(roomId, state)

        sseService.broadcast(roomId, "LIAR_POINTING", mapOf(
            "phase" to state.phase.name,
            "message" to "토론 시간입니다. 라이어가 누구인지 토론해주세요!"
        ))
    }

    // ============================================
    // 지목 투표
    // ============================================

    fun startPointingVote(roomId: String) {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        if (state.phase != LiarPhase.POINTING) {
            throw IllegalArgumentException("토론 시간이 아닙니다")
        }

        state.phase = LiarPhase.POINTING_VOTE
        state.timerSec = LiarPhase.POINTING_VOTE.durationSeconds
        state.pointingVotes.clear()

        saveState(roomId, state)

        val room = roomService.getRoomInfo(roomId)!!

        sseService.broadcast(roomId, "LIAR_POINTING_VOTE_START", mapOf(
            "phase" to state.phase.name,
            "timer" to state.timerSec,
            "players" to room.players.map { mapOf("deviceId" to it.deviceId, "nickname" to it.nickname) },
            "message" to "라이어를 지목해주세요!"
        ))

        startPhaseTimer(roomId, state.timerSec)
    }

    fun pointLiar(roomId: String, voterDeviceId: String, targetDeviceId: String) {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        if (state.phase != LiarPhase.POINTING_VOTE) {
            throw IllegalArgumentException("지목 투표 시간이 아닙니다")
        }

        val room = roomService.getRoomInfo(roomId)!!
        val voter = room.players.find { it.deviceId == voterDeviceId }
            ?: throw IllegalArgumentException("투표자를 찾을 수 없습니다")
        val target = room.players.find { it.deviceId == targetDeviceId }
            ?: throw IllegalArgumentException("지목 대상을 찾을 수 없습니다")

        state.pointingVotes[voterDeviceId] = targetDeviceId
        saveState(roomId, state)

        // 투표 현황 브로드캐스트 (누가 누구를 투표했는지 표시)
        val voteCountMap = state.pointingVotes.values.groupingBy { it }.eachCount()

        // 모든 투표 내역 (누가 누구를 투표했는지)
        val voteDetails = state.pointingVotes.map { (voterId, targetId) ->
            val voterPlayer = room.players.find { it.deviceId == voterId }
            val targetPlayer = room.players.find { it.deviceId == targetId }
            mapOf(
                "voterDeviceId" to voterId,
                "voterNickname" to (voterPlayer?.nickname ?: "?"),
                "targetDeviceId" to targetId,
                "targetNickname" to (targetPlayer?.nickname ?: "?")
            )
        }

        sseService.broadcast(roomId, "LIAR_POINTING_VOTE_UPDATE", mapOf(
            "voterNickname" to voter.nickname,
            "targetNickname" to target.nickname,
            "votedCount" to state.pointingVotes.size,
            "totalPlayers" to room.players.size,
            "voteDetails" to voteDetails,  // 누가 누구를 투표했는지 전체 목록
            "voteCounts" to voteCountMap.map { (deviceId, count) ->
                val player = room.players.find { it.deviceId == deviceId }
                mapOf("deviceId" to deviceId, "nickname" to (player?.nickname ?: "?"), "count" to count)
            },
            "message" to "${voter.nickname}님이 ${target.nickname}님을 지목했습니다"
        ))
    }

    private fun processPointingResult(roomId: String) {
        val state = getState(roomId) ?: return
        val room = roomService.getRoomInfo(roomId) ?: return

        // 최다 득표자 찾기
        val voteCountMap = state.pointingVotes.values.groupingBy { it }.eachCount()
        val maxVotes = voteCountMap.values.maxOrNull() ?: 0
        val topVoted = voteCountMap.filter { it.value == maxVotes }.keys.toList()

        // 동점이면 랜덤 선택 (또는 다른 규칙 적용 가능)
        val pointedDeviceId = topVoted.randomOrNull()
        state.pointedDeviceId = pointedDeviceId

        val pointedPlayer = room.players.find { it.deviceId == pointedDeviceId }
        val liar = room.players.find { it.deviceId == state.liarDeviceId }
        val isLiarCaught = pointedDeviceId == state.liarDeviceId

        state.phase = LiarPhase.POINTING_RESULT
        state.timerSec = LiarPhase.POINTING_RESULT.durationSeconds
        saveState(roomId, state)

        // 모든 투표 내역
        val voteDetails = state.pointingVotes.map { (voterId, targetId) ->
            val voterPlayer = room.players.find { it.deviceId == voterId }
            val targetPlayer = room.players.find { it.deviceId == targetId }
            mapOf(
                "voterNickname" to (voterPlayer?.nickname ?: "?"),
                "targetNickname" to (targetPlayer?.nickname ?: "?")
            )
        }

        sseService.broadcast(roomId, "LIAR_POINTING_RESULT", mapOf(
            "phase" to state.phase.name,
            "timer" to state.timerSec,
            "pointedPlayer" to mapOf(
                "deviceId" to pointedDeviceId,
                "nickname" to (pointedPlayer?.nickname ?: "?"),
                "votes" to maxVotes
            ),
            "liar" to mapOf(
                "deviceId" to state.liarDeviceId,
                "nickname" to (liar?.nickname ?: "?")
            ),
            "voteDetails" to voteDetails,
            "voteCounts" to voteCountMap.map { (deviceId, count) ->
                val player = room.players.find { it.deviceId == deviceId }
                mapOf("deviceId" to deviceId, "nickname" to (player?.nickname ?: "?"), "count" to count)
            },
            "isLiarCaught" to isLiarCaught,
            "message" to if (isLiarCaught) {
                "라이어는 ${liar?.nickname}님이었습니다!"
            } else {
                "검거 실패! 라이어 승리!"
            }
        ))

        startPhaseTimer(roomId, state.timerSec)
    }

    private fun transitionAfterPointingResult(roomId: String) {
        val state = getState(roomId) ?: return

        val isLiarCaught = state.pointedDeviceId == state.liarDeviceId

        if (isLiarCaught) {
            // 라이어가 지목됨 → 라이어에게 정답 맞추기 기회
            transitionToLiarGuess(roomId)
        } else {
            // 라이어 지목 실패 → 라이어 승리
            state.winner = LiarWinner.LIAR
            saveState(roomId, state)
            finishGame(roomId)
        }
    }

    // ============================================
    // 라이어 정답 맞추기
    // ============================================

    private fun transitionToLiarGuess(roomId: String) {
        val state = getState(roomId) ?: return
        val room = roomService.getRoomInfo(roomId) ?: return

        state.phase = LiarPhase.LIAR_GUESS
        state.timerSec = LiarPhase.LIAR_GUESS.durationSeconds
        state.liarGuess = null

        saveState(roomId, state)

        val liar = room.players.find { it.deviceId == state.liarDeviceId }

        // 메인화면: "제시어는?", 라이어 컨트롤러: 입력창
        sseService.broadcast(roomId, "LIAR_GUESS_START", mapOf(
            "phase" to state.phase.name,
            "timer" to state.timerSec,
            "liar" to mapOf(
                "deviceId" to state.liarDeviceId,
                "nickname" to (liar?.nickname ?: "?")
            ),
            "categoryName" to state.categoryName,
            "message" to "제시어는?"
        ))

        startPhaseTimer(roomId, state.timerSec)
    }

    fun guessKeyword(roomId: String, deviceId: String, guess: String) {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        if (state.phase != LiarPhase.LIAR_GUESS) {
            throw IllegalArgumentException("정답 맞추기 시간이 아닙니다")
        }

        if (deviceId != state.liarDeviceId) {
            throw IllegalArgumentException("라이어만 정답을 맞출 수 있습니다")
        }

        val room = roomService.getRoomInfo(roomId)!!
        val liar = room.players.find { it.deviceId == state.liarDeviceId }

        state.liarGuess = guess.trim()

        // 정답 체크 (대소문자, 공백 무시)
        val isCorrect = state.liarGuess.equals(state.keyword.trim(), ignoreCase = true)
        state.winner = if (isCorrect) LiarWinner.LIAR else LiarWinner.CITIZEN

        saveState(roomId, state)

        // 라이어가 입력한 제시어 먼저 보여주기
        sseService.broadcast(roomId, "LIAR_GUESS_SUBMITTED", mapOf(
            "liarGuess" to state.liarGuess,
            "liarNickname" to (liar?.nickname ?: "?"),
            "message" to "${liar?.nickname}님이 '${state.liarGuess}'을(를) 입력했습니다"
        ))

        // 타이머 취소하고 바로 결과로
        timerService.cancelTimer(roomId)
        finishGame(roomId)
    }

    fun guessPass(roomId: String, deviceId: String) {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        if (state.phase != LiarPhase.LIAR_GUESS) {
            throw IllegalArgumentException("정답 맞추기 시간이 아닙니다")
        }

        if (deviceId != state.liarDeviceId) {
            throw IllegalArgumentException("라이어만 패스할 수 있습니다")
        }

        val room = roomService.getRoomInfo(roomId)!!
        val liar = room.players.find { it.deviceId == state.liarDeviceId }

        state.liarGuess = "(패스)"
        state.winner = LiarWinner.CITIZEN  // 패스하면 시민 승리

        saveState(roomId, state)

        // 패스했다고 알림
        sseService.broadcast(roomId, "LIAR_GUESS_SUBMITTED", mapOf(
            "liarGuess" to "(패스)",
            "liarNickname" to (liar?.nickname ?: "?"),
            "message" to "${liar?.nickname}님이 패스했습니다"
        ))

        timerService.cancelTimer(roomId)
        finishGame(roomId)
    }

    private fun processLiarGuessTimeout(roomId: String) {
        val state = getState(roomId) ?: return
        val room = roomService.getRoomInfo(roomId) ?: return

        // 시간 초과 → 시민 승리
        if (state.liarGuess == null) {
            val liar = room.players.find { it.deviceId == state.liarDeviceId }

            state.liarGuess = "(시간 초과)"
            state.winner = LiarWinner.CITIZEN
            saveState(roomId, state)

            // 시간 초과 알림
            sseService.broadcast(roomId, "LIAR_GUESS_SUBMITTED", mapOf(
                "liarGuess" to "(시간 초과)",
                "liarNickname" to (liar?.nickname ?: "?"),
                "message" to "시간 초과!"
            ))
        }

        finishGame(roomId)
    }

    // ============================================
    // 게임 종료
    // ============================================

    private fun finishGame(roomId: String) {
        val state = getState(roomId) ?: return
        val room = roomService.getRoomInfo(roomId) ?: return

        state.phase = LiarPhase.GAME_END
        saveState(roomId, state)

        timerService.cleanup(roomId)

        val liar = room.players.find { it.deviceId == state.liarDeviceId }
        val pointedPlayer = room.players.find { it.deviceId == state.pointedDeviceId }
        val isLiarCaught = state.pointedDeviceId == state.liarDeviceId

        // 결과 메시지 결정
        val winnerMessage = when (state.winner) {
            LiarWinner.LIAR -> {
                if (!isLiarCaught) {
                    "검거 실패! 라이어 승리!"
                } else {
                    "정답! 라이어 승리!"
                }
            }
            LiarWinner.CITIZEN -> "오답! 시민 승리!"
            null -> "게임이 종료되었습니다."
        }

        sseService.broadcast(roomId, "LIAR_GAME_END", mapOf(
            "phase" to state.phase.name,
            "keyword" to state.keyword,
            "categoryName" to state.categoryName,
            "liar" to mapOf(
                "nickname" to (liar?.nickname ?: "?"),
                "deviceId" to state.liarDeviceId
            ),
            "pointedPlayer" to if (state.pointedDeviceId != null) mapOf(
                "nickname" to (pointedPlayer?.nickname ?: "?"),
                "deviceId" to state.pointedDeviceId
            ) else null,
            "isLiarCaught" to isLiarCaught,
            "liarGuess" to state.liarGuess,
            "isGuessCorrect" to (state.liarGuess?.equals(state.keyword, ignoreCase = true) ?: false),
            "winner" to state.winner?.name,
            "message" to winnerMessage
        ))
    }

    // ============================================
    // 게임 강제 종료 (호스트용)
    // ============================================

    fun endGame(roomId: String): LiarGameResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다")

        timerService.cleanup(roomId)
        finishGame(roomId)

        val liar = room.players.find { it.deviceId == state.liarDeviceId }
        val pointedPlayer = room.players.find { it.deviceId == state.pointedDeviceId }

        return LiarGameResult(
            keyword = state.keyword,
            categoryName = state.categoryName,
            liarNickname = liar?.nickname ?: "?",
            liarDeviceId = state.liarDeviceId,
            pointedNickname = pointedPlayer?.nickname,
            pointedDeviceId = state.pointedDeviceId,
            liarGuess = state.liarGuess,
            winner = state.winner
        )
    }

    // ============================================
    // 타이머 관리
    // ============================================

    private fun startPhaseTimer(roomId: String, durationSeconds: Int) {
        timerService.startTimer(
            roomId = roomId,
            durationSeconds = durationSeconds,
            onTick = { remaining ->
                sseService.broadcast(roomId, "LIAR_TIMER", mapOf(
                    "remaining" to remaining
                ))
            },
            onComplete = {
                onPhaseComplete(roomId)
            }
        )
    }

    private fun onPhaseComplete(roomId: String) {
        val state = getState(roomId) ?: return
        logger.info("Phase ${state.phase} completed for room $roomId")

        when (state.phase) {
            LiarPhase.ROLE_REVEAL -> transitionToExplanation(roomId)
            LiarPhase.EXPLANATION -> moveToNextExplainer(roomId)
            LiarPhase.VOTE_MORE_ROUND -> processVoteResult(roomId)
            LiarPhase.POINTING -> { /* 시간 제한 없음, startPointingVote로 다음 단계 */ }
            LiarPhase.POINTING_VOTE -> processPointingResult(roomId)
            LiarPhase.POINTING_RESULT -> transitionAfterPointingResult(roomId)
            LiarPhase.LIAR_GUESS -> processLiarGuessTimeout(roomId)
            LiarPhase.GAME_END -> { /* 종료 */ }
        }
    }

    // ============================================
    // 상태 조회
    // ============================================

    fun getGameState(roomId: String): LiarGameState? {
        return getState(roomId)
    }

    fun getCurrentExplainer(roomId: String): CurrentExplainerInfo? {
        val state = getState(roomId) ?: return null
        val room = roomService.getRoomInfo(roomId) ?: return null

        if (state.phase != LiarPhase.EXPLANATION) {
            return null
        }

        val currentDeviceId = state.explanationOrder.getOrNull(state.currentExplainerIndex) ?: return null
        val player = room.players.find { it.deviceId == currentDeviceId }

        return CurrentExplainerInfo(
            deviceId = currentDeviceId,
            nickname = player?.nickname ?: "?",
            currentIndex = state.currentExplainerIndex,
            totalPlayers = state.explanationOrder.size,
            roundCount = state.roundCount
        )
    }

    // ============================================
    // Redis 상태 관리
    // ============================================

    private fun saveState(roomId: String, state: LiarGameState) {
        val key = STATE_KEY.format(roomId)
        val json = objectMapper.writeValueAsString(state)
        redisTemplate.opsForValue().set(key, json)
        redisTemplate.expire(key, TTL_HOURS, TimeUnit.HOURS)
    }

    private fun getState(roomId: String): LiarGameState? {
        val key = STATE_KEY.format(roomId)
        val json = redisTemplate.opsForValue().get(key) ?: return null
        return objectMapper.readValue(json, LiarGameState::class.java)
    }
}

// ============================================
// DTOs
// ============================================

data class CategoryInfo(
    val categoryId: Long,
    val name: String
)

data class LiarRoleInfo(
    val isLiar: Boolean,
    val keyword: String?,  // 라이어는 null
    val categoryName: String
)

data class LiarGameResult(
    val keyword: String,
    val categoryName: String,
    val liarNickname: String,
    val liarDeviceId: String,
    val pointedNickname: String? = null,
    val pointedDeviceId: String? = null,
    val liarGuess: String? = null,
    val winner: LiarWinner? = null
)

data class CurrentExplainerInfo(
    val deviceId: String,
    val nickname: String,
    val currentIndex: Int,
    val totalPlayers: Int,
    val roundCount: Int
)
