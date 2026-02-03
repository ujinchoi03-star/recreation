package com.soju.recreation.domain.game.liar

import com.soju.recreation.domain.room.LiarPhase
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service
class LiarTimerService {
    private val logger = LoggerFactory.getLogger(LiarTimerService::class.java)

    private val schedulers = ConcurrentHashMap<String, ScheduledExecutorService>()
    private val timerTasks = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val remainingTimes = ConcurrentHashMap<String, Int>()

    fun startTimer(
        roomId: String,
        durationSeconds: Int,
        onTick: (Int) -> Unit,
        onComplete: () -> Unit
    ) {
        cancelTimer(roomId)

        if (durationSeconds <= 0) {
            logger.info("Duration is 0, skipping timer for room $roomId")
            return
        }

        remainingTimes[roomId] = durationSeconds

        val scheduler = schedulers.computeIfAbsent(roomId) {
            Executors.newSingleThreadScheduledExecutor()
        }

        val task = scheduler.scheduleAtFixedRate({
            try {
                val remaining = remainingTimes[roomId] ?: 0

                if (remaining > 0) {
                    remainingTimes[roomId] = remaining - 1
                    onTick(remaining - 1)
                } else {
                    cancelTimer(roomId)
                    onComplete()
                }
            } catch (e: Exception) {
                logger.error("Timer error for room $roomId: ${e.message}", e)
            }
        }, 1, 1, TimeUnit.SECONDS)

        timerTasks[roomId] = task
        logger.info("Timer started for room $roomId, duration ${durationSeconds}s")
    }

    fun cancelTimer(roomId: String) {
        timerTasks[roomId]?.cancel(false)
        timerTasks.remove(roomId)
        remainingTimes.remove(roomId)
        logger.info("Timer cancelled for room $roomId")
    }

    fun getRemainingTime(roomId: String): Int {
        return remainingTimes[roomId] ?: 0
    }

    fun cleanup(roomId: String) {
        cancelTimer(roomId)
        schedulers[roomId]?.shutdown()
        schedulers.remove(roomId)
        logger.info("Cleaned up timer resources for room $roomId")
    }

    /**
     * 지정된 시간 후에 작업을 실행 (Thread.sleep 대체용)
     */
    fun scheduleDelayed(roomId: String, delayMillis: Long, action: () -> Unit) {
        val scheduler = schedulers.computeIfAbsent(roomId) {
            Executors.newSingleThreadScheduledExecutor()
        }

        scheduler.schedule({
            try {
                action()
            } catch (e: Exception) {
                logger.error("Delayed action error for room $roomId: ${e.message}", e)
            }
        }, delayMillis, TimeUnit.MILLISECONDS)

        logger.info("Scheduled delayed action for room $roomId in ${delayMillis}ms")
    }
}
