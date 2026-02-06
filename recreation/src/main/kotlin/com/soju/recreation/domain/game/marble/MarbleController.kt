package com.soju.recreation.domain.game.marble

import com.soju.recreation.domain.room.ApiResponse
import com.soju.recreation.domain.room.BoardCell
import com.soju.recreation.domain.room.MarbleGameState
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/games/marble")
class MarbleController(
    private val marbleService: MarbleService
) {

    // ============================================
    // Phase 1: 벌칙 제출
    // ============================================

    /**
     * 벌칙 제출 (인당 2개 제한)
     * POST /api/v1/games/marble/penalty/submit
     */
    @PostMapping("/penalty/submit")
    fun submitPenalty(@RequestBody request: PenaltySubmitRequest): ApiResponse<PenaltySubmitResult> {
        val result = marbleService.submitPenalty(request.roomId, request.deviceId, request.text)
        return ApiResponse.success(result)
    }

    /**
     * 제출 현황 조회 (메인 화면용)
     * GET /api/v1/games/marble/penalty/status/{roomId}
     */
    @GetMapping("/penalty/status/{roomId}")
    fun getSubmitStatus(@PathVariable roomId: String): ApiResponse<PenaltySubmitStatus> {
        val status = marbleService.getSubmitStatus(roomId)
        return ApiResponse.success(status)
    }

    // ============================================
    // Phase 2: 벌칙 투표
    // ============================================

    /**
     * 모든 벌칙 목록 조회 (투표용 - 참가자 폰)
     * GET /api/v1/games/marble/vote/penalties/{roomId}
     */
    @GetMapping("/vote/penalties/{roomId}")
    fun getPenaltiesForVote(@PathVariable roomId: String): ApiResponse<List<PenaltyForVote>> {
        val penalties = marbleService.getAllPenaltiesForVote(roomId)
        return ApiResponse.success(penalties)
    }

    /**
     * 벌칙 투표 (토글 - 다시 누르면 취소)
     * POST /api/v1/games/marble/vote
     */
    @PostMapping("/vote")
    fun votePenalty(@RequestBody request: VoteRequest): ApiResponse<VoteResult> {
        val result = marbleService.votePenalty(request.roomId, request.deviceId, request.penaltyId)
        return ApiResponse.success(result)
    }

    /**
     * 투표 현황 조회 (메인 화면용)
     * GET /api/v1/games/marble/vote/status/{roomId}
     */
    @GetMapping("/vote/status/{roomId}")
    fun getVoteStatus(@PathVariable roomId: String): ApiResponse<VoteStatus> {
        val status = marbleService.getVoteStatus(roomId)
        return ApiResponse.success(status)
    }

    /**
     * 플레이어 개인 투표 완료
     * POST /api/v1/games/marble/vote/done
     */
    @PostMapping("/vote/done")
    fun playerVoteDone(@RequestBody request: MarbleRollRequest): ApiResponse<Unit> {
        marbleService.playerVoteDone(request.roomId, request.deviceId)
        return ApiResponse.success(Unit)
    }

    /**
     * 투표 완료 - 상위 26개 선정
     * POST /api/v1/games/marble/vote/finish
     */
    @PostMapping("/vote/finish")
    fun finishVoting(@RequestBody request: MarbleRoomRequest): ApiResponse<List<String>> {
        val selectedPenalties = marbleService.finishVoting(request.roomId)
        return ApiResponse.success(selectedPenalties)
    }

    // ============================================
    // Phase 2.5: 모드 선택 (팀전 / 개인전)
    // ============================================

    /**
     * 게임 모드 선택 (팀전 / 개인전)
     * POST /api/v1/games/marble/mode/select
     */
    @PostMapping("/mode/select")
    fun selectGameMode(@RequestBody request: GameModeSelectRequest): ApiResponse<ModeSelectResult> {
        val result = marbleService.selectGameMode(request.roomId, request.mode)
        return ApiResponse.success(result)
    }

    // ============================================
    // Phase 3: 게임 시작
    // ============================================

    /**
     * 주루마블 게임 초기화
     * 팀전: 팀은 미리 /api/v1/teams 로 배정되어 있어야 함
     * 개인전: selectGameMode에서 자동으로 순서 정해짐
     * POST /api/v1/games/marble/init
     */
    @PostMapping("/init")
    fun initializeGame(@RequestBody request: MarbleRoomRequest): ApiResponse<MarbleGameState> {
        val state = marbleService.initializeGame(request.roomId)
        return ApiResponse.success(state)
    }

    // ============================================
    // Phase 4: 게임 진행
    // ============================================

    /**
     * 주사위 굴리기
     * POST /api/v1/games/marble/roll
     */
    @PostMapping("/roll")
    fun rollDice(@RequestBody request: MarbleRollRequest): ApiResponse<DiceResult> {
        val result = marbleService.rollDice(request.roomId, request.deviceId)
        return ApiResponse.success(result)
    }

    /**
     * 현재 게임 상태 조회
     * GET /api/v1/games/marble/state/{roomId}
     */
    @GetMapping("/state/{roomId}")
    fun getGameState(@PathVariable roomId: String): ApiResponse<MarbleGameState> {
        val state = marbleService.getGameState(roomId)
            ?: return ApiResponse.error("게임이 시작되지 않았습니다")
        return ApiResponse.success(state)
    }

    /**
     * 게임 종료
     * POST /api/v1/games/marble/end
     */
    @PostMapping("/end")
    fun endGame(@RequestBody request: MarbleRoomRequest): ApiResponse<Unit> {
        marbleService.endGame(request.roomId)
        return ApiResponse.success(Unit)
    }
}

// ============================================
// Request DTOs
// ============================================

data class MarbleRoomRequest(
    val roomId: String
)

data class MarbleRollRequest(
    val roomId: String,
    val deviceId: String
)

data class PenaltySubmitRequest(
    val roomId: String,
    val deviceId: String,
    val text: String
)

data class VoteRequest(
    val roomId: String,
    val deviceId: String,
    val penaltyId: String
)

data class GameModeSelectRequest(
    val roomId: String,
    val mode: String  // "TEAM" 또는 "SOLO"
)
