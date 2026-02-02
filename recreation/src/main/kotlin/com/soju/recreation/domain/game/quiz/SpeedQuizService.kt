package com.soju.recreation.domain.game.quiz

import com.fasterxml.jackson.databind.ObjectMapper
import com.soju.recreation.domain.game.*
import com.soju.recreation.domain.room.*
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service
class SpeedQuizService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val roomService: RoomService,
    private val sseService: SseService,
    private val categoryRepository: CategoryRepository,
    private val gameContentRepository: GameContentRepository
) {
    companion object {
        private const val STATE_KEY = "room:%s:quiz:state"
        private const val TTL_HOURS = 6L
        private const val DEFAULT_WORD_COUNT = 50
        private const val DEFAULT_ROUND_TIME = 60
    }

    // 타이머 관리
    private val schedulers = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val executor = Executors.newScheduledThreadPool(4)

    // ============================================
    // Phase 1: 게임 설정
    // ============================================

    /**
     * 사용 가능한 카테고리 목록 조회
     */
    fun getCategories(): List<CategoryInfo> {
        return categoryRepository.findByGameCode(GameCode.SPEED_QUIZ).map {
            CategoryInfo(
                categoryId = it.categoryId!!,
                name = it.name,
                wordCount = it.contents.size
            )
        }
    }

    /**
     * 게임 초기화 (팀은 미리 TeamService로 배정되어 있어야 함)
     */
    fun initializeGame(roomId: String, roundTimeSeconds: Int = DEFAULT_ROUND_TIME): SpeedQuizState {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다: $roomId")

        // 팀 확인
        val teams = room.players.mapNotNull { it.team }.distinct().sorted()
        if (teams.isEmpty()) {
            throw IllegalArgumentException("팀이 배정되지 않았습니다. 먼저 팀을 나눠주세요.")
        }

        val state = SpeedQuizState(
            teams = teams.toMutableList(),
            currentTeamIndex = 0,
            roundTimeSeconds = roundTimeSeconds,
            teamScores = teams.associateWith { 0 }.toMutableMap(),
            completedTeams = mutableListOf(),
            phase = QuizPhase.WAITING
        )

        saveState(roomId, state)

        sseService.broadcast(roomId, "QUIZ_INIT", mapOf(
            "teams" to teams,
            "roundTimeSeconds" to roundTimeSeconds,
            "currentTeam" to teams.first(),
            "phase" to state.phase.name
        ))

        return state
    }

    // ============================================
    // Phase 2: 팀별 라운드 시작
    // ============================================

    /**
     * 카테고리 선택 및 라운드 시작
     */
    fun startRound(roomId: String, categoryId: Long): RoundStartResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 초기화되지 않았습니다")

        if (state.phase == QuizPhase.PLAYING) {
            throw IllegalArgumentException("이미 게임이 진행 중입니다")
        }

        val currentTeam = state.teams[state.currentTeamIndex]

        // 카테고리에서 제시어 로딩
        val words = loadWordsFromCategory(categoryId)
        if (words.isEmpty()) {
            throw IllegalArgumentException("해당 카테고리에 제시어가 없습니다")
        }

        state.currentWord = words.first()
        state.remainingWords = words.drop(1).toMutableList()
        state.currentRoundScore = 0
        state.phase = QuizPhase.PLAYING
        state.remainingTime = state.roundTimeSeconds

        saveState(roomId, state)

        // 타이머 시작
        startTimer(roomId, state.roundTimeSeconds)

        val result = RoundStartResult(
            team = currentTeam,
            currentWord = state.currentWord!!,
            totalWords = words.size,
            roundTimeSeconds = state.roundTimeSeconds
        )

        sseService.broadcast(roomId, "QUIZ_ROUND_START", mapOf(
            "team" to currentTeam,
            "currentWord" to state.currentWord,
            "totalWords" to words.size,
            "timer" to state.roundTimeSeconds,
            "phase" to state.phase.name
        ))

        return result
    }

    /**
     * 정답 처리 - 점수 증가 + 다음 문제
     */
    fun correct(roomId: String): QuizResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        if (state.phase != QuizPhase.PLAYING) {
            throw IllegalArgumentException("게임이 진행 중이 아닙니다")
        }

        val currentTeam = state.teams[state.currentTeamIndex]

        // 점수 증가
        state.currentRoundScore++

        // 다음 문제로
        val previousWord = state.currentWord
        state.currentWord = state.remainingWords.removeFirstOrNull()

        val isWordFinished = state.currentWord == null

        saveState(roomId, state)

        val result = QuizResult(
            previousWord = previousWord,
            currentWord = state.currentWord,
            currentScore = state.currentRoundScore,
            remainingCount = state.remainingWords.size,
            isWordFinished = isWordFinished,
            team = currentTeam
        )

        sseService.broadcast(roomId, "QUIZ_CORRECT", result)

        // 문제가 다 떨어지면 라운드 종료
        if (isWordFinished) {
            endRound(roomId)
        }

        return result
    }

    /**
     * 패스 - 문제 건너뛰기 (뒤로 보내기)
     */
    fun pass(roomId: String): QuizResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        if (state.phase != QuizPhase.PLAYING) {
            throw IllegalArgumentException("게임이 진행 중이 아닙니다")
        }

        val currentTeam = state.teams[state.currentTeamIndex]

        // 현재 문제를 뒤로 보내기
        val skippedWord = state.currentWord
        if (skippedWord != null && state.remainingWords.isNotEmpty()) {
            state.remainingWords.add(skippedWord)
        }

        // 다음 문제로
        state.currentWord = state.remainingWords.removeFirstOrNull() ?: skippedWord

        saveState(roomId, state)

        val result = QuizResult(
            previousWord = skippedWord,
            currentWord = state.currentWord,
            currentScore = state.currentRoundScore,
            remainingCount = state.remainingWords.size,
            isWordFinished = false,
            team = currentTeam,
            wasSkipped = true
        )

        sseService.broadcast(roomId, "QUIZ_PASS", result)

        return result
    }

    // ============================================
    // Phase 3: 라운드 종료 및 다음 팀
    // ============================================

    /**
     * 라운드 종료 (타이머 종료 또는 수동)
     */
    fun endRound(roomId: String): RoundEndResult {
        val state = getState(roomId) ?: throw IllegalArgumentException("게임이 없습니다")

        // 타이머 취소
        cancelTimer(roomId)

        val currentTeam = state.teams[state.currentTeamIndex]

        // 팀 점수 기록
        state.teamScores[currentTeam] = state.currentRoundScore
        state.completedTeams.add(currentTeam)

        state.phase = QuizPhase.ROUND_END
        state.currentWord = null
        state.remainingWords.clear()

        saveState(roomId, state)

        val result = RoundEndResult(
            team = currentTeam,
            score = state.currentRoundScore,
            allScores = state.teamScores.toMap(),
            isGameFinished = state.completedTeams.size >= state.teams.size
        )

        sseService.broadcast(roomId, "QUIZ_ROUND_END", mapOf(
            "team" to currentTeam,
            "score" to state.currentRoundScore,
            "allScores" to state.teamScores,
            "completedTeams" to state.completedTeams,
            "remainingTeams" to state.teams.filter { it !in state.completedTeams },
            "isGameFinished" to result.isGameFinished
        ))

        return result
    }

    /**
     * 다음 팀으로 (카테고리 선택 화면으로)
     */
    fun nextTeam(roomId: String): NextTeamResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 없습니다")

        // 모든 팀 완료 체크
        if (state.completedTeams.size >= state.teams.size) {
            throw IllegalArgumentException("모든 팀이 완료했습니다. 순위를 확인하세요.")
        }

        // 다음 팀으로
        state.currentTeamIndex = (state.currentTeamIndex + 1) % state.teams.size

        // 이미 완료한 팀이면 다음 팀 찾기
        while (state.teams[state.currentTeamIndex] in state.completedTeams) {
            state.currentTeamIndex = (state.currentTeamIndex + 1) % state.teams.size
        }

        state.phase = QuizPhase.WAITING
        state.currentRoundScore = 0

        saveState(roomId, state)

        val nextTeam = state.teams[state.currentTeamIndex]

        sseService.broadcast(roomId, "QUIZ_NEXT_TEAM", mapOf(
            "nextTeam" to nextTeam,
            "completedTeams" to state.completedTeams,
            "allScores" to state.teamScores,
            "phase" to state.phase.name
        ))

        return NextTeamResult(
            nextTeam = nextTeam,
            completedTeams = state.completedTeams.toList(),
            allScores = state.teamScores.toMap()
        )
    }

    // ============================================
    // Phase 4: 최종 순위
    // ============================================

    /**
     * 최종 순위 조회
     */
    fun getFinalRanking(roomId: String): FinalRankingResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 없습니다")

        val ranking = state.teamScores.entries
            .sortedByDescending { it.value }
            .mapIndexed { index, entry ->
                TeamRanking(
                    rank = index + 1,
                    team = entry.key,
                    score = entry.value
                )
            }

        val result = FinalRankingResult(
            ranking = ranking,
            isComplete = state.completedTeams.size >= state.teams.size
        )

        sseService.broadcast(roomId, "QUIZ_FINAL_RANKING", mapOf(
            "ranking" to ranking,
            "isComplete" to result.isComplete
        ))

        return result
    }

    /**
     * 게임 종료
     */
    fun endGame(roomId: String) {
        cancelTimer(roomId)

        val key = STATE_KEY.format(roomId)
        redisTemplate.delete(key)

        sseService.broadcast(roomId, "QUIZ_GAME_END", mapOf(
            "message" to "스피드 퀴즈가 종료되었습니다!"
        ))
    }

    // ============================================
    // 조회 API
    // ============================================

    /**
     * 현재 게임 상태 조회
     */
    fun getGameState(roomId: String): SpeedQuizState? {
        return getState(roomId)
    }

    /**
     * 현재 제시어 조회 (개인 컨트롤러용)
     */
    fun getCurrentWord(roomId: String): CurrentWordResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 없습니다")

        return CurrentWordResult(
            currentWord = state.currentWord,
            currentTeam = state.teams.getOrNull(state.currentTeamIndex),
            currentScore = state.currentRoundScore,
            remainingTime = state.remainingTime,
            phase = state.phase
        )
    }

    // ============================================
    // 타이머 관리
    // ============================================

    private fun startTimer(roomId: String, seconds: Int) {
        cancelTimer(roomId)

        var remaining = seconds

        val task = executor.scheduleAtFixedRate({
            try {
                val state = getState(roomId)
                if (state == null || state.phase != QuizPhase.PLAYING) {
                    cancelTimer(roomId)
                    return@scheduleAtFixedRate
                }

                remaining--
                state.remainingTime = remaining
                saveState(roomId, state)

                // 매초 브로드캐스트
                sseService.broadcast(roomId, "QUIZ_TIMER", mapOf(
                    "remaining" to remaining,
                    "currentWord" to state.currentWord,
                    "currentScore" to state.currentRoundScore
                ))

                if (remaining <= 0) {
                    cancelTimer(roomId)
                    endRound(roomId)
                }
            } catch (e: Exception) {
                // 에러 무시
            }
        }, 1, 1, TimeUnit.SECONDS)

        schedulers[roomId] = task
    }

    private fun cancelTimer(roomId: String) {
        schedulers[roomId]?.cancel(false)
        schedulers.remove(roomId)
    }

    // ============================================
    // Redis 관리
    // ============================================

    private fun loadWordsFromCategory(categoryId: Long): List<String> {
        val contents = gameContentRepository.findRandomByCategoryIdWithLimit(categoryId, DEFAULT_WORD_COUNT)
        return contents.map { it.textContent }.shuffled()
    }

    private fun saveState(roomId: String, state: SpeedQuizState) {
        val key = STATE_KEY.format(roomId)
        val json = objectMapper.writeValueAsString(state)
        redisTemplate.opsForValue().set(key, json)
        redisTemplate.expire(key, TTL_HOURS, TimeUnit.HOURS)
    }

    private fun getState(roomId: String): SpeedQuizState? {
        val key = STATE_KEY.format(roomId)
        val json = redisTemplate.opsForValue().get(key) ?: return null
        return objectMapper.readValue(json, SpeedQuizState::class.java)
    }
}

// ============================================
// Game State
// ============================================

data class SpeedQuizState(
    val teams: MutableList<String>,                          // 참여 팀 목록
    var currentTeamIndex: Int = 0,                           // 현재 진행 중인 팀 인덱스
    var roundTimeSeconds: Int = 60,                          // 라운드 제한 시간
    var remainingTime: Int = 0,                              // 남은 시간
    val teamScores: MutableMap<String, Int> = mutableMapOf(),// 팀별 최종 점수
    val completedTeams: MutableList<String> = mutableListOf(),// 완료한 팀들
    var phase: QuizPhase = QuizPhase.WAITING,                // 현재 상태
    var currentWord: String? = null,                         // 현재 제시어
    var remainingWords: MutableList<String> = mutableListOf(),// 남은 제시어
    var currentRoundScore: Int = 0                           // 현재 라운드 점수
)

enum class QuizPhase {
    WAITING,    // 카테고리 선택 대기
    PLAYING,    // 게임 진행 중
    ROUND_END,  // 라운드 종료
    FINISHED    // 게임 완료
}

// ============================================
// DTOs
// ============================================

data class CategoryInfo(
    val categoryId: Long,
    val name: String,
    val wordCount: Int
)

data class RoundStartResult(
    val team: String,
    val currentWord: String,
    val totalWords: Int,
    val roundTimeSeconds: Int
)

data class QuizResult(
    val previousWord: String?,
    val currentWord: String?,
    val currentScore: Int,
    val remainingCount: Int,
    val isWordFinished: Boolean,
    val team: String,
    val wasSkipped: Boolean = false
)

data class RoundEndResult(
    val team: String,
    val score: Int,
    val allScores: Map<String, Int>,
    val isGameFinished: Boolean
)

data class NextTeamResult(
    val nextTeam: String,
    val completedTeams: List<String>,
    val allScores: Map<String, Int>
)

data class FinalRankingResult(
    val ranking: List<TeamRanking>,
    val isComplete: Boolean
)

data class TeamRanking(
    val rank: Int,
    val team: String,
    val score: Int
)

data class CurrentWordResult(
    val currentWord: String?,
    val currentTeam: String?,
    val currentScore: Int,
    val remainingTime: Int,
    val phase: QuizPhase
)
