package com.soju.recreation.domain.room

import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/teams")
class TeamController(
    private val teamService: TeamService
) {

    /**
     * 랜덤 팀 배정
     * POST /api/v1/teams/random
     */
    @PostMapping("/random")
    fun assignRandomTeams(@RequestBody request: TeamAssignRequest): ApiResponse<TeamAssignmentResult> {
        val result = teamService.assignRandomTeams(request.roomId, request.teamCount)
        return ApiResponse.success(result)
    }

    /**
     * 수동 팀 선택 (플레이어가 직접)
     * POST /api/v1/teams/select
     */
    @PostMapping("/select")
    fun selectTeam(@RequestBody request: TeamSelectRequest): ApiResponse<PlayerTeamInfo> {
        val result = teamService.selectTeam(request.roomId, request.deviceId, request.teamName, request.teamCount)
        return ApiResponse.success(result)
    }

    /**
     * 팀 초기화
     * POST /api/v1/teams/reset
     */
    @PostMapping("/reset")
    fun resetTeams(@RequestBody request: TeamResetRequest): ApiResponse<Unit> {
        teamService.resetTeams(request.roomId, request.teamCount)
        return ApiResponse.success(Unit)
    }

    /**
     * 현재 팀 상태 조회
     * GET /api/v1/teams/status/{roomId}
     */
    @GetMapping("/status/{roomId}")
    fun getTeamStatus(@PathVariable roomId: String): ApiResponse<Map<String, List<String>>> {
        val status = teamService.getTeamStatus(roomId)
        return ApiResponse.success(status)
    }
}

// ============================================
// Request DTOs
// ============================================

data class TeamAssignRequest(
    val roomId: String,
    val teamCount: Int = 2  // 기본 2팀
)

data class TeamSelectRequest(
    val roomId: String,
    val deviceId: String,
    val teamName: String,
    val teamCount: Int = 2
)

data class TeamResetRequest(
    val roomId: String,
    val teamCount: Int = 2
)

data class RoomIdRequest(
    val roomId: String
)
