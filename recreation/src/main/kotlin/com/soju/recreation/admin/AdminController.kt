package com.soju.recreation.admin

import com.soju.recreation.domain.room.ApiResponse
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val adminService: AdminService
) {
    // 관리자 SSE 연결 관리
    private val adminEmitters = ConcurrentHashMap<String, SseEmitter>()
    private val scheduler = Executors.newScheduledThreadPool(1)

    init {
        // 5초마다 관리자에게 서버 상태 전송
        scheduler.scheduleAtFixedRate({
            broadcastToAdmins()
        }, 5, 5, TimeUnit.SECONDS)
    }

    // ============================================
    // 대시보드 데이터 API
    // ============================================

    /**
     * 서버 통계 조회
     * GET /api/admin/stats
     */
    @GetMapping("/stats")
    fun getServerStats(): ApiResponse<ServerStats> {
        val stats = adminService.getServerStats()
        return ApiResponse.success(stats)
    }

    /**
     * 모든 활성 방 목록
     * GET /api/admin/rooms
     */
    @GetMapping("/rooms")
    fun getAllRooms(): ApiResponse<List<RoomSummary>> {
        val rooms = adminService.getAllActiveRooms()
        return ApiResponse.success(rooms)
    }

    /**
     * 특정 방 상세 정보
     * GET /api/admin/rooms/{roomId}
     */
    @GetMapping("/rooms/{roomId}")
    fun getRoomDetail(@PathVariable roomId: String): ApiResponse<RoomDetail> {
        val detail = adminService.getRoomDetail(roomId)
            ?: return ApiResponse.error("방을 찾을 수 없습니다: $roomId")
        return ApiResponse.success(detail)
    }

    // ============================================
    // 관리자 액션 API
    // ============================================

    /**
     * 강제 페이즈 전환 (마피아)
     * POST /api/admin/rooms/{roomId}/force-next-phase
     */
    @PostMapping("/rooms/{roomId}/force-next-phase")
    fun forceNextPhase(@PathVariable roomId: String): ApiResponse<String> {
        val success = adminService.forceNextPhase(roomId)
        return if (success) {
            ApiResponse.success("페이즈가 강제 전환되었습니다")
        } else {
            ApiResponse.error("페이즈 전환 실패")
        }
    }

    /**
     * 방 강제 삭제
     * DELETE /api/admin/rooms/{roomId}
     */
    @DeleteMapping("/rooms/{roomId}")
    fun deleteRoom(@PathVariable roomId: String): ApiResponse<String> {
        adminService.forceDeleteRoom(roomId)
        return ApiResponse.success("방이 삭제되었습니다: $roomId")
    }

    // ============================================
    // SSE 실시간 업데이트
    // ============================================

    /**
     * 관리자 SSE 연결
     * GET /api/admin/sse
     */
    @GetMapping("/sse")
    fun connectAdminSse(): SseEmitter {
        val emitter = SseEmitter(Long.MAX_VALUE)
        val sessionId = System.currentTimeMillis().toString()

        adminEmitters[sessionId] = emitter

        emitter.onCompletion { adminEmitters.remove(sessionId) }
        emitter.onTimeout { adminEmitters.remove(sessionId) }
        emitter.onError { adminEmitters.remove(sessionId) }

        // 초기 데이터 전송
        try {
            emitter.send(SseEmitter.event()
                .name("CONNECTED")
                .data(mapOf("message" to "Admin SSE connected", "sessionId" to sessionId)))

            // 즉시 현재 상태 전송
            val stats = adminService.getServerStats()
            val rooms = adminService.getAllActiveRooms()
            emitter.send(SseEmitter.event()
                .name("INIT_DATA")
                .data(mapOf("stats" to stats, "rooms" to rooms)))
        } catch (e: Exception) {
            adminEmitters.remove(sessionId)
        }

        return emitter
    }

    private fun broadcastToAdmins() {
        if (adminEmitters.isEmpty()) return

        try {
            val stats = adminService.getServerStats()
            val rooms = adminService.getAllActiveRooms()
            val data = mapOf("stats" to stats, "rooms" to rooms, "timestamp" to System.currentTimeMillis())

            adminEmitters.forEach { (sessionId, emitter) ->
                try {
                    emitter.send(SseEmitter.event().name("UPDATE").data(data))
                } catch (e: Exception) {
                    adminEmitters.remove(sessionId)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }
}
