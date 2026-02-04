package com.soju.recreation.domain.room

import org.springframework.stereotype.Service

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

        // Host + 모든 플레이어에게 결과 전송
        sseService.broadcastToAll(roomId, "TEAM_ASSIGNED", result)

        return result
    }

    /**
     * 수동 팀 선택
     * 플레이어가 직접 팀을 선택
     */
    fun selectTeam(roomId: String, deviceId: String, teamName: String, teamCount: Int): PlayerTeamInfo {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다: $roomId")

        val player = room.players.find { it.deviceId == deviceId }
            ?: throw IllegalArgumentException("플레이어를 찾을 수 없습니다")

        // 최대 인원 체크
        val totalPlayers = room.players.size
        val maxPerTeam = Math.ceil(totalPlayers.toDouble() / teamCount).toInt()
        val currentMembers = room.players.count { it.team == teamName }
        if (currentMembers >= maxPerTeam) {
            throw IllegalArgumentException("${teamName}팀 인원이 가득 찼습니다 ($currentMembers/$maxPerTeam)")
        }

        player.team = teamName
        roomService.saveRoomInfo(room)

        val result = PlayerTeamInfo(
            nickname = player.nickname,
            deviceId = player.deviceId,
            team = teamName
        )

        // Host + 모든 플레이어에게 팀 선택 알림
        sseService.broadcastToAll(roomId, "PLAYER_TEAM_SELECTED", mapOf(
            "nickname" to player.nickname,
            "team" to teamName,
            "teamCount" to teamCount,
            "teams" to getTeamStatus(room)
        ))

        return result
    }

    /**
     * 팀 초기화 (수동 선택 모드 시작)
     */
    fun resetTeams(roomId: String, teamCount: Int = 2) {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다: $roomId")

        val totalPlayers = room.players.size
        val maxPerTeam = Math.ceil(totalPlayers.toDouble() / teamCount).toInt()

        room.players.forEach { it.team = null }
        roomService.saveRoomInfo(room)

        // Host + 모든 플레이어에게 수동 선택 모드 시작 알림
        sseService.broadcastToAll(roomId, "TEAM_MANUAL_START", mapOf(
            "teamCount" to teamCount,
            "maxPerTeam" to maxPerTeam,
            "teamNames" to getTeamNames(teamCount)
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
        return (1..count).map { it.toString() }
    }
}

// ============================================
// DTOs
// ============================================

data class TeamAssignmentResult(
    val method: String,  // RANDOM, MANUAL
    val teams: Map<String, List<String>>,  // 팀명 -> 닉네임 목록
    val players: List<PlayerTeamInfo>
)

data class PlayerTeamInfo(
    val nickname: String,
    val deviceId: String,
    val team: String
)

