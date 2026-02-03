package com.soju.recreation.domain.game.liar

import com.soju.recreation.domain.room.ApiResponse
import com.soju.recreation.domain.room.LiarGameState
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/games/liar")
class LiarController(
    private val liarService: LiarService
) {

    // ============================================
    // 카테고리 조회
    // ============================================

    /**
     * 라이어 게임 카테고리 목록 조회
     * GET /api/v1/games/liar/categories
     */
    @GetMapping("/categories")
    fun getCategories(): ApiResponse<List<CategoryInfo>> {
        val categories = liarService.getCategories()
        return ApiResponse.success(categories)
    }

    // ============================================
    // 게임 시작
    // ============================================

    /**
     * 라이어 게임 초기화 (역할 배정 + 역할 확인 시작)
     * POST /api/v1/games/liar/init
     */
    @PostMapping("/init")
    fun initializeGame(@RequestBody request: LiarInitRequest): ApiResponse<LiarGameState> {
        val state = liarService.initializeGame(request.roomId, request.categoryId)
        return ApiResponse.success(state)
    }

    /**
     * 내 역할 조회 (개인 컨트롤러용)
     * GET /api/v1/games/liar/role?roomId={roomId}&deviceId={deviceId}
     */
    @GetMapping("/role")
    fun getMyRole(
        @RequestParam roomId: String,
        @RequestParam deviceId: String
    ): ApiResponse<LiarRoleInfo> {
        val roleInfo = liarService.getMyRole(roomId, deviceId)
        return ApiResponse.success(roleInfo)
    }

    // ============================================
    // 투표
    // ============================================

    /**
     * 한바퀴 더 투표
     * POST /api/v1/games/liar/vote-more
     */
    @PostMapping("/vote-more")
    fun voteMoreRound(@RequestBody request: VoteMoreRequest): ApiResponse<Unit> {
        liarService.voteMoreRound(request.roomId, request.deviceId, request.wantMore)
        return ApiResponse.success(Unit)
    }

    // ============================================
    // 지목 투표
    // ============================================

    /**
     * 지목 투표 시작 (토론 후 호스트가 호출)
     * POST /api/v1/games/liar/start-vote
     */
    @PostMapping("/start-vote")
    fun startPointingVote(@RequestBody request: LiarRoomRequest): ApiResponse<Unit> {
        liarService.startPointingVote(request.roomId)
        return ApiResponse.success(Unit)
    }

    /**
     * 라이어 지목 투표
     * POST /api/v1/games/liar/point
     */
    @PostMapping("/point")
    fun pointLiar(@RequestBody request: PointLiarRequest): ApiResponse<Unit> {
        liarService.pointLiar(request.roomId, request.voterDeviceId, request.targetDeviceId)
        return ApiResponse.success(Unit)
    }

    // ============================================
    // 라이어 정답 맞추기
    // ============================================

    /**
     * 라이어가 정답 맞추기
     * POST /api/v1/games/liar/guess
     */
    @PostMapping("/guess")
    fun guessKeyword(@RequestBody request: GuessKeywordRequest): ApiResponse<Unit> {
        liarService.guessKeyword(request.roomId, request.deviceId, request.guess)
        return ApiResponse.success(Unit)
    }

    /**
     * 라이어가 정답 맞추기 패스 (모르겠음)
     * POST /api/v1/games/liar/guess-pass
     */
    @PostMapping("/guess-pass")
    fun guessPass(@RequestBody request: GuessPassRequest): ApiResponse<Unit> {
        liarService.guessPass(request.roomId, request.deviceId)
        return ApiResponse.success(Unit)
    }

    // ============================================
    // 게임 종료
    // ============================================

    /**
     * 게임 강제 종료
     * POST /api/v1/games/liar/end
     */
    @PostMapping("/end")
    fun endGame(@RequestBody request: LiarRoomRequest): ApiResponse<LiarGameResult> {
        val result = liarService.endGame(request.roomId)
        return ApiResponse.success(result)
    }

    // ============================================
    // 상태 조회
    // ============================================

    /**
     * 현재 게임 상태 조회
     * GET /api/v1/games/liar/state/{roomId}
     */
    @GetMapping("/state/{roomId}")
    fun getGameState(@PathVariable roomId: String): ApiResponse<LiarGameState> {
        val state = liarService.getGameState(roomId)
            ?: return ApiResponse.error("게임이 시작되지 않았습니다")
        return ApiResponse.success(state)
    }

    /**
     * 현재 설명자 정보 조회
     * GET /api/v1/games/liar/current-explainer/{roomId}
     */
    @GetMapping("/current-explainer/{roomId}")
    fun getCurrentExplainer(@PathVariable roomId: String): ApiResponse<CurrentExplainerInfo> {
        val info = liarService.getCurrentExplainer(roomId)
            ?: return ApiResponse.error("설명 시간이 아닙니다")
        return ApiResponse.success(info)
    }
}

// ============================================
// Request DTOs
// ============================================

data class LiarRoomRequest(
    val roomId: String
)

data class LiarInitRequest(
    val roomId: String,
    val categoryId: Long
)

data class VoteMoreRequest(
    val roomId: String,
    val deviceId: String,
    val wantMore: Boolean  // true = 한바퀴 더, false = STOP
)

data class PointLiarRequest(
    val roomId: String,
    val voterDeviceId: String,
    val targetDeviceId: String  // 지목할 플레이어의 deviceId
)

data class GuessKeywordRequest(
    val roomId: String,
    val deviceId: String,
    val guess: String  // 라이어가 추측한 정답
)

data class GuessPassRequest(
    val roomId: String,
    val deviceId: String
)
