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
        private const val DEFAULT_ROUND_TIME = 120
    }

    // Timer Management
    private val schedulers = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val executor = Executors.newScheduledThreadPool(4)

    // ============================================
    // Phase 1: Game Setup
    // ============================================

    /**
     * Get available categories
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
     * Initialize game (teams must be assigned by TeamService first)
     */
    fun initializeGame(roomId: String, roundTimeSeconds: Int = DEFAULT_ROUND_TIME): SpeedQuizState {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("Room not found: $roomId")

        // Check teams
        val teams = room.players.mapNotNull { it.team }.distinct().sorted()
        if (teams.isEmpty()) {
            throw IllegalArgumentException("No teams assigned. Please divide teams first.")
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
    // Phase 2: Start Round
    // ============================================

    /**
     * Select category and start round
     */
    fun startRound(roomId: String, categoryId: Long): RoundStartResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("Game not initialized")

        if (state.phase == QuizPhase.PLAYING) {
            throw IllegalArgumentException("Game already in progress")
        }

        val currentTeam = state.teams[state.currentTeamIndex]

        // Load words from category
        val words = loadWordsFromCategory(categoryId)
        if (words.isEmpty()) {
            throw IllegalArgumentException("No words found in this category")
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
     * Correct answer - increase score + next word
     */
    fun correct(roomId: String): QuizResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("Game not initialized")

        if (state.phase != QuizPhase.PLAYING) {
            throw IllegalArgumentException("Game is not in progress")
        }

        val currentTeam = state.teams[state.currentTeamIndex]

        // Increase score
        state.currentRoundScore++

        // Next word
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

        // [DEBUG LOG] Correct answer processing
        println("[SpeedQuiz] Correct! Team: $currentTeam, Score: ${state.currentRoundScore}, NextWord: ${state.currentWord}, Remaining: ${state.remainingWords.size}")

        sseService.broadcastToAll(roomId, "QUIZ_CORRECT", result)

        // End round if no more words
        if (isWordFinished) {
            endRound(roomId)
        }

        return result
    }

    /**
     * Pass - skip word (send to back)
     */
    fun pass(roomId: String): QuizResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("Game not initialized")

        if (state.phase != QuizPhase.PLAYING) {
            throw IllegalArgumentException("Game is not in progress")
        }

        val currentTeam = state.teams[state.currentTeamIndex]

        // Send current word to back
        val skippedWord = state.currentWord
        if (skippedWord != null && state.remainingWords.isNotEmpty()) {
            state.remainingWords.add(skippedWord)
        }

        // Next word
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

        // [DEBUG LOG] Pass button processing
        println("[SpeedQuiz] Pass! Team: $currentTeam, Score: ${state.currentRoundScore}, NextWord: ${state.currentWord}, Remaining: ${state.remainingWords.size}")

        sseService.broadcastToAll(roomId, "QUIZ_PASS", result)

        return result
    }

    // ============================================
    // Phase 3: End Round and Next Team
    // ============================================

    /**
     * End Round (Timer end or manual)
     */
    fun endRound(roomId: String): RoundEndResult {
        val state = getState(roomId) ?: throw IllegalArgumentException("Game not found")

        // Cancel timer
        cancelTimer(roomId)

        val currentTeam = state.teams[state.currentTeamIndex]

        // Record team score
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
     * Next Team (To Category Selection Screen)
     */
    fun nextTeam(roomId: String): NextTeamResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("Game not found")

        // Check completion of all teams
        if (state.completedTeams.size >= state.teams.size) {
            throw IllegalArgumentException("All teams completed. Please check ranking.")
        }

        // To next team
        state.currentTeamIndex = (state.currentTeamIndex + 1) % state.teams.size

        // Find next team if already completed
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
    // Phase 4: Final Ranking
    // ============================================

    /**
     * Get Final Ranking
     */
    fun getFinalRanking(roomId: String): FinalRankingResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("Game not found")

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
     * End Game
     */
    fun endGame(roomId: String) {
        cancelTimer(roomId)

        val key = STATE_KEY.format(roomId)
        redisTemplate.delete(key)

        sseService.broadcastToAll(roomId, "QUIZ_GAME_END", mapOf(
            "message" to "Speed quiz has ended!"
        ))
    }

    // ============================================
    // Query API
    // ============================================

    /**
     * Get Current Game State
     */
    fun getGameState(roomId: String): SpeedQuizState? {
        return getState(roomId)
    }

    /**
     * Get Current Word (For Individual Controller)
     */
    fun getCurrentWord(roomId: String): CurrentWordResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("Game not found")

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

                // Broadcast every second
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
                // Ignore error
            }
        }, 1, 1, TimeUnit.SECONDS)

        schedulers[roomId] = task
    }

    private fun cancelTimer(roomId: String) {
        schedulers[roomId]?.cancel(false)
        schedulers.remove(roomId)
    }

    // ============================================
    // Redis Management
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
    val teams: MutableList<String>,                          // Participating teams
    var currentTeamIndex: Int = 0,                           // Current team index
    var roundTimeSeconds: Int = 120,                         // Round time limit (2 minutes)
    var remainingTime: Int = 0,                              // Remaining time
    val teamScores: MutableMap<String, Int> = mutableMapOf(),// Team scores
    val completedTeams: MutableList<String> = mutableListOf(),// Completed teams
    var phase: QuizPhase = QuizPhase.WAITING,                // Current phase
    var currentWord: String? = null,                         // Current word
    var remainingWords: MutableList<String> = mutableListOf(),// Remaining words
    var currentRoundScore: Int = 0                           // Current round score
)

enum class QuizPhase {
    WAITING,    // Waiting for category selection
    PLAYING,    // Game in progress
    ROUND_END,  // Round ended
    FINISHED    // Game finished
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
