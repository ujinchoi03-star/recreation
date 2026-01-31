package com.soju.recreation.domain.game.mafia

import com.fasterxml.jackson.databind.ObjectMapper
import com.soju.recreation.domain.room.*
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class MafiaService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val roomService: RoomService,
    private val sseService: SseService,
    private val timerService: MafiaTimerService
) {
    private val logger = LoggerFactory.getLogger(MafiaService::class.java)

    companion object {
        private const val STATE_KEY = "room:%s:state"
        private const val TTL_HOURS = 6L
    }

    // ============================================
    // 게임 초기화
    // ============================================

    /**
     * 게임 초기화 - 역할 배정 후 밤 시작
     */
    fun initializeGame(roomId: String): MafiaGameState {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다: $roomId")

        val playerCount = room.players.size
        if (playerCount < 4) {
            throw IllegalArgumentException("마피아 게임은 최소 4명이 필요합니다")
        }

        // 역할 배정
        val mafiaCount = when {
            playerCount <= 5 -> 1
            playerCount <= 8 -> 2
            else -> 3
        }

        val roles = mutableListOf<MafiaRole>()
        repeat(mafiaCount) { roles.add(MafiaRole.MAFIA) }

        if (playerCount >= 6) roles.add(MafiaRole.DOCTOR)
        if (playerCount >= 7) roles.add(MafiaRole.POLICE)

        while (roles.size < playerCount) {
            roles.add(MafiaRole.CIVILIAN)
        }

        roles.shuffle()
        room.players.forEachIndexed { index, player ->
            player.role = roles[index]
            player.isAlive = true
        }
        roomService.saveRoomInfo(room)

        val state = MafiaGameState(
            phase = MafiaPhase.NIGHT,
            timerSec = MafiaPhase.NIGHT.durationSeconds,
            dayCount = 1
        )

        saveState(roomId, state)

        // Host에게 게임 시작 알림
        sseService.broadcast(roomId, "MAFIA_INIT", mapOf(
            "playerCount" to playerCount,
            "mafiaCount" to mafiaCount,
            "phase" to state.phase.name,
            "timer" to state.timerSec,
            "players" to room.players.map { mapOf("nickname" to it.nickname, "deviceId" to it.deviceId) },
            "audio" to "night_start",
            "bgm" to "night"
        ))

        // 밤 타이머 시작
        startPhaseTimer(roomId, state.phase)

        return state
    }

    // ============================================
    // 밤 행동
    // ============================================

    /**
     * 플레이어별 역할 조회 (본인만)
     */
    fun getMyRole(roomId: String, deviceId: String): PlayerRoleInfo {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다")

        val player = room.players.find { it.deviceId == deviceId }
            ?: throw IllegalArgumentException("플레이어를 찾을 수 없습니다")

        val teammates = if (player.role == MafiaRole.MAFIA) {
            room.players
                .filter { it.role == MafiaRole.MAFIA && it.deviceId != deviceId }
                .map { it.nickname }
        } else {
            emptyList()
        }

        return PlayerRoleInfo(
            role = player.role!!,
            teammates = teammates,
            isAlive = player.isAlive
        )
    }

    /**
     * 마피아 채팅 전송
     */
    fun sendMafiaChat(roomId: String, deviceId: String, message: String): MafiaChatMessage {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        if (state.phase != MafiaPhase.NIGHT) {
            throw IllegalArgumentException("밤에만 채팅할 수 있습니다")
        }

        val room = roomService.getRoomInfo(roomId)!!
        val player = room.players.find { it.deviceId == deviceId }
            ?: throw IllegalArgumentException("플레이어를 찾을 수 없습니다")

        if (player.role != MafiaRole.MAFIA) {
            throw IllegalArgumentException("마피아만 채팅할 수 있습니다")
        }

        val chatMessage = MafiaChatMessage(
            senderDeviceId = deviceId,
            senderNickname = player.nickname,
            message = message
        )

        state.mafiaChat.add(chatMessage)
        saveState(roomId, state)

        // 마피아들에게만 채팅 전달 (개인 폰)
        // 실제로는 각 마피아 클라이언트가 폴링하거나 별도 채널로 받아야 함
        // 여기서는 SSE로 전체 브로드캐스트하되, 프론트에서 필터링

        return chatMessage
    }

    /**
     * 마피아 채팅 조회 (마피아 전용)
     */
    fun getMafiaChat(roomId: String, deviceId: String): List<MafiaChatMessage> {
        val room = roomService.getRoomInfo(roomId)!!
        val player = room.players.find { it.deviceId == deviceId }
            ?: throw IllegalArgumentException("플레이어를 찾을 수 없습니다")

        if (player.role != MafiaRole.MAFIA) {
            throw IllegalArgumentException("마피아만 채팅을 볼 수 있습니다")
        }

        val state = getState(roomId) ?: return emptyList()
        return state.mafiaChat.toList()
    }

    /**
     * 마피아 살해 타겟 지목
     */
    fun mafiaKill(roomId: String, mafiaDeviceId: String, targetDeviceId: String) {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        if (state.phase != MafiaPhase.NIGHT) {
            throw IllegalArgumentException("밤에만 타겟을 지목할 수 있습니다")
        }

        val room = roomService.getRoomInfo(roomId)!!
        val mafia = room.players.find { it.deviceId == mafiaDeviceId }
            ?: throw IllegalArgumentException("플레이어를 찾을 수 없습니다")

        if (mafia.role != MafiaRole.MAFIA || !mafia.isAlive) {
            throw IllegalArgumentException("살아있는 마피아만 타겟을 지목할 수 있습니다")
        }

        val target = room.players.find { it.deviceId == targetDeviceId }
            ?: throw IllegalArgumentException("타겟을 찾을 수 없습니다")

        if (!target.isAlive) {
            throw IllegalArgumentException("이미 죽은 플레이어입니다")
        }

        state.mafiaTarget = targetDeviceId
        saveState(roomId, state)

        // Host에게 알림 (누군지는 비공개)
        sseService.broadcast(roomId, "MAFIA_ACTION", mapOf(
            "action" to "KILL_SELECTED"
        ))

        checkNightComplete(roomId)
    }

    /**
     * 의사 살리기
     */
    fun doctorSave(roomId: String, doctorDeviceId: String, targetDeviceId: String) {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        if (state.phase != MafiaPhase.NIGHT) {
            throw IllegalArgumentException("밤에만 행동할 수 있습니다")
        }

        val room = roomService.getRoomInfo(roomId)!!
        val doctor = room.players.find { it.deviceId == doctorDeviceId }
            ?: throw IllegalArgumentException("플레이어를 찾을 수 없습니다")

        if (doctor.role != MafiaRole.DOCTOR || !doctor.isAlive) {
            throw IllegalArgumentException("살아있는 의사만 행동할 수 있습니다")
        }

        state.doctorTarget = targetDeviceId
        saveState(roomId, state)

        sseService.broadcast(roomId, "MAFIA_ACTION", mapOf(
            "action" to "SAVE_SELECTED"
        ))

        checkNightComplete(roomId)
    }

    /**
     * 경찰 조사 - 결과 즉시 반환 (개인 폰에 표시)
     */
    fun policeInvestigate(roomId: String, policeDeviceId: String, targetDeviceId: String): PoliceResult {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        if (state.phase != MafiaPhase.NIGHT) {
            throw IllegalArgumentException("밤에만 조사할 수 있습니다")
        }

        val room = roomService.getRoomInfo(roomId)!!
        val police = room.players.find { it.deviceId == policeDeviceId }
            ?: throw IllegalArgumentException("플레이어를 찾을 수 없습니다")

        if (police.role != MafiaRole.POLICE || !police.isAlive) {
            throw IllegalArgumentException("살아있는 경찰만 조사할 수 있습니다")
        }

        val target = room.players.find { it.deviceId == targetDeviceId }
            ?: throw IllegalArgumentException("타겟을 찾을 수 없습니다")

        state.policeTarget = targetDeviceId
        saveState(roomId, state)

        val isMafia = target.role == MafiaRole.MAFIA

        sseService.broadcast(roomId, "MAFIA_ACTION", mapOf(
            "action" to "INVESTIGATE_DONE"
        ))

        checkNightComplete(roomId)

        return PoliceResult(
            targetNickname = target.nickname,
            isMafia = isMafia
        )
    }

    /**
     * 밤 행동 완료 체크 - 모든 역할이 행동했으면 자동으로 낮 전환
     */
    private fun checkNightComplete(roomId: String) {
        val state = getState(roomId) ?: return
        val room = roomService.getRoomInfo(roomId) ?: return

        val aliveMafia = room.players.any { it.role == MafiaRole.MAFIA && it.isAlive }
        val aliveDoctor = room.players.any { it.role == MafiaRole.DOCTOR && it.isAlive }
        val alivePolice = room.players.any { it.role == MafiaRole.POLICE && it.isAlive }

        val mafiaReady = !aliveMafia || state.mafiaTarget != null
        val doctorReady = !aliveDoctor || state.doctorTarget != null
        val policeReady = !alivePolice || state.policeTarget != null

        if (mafiaReady && doctorReady && policeReady) {
            logger.info("All night actions complete for room $roomId, transitioning to day")
            timerService.cancelTimer(roomId)
            transitionToDay(roomId)
        }
    }

    // ============================================
    // 페이즈 전환
    // ============================================

    /**
     * 밤 → 낮 발표
     */
    private fun transitionToDay(roomId: String) {
        val state = getState(roomId) ?: return
        val room = roomService.getRoomInfo(roomId) ?: return

        var killedNickname: String? = null
        var wasSaved = false

        // 마피아 타겟 처리
        if (state.mafiaTarget != null) {
            if (state.mafiaTarget == state.doctorTarget) {
                // 의사가 살림
                wasSaved = true
            } else {
                // 사망 처리
                val target = room.players.find { it.deviceId == state.mafiaTarget }
                if (target != null && target.isAlive) {
                    target.isAlive = false
                    state.deadPlayers.add(target.deviceId)
                    killedNickname = target.nickname
                }
            }
        }

        state.lastNightKilled = killedNickname
        state.wasSaved = wasSaved
        state.phase = MafiaPhase.DAY_ANNOUNCEMENT
        state.timerSec = MafiaPhase.DAY_ANNOUNCEMENT.durationSeconds

        // 밤 행동 초기화
        state.mafiaTarget = null
        state.doctorTarget = null
        state.policeTarget = null
        state.mafiaChat.clear()

        roomService.saveRoomInfo(room)
        saveState(roomId, state)

        // 승리 체크
        val winner = checkWinner(room)
        if (winner != null) {
            endGame(roomId, winner)
            return
        }

        // Host에게 발표 내용 전송
        sseService.broadcast(roomId, "MAFIA_DAY_ANNOUNCEMENT", mapOf(
            "phase" to state.phase.name,
            "timer" to state.timerSec,
            "killedPlayer" to killedNickname,
            "wasSaved" to wasSaved,
            "dayCount" to state.dayCount,
            "alivePlayers" to room.players.filter { it.isAlive }.map {
                mapOf("nickname" to it.nickname, "deviceId" to it.deviceId)
            },
            "audio" to "day_start",
            "bgm" to "day"
        ))

        startPhaseTimer(roomId, state.phase)
    }

    /**
     * 발표 → 토론
     */
    private fun transitionToDiscussion(roomId: String) {
        val state = getState(roomId) ?: return

        state.phase = MafiaPhase.DAY_DISCUSSION
        state.timerSec = MafiaPhase.DAY_DISCUSSION.durationSeconds

        saveState(roomId, state)

        sseService.broadcast(roomId, "MAFIA_PHASE_CHANGE", mapOf(
            "phase" to state.phase.name,
            "timer" to state.timerSec,
            "message" to "토론을 시작하세요!",
            "audio" to "discussion"
        ))

        startPhaseTimer(roomId, state.phase)
    }

    /**
     * 토론 → 투표
     */
    private fun transitionToVote(roomId: String) {
        val state = getState(roomId) ?: return

        state.phase = MafiaPhase.VOTE
        state.timerSec = MafiaPhase.VOTE.durationSeconds
        state.votes.clear()

        saveState(roomId, state)

        val room = roomService.getRoomInfo(roomId)!!

        sseService.broadcast(roomId, "MAFIA_VOTE_START", mapOf(
            "phase" to state.phase.name,
            "timer" to state.timerSec,
            "candidates" to room.players.filter { it.isAlive }.map {
                mapOf("nickname" to it.nickname, "deviceId" to it.deviceId)
            },
            "audio" to "vote_start"
        ))

        startPhaseTimer(roomId, state.phase)
    }

    /**
     * 투표 제출
     */
    fun vote(roomId: String, voterDeviceId: String, targetDeviceId: String) {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        if (state.phase != MafiaPhase.VOTE) {
            throw IllegalArgumentException("투표 시간이 아닙니다")
        }

        val room = roomService.getRoomInfo(roomId)!!
        val voter = room.players.find { it.deviceId == voterDeviceId }
            ?: throw IllegalArgumentException("투표자를 찾을 수 없습니다")

        if (!voter.isAlive) {
            throw IllegalArgumentException("죽은 플레이어는 투표할 수 없습니다")
        }

        state.votes[voterDeviceId] = targetDeviceId
        saveState(roomId, state)

        val target = room.players.find { it.deviceId == targetDeviceId }

        // 실시간 투표 현황 브로드캐스트
        sseService.broadcast(roomId, "MAFIA_VOTE_UPDATE", mapOf(
            "voterNickname" to voter.nickname,
            "targetNickname" to (target?.nickname ?: "기권"),
            "votedCount" to state.votes.size,
            "totalAlive" to room.players.count { it.isAlive }
        ))
    }

    /**
     * 투표 → 결과 발표
     */
    private fun transitionToVoteResult(roomId: String) {
        val state = getState(roomId) ?: return
        val room = roomService.getRoomInfo(roomId) ?: return

        // 투표 집계
        val voteCount = state.votes.values.groupingBy { it }.eachCount()
        val maxVotes = voteCount.maxByOrNull { it.value }

        var executionTarget: String? = null
        var targetNickname: String? = null

        if (maxVotes != null) {
            val maxVoteCount = maxVotes.value
            val candidates = voteCount.filter { it.value == maxVoteCount }

            // 동률이 아닌 경우에만 처형 대상 지정
            if (candidates.size == 1) {
                executionTarget = maxVotes.key
                targetNickname = room.players.find { it.deviceId == executionTarget }?.nickname
            }
        }

        state.executionTarget = executionTarget
        state.phase = MafiaPhase.VOTE_RESULT
        state.timerSec = MafiaPhase.VOTE_RESULT.durationSeconds

        saveState(roomId, state)

        // 투표 결과 상세
        val voteDetails = state.votes.map { (voterId, targetId) ->
            val voterName = room.players.find { it.deviceId == voterId }?.nickname ?: "?"
            val targetName = room.players.find { it.deviceId == targetId }?.nickname ?: "?"
            mapOf("voter" to voterName, "target" to targetName)
        }

        sseService.broadcast(roomId, "MAFIA_VOTE_RESULT", mapOf(
            "phase" to state.phase.name,
            "timer" to state.timerSec,
            "executionTarget" to targetNickname,
            "voteDetails" to voteDetails,
            "isTie" to (executionTarget == null && state.votes.isNotEmpty())
        ))

        startPhaseTimer(roomId, state.phase)
    }

    /**
     * 결과 발표 → 최후의 변론 또는 밤
     */
    private fun transitionAfterVoteResult(roomId: String) {
        val state = getState(roomId) ?: return

        if (state.executionTarget != null) {
            // 처형 대상이 있으면 최후의 변론
            transitionToFinalDefense(roomId)
        } else {
            // 동률 또는 기권 → 바로 밤으로
            transitionToNight(roomId)
        }
    }

    /**
     * 최후의 변론
     */
    private fun transitionToFinalDefense(roomId: String) {
        val state = getState(roomId) ?: return
        val room = roomService.getRoomInfo(roomId) ?: return

        state.phase = MafiaPhase.FINAL_DEFENSE
        state.timerSec = MafiaPhase.FINAL_DEFENSE.durationSeconds

        saveState(roomId, state)

        val targetNickname = room.players.find { it.deviceId == state.executionTarget }?.nickname

        sseService.broadcast(roomId, "MAFIA_FINAL_DEFENSE", mapOf(
            "phase" to state.phase.name,
            "timer" to state.timerSec,
            "defendant" to targetNickname,
            "audio" to "final_defense"
        ))

        startPhaseTimer(roomId, state.phase)
    }

    /**
     * 최후의 변론 → 찬반 투표
     */
    private fun transitionToFinalVote(roomId: String) {
        val state = getState(roomId) ?: return
        val room = roomService.getRoomInfo(roomId) ?: return

        state.phase = MafiaPhase.FINAL_VOTE
        state.timerSec = MafiaPhase.FINAL_VOTE.durationSeconds
        state.finalVotes.clear()

        saveState(roomId, state)

        val targetNickname = room.players.find { it.deviceId == state.executionTarget }?.nickname

        sseService.broadcast(roomId, "MAFIA_FINAL_VOTE_START", mapOf(
            "phase" to state.phase.name,
            "timer" to state.timerSec,
            "defendant" to targetNickname,
            "audio" to "final_vote"
        ))

        startPhaseTimer(roomId, state.phase)
    }

    /**
     * 찬반 투표 제출
     */
    fun finalVote(roomId: String, voterDeviceId: String, killVote: Boolean) {
        val state = getState(roomId)
            ?: throw IllegalArgumentException("게임이 시작되지 않았습니다")

        if (state.phase != MafiaPhase.FINAL_VOTE) {
            throw IllegalArgumentException("찬반 투표 시간이 아닙니다")
        }

        val room = roomService.getRoomInfo(roomId)!!
        val voter = room.players.find { it.deviceId == voterDeviceId }
            ?: throw IllegalArgumentException("투표자를 찾을 수 없습니다")

        if (!voter.isAlive) {
            throw IllegalArgumentException("죽은 플레이어는 투표할 수 없습니다")
        }

        // 처형 대상자는 투표 불가
        if (voterDeviceId == state.executionTarget) {
            throw IllegalArgumentException("처형 대상자는 투표할 수 없습니다")
        }

        state.finalVotes[voterDeviceId] = killVote
        saveState(roomId, state)

        // 실시간 투표 현황 브로드캐스트
        sseService.broadcast(roomId, "MAFIA_FINAL_VOTE_UPDATE", mapOf(
            "voterNickname" to voter.nickname,
            "vote" to if (killVote) "처형" else "생존",
            "votedCount" to state.finalVotes.size,
            "totalVoters" to (room.players.count { it.isAlive } - 1) // 피고인 제외
        ))
    }

    /**
     * 찬반 투표 결과 처리
     */
    private fun transitionToFinalVoteResult(roomId: String) {
        val state = getState(roomId) ?: return
        val room = roomService.getRoomInfo(roomId) ?: return

        val killVotes = state.finalVotes.count { it.value }
        val saveVotes = state.finalVotes.count { !it.value }

        val isExecuted = killVotes > saveVotes

        state.phase = MafiaPhase.FINAL_VOTE_RESULT
        state.timerSec = MafiaPhase.FINAL_VOTE_RESULT.durationSeconds

        val targetNickname = room.players.find { it.deviceId == state.executionTarget }?.nickname

        if (isExecuted && state.executionTarget != null) {
            val target = room.players.find { it.deviceId == state.executionTarget }
            if (target != null) {
                target.isAlive = false
                state.deadPlayers.add(target.deviceId)
            }
            roomService.saveRoomInfo(room)
        }

        saveState(roomId, state)

        // 투표 상세
        val voteDetails = state.finalVotes.map { (voterId, kill) ->
            val voterName = room.players.find { it.deviceId == voterId }?.nickname ?: "?"
            mapOf("voter" to voterName, "vote" to if (kill) "처형" else "생존")
        }

        sseService.broadcast(roomId, "MAFIA_FINAL_VOTE_RESULT", mapOf(
            "phase" to state.phase.name,
            "timer" to state.timerSec,
            "defendant" to targetNickname,
            "isExecuted" to isExecuted,
            "killVotes" to killVotes,
            "saveVotes" to saveVotes,
            "voteDetails" to voteDetails
        ))

        // 승리 체크
        val winner = checkWinner(room)
        if (winner != null) {
            endGame(roomId, winner)
            return
        }

        startPhaseTimer(roomId, state.phase)
    }

    /**
     * 밤으로 전환
     */
    private fun transitionToNight(roomId: String) {
        val state = getState(roomId) ?: return

        state.dayCount++
        state.phase = MafiaPhase.NIGHT
        state.timerSec = MafiaPhase.NIGHT.durationSeconds
        state.votes.clear()
        state.finalVotes.clear()
        state.executionTarget = null
        state.lastNightKilled = null
        state.wasSaved = false

        saveState(roomId, state)

        val room = roomService.getRoomInfo(roomId)!!

        sseService.broadcast(roomId, "MAFIA_NIGHT", mapOf(
            "phase" to state.phase.name,
            "timer" to state.timerSec,
            "dayCount" to state.dayCount,
            "message" to "밤이 되었습니다. 마피아가 활동합니다...",
            "alivePlayers" to room.players.filter { it.isAlive }.map {
                mapOf("nickname" to it.nickname, "deviceId" to it.deviceId)
            },
            "audio" to "night_start",
            "bgm" to "night"
        ))

        startPhaseTimer(roomId, state.phase)
    }

    // ============================================
    // 타이머 관리
    // ============================================

    private fun startPhaseTimer(roomId: String, phase: MafiaPhase) {
        timerService.startTimer(
            roomId = roomId,
            phase = phase,
            onTick = { remaining ->
                // 매초 남은 시간 브로드캐스트
                sseService.broadcast(roomId, "MAFIA_TIMER", mapOf(
                    "remaining" to remaining,
                    "phase" to phase.name
                ))
            },
            onComplete = {
                // 페이즈 완료 시 다음 페이즈로 전환
                onPhaseComplete(roomId, phase)
            }
        )
    }

    private fun onPhaseComplete(roomId: String, completedPhase: MafiaPhase) {
        logger.info("Phase $completedPhase completed for room $roomId")

        when (completedPhase) {
            MafiaPhase.NIGHT -> transitionToDay(roomId)
            MafiaPhase.DAY_ANNOUNCEMENT -> transitionToDiscussion(roomId)
            MafiaPhase.DAY_DISCUSSION -> transitionToVote(roomId)
            MafiaPhase.VOTE -> transitionToVoteResult(roomId)
            MafiaPhase.VOTE_RESULT -> transitionAfterVoteResult(roomId)
            MafiaPhase.FINAL_DEFENSE -> transitionToFinalVote(roomId)
            MafiaPhase.FINAL_VOTE -> transitionToFinalVoteResult(roomId)
            MafiaPhase.FINAL_VOTE_RESULT -> transitionToNight(roomId)
            MafiaPhase.GAME_END -> { /* 게임 종료 */ }
        }
    }

    // ============================================
    // 게임 종료
    // ============================================

    private fun checkWinner(room: RoomInfo): MafiaWinner? {
        val alivePlayers = room.players.filter { it.isAlive }
        val aliveMafia = alivePlayers.count { it.role == MafiaRole.MAFIA }
        val aliveCitizens = alivePlayers.count { it.role != MafiaRole.MAFIA }

        return when {
            aliveMafia == 0 -> MafiaWinner.CITIZEN
            aliveMafia >= aliveCitizens -> MafiaWinner.MAFIA
            else -> null
        }
    }

    private fun endGame(roomId: String, winner: MafiaWinner) {
        val state = getState(roomId) ?: return
        val room = roomService.getRoomInfo(roomId) ?: return

        state.phase = MafiaPhase.GAME_END
        state.winner = winner
        state.timerSec = 0

        saveState(roomId, state)
        timerService.cleanup(roomId)

        // 모든 역할 공개
        val playerRoles = room.players.map {
            mapOf(
                "nickname" to it.nickname,
                "role" to it.role?.name,
                "isAlive" to it.isAlive
            )
        }

        sseService.broadcast(roomId, "MAFIA_GAME_END", mapOf(
            "winner" to winner.name,
            "winnerText" to if (winner == MafiaWinner.MAFIA) "마피아 승리!" else "시민 승리!",
            "playerRoles" to playerRoles,
            "audio" to if (winner == MafiaWinner.MAFIA) "mafia_win" else "citizen_win",
            "bgm" to "none"
        ))
    }

    // ============================================
    // 조회 API
    // ============================================

    fun getAlivePlayers(roomId: String): List<PlayerInfo> {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다")

        return room.players.filter { it.isAlive }.map {
            PlayerInfo(
                deviceId = it.deviceId,
                nickname = it.nickname,
                isAlive = it.isAlive
            )
        }
    }

    fun getGameState(roomId: String): MafiaGameState? {
        return getState(roomId)
    }

    // ============================================
    // Redis 상태 관리
    // ============================================

    private fun saveState(roomId: String, state: MafiaGameState) {
        val key = STATE_KEY.format(roomId)
        val json = objectMapper.writeValueAsString(state)
        redisTemplate.opsForValue().set(key, json)
        redisTemplate.expire(key, TTL_HOURS, TimeUnit.HOURS)
    }

    private fun getState(roomId: String): MafiaGameState? {
        val key = STATE_KEY.format(roomId)
        val json = redisTemplate.opsForValue().get(key) ?: return null
        return objectMapper.readValue(json, MafiaGameState::class.java)
    }
}

// ============================================
// DTOs
// ============================================

data class PlayerRoleInfo(
    val role: MafiaRole,
    val teammates: List<String>,
    val isAlive: Boolean
)

data class PlayerInfo(
    val deviceId: String,
    val nickname: String,
    val isAlive: Boolean
)

data class PoliceResult(
    val targetNickname: String,
    val isMafia: Boolean
)
