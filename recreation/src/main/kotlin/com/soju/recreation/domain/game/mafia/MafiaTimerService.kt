package com.soju.recreation.domain.game.mafia

import com.soju.recreation.domain.room.MafiaPhase
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service
class MafiaTimerService {
    private val logger = LoggerFactory.getLogger(MafiaTimerService::class.java)

    // 방별 스케줄러
    private val schedulers = ConcurrentHashMap<String, ScheduledExecutorService>()

    // 방별 현재 타이머 작업
    private val timerTasks = ConcurrentHashMap<String, ScheduledFuture<*>>()

    // 방별 남은 시간 (1초마다 업데이트)
    private val remainingTimes = ConcurrentHashMap<String, Int>()

    /**
     * 페이즈 타이머 시작
     * @param roomId 방 ID
     * @param phase 현재 페이즈
     * @param onTick 매초 호출 (남은 시간 전달)
     * @param onComplete 타이머 완료 시 호출
     */
    fun startTimer(
        roomId: String,
        phase: MafiaPhase,
        onTick: (Int) -> Unit,
        onComplete: () -> Unit
    ) {
        // 기존 타이머 취소
        cancelTimer(roomId)

        val duration = phase.durationSeconds
        if (duration <= 0) {
            logger.info("Phase $phase has no timer, skipping")
            return
        }

        remainingTimes[roomId] = duration

        val scheduler = schedulers.computeIfAbsent(roomId) {
            Executors.newSingleThreadScheduledExecutor()
        }

        // 1초마다 틱
        val task = scheduler.scheduleAtFixedRate({
            try {
                val remaining = remainingTimes[roomId] ?: 0

                if (remaining > 0) {
                    remainingTimes[roomId] = remaining - 1
                    onTick(remaining - 1)
                } else {
                    // 타이머 완료
                    cancelTimer(roomId)
                    onComplete()
                }
            } catch (e: Exception) {
                logger.error("Timer error for room $roomId: ${e.message}", e)
            }
        }, 1, 1, TimeUnit.SECONDS)

        timerTasks[roomId] = task
        logger.info("Timer started for room $roomId, phase $phase, duration ${duration}s")
    }

    /**
     * 타이머 취소
     */
    fun cancelTimer(roomId: String) {
        timerTasks[roomId]?.cancel(false)
        timerTasks.remove(roomId)
        remainingTimes.remove(roomId)
        logger.info("Timer cancelled for room $roomId")
    }

    /**
     * 남은 시간 조회
     */
    fun getRemainingTime(roomId: String): Int {
        return remainingTimes[roomId] ?: 0
    }

    /**
     * 방 정리 (게임 종료 시)
     */
    fun cleanup(roomId: String) {
        cancelTimer(roomId)
        schedulers[roomId]?.shutdown()
        schedulers.remove(roomId)
        logger.info("Cleaned up timer resources for room $roomId")
    }
}
