package com.soju.recreation.domain.room

import com.soju.recreation.domain.game.GameCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/api/v1")
class RoomController(
    private val roomService: RoomService,
    private val sseService: SseService
) {

    // ============================================
    // 방 관리 API
    // ============================================

    /**
     * 방 생성 (Host 전용)
     * POST /api/v1/rooms
     */
    @PostMapping("/rooms")
    fun createRoom(): ApiResponse<RoomCreateResponse> {
        val room = roomService.createRoom()
        return ApiResponse.success(
            RoomCreateResponse(
                roomId = room.roomId,
                hostSessionId = room.hostSessionId
            )
        )
    }

    /**
     * 방 입장 (User - QR 스캔)
     * POST /api/v1/rooms/join
     */
    @PostMapping("/rooms/join")
    fun joinRoom(@RequestBody request: JoinRequest): ApiResponse<JoinResponse> {
        val player = roomService.joinRoom(request.roomId, request.nickname)
        return ApiResponse.success(
            JoinResponse(
                deviceId = player.deviceId,
                nickname = player.nickname
            )
        )
    }

    /**
     * 방 정보 조회
     * GET /api/v1/rooms/{roomId}
     */
    @GetMapping("/rooms/{roomId}")
    fun getRoomInfo(@PathVariable roomId: String): ApiResponse<RoomInfo> {
        val room = roomService.getRoomInfo(roomId)
            ?: return ApiResponse.error("방을 찾을 수 없습니다: $roomId")
        return ApiResponse.success(room)
    }

    // ============================================
    // SSE 연결
    // ============================================

    /**
     * SSE 연결 (Host 화면)
     * GET /api/v1/sse/connect?roomId={roomId}&sessionId={hostSessionId}
     */
    @GetMapping("/sse/connect")
    fun connectSse(
        @RequestParam roomId: String,
        @RequestParam sessionId: String
    ): SseEmitter {
        // Host 세션 검증
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("방을 찾을 수 없습니다: $roomId")

        if (room.hostSessionId != sessionId) {
            throw IllegalArgumentException("유효하지 않은 Host 세션입니다")
        }

        return sseService.connect(roomId)
    }

    // ============================================
    // 게임 제어 API
    // ============================================

    /**
     * 게임 시작/변경 (Host가 제어)
     * POST /api/v1/games/start
     */
    @PostMapping("/games/start")
    fun startGame(@RequestBody request: GameStartRequest): ApiResponse<Unit> {
        roomService.startGame(request.roomId, request.gameCode)
        return ApiResponse.success(Unit)
    }

    /**
     * 리액션 전송 (User → Host)
     * POST /api/v1/games/reaction
     */
    @PostMapping("/games/reaction")
    fun sendReaction(@RequestBody request: ReactionRequest): ApiResponse<Unit> {
        sseService.broadcast(request.roomId, "REACTION", mapOf(
            "type" to request.type,
            "from" to request.deviceId
        ))
        return ApiResponse.success(Unit)
    }

    // ============================================
    // 예외 처리
    // ============================================

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<ApiResponse<Unit>> {
        return ResponseEntity.badRequest().body(ApiResponse.error(e.message ?: "잘못된 요청입니다"))
    }
}

// ============================================
// Request/Response DTOs
// ============================================

data class ApiResponse<T>(
    val success: Boolean,
    val data: T?,
    val error: String?
) {
    companion object {
        fun <T> success(data: T) = ApiResponse(success = true, data = data, error = null)
        fun <T> error(message: String) = ApiResponse<T>(success = false, data = null, error = message)
    }
}

data class RoomCreateResponse(
    val roomId: String,
    val hostSessionId: String
)

data class JoinRequest(
    val roomId: String,
    val nickname: String
)

data class JoinResponse(
    val deviceId: String,
    val nickname: String
)

data class GameStartRequest(
    val roomId: String,
    val gameCode: GameCode,
    val categoryId: Long? = null
)

data class ReactionRequest(
    val roomId: String,
    val deviceId: String,
    val type: ReactionType
)

enum class ReactionType {
    FIREWORK,  // 폭죽
    BOO,       // 야유
    LAUGH,     // 웃음
    ANGRY      // 화남
}
