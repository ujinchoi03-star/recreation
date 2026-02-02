package com.soju.recreation.domain.game.mafia

import com.soju.recreation.domain.room.ApiResponse
import com.soju.recreation.domain.room.MafiaChatMessage
import com.soju.recreation.domain.room.MafiaGameState
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/games/mafia")
class MafiaController(
    private val mafiaService: MafiaService
) {

    // ============================================
    // 게임 시작
    // ============================================

    /**
     * 마피아 게임 초기화 (역할 배정 + 밤 시작)
     * POST /api/v1/games/mafia/init
     */
    @PostMapping("/init")
    fun initializeGame(@RequestBody request: MafiaRoomRequest): ApiResponse<MafiaGameState> {
        val state = mafiaService.initializeGame(request.roomId)
        return ApiResponse.success(state)
    }

    /**
     * 내 역할 조회 (본인만)
     * GET /api/v1/games/mafia/role?roomId={roomId}&deviceId={deviceId}
     */
    @GetMapping("/role")
    fun getMyRole(
        @RequestParam roomId: String,
        @RequestParam deviceId: String
    ): ApiResponse<PlayerRoleInfo> {
        val roleInfo = mafiaService.getMyRole(roomId, deviceId)
        return ApiResponse.success(roleInfo)
    }

    // ============================================
    // 밤 행동 (Night Actions)
    // ============================================

    /**
     * 마피아 채팅 전송 (밤에만)
     * POST /api/v1/games/mafia/chat
     */
    @PostMapping("/chat")
    fun sendMafiaChat(@RequestBody request: MafiaChatRequest): ApiResponse<MafiaChatMessage> {
        val message = mafiaService.sendMafiaChat(request.roomId, request.deviceId, request.message)
        return ApiResponse.success(message)
    }

    /**
     * 마피아 채팅 조회 (마피아 전용)
     * GET /api/v1/games/mafia/chat?roomId={roomId}&deviceId={deviceId}
     */
    @GetMapping("/chat")
    fun getMafiaChat(
        @RequestParam roomId: String,
        @RequestParam deviceId: String
    ): ApiResponse<List<MafiaChatMessage>> {
        val messages = mafiaService.getMafiaChat(roomId, deviceId)
        return ApiResponse.success(messages)
    }

    /**
     * 마피아 살해 타겟 지목 (밤)
     * POST /api/v1/games/mafia/kill
     */
    @PostMapping("/kill")
    fun mafiaKill(@RequestBody request: MafiaActionRequest): ApiResponse<Unit> {
        mafiaService.mafiaKill(request.roomId, request.deviceId, request.targetDeviceId)
        return ApiResponse.success(Unit)
    }

    /**
     * 의사 살리기 (밤)
     * POST /api/v1/games/mafia/save
     */
    @PostMapping("/save")
    fun doctorSave(@RequestBody request: MafiaActionRequest): ApiResponse<Unit> {
        mafiaService.doctorSave(request.roomId, request.deviceId, request.targetDeviceId)
        return ApiResponse.success(Unit)
    }

    /**
     * 경찰 조사 (밤) - 결과 즉시 반환
     * POST /api/v1/games/mafia/investigate
     */
    @PostMapping("/investigate")
    fun policeInvestigate(@RequestBody request: MafiaActionRequest): ApiResponse<PoliceResult> {
        val result = mafiaService.policeInvestigate(request.roomId, request.deviceId, request.targetDeviceId)
        return ApiResponse.success(result)
    }

    // ============================================
    // 투표 (Voting)
    // ============================================

    /**
     * 투표 (낮 - 처형 대상 선택)
     * POST /api/v1/games/mafia/vote
     */
    @PostMapping("/vote")
    fun vote(@RequestBody request: MafiaVoteRequest): ApiResponse<Unit> {
        mafiaService.vote(request.roomId, request.voterDeviceId, request.targetDeviceId)
        return ApiResponse.success(Unit)
    }

    /**
     * 찬반 투표 (최후의 변론 후)
     * POST /api/v1/games/mafia/final-vote
     */
    @PostMapping("/final-vote")
    fun finalVote(@RequestBody request: FinalVoteRequest): ApiResponse<Unit> {
        mafiaService.finalVote(request.roomId, request.deviceId, request.killVote)
        return ApiResponse.success(Unit)
    }

    // ============================================
    // 조회 API
    // ============================================

    /**
     * 생존 플레이어 목록 조회
     * GET /api/v1/games/mafia/alive/{roomId}
     */
    @GetMapping("/alive/{roomId}")
    fun getAlivePlayers(@PathVariable roomId: String): ApiResponse<List<PlayerInfo>> {
        val players = mafiaService.getAlivePlayers(roomId)
        return ApiResponse.success(players)
    }

    /**
     * 현재 게임 상태 조회
     * GET /api/v1/games/mafia/state/{roomId}
     */
    @GetMapping("/state/{roomId}")
    fun getGameState(@PathVariable roomId: String): ApiResponse<MafiaGameState> {
        val state = mafiaService.getGameState(roomId)
            ?: return ApiResponse.error("게임이 시작되지 않았습니다")
        return ApiResponse.success(state)
    }
}

// ============================================
// Request DTOs
// ============================================

data class MafiaRoomRequest(
    val roomId: String
)

data class MafiaActionRequest(
    val roomId: String,
    val deviceId: String,
    val targetDeviceId: String
)

data class MafiaVoteRequest(
    val roomId: String,
    val voterDeviceId: String,
    val targetDeviceId: String
)

data class FinalVoteRequest(
    val roomId: String,
    val deviceId: String,
    val killVote: Boolean  // true = 처형, false = 생존
)

data class MafiaChatRequest(
    val roomId: String,
    val deviceId: String,
    val message: String
)
