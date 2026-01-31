package com.soju.recreation.domain.game.truth

import com.soju.recreation.domain.room.ApiResponse
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/games/truth")
class TruthController(
    private val truthService: TruthService
) {

    // ============================================
    // 게임 시작/종료
    // ============================================

    /**
     * 진실게임 초기화
     * POST /api/v1/games/truth/init
     */
    @PostMapping("/init")
    fun initializeGame(@RequestBody request: TruthRoomRequest): ApiResponse<TruthGameState> {
        val state = truthService.initializeGame(request.roomId)
        return ApiResponse.success(state)
    }

    /**
     * 게임 종료
     * POST /api/v1/games/truth/end
     */
    @PostMapping("/end")
    fun endGame(@RequestBody request: TruthRoomRequest): ApiResponse<Unit> {
        truthService.endGame(request.roomId)
        return ApiResponse.success(Unit)
    }

    /**
     * 다음 라운드
     * POST /api/v1/games/truth/next-round
     */
    @PostMapping("/next-round")
    fun nextRound(@RequestBody request: TruthRoomRequest): ApiResponse<TruthGameState> {
        val state = truthService.nextRound(request.roomId)
        return ApiResponse.success(state)
    }

    // ============================================
    // Phase 1: 답변자 선택
    // ============================================

    /**
     * 랜덤 답변자 선택
     * POST /api/v1/games/truth/answerer/random
     */
    @PostMapping("/answerer/random")
    fun selectRandomAnswerer(@RequestBody request: TruthRoomRequest): ApiResponse<AnswererInfo> {
        val answerer = truthService.selectRandomAnswerer(request.roomId)
        return ApiResponse.success(answerer)
    }

    /**
     * 수동 답변자 지목
     * POST /api/v1/games/truth/answerer/select
     */
    @PostMapping("/answerer/select")
    fun selectAnswerer(@RequestBody request: SelectAnswererRequest): ApiResponse<AnswererInfo> {
        val answerer = truthService.selectAnswerer(request.roomId, request.answererDeviceId)
        return ApiResponse.success(answerer)
    }

    /**
     * 현재 답변자 조회
     * GET /api/v1/games/truth/answerer/{roomId}
     */
    @GetMapping("/answerer/{roomId}")
    fun getCurrentAnswerer(@PathVariable roomId: String): ApiResponse<AnswererInfo> {
        val answerer = truthService.getCurrentAnswerer(roomId)
            ?: return ApiResponse.error("답변자가 선택되지 않았습니다")
        return ApiResponse.success(answerer)
    }

    // ============================================
    // Phase 2: 질문 제출
    // ============================================

    /**
     * 질문 제출 (답변자 제외)
     * POST /api/v1/games/truth/question/submit
     */
    @PostMapping("/question/submit")
    fun submitQuestion(@RequestBody request: SubmitQuestionRequest): ApiResponse<SubmittedQuestion> {
        val question = truthService.submitQuestion(request.roomId, request.deviceId, request.question)
        return ApiResponse.success(question)
    }

    /**
     * 제출된 질문 개수 조회
     * GET /api/v1/games/truth/question/count/{roomId}
     */
    @GetMapping("/question/count/{roomId}")
    fun getQuestionCount(@PathVariable roomId: String): ApiResponse<Int> {
        val count = truthService.getQuestionCount(roomId)
        return ApiResponse.success(count)
    }

    /**
     * 질문 제출 완료 (다음 단계로)
     * POST /api/v1/games/truth/question/finish
     */
    @PostMapping("/question/finish")
    fun finishQuestionSubmission(@RequestBody request: TruthRoomRequest): ApiResponse<List<SubmittedQuestion>> {
        val questions = truthService.finishQuestionSubmission(request.roomId)
        return ApiResponse.success(questions)
    }

    // ============================================
    // Phase 3: 질문 선택
    // ============================================

    /**
     * 랜덤 질문 선택 (리롤용으로도 사용)
     * POST /api/v1/games/truth/question/select
     */
    @PostMapping("/question/select")
    fun selectRandomQuestion(@RequestBody request: TruthRoomRequest): ApiResponse<SelectedQuestion> {
        val question = truthService.selectRandomQuestion(request.roomId)
        return ApiResponse.success(question)
    }

    /**
     * 질문 확정 (답변 단계로)
     * POST /api/v1/games/truth/question/confirm
     */
    @PostMapping("/question/confirm")
    fun confirmQuestion(@RequestBody request: TruthRoomRequest): ApiResponse<TruthGameState> {
        val state = truthService.confirmQuestion(request.roomId)
        return ApiResponse.success(state)
    }

    // ============================================
    // Phase 4: 답변 & 얼굴 트래킹
    // ============================================

    /**
     * 얼굴 트래킹 데이터 전송 (프론트에서 주기적으로)
     * POST /api/v1/games/truth/face-tracking
     */
    @PostMapping("/face-tracking")
    fun submitFaceTrackingData(@RequestBody request: FaceTrackingRequest): ApiResponse<Unit> {
        truthService.submitFaceTrackingData(request.roomId, request.deviceId, request.data)
        return ApiResponse.success(Unit)
    }

    /**
     * 답변 완료 → 결과 분석
     * POST /api/v1/games/truth/finish-answering
     */
    @PostMapping("/finish-answering")
    fun finishAnswering(@RequestBody request: TruthRoomRequest): ApiResponse<LieDetectionResult> {
        val result = truthService.finishAnswering(request.roomId)
        return ApiResponse.success(result)
    }

    // ============================================
    // 상태 조회
    // ============================================

    /**
     * 현재 게임 상태 조회
     * GET /api/v1/games/truth/state/{roomId}
     */
    @GetMapping("/state/{roomId}")
    fun getGameState(@PathVariable roomId: String): ApiResponse<TruthGameState> {
        val state = truthService.getGameState(roomId)
            ?: return ApiResponse.error("게임이 시작되지 않았습니다")
        return ApiResponse.success(state)
    }
}

// ============================================
// Request DTOs
// ============================================

data class TruthRoomRequest(
    val roomId: String
)

data class SelectAnswererRequest(
    val roomId: String,
    val answererDeviceId: String
)

data class SubmitQuestionRequest(
    val roomId: String,
    val deviceId: String,
    val question: String
)

data class FaceTrackingRequest(
    val roomId: String,
    val deviceId: String,
    val data: FaceTrackingData
)
