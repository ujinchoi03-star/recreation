package com.soju.recreation.domain.room

import com.soju.recreation.domain.game.GameCode
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/api/v1")
class RoomController(
    private val roomService: RoomService,
    private val sseService: SseService
) {

    // ============================================
    // Room Management API
    // ============================================

    /**
     * Create Room (Host only)
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
     * Join Room (User - QR Scan)
     * POST /api/v1/rooms/join
     */
    @PostMapping("/rooms/join")
    fun joinRoom(@Valid @RequestBody request: JoinRequest): ApiResponse<JoinResponse> {
        val player = roomService.joinRoom(request.roomId, request.nickname)
        return ApiResponse.success(
            JoinResponse(
                deviceId = player.deviceId,
                nickname = player.nickname
            )
        )
    }

    /**
     * Get Room Info
     * GET /api/v1/rooms/{roomId}
     */
    @GetMapping("/rooms/{roomId}")
    fun getRoomInfo(@PathVariable roomId: String): ApiResponse<RoomInfo> {
        val room = roomService.getRoomInfo(roomId)
            ?: return ApiResponse.error("Room not found: $roomId")
        return ApiResponse.success(room)
    }

    // ============================================
    // SSE Connection
    // ============================================

    /**
     * SSE Connect (Host Screen)
     * GET /api/v1/sse/connect?roomId={roomId}&sessionId={hostSessionId}
     */
    @GetMapping("/sse/connect")
    fun connectSse(
        @RequestParam roomId: String,
        @RequestParam sessionId: String
    ): SseEmitter {
        // Validate Host Session
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("Room not found: $roomId")

        if (room.hostSessionId != sessionId) {
            throw IllegalArgumentException("Invalid Host Session")
        }

        return sseService.connect(roomId)
    }

    /**
     * SSE Connect (Player Screen)
     * GET /api/v1/sse/player/connect?roomId={roomId}&deviceId={deviceId}
     */
    @GetMapping("/sse/player/connect")
    fun connectPlayerSse(
        @RequestParam roomId: String,
        @RequestParam deviceId: String
    ): SseEmitter {
        val room = roomService.getRoomInfo(roomId)
            ?: throw IllegalArgumentException("Room not found: $roomId")

        // Check if player exists in room
        val playerExists = room.players.any { it.deviceId == deviceId }
        if (!playerExists) {
            throw IllegalArgumentException("Player not found in this room")
        }

        return sseService.connectPlayer(roomId, deviceId)
    }

    // ============================================
    // Game Control API
    // ============================================

    /**
     * Start/Change Game (Host)
     * POST /api/v1/games/start
     */
    @PostMapping("/games/start")
    fun startGame(@Valid @RequestBody request: GameStartRequest): ApiResponse<Unit> {
        roomService.startGame(request.roomId, request.gameCode)
        return ApiResponse.success(Unit)
    }

    /**
     * Change Phase (Host -> Broadcast)
     * POST /api/v1/phase/change
     */
    @PostMapping("/phase/change")
    fun changePhase(@RequestBody request: PhaseChangeRequest): ApiResponse<Unit> {
        val room = roomService.getRoomInfo(request.roomId)
            ?: throw IllegalArgumentException("Room not found: ${request.roomId}")

        sseService.broadcastToAll(request.roomId, "MARBLE_PHASE_CHANGE", mapOf(
            "phase" to request.phase
        ))
        return ApiResponse.success(Unit)
    }

    /**
     * Send Reaction (User -> Host)
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
    // Exception Handling
    // ============================================

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<ApiResponse<Unit>> {
        return ResponseEntity.badRequest().body(ApiResponse.error(e.message ?: "Invalid Request"))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationError(e: MethodArgumentNotValidException): ResponseEntity<ApiResponse<Unit>> {
        val errors = e.bindingResult.fieldErrors.map { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.badRequest().body(ApiResponse.error(errors.joinToString(", ")))
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
    @field:NotBlank(message = "Room ID is required")
    val roomId: String,

    @field:NotBlank(message = "Nickname is required")
    @field:Size(min = 1, max = 8, message = "Nickname must be 1-8 chars")
    val nickname: String
)

data class JoinResponse(
    val deviceId: String,
    val nickname: String
)

data class GameStartRequest(
    @field:NotBlank(message = "Room ID is required")
    val roomId: String,

    @field:NotNull(message = "Game Code is required")
    val gameCode: GameCode,

    val categoryId: Long? = null
)

data class PhaseChangeRequest(
    val roomId: String,
    val phase: String
)

data class ReactionRequest(
    @field:NotBlank(message = "Room ID is required")
    val roomId: String,

    @field:NotBlank(message = "Device ID is required")
    val deviceId: String,

    @field:NotNull(message = "Reaction type is required")
    val type: ReactionType
)

enum class ReactionType {
    FIREWORK,
    BOO,
    LAUGH,
    ANGRY
}
