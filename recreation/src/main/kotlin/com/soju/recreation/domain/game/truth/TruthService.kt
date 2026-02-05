package com.soju.recreation.domain.game.truth

import com.fasterxml.jackson.databind.ObjectMapper
import com.soju.recreation.domain.room.*
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class TruthService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val roomService: RoomService,
    private val sseService: SseService
) {
    companion object {
        private const val STATE_KEY = "room:%s:truth:state"
        private const val QUESTIONS_KEY = "room:%s:truth:questions"
        private const val TTL_HOURS = 6L
    }

    // ============================================
    // 게임 초기화
    // ============================================

    /**
     * 진실게임 시작
     */
    fun initializeGame(roomId: String): TruthGameState {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다: $roomId")

        if (room.players.size < 2) {
            throw IllegalArgumentException("진실게임은 최소 2명이 필요합니다")
        }

        val state = TruthGameState(
            phase = TruthPhase.SELECT_ANSWERER,
            round = 1
        )

        saveState(roomId, state)
        clearQuestions(roomId)

        sseService.broadcastToAll(roomId, "TRUTH_INIT", mapOf(
            "phase" to state.phase.name,
            "round" to state.round,
            "players" to room.players.map { mapOf("nickname" to it.nickname, "deviceId" to it.deviceId) }
        ))

        return state
    }

    // ============================================
    // Phase 1: 답변자 선택
    // ============================================

    /**
     * 랜덤으로 답변자 선택
     */
    fun selectRandomAnswerer(roomId: String): AnswererInfo {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다: $roomId")

        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다. 먼저 /init을 호출하세요")

        if (room.players.isEmpty()) {
            throw IllegalArgumentException("방에 플레이어가 없습니다")
        }

        val randomPlayer = room.players.random()

        state.currentAnswerer = randomPlayer.deviceId
        state.phase = TruthPhase.SUBMIT_QUESTIONS
        saveState(roomId, state)

        val result = AnswererInfo(
            deviceId = randomPlayer.deviceId,
            nickname = randomPlayer.nickname
        )

        sseService.broadcastToAll(roomId, "TRUTH_ANSWERER_SELECTED", mapOf(
            "answerer" to result,
            "phase" to state.phase.name,
            "message" to "${randomPlayer.nickname}님이 답변자로 선정되었습니다!"
        ))

        return result
    }

    /**
     * 수동으로 답변자 지목
     */
    fun selectAnswerer(roomId: String, answererDeviceId: String): AnswererInfo {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다")

        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        val player = room.players.find { it.deviceId == answererDeviceId }
            ?: throw IllegalArgumentException("플레이어를 찾을 수 없습니다")

        state.currentAnswerer = answererDeviceId
        state.phase = TruthPhase.SUBMIT_QUESTIONS
        saveState(roomId, state)

        val result = AnswererInfo(
            deviceId = player.deviceId,
            nickname = player.nickname
        )

        sseService.broadcastToAll(roomId, "TRUTH_ANSWERER_SELECTED", mapOf(
            "answerer" to result,
            "phase" to state.phase.name,
            "message" to "${player.nickname}님이 답변자로 지목되었습니다!"
        ))

        return result
    }

    // ============================================
    // Phase 2: 질문 제출
    // ============================================

    /**
     * 질문 제출 (답변자 제외 모두)
     */
    fun submitQuestion(roomId: String, deviceId: String, question: String): SubmittedQuestion {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        if (state.phase != TruthPhase.SUBMIT_QUESTIONS) {
            throw IllegalArgumentException("질문 제출 시간이 아닙니다")
        }

        if (deviceId == state.currentAnswerer) {
            throw IllegalArgumentException("답변자는 질문을 제출할 수 없습니다")
        }

        val room = roomService.getRoomInfo(roomId)!!
        val player = room.players.find { it.deviceId == deviceId }
            ?: throw IllegalArgumentException("플레이어를 찾을 수 없습니다")

        val submittedQuestion = SubmittedQuestion(
            deviceId = deviceId,
            nickname = player.nickname,
            question = question,
            timestamp = System.currentTimeMillis()
        )

        // Redis에 질문 저장
        val key = QUESTIONS_KEY.format(roomId)
        val json = objectMapper.writeValueAsString(submittedQuestion)
        redisTemplate.opsForList().rightPush(key, json)
        redisTemplate.expire(key, TTL_HOURS, TimeUnit.HOURS)

        val questionCount = redisTemplate.opsForList().size(key) ?: 0

        sseService.broadcast(roomId, "TRUTH_QUESTION_SUBMITTED", mapOf(
            "from" to player.nickname,
            "questionCount" to questionCount,
            "totalPlayers" to (room.players.size - 1)  // 답변자 제외
        ))

        return submittedQuestion
    }

    /**
     * 제출된 질문 개수 조회
     */
    fun getQuestionCount(roomId: String): Int {
        val key = QUESTIONS_KEY.format(roomId)
        return redisTemplate.opsForList().size(key)?.toInt() ?: 0
    }

    /**
     * 질문 제출 완료 → 질문 선택 단계로
     */
    fun finishQuestionSubmission(roomId: String): List<SubmittedQuestion> {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        val questions = getAllQuestions(roomId)

        if (questions.isEmpty()) {
            throw IllegalArgumentException("제출된 질문이 없습니다")
        }

        state.phase = TruthPhase.SELECT_QUESTION
        state.submittedQuestions = questions.toMutableList()
        state.questionVotes.clear()
        state.voteDoneDevices.clear()
        saveState(roomId, state)

        // 익명 질문 목록 (index + question만)
        val anonymousQuestions = questions.mapIndexed { index, q ->
            mapOf("index" to index, "question" to q.question)
        }

        sseService.broadcastToAll(roomId, "TRUTH_QUESTIONS_READY", mapOf(
            "phase" to state.phase.name,
            "questionCount" to questions.size,
            "questions" to anonymousQuestions
        ))

        return questions
    }

    // ============================================
    // Phase 3: 질문 선택
    // ============================================

    /**
     * 랜덤 질문 선택 (리롤 가능)
     */
    fun selectRandomQuestion(roomId: String): SelectedQuestion {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        if (state.submittedQuestions.isEmpty()) {
            throw IllegalArgumentException("제출된 질문이 없습니다")
        }

        // 아직 선택되지 않은 질문 중에서 랜덤 선택
        val availableQuestions = state.submittedQuestions.filter { !it.isUsed }

        if (availableQuestions.isEmpty()) {
            throw IllegalArgumentException("더 이상 선택할 질문이 없습니다")
        }

        val selected = availableQuestions.random()
        state.currentQuestion = selected.question
        state.currentQuestionFrom = selected.nickname

        saveState(roomId, state)

        val result = SelectedQuestion(
            question = selected.question,
            from = selected.nickname,
            remainingCount = availableQuestions.size - 1
        )

        sseService.broadcast(roomId, "TRUTH_QUESTION_SELECTED", mapOf(
            "question" to result.question,
            "from" to result.from,
            "remainingCount" to result.remainingCount
        ))

        return result
    }

    /**
     * 질문 확정 → 답변 단계로
     */
    fun confirmQuestion(roomId: String): TruthGameState {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        if (state.currentQuestion == null) {
            throw IllegalArgumentException("선택된 질문이 없습니다")
        }

        // 선택된 질문을 사용됨으로 표시
        state.submittedQuestions.find { it.question == state.currentQuestion }?.isUsed = true

        state.phase = TruthPhase.ANSWERING
        state.faceTrackingData.clear()
        saveState(roomId, state)

        val room = roomService.getRoomInfo(roomId)!!
        val answerer = room.players.find { it.deviceId == state.currentAnswerer }

        sseService.broadcastToAll(roomId, "TRUTH_START_ANSWERING", mapOf(
            "phase" to state.phase.name,
            "answerer" to answerer?.nickname,
            "answererDeviceId" to state.currentAnswerer,
            "question" to state.currentQuestion,
            "message" to "${answerer?.nickname}님, 카메라를 켜고 질문에 답해주세요!"
        ))

        return state
    }

    // ============================================
    // Phase 3.5: 질문 투표
    // ============================================

    /**
     * 익명 질문 목록 조회 (투표용)
     */
    fun getQuestionList(roomId: String): List<Map<String, Any>> {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        return state.submittedQuestions.mapIndexed { index, q ->
            mapOf("index" to index, "question" to q.question)
        }
    }

    /**
     * 질문 투표 토글
     */
    fun voteQuestion(roomId: String, deviceId: String, questionIndex: Int) {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        if (state.phase != TruthPhase.SELECT_QUESTION) {
            throw IllegalArgumentException("투표 시간이 아닙니다")
        }

        if (questionIndex < 0 || questionIndex >= state.submittedQuestions.size) {
            throw IllegalArgumentException("잘못된 질문 번호입니다")
        }

        // 이미 해당 질문에 투표했으면 취소, 아니면 추가 (토글)
        val existing = state.questionVotes.find { it.deviceId == deviceId && it.questionIndex == questionIndex }
        if (existing != null) {
            state.questionVotes.remove(existing)
        } else {
            state.questionVotes.add(QuestionVote(deviceId = deviceId, questionIndex = questionIndex))
        }

        saveState(roomId, state)
    }

    /**
     * 플레이어 투표 완료
     */
    fun playerVoteDone(roomId: String, deviceId: String) {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        state.voteDoneDevices.add(deviceId)
        saveState(roomId, state)

        val room = roomService.getRoomInfo(roomId)!!
        val totalVoters = room.players.size - 1 // 답변자 제외

        sseService.broadcast(roomId, "TRUTH_QUESTION_VOTE_DONE", mapOf(
            "doneCount" to state.voteDoneDevices.size,
            "totalPlayers" to totalVoters
        ))
    }

    /**
     * 투표 마감 → 최다 득표 질문 선정 → ANSWERING 전환
     */
    fun finishQuestionVote(roomId: String): TruthGameState {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        if (state.submittedQuestions.isEmpty()) {
            throw IllegalArgumentException("제출된 질문이 없습니다")
        }

        // 질문별 투표 수 집계
        val voteCounts = state.questionVotes.groupBy { it.questionIndex }
            .mapValues { it.value.size }

        // 최다 득표 질문 선택 (동점이면 랜덤)
        val maxVotes = voteCounts.values.maxOrNull() ?: 0
        val topQuestionIndex = if (maxVotes > 0) {
            voteCounts.filter { it.value == maxVotes }.keys.random()
        } else {
            // 아무도 투표하지 않으면 랜덤 선택
            state.submittedQuestions.indices.random()
        }

        val selectedQuestion = state.submittedQuestions[topQuestionIndex]
        selectedQuestion.isUsed = true

        state.currentQuestion = selectedQuestion.question
        state.currentQuestionFrom = selectedQuestion.nickname
        state.phase = TruthPhase.ANSWERING
        state.faceTrackingData.clear()
        saveState(roomId, state)

        val room = roomService.getRoomInfo(roomId)!!
        val answerer = room.players.find { it.deviceId == state.currentAnswerer }

        sseService.broadcastToAll(roomId, "TRUTH_START_ANSWERING", mapOf(
            "phase" to state.phase.name,
            "answerer" to answerer?.nickname,
            "answererDeviceId" to state.currentAnswerer,
            "question" to state.currentQuestion,
            "message" to "${answerer?.nickname}님, 카메라를 보고 질문에 답해주세요!"
        ))

        return state
    }

    // ============================================
    // Phase 4: 답변 & 얼굴 트래킹
    // ============================================

    /**
     * 얼굴 트래킹 데이터 수신 (프론트에서 주기적으로 전송)
     * 프론트에서 face-api.js나 MediaPipe로 분석 후 전송
     */
    fun submitFaceTrackingData(roomId: String, deviceId: String, data: FaceTrackingData) {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        if (state.phase != TruthPhase.ANSWERING) {
            throw IllegalArgumentException("답변 시간이 아닙니다")
        }

        if (deviceId != state.currentAnswerer) {
            throw IllegalArgumentException("답변자만 데이터를 전송할 수 있습니다")
        }

        state.faceTrackingData.add(data)
        saveState(roomId, state)

        // 실시간으로 Host에게 트래킹 데이터 전송 (오버레이 표시용)
        sseService.broadcast(roomId, "TRUTH_FACE_DATA", mapOf(
            "eyeBlinkRate" to data.eyeBlinkRate,
            "eyeMovement" to data.eyeMovement,
            "facialTremor" to data.facialTremor,
            "nostrilMovement" to data.nostrilMovement,
            "microExpression" to data.microExpression,
            "timestamp" to data.timestamp
        ))
    }

    /**
     * 답변 완료 → 결과 분석
     */
    fun finishAnswering(roomId: String): LieDetectionResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        state.phase = TruthPhase.RESULT
        saveState(roomId, state)

        // 거짓말 판정 분석
        val result = analyzeLieDetection(state.faceTrackingData)

        val room = roomService.getRoomInfo(roomId)!!
        val answerer = room.players.find { it.deviceId == state.currentAnswerer }

        sseService.broadcastToAll(roomId, "TRUTH_RESULT", mapOf(
            "phase" to state.phase.name,
            "answerer" to answerer?.nickname,
            "question" to state.currentQuestion,
            "result" to result,
            "message" to if (result.isLie) "거짓말 탐지! 🚨" else "진실로 판정! ✅"
        ))

        return result
    }

    /**
     * 거짓말(긴장도) 판정 분석 로직
     * 순수 수치 기반 판정 (랜덤성 없음)
     */
    private fun analyzeLieDetection(trackingData: List<FaceTrackingData>): LieDetectionResult {
        if (trackingData.isEmpty()) {
            return LieDetectionResult(
                isLie = false,
                confidence = 0,
                details = LieDetectionDetails(
                    eyeBlinkScore = 0,
                    eyeMovementScore = 0,
                    facialTremorScore = 0,
                    nostrilScore = 0,
                    overallStressLevel = 0
                ),
                analysisComment = "분석 데이터가 없습니다. 카메라가 제대로 작동했는지 확인해주세요."
            )
        }

        // 프론트엔드에서 이미 Blendshape + Baseline + EMA로 정교하게 계산한 stressLevel 활용
        val avgFrontendStress = trackingData.map { it.stressLevel }.average()

        // 개별 지표도 3배 증폭하여 점수화 (초민감 모드)
        val avgBlinkRate = trackingData.map { it.eyeBlinkRate }.average()
        val avgEyeMovement = trackingData.map { it.eyeMovement }.average()
        val avgTremor = trackingData.map { it.facialTremor }.average()
        val avgNostril = trackingData.map { it.nostrilMovement }.average()

        // 눈 깜빡임: 아무 깜빡임도 점수 부여 (기존: -2 보정 제거)
        val blinkScore = (avgBlinkRate / 3 * 100).toInt().coerceIn(0, 100)
        // 나머지: 3배 증폭 (0.1 → 30점)
        val eyeScore = (avgEyeMovement * 300).toInt().coerceIn(0, 100)
        val tremorScore = (avgTremor * 300).toInt().coerceIn(0, 100)
        val nostrilScore = (avgNostril * 300).toInt().coerceIn(0, 100)

        // Apply weights to calculate overall score (higher weight to eye-related metrics)
        // Increased sensitivity: higher base scoring
        val overallScore = (
            blinkScore * 0.4 +
            eyeScore * 0.4 +
            tremorScore * 0.1 +
            nostrilScore * 0.1
        ).toInt().coerceIn(0, 100)

        // Increased sensitivity: 20+ score triggers lie detection (was 55)
        val isLie = overallScore >= 20

        val comment = buildAnalysisComment(overallScore, blinkScore, eyeScore, tremorScore, nostrilScore)

        return LieDetectionResult(
            isLie = isLie,
            confidence = overallScore,
            details = LieDetectionDetails(
                eyeBlinkScore = blinkScore,
                eyeMovementScore = eyeScore,
                facialTremorScore = tremorScore,
                nostrilScore = nostrilScore,
                overallStressLevel = overallScore
            ),
            analysisComment = comment
        )
    }

    /**
     * 분석 결과에 따른 코멘트 생성
     */
    private fun buildAnalysisComment(
        overall: Int,
        blink: Int,
        eye: Int,
        tremor: Int,
        nostril: Int
    ): String {
        val highestFactor = listOf(
            "눈 깜빡임" to blink,
            "시선 불안정" to eye,
            "얼굴 떨림" to tremor,
            "콧구멍 움직임" to nostril
        ).maxByOrNull { it.second }!!

        return when {
            overall >= 80 -> "긴장도 매우 높음! ${highestFactor.first}이(가) 특히 두드러집니다."
            overall >= 65 -> "긴장하고 있는 것 같습니다. ${highestFactor.first} 수치가 높습니다."
            overall >= 55 -> "약간의 긴장이 감지됩니다."
            overall >= 40 -> "비교적 안정적인 상태입니다."
            overall >= 20 -> "매우 침착한 상태입니다."
            else -> "완벽한 포커페이스입니다."
        }
    }

    // ============================================
    // 다음 라운드
    // ============================================

    /**
     * 다음 라운드 시작
     */
    fun nextRound(roomId: String): TruthGameState {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        state.round++
        state.phase = TruthPhase.SELECT_ANSWERER
        state.currentAnswerer = null
        state.currentQuestion = null
        state.currentQuestionFrom = null
        state.faceTrackingData.clear()
        state.questionVotes.clear()
        state.voteDoneDevices.clear()

        // 질문 목록 초기화
        clearQuestions(roomId)
        state.submittedQuestions.clear()

        saveState(roomId, state)

        sseService.broadcastToAll(roomId, "TRUTH_NEXT_ROUND", mapOf(
            "phase" to state.phase.name,
            "round" to state.round
        ))

        return state
    }

    /**
     * 게임 종료
     */
    fun endGame(roomId: String) {
        clearQuestions(roomId)
        deleteState(roomId)

        sseService.broadcastToAll(roomId, "TRUTH_GAME_END", mapOf(
            "message" to "진실게임이 종료되었습니다!"
        ))
    }

    // ============================================
    // 상태 조회
    // ============================================

    fun getGameState(roomId: String): TruthGameState? {
        return getState(roomId)
    }

    fun getCurrentAnswerer(roomId: String): AnswererInfo? {
        val state = getState(roomId) ?: return null
        val room = roomService.getRoomInfo(roomId) ?: return null

        val answerer = room.players.find { it.deviceId == state.currentAnswerer }
            ?: return null

        return AnswererInfo(
            deviceId = answerer.deviceId,
            nickname = answerer.nickname
        )
    }

    // ============================================
    // Redis 관리
    // ============================================

    private fun getAllQuestions(roomId: String): List<SubmittedQuestion> {
        val key = QUESTIONS_KEY.format(roomId)
        val jsonList = redisTemplate.opsForList().range(key, 0, -1) ?: return emptyList()
        return jsonList.map { objectMapper.readValue(it, SubmittedQuestion::class.java) }
    }

    private fun clearQuestions(roomId: String) {
        val key = QUESTIONS_KEY.format(roomId)
        redisTemplate.delete(key)
    }

    private fun saveState(roomId: String, state: TruthGameState) {
        val key = STATE_KEY.format(roomId)
        val json = objectMapper.writeValueAsString(state)
        redisTemplate.opsForValue().set(key, json)
        redisTemplate.expire(key, TTL_HOURS, TimeUnit.HOURS)
    }

    private fun getState(roomId: String): TruthGameState? {
        val key = STATE_KEY.format(roomId)
        val json = redisTemplate.opsForValue().get(key) ?: return null
        return objectMapper.readValue(json, TruthGameState::class.java)
    }

    private fun deleteState(roomId: String) {
        val key = STATE_KEY.format(roomId)
        redisTemplate.delete(key)
    }
}

// ============================================
// Game State & DTOs
// ============================================

data class TruthGameState(
    var phase: TruthPhase = TruthPhase.SELECT_ANSWERER,
    var round: Int = 1,
    var currentAnswerer: String? = null,          // 현재 답변자 deviceId
    var currentQuestion: String? = null,          // 현재 질문
    var currentQuestionFrom: String? = null,      // 질문 제출자 닉네임
    var submittedQuestions: MutableList<SubmittedQuestion> = mutableListOf(),
    var faceTrackingData: MutableList<FaceTrackingData> = mutableListOf(),
    var questionVotes: MutableList<QuestionVote> = mutableListOf(),
    var voteDoneDevices: MutableSet<String> = mutableSetOf()
)

enum class TruthPhase {
    SELECT_ANSWERER,    // 답변자 선택
    SUBMIT_QUESTIONS,   // 질문 제출
    SELECT_QUESTION,    // 질문 선택 (리롤 가능)
    ANSWERING,          // 답변 중 (카메라 + 트래킹)
    RESULT              // 결과 발표
}

data class AnswererInfo(
    val deviceId: String,
    val nickname: String
)

data class SubmittedQuestion(
    val deviceId: String,
    val nickname: String,
    val question: String,
    val timestamp: Long,
    var isUsed: Boolean = false
)

data class SelectedQuestion(
    val question: String,
    val from: String,
    val remainingCount: Int
)

data class QuestionVote(
    val deviceId: String,
    val questionIndex: Int
)

data class FaceTrackingData(
    val eyeBlinkRate: Double,      // 눈 깜빡임 빈도 (회/초)
    val eyeMovement: Double,       // 눈동자 움직임 (0~1, 불안정할수록 높음)
    val facialTremor: Double,      // 얼굴 떨림 (0~1)
    val nostrilMovement: Double,   // 콧구멍 움직임 (0~1)
    val stressLevel: Int = 0,      // 프론트에서 계산한 종합 스트레스 (0~100)
    val isLie: Boolean = false,    // 프론트에서 판정한 거짓말 여부
    val microExpression: String?,  // 미세표정 ("nervous", "confident", etc.)
    val timestamp: Long = System.currentTimeMillis()
)

data class LieDetectionResult(
    val isLie: Boolean,
    val confidence: Int,           // 0~100 확신도
    val details: LieDetectionDetails,
    val analysisComment: String
)

data class LieDetectionDetails(
    val eyeBlinkScore: Int,        // 눈 깜빡임 점수
    val eyeMovementScore: Int,     // 눈동자 불안정 점수
    val facialTremorScore: Int,    // 얼굴 떨림 점수
    val nostrilScore: Int,         // 콧구멍 움직임 점수
    val overallStressLevel: Int    // 종합 스트레스 레벨
)
