package com.soju.recreation.domain.room

import com.soju.recreation.domain.game.GameCode

/**
 * Redis Key: room:{roomId}:info
 * TTL: 6시간
 * 방의 기본 정보와 플레이어 목록을 관리
 */
data class RoomInfo(
    val roomId: String,                           // 방 코드 (예: X9Z1)
    val hostSessionId: String,                    // Host 구분을 위한 세션 ID
    var status: RoomStatus = RoomStatus.WAITING,  // 방 상태
    var currentGame: GameCode? = null,            // 현재 진행 중인 게임
    val players: MutableList<Player> = mutableListOf()
)

enum class RoomStatus {
    WAITING,  // 대기 중 (유저 입장 대기)
    PLAYING,  // 게임 진행 중
    END       // 게임 종료
}

/**
 * 플레이어 정보
 */
data class Player(
    val deviceId: String,            // 유저 식별값 (UUID)
    val nickname: String,            // 닉네임
    var team: String? = null,        // 팀 (A, B) - 주루마블/몸으로말해요용
    var profile: String? = null,     // 프로필 아이콘
    var role: MafiaRole? = null,     // 마피아 게임 역할
    var isAlive: Boolean = true      // 마피아 게임 생존 여부
)

enum class MafiaRole {
    MAFIA,     // 마피아
    CIVILIAN,  // 시민
    DOCTOR,    // 의사
    POLICE     // 경찰
}
