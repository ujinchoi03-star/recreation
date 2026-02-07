package com.soju.recreation.domain.game.quiz

import com.soju.recreation.domain.room.ApiResponse
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/games/quiz")
class SpeedQuizController(
    private val speedQuizService: SpeedQuizService
) {

    // ============================================
    // 카테고리 조회
    // ============================================

    /**
     * 사용 가능한 카테고리 목록 조회
     * GET /api/v1/games/quiz/categories
     */
    @GetMapping("/categories")
    fun getCategories(): ApiResponse<List<CategoryInfo>> {
        val categories = speedQuizService.getCategories()
        return ApiResponse.success(categories)
    }

    // ============================================
    // 게임 설정 및 시작
    // ============================================

    /**
     * 게임 초기화 (팀 확인 + 시간 설정)
     * 팀은 미리 /api/v1/teams 로 배정되어 있어야 함
     * POST /api/v1/games/quiz/init
     */
    @PostMapping("/init")
    fun initializeGame(@RequestBody request: QuizInitRequest): ApiResponse<SpeedQuizState> {
        val state = speedQuizService.initializeGame(
            roomId = request.roomId,
            roundTimeSeconds = request.roundTimeSeconds
        )
        return ApiResponse.success(state)
    }

    /**
     * 라운드 시작 (카테고리 선택)
     * POST /api/v1/games/quiz/start
     */
    @PostMapping("/start")
    fun startRound(@RequestBody request: RoundStartRequest): ApiResponse<RoundStartResult> {
        val result = speedQuizService.startRound(request.roomId, request.categoryId)
        return ApiResponse.success(result)
    }

    // ============================================
    // 게임 진행
    // ============================================

    /**
     * 정답 처리 (다음 문제 + 점수 증가)
     * POST /api/v1/games/quiz/correct
     */
    @PostMapping("/correct")
    fun correct(@RequestBody request: QuizRoomRequest): ApiResponse<QuizResult> {
        val result = speedQuizService.correct(request.roomId)
        return ApiResponse.success(result)
    }

    /**
     * 패스 (문제 건너뛰기)
     * POST /api/v1/games/quiz/pass
     */
    @PostMapping("/pass")
    fun pass(@RequestBody request: QuizRoomRequest): ApiResponse<QuizResult> {
        val result = speedQuizService.pass(request.roomId)
        return ApiResponse.success(result)
    }

    // ============================================
    // 라운드/팀 전환
    // ============================================

    /**
     * 라운드 종료 (수동)
     * POST /api/v1/games/quiz/end-round
     */
    @PostMapping("/end-round")
    fun endRound(@RequestBody request: QuizRoomRequest): ApiResponse<RoundEndResult> {
        val result = speedQuizService.endRound(request.roomId)
        return ApiResponse.success(result)
    }

    /**
     * 다음 팀으로
     * POST /api/v1/games/quiz/next-team
     */
    @PostMapping("/next-team")
    fun nextTeam(@RequestBody request: QuizRoomRequest): ApiResponse<NextTeamResult> {
        val result = speedQuizService.nextTeam(request.roomId)
        return ApiResponse.success(result)
    }

    // ============================================
    // 순위 및 종료
    // ============================================

    /**
     * 최종 순위 조회
     * GET /api/v1/games/quiz/ranking/{roomId}
     */
    @GetMapping("/ranking/{roomId}")
    fun getFinalRanking(@PathVariable roomId: String): ApiResponse<FinalRankingResult> {
        val result = speedQuizService.getFinalRanking(roomId)
        return ApiResponse.success(result)
    }

    /**
     * 게임 종료
     * POST /api/v1/games/quiz/end
     */
    @PostMapping("/end")
    fun endGame(@RequestBody request: QuizRoomRequest): ApiResponse<Unit> {
        speedQuizService.endGame(request.roomId)
        return ApiResponse.success(Unit)
    }

    // ============================================
    // 상태 조회
    // ============================================

    /**
     * 현재 게임 상태 조회 (메인 화면)
     * GET /api/v1/games/quiz/state/{roomId}
     */
    @GetMapping("/state/{roomId}")
    fun getGameState(@PathVariable roomId: String): ApiResponse<SpeedQuizState> {
        val state = speedQuizService.getGameState(roomId)
            ?: return ApiResponse.error("게임이 시작되지 않았습니다")
        return ApiResponse.success(state)
    }

    /**
     * 현재 제시어 조회 (개인 컨트롤러용)
     * GET /api/v1/games/quiz/current-word/{roomId}
     */
    @GetMapping("/current-word/{roomId}")
    fun getCurrentWord(@PathVariable roomId: String): ApiResponse<CurrentWordResult> {
        val result = speedQuizService.getCurrentWord(roomId)
        return ApiResponse.success(result)
    }
}

// ============================================
// Request DTOs
// ============================================

data class QuizInitRequest(
    val roomId: String,
    val roundTimeSeconds: Int = 120  // 기본 120초 (2분)
)

data class RoundStartRequest(
    val roomId: String,
    val categoryId: Long
)

data class QuizRoomRequest(
    val roomId: String
)
