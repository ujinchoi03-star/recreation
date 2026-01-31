import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class TestController(
    private val redisTemplate: StringRedisTemplate
) {

    @GetMapping("/test/redis")
    fun testRedis(): String {
        val ops = redisTemplate.opsForValue()
        ops.set("mt:greeting", "Hello from Redis! 서버 연결 성공!")

        return ops.get("mt:greeting") ?: "데이터 없음"
    }
}