package com.soju.recreation.domain.room

import org.springframework.stereotype.Service
import kotlin.random.Random

@Service
class TeamService(
    private val roomService: RoomService,
    private val sseService: SseService
) {

    /**
     * 랜덤 팀 배정
     * 플레이어를 균등하게 팀으로 나눔
     */
    fun assignRandomTeams(roomId: String, teamCount: Int = 2): TeamAssignmentResult {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다: $roomId")

        if (room.players.isEmpty()) {
            throw IllegalArgumentException("플레이어가 없습니다")
        }

        val teamNames = getTeamNames(teamCount)
        val shuffledPlayers = room.players.shuffled()

        // 균등하게 팀 배정
        shuffledPlayers.forEachIndexed { index, player ->
            player.team = teamNames[index % teamCount]
        }

        roomService.saveRoomInfo(room)

        val teams = teamNames.associateWith { teamName ->
            room.players.filter { it.team == teamName }.map { it.nickname }
        }

        val result = TeamAssignmentResult(
            method = "RANDOM",
            teams = teams,
            players = room.players.map { PlayerTeamInfo(it.nickname, it.deviceId, it.team!!) }
        )

        // Host에게 결과 전송
        sseService.broadcast(roomId, "TEAM_ASSIGNED", result)

        return result
    }

    /**
     * 사다리타기 결과 생성
     * 프론트에서 애니메이션 표시용 데이터 반환
     */
    fun generateLadderGame(roomId: String, teamCount: Int = 2): LadderGameResult {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다: $roomId")

        if (room.players.isEmpty()) {
            throw IllegalArgumentException("플레이어가 없습니다")
        }

        val playerCount = room.players.size
        val teamNames = getTeamNames(teamCount)

        // 사다리 가로선 생성 (랜덤)
        val ladderHeight = 10  // 사다리 높이 (가로선이 들어갈 수 있는 위치 수)
        val horizontalLines = mutableListOf<LadderLine>()

        for (row in 0 until ladderHeight) {
            for (col in 0 until playerCount - 1) {
                // 50% 확률로 가로선 생성 (연속된 가로선 방지)
                val prevHasLine = horizontalLines.any { it.row == row && it.col == col - 1 }
                if (!prevHasLine && Random.nextBoolean()) {
                    horizontalLines.add(LadderLine(row, col))
                }
            }
        }

        // 각 플레이어의 사다리 결과 계산
        val results = room.players.mapIndexed { startIndex, player ->
            var currentCol = startIndex

            for (row in 0 until ladderHeight) {
                // 오른쪽으로 가는 가로선 있는지
                if (horizontalLines.any { it.row == row && it.col == currentCol }) {
                    currentCol++
                }
                // 왼쪽으로 가는 가로선 있는지
                else if (horizontalLines.any { it.row == row && it.col == currentCol - 1 }) {
                    currentCol--
                }
            }

            LadderPlayerResult(
                nickname = player.nickname,
                deviceId = player.deviceId,
                startPosition = startIndex,
                endPosition = currentCol
            )
        }

        // 도착 위치에 따라 팀 배정
        val sortedByEnd = results.sortedBy { it.endPosition }
        val playersPerTeam = (playerCount + teamCount - 1) / teamCount

        sortedByEnd.forEachIndexed { index, result ->
            val teamIndex = index / playersPerTeam
            val teamName = teamNames[teamIndex.coerceAtMost(teamCount - 1)]
            val player = room.players.find { it.deviceId == result.deviceId }
            player?.team = teamName
        }

        roomService.saveRoomInfo(room)

        val teams = teamNames.associateWith { teamName ->
            room.players.filter { it.team == teamName }.map { it.nickname }
        }

        val ladderResult = LadderGameResult(
            playerCount = playerCount,
            ladderHeight = ladderHeight,
            horizontalLines = horizontalLines,
            playerResults = results,
            teams = teams
        )

        // Host에게 사다리 데이터 전송 (애니메이션용)
        sseService.broadcast(roomId, "LADDER_GAME", ladderResult)

        return ladderResult
    }

    /**
     * 수동 팀 선택
     * 플레이어가 직접 팀을 선택
     */
    fun selectTeam(roomId: String, deviceId: String, teamName: String): PlayerTeamInfo {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다: $roomId")

        val player = room.players.find { it.deviceId == deviceId }
            ?: throw IllegalArgumentException("플레이어를 찾을 수 없습니다")

        player.team = teamName
        roomService.saveRoomInfo(room)

        val result = PlayerTeamInfo(
            nickname = player.nickname,
            deviceId = player.deviceId,
            team = teamName
        )

        // Host에게 팀 선택 알림
        sseService.broadcast(roomId, "PLAYER_TEAM_SELECTED", mapOf(
            "nickname" to player.nickname,
            "team" to teamName,
            "teams" to getTeamStatus(room)
        ))

        return result
    }

    /**
     * 팀 초기화 (팀 배정 취소)
     */
    fun resetTeams(roomId: String) {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다: $roomId")

        room.players.forEach { it.team = null }
        roomService.saveRoomInfo(room)

        sseService.broadcast(roomId, "TEAMS_RESET", mapOf(
            "message" to "팀이 초기화되었습니다"
        ))
    }

    /**
     * 현재 팀 상태 조회
     */
    fun getTeamStatus(roomId: String): Map<String, List<String>> {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다: $roomId")

        return getTeamStatus(room)
    }

    private fun getTeamStatus(room: RoomInfo): Map<String, List<String>> {
        val assigned = room.players.filter { it.team != null }
            .groupBy { it.team!! }
            .mapValues { entry -> entry.value.map { it.nickname } }

        val unassigned = room.players.filter { it.team == null }.map { it.nickname }

        return assigned + ("미배정" to unassigned)
    }

    private fun getTeamNames(count: Int): List<String> {
        return when (count) {
            2 -> listOf("A", "B")
            3 -> listOf("A", "B", "C")
            4 -> listOf("A", "B", "C", "D")
            else -> (1..count).map { "Team$it" }
        }
    }
}

// ============================================
// DTOs
// ============================================

data class TeamAssignmentResult(
    val method: String,  // RANDOM, LADDER, MANUAL
    val teams: Map<String, List<String>>,  // 팀명 -> 닉네임 목록
    val players: List<PlayerTeamInfo>
)

data class PlayerTeamInfo(
    val nickname: String,
    val deviceId: String,
    val team: String
)

data class LadderGameResult(
    val playerCount: Int,
    val ladderHeight: Int,
    val horizontalLines: List<LadderLine>,  // 프론트 애니메이션용
    val playerResults: List<LadderPlayerResult>,
    val teams: Map<String, List<String>>
)

data class LadderLine(
    val row: Int,  // 세로 위치 (0부터)
    val col: Int   // 가로 위치 (col과 col+1 사이에 선)
)

data class LadderPlayerResult(
    val nickname: String,
    val deviceId: String,
    val startPosition: Int,  // 시작 위치 (왼쪽부터 0)
    val endPosition: Int     // 도착 위치
)
