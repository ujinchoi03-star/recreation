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

    // ??대㉧ 愿由?
    private val schedulers = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val executor = Executors.newScheduledThreadPool(4)

    // ============================================
    // Phase 1: 寃뚯엫 ?ㅼ젙
    // ============================================

    /**
     * ?ъ슜 媛?ν븳 移댄뀒怨좊━ 紐⑸줉 議고쉶
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
     * 寃뚯엫 珥덇린??(?? 誘몃━ TeamService濡?諛곗젙?섏뼱 ?덉뼱????
     */
    fun initializeGame(roomId: String, roundTimeSeconds: Int = DEFAULT_ROUND_TIME): SpeedQuizState {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("諛⑹쓣 李얠쓣 ???놁뒿?덈떎: $roomId")

        // ? ?뺤씤
        val teams = room.players.mapNotNull { it.team }.distinct().sorted()
        if (teams.isEmpty()) {
            throw IllegalArgumentException("???諛곗젙?섏? ?딆븯?듬땲?? 癒쇱? ????섎닠二쇱꽭??")
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

        sseService.broadcastToAll(roomId, "QUIZ_INIT", mapOf(
            "teams" to teams,
            "roundTimeSeconds" to roundTimeSeconds,
            "currentTeam" to teams.first(),
            "phase" to state.phase.name
        ))

        return state
    }

    // ============================================
    // Phase 2: ?蹂??쇱슫???쒖옉
    // ============================================

    /**
     * 移댄뀒怨좊━ ?좏깮 諛??쇱슫???쒖옉
     */
    fun startRound(roomId: String, categoryId: Long): RoundStartResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("寃뚯엫??珥덇린?붾릺吏 ?딆븯?듬땲??)

        if (state.phase == QuizPhase.PLAYING) {
            throw IllegalArgumentException("?대? 寃뚯엫??吏꾪뻾 以묒엯?덈떎")
        }

        val currentTeam = state.teams[state.currentTeamIndex]

        // 移댄뀒怨좊━?먯꽌 ?쒖떆??濡쒕뵫
        val words = loadWordsFromCategory(categoryId)
        if (words.isEmpty()) {
            throw IllegalArgumentException("?대떦 移댄뀒怨좊━???쒖떆?닿? ?놁뒿?덈떎")
        }

        state.currentWord = words.first()
        state.remainingWords = words.drop(1).toMutableList()
        state.currentRoundScore = 0
        state.phase = QuizPhase.PLAYING
        state.remainingTime = state.roundTimeSeconds

        saveState(roomId, state)

        // ??대㉧ ?쒖옉
        startTimer(roomId, state.roundTimeSeconds)

        val result = RoundStartResult(
            team = currentTeam,
            currentWord = state.currentWord!!,
            totalWords = words.size,
            roundTimeSeconds = state.roundTimeSeconds
        )

        sseService.broadcastToAll(roomId, "QUIZ_ROUND_START", mapOf(
            "team" to currentTeam,
            "currentWord" to state.currentWord,
            "totalWords" to words.size,
            "timer" to state.roundTimeSeconds,
            "phase" to state.phase.name
        ))

        return result
    }

    /**
     * ?뺣떟 泥섎━ - ?먯닔 利앷? + ?ㅼ쓬 臾몄젣
     */
    fun correct(roomId: String): QuizResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("寃뚯엫???쒖옉?섏? ?딆븯?듬땲??)

        if (state.phase != QuizPhase.PLAYING) {
            throw IllegalArgumentException("寃뚯엫??吏꾪뻾 以묒씠 ?꾨떃?덈떎")
        }

        val currentTeam = state.teams[state.currentTeamIndex]

        // ?먯닔 利앷?
        state.currentRoundScore++

        // ?ㅼ쓬 臾몄젣濡?
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

        // [DEBUG LOG] ?뺣떟 泥섎━ 濡쒓렇
        println("[SpeedQuiz] Correct! Team: $currentTeam, Score: ${state.currentRoundScore}, NextWord: ${state.currentWord}, Remaining: ${state.remainingWords.size}")

        sseService.broadcastToAll(roomId, "QUIZ_CORRECT", result)

        // 臾몄젣媛 ???⑥뼱吏硫??쇱슫??醫낅즺
        if (isWordFinished) {
            endRound(roomId)
        }

        return result
    }

    /**
     * ?⑥뒪 - 臾몄젣 嫄대꼫?곌린 (?ㅻ줈 蹂대궡湲?
     */
    fun pass(roomId: String): QuizResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("寃뚯엫???쒖옉?섏? ?딆븯?듬땲??)

        if (state.phase != QuizPhase.PLAYING) {
            throw IllegalArgumentException("寃뚯엫??吏꾪뻾 以묒씠 ?꾨떃?덈떎")
        }

        val currentTeam = state.teams[state.currentTeamIndex]

        // ?꾩옱 臾몄젣瑜??ㅻ줈 蹂대궡湲?
        val skippedWord = state.currentWord
        if (skippedWord != null && state.remainingWords.isNotEmpty()) {
            state.remainingWords.add(skippedWord)
        }

        // ?ㅼ쓬 臾몄젣濡?
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

        sseService.broadcastToAll(roomId, "QUIZ_PASS", result)

        return result
    }

    // ============================================
    // Phase 3: ?쇱슫??醫낅즺 諛??ㅼ쓬 ?
    // ============================================

    /**
     * ?쇱슫??醫낅즺 (??대㉧ 醫낅즺 ?먮뒗 ?섎룞)
     */
    fun endRound(roomId: String): RoundEndResult {
        val state = getState(roomId) ?: throw IllegalArgumentException("寃뚯엫???놁뒿?덈떎")

        // ??대㉧ 痍⑥냼
        cancelTimer(roomId)

        val currentTeam = state.teams[state.currentTeamIndex]

        // ? ?먯닔 湲곕줉
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

        sseService.broadcastToAll(roomId, "QUIZ_ROUND_END", mapOf(
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
     * ?ㅼ쓬 ??쇰줈 (移댄뀒怨좊━ ?좏깮 ?붾㈃?쇰줈)
     */
    fun nextTeam(roomId: String): NextTeamResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("寃뚯엫???놁뒿?덈떎")

        // 紐⑤뱺 ? ?꾨즺 泥댄겕
        if (state.completedTeams.size >= state.teams.size) {
            throw IllegalArgumentException("紐⑤뱺 ????꾨즺?덉뒿?덈떎. ?쒖쐞瑜??뺤씤?섏꽭??")
        }

        // ?ㅼ쓬 ??쇰줈
        state.currentTeamIndex = (state.currentTeamIndex + 1) % state.teams.size

        // ?대? ?꾨즺????대㈃ ?ㅼ쓬 ? 李얘린
        while (state.teams[state.currentTeamIndex] in state.completedTeams) {
            state.currentTeamIndex = (state.currentTeamIndex + 1) % state.teams.size
        }

        state.phase = QuizPhase.WAITING
        state.currentRoundScore = 0

        saveState(roomId, state)

        val nextTeam = state.teams[state.currentTeamIndex]

        sseService.broadcastToAll(roomId, "QUIZ_NEXT_TEAM", mapOf(
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
    // Phase 4: 理쒖쥌 ?쒖쐞
    // ============================================

    /**
     * 理쒖쥌 ?쒖쐞 議고쉶
     */
    fun getFinalRanking(roomId: String): FinalRankingResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("寃뚯엫???놁뒿?덈떎")

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

        sseService.broadcastToAll(roomId, "QUIZ_FINAL_RANKING", mapOf(
            "ranking" to ranking,
            "isComplete" to result.isComplete
        ))

        return result
    }

    /**
     * 寃뚯엫 醫낅즺
     */
    fun endGame(roomId: String) {
        cancelTimer(roomId)

        val key = STATE_KEY.format(roomId)
        redisTemplate.delete(key)

        sseService.broadcastToAll(roomId, "QUIZ_GAME_END", mapOf(
            "message" to "?ㅽ뵾???댁쫰媛 醫낅즺?섏뿀?듬땲??"
        ))
    }

    // ============================================
    // 議고쉶 API
    // ============================================

    /**
     * ?꾩옱 寃뚯엫 ?곹깭 議고쉶
     */
    fun getGameState(roomId: String): SpeedQuizState? {
        return getState(roomId)
    }

    /**
     * ?꾩옱 ?쒖떆??議고쉶 (媛쒖씤 而⑦듃濡ㅻ윭??
     */
    fun getCurrentWord(roomId: String): CurrentWordResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("寃뚯엫???놁뒿?덈떎")

        return CurrentWordResult(
            currentWord = state.currentWord,
            currentTeam = state.teams.getOrNull(state.currentTeamIndex),
            currentScore = state.currentRoundScore,
            remainingTime = state.remainingTime,
            phase = state.phase
        )
    }

    // ============================================
    // ??대㉧ 愿由?
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

                // 留ㅼ큹 釉뚮줈?쒖틦?ㅽ듃
                sseService.broadcastToAll(roomId, "QUIZ_TIMER", mapOf(
                    "remaining" to remaining,
                    "currentWord" to state.currentWord,
                    "currentScore" to state.currentRoundScore
                ))

                if (remaining <= 0) {
                    cancelTimer(roomId)
                    endRound(roomId)
                }
            } catch (e: Exception) {
                // ?먮윭 臾댁떆
            }
        }, 1, 1, TimeUnit.SECONDS)

        schedulers[roomId] = task
    }

    private fun cancelTimer(roomId: String) {
        schedulers[roomId]?.cancel(false)
        schedulers.remove(roomId)
    }

    // ============================================
    // Redis 愿由?
    // ============================================

    private fun loadWordsFromCategory(categoryId: Long): List<String> {
        val contents = gameContentRepository.findRandomByCategoryIdWithLimit(categoryId, DEFAULT_WORD_COUNT)
        println("[SpeedQuiz] Loading words for category $categoryId. Found: ${contents.size} words.") // [DEBUG LOG]
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
    val teams: MutableList<String>,                          // 李몄뿬 ? 紐⑸줉
    var currentTeamIndex: Int = 0,                           // ?꾩옱 吏꾪뻾 以묒씤 ? ?몃뜳??
    var roundTimeSeconds: Int = 60,                          // ?쇱슫???쒗븳 ?쒓컙
    var remainingTime: Int = 0,                              // ?⑥? ?쒓컙
    val teamScores: MutableMap<String, Int> = mutableMapOf(),// ?蹂?理쒖쥌 ?먯닔
    val completedTeams: MutableList<String> = mutableListOf(),// ?꾨즺?????
    var phase: QuizPhase = QuizPhase.WAITING,                // ?꾩옱 ?곹깭
    var currentWord: String? = null,                         // ?꾩옱 ?쒖떆??
    var remainingWords: MutableList<String> = mutableListOf(),// ?⑥? ?쒖떆??
    var currentRoundScore: Int = 0                           // ?꾩옱 ?쇱슫???먯닔
)

enum class QuizPhase {
    WAITING,    // 移댄뀒怨좊━ ?좏깮 ?湲?
    PLAYING,    // 寃뚯엫 吏꾪뻾 以?
    ROUND_END,  // ?쇱슫??醫낅즺
    FINISHED    // 寃뚯엫 ?꾨즺
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
