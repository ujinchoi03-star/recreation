package com.soju.recreation.domain.room

import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap

@Service
class SseService {
    // 방 번호(Key) -> 연결 통로(Value) 저장소 (Host용)
    private val emitters = ConcurrentHashMap<String, SseEmitter>()

    // 플레이어용 emitter 저장소: roomId -> (deviceId -> emitter)
    private val playerEmitters = ConcurrentHashMap<String, ConcurrentHashMap<String, SseEmitter>>()

    // 1. 메인 화면(Host)이 접속하면 연결 통로를 만든다
    fun connect(roomId: String): SseEmitter {
        // 연결 유지 시간: 1시간 (3600초 * 1000ms) - 술자리 길어지니까 넉넉하게
        val emitter = SseEmitter(60 * 60 * 1000L)

        emitters[roomId] = emitter

        // 연결이 끊기거나 타임아웃 되면 리스트에서 삭제
        emitter.onCompletion { emitters.remove(roomId) }
        emitter.onTimeout { emitters.remove(roomId) }

        // "연결 성공!" 이라는 첫 번째 이벤트를 보냄 (더미 데이터)
        try {
            emitter.send(SseEmitter.event().name("CONNECT").data("connected"))
        } catch (e: Exception) {
            emitters.remove(roomId)
        }

        return emitter
    }

    // 2. 특정 방에 이벤트를 쏘는 기능 (나중에 주사위 굴릴 때 씀)
    fun broadcast(roomId: String, eventName: String, data: Any) {
        val emitter = emitters[roomId]
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data))
            } catch (e: Exception) {
                emitters.remove(roomId)
            }
        }
    }

    // 3. 플레이어가 접속하면 연결 통로를 만든다
    fun connectPlayer(roomId: String, deviceId: String): SseEmitter {
        val emitter = SseEmitter(60 * 60 * 1000L)

        playerEmitters.computeIfAbsent(roomId) { ConcurrentHashMap() }[deviceId] = emitter

        emitter.onCompletion { playerEmitters[roomId]?.remove(deviceId) }
        emitter.onTimeout { playerEmitters[roomId]?.remove(deviceId) }

        try {
            emitter.send(SseEmitter.event().name("CONNECT").data("connected"))
        } catch (e: Exception) {
            playerEmitters[roomId]?.remove(deviceId)
        }

        return emitter
    }

    // 4. 방의 모든 플레이어에게 브로드캐스트
    fun broadcastToPlayers(roomId: String, eventName: String, data: Any) {
        playerEmitters[roomId]?.forEach { (deviceId, emitter) ->
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data))
            } catch (e: Exception) {
                playerEmitters[roomId]?.remove(deviceId)
            }
        }
    }

    // 5. Host와 모든 플레이어에게 동시에 브로드캐스트
    fun broadcastToAll(roomId: String, eventName: String, data: Any) {
        broadcast(roomId, eventName, data)
        broadcastToPlayers(roomId, eventName, data)
    }
}