package com.soju.recreation.domain.room

/**
 * Redis Key: room:{roomId}:state
 * 게임별 진행 상태를 저장하는 클래스들
 * 게임 종류에 따라 다른 구조를 가짐
 */

// ============================================
// 주루마블 (Marble) 게임 상태
// ============================================
data class MarbleGameState(
    var turnTeam: String = "A",                        // 현재 턴인 팀
    var lastDiceValue: Int? = null,                    // 마지막 주사위 값
    val teams: MutableList<String> = mutableListOf("A", "B"),  // 참여 팀 목록 (턴 순서)
    val positions: MutableMap<String, Int> = mutableMapOf(),   // 팀별 말 위치
    val board: MutableList<BoardCell> = mutableListOf()        // 게임판 (벌칙 칸들)
)

data class BoardCell(
    val index: Int,           // 칸 번호 (0부터 시작)
    val type: CellType,       // 칸 종류
    val text: String          // 벌칙/미니게임 내용
)

enum class CellType {
    START,         // 출발
    PENALTY,       // 벌칙 칸
    BONUS,         // 보너스 칸 (면제 등)
    UIRIJU_FILL,   // 의리주 채우기
    UIRIJU_DRINK,  // 의리주 마시기
    FINISH         // 도착
}

// ============================================
// 몸으로 말해요 (Speed Quiz) 게임 상태
// ============================================
data class SpeedQuizGameState(
    var currentWord: String? = null,                   // 현재 제시어
    val scores: MutableMap<String, Int> = mutableMapOf("A" to 0, "B" to 0),  // 팀별 점수
    val remainingWords: MutableList<String> = mutableListOf(),  // 남은 문제 큐
    var currentTeam: String = "A",                     // 현재 맞추는 팀
    var roundTimeSeconds: Int = 60                     // 라운드 제한 시간
)

// ============================================
// 마피아 (Mafia) 게임 상태
// ============================================
data class MafiaGameState(
    var phase: MafiaPhase = MafiaPhase.NIGHT,          // 현재 페이즈
    var timerSec: Int = 30,                            // 남은 시간
    var dayCount: Int = 1,                             // 몇 번째 낮인지
    val votes: MutableMap<String, String> = mutableMapOf(),    // 투표 현황 (voterId -> targetId)
    val finalVotes: MutableMap<String, Boolean> = mutableMapOf(), // 찬반 투표 (deviceId -> true=죽임/false=살림)
    var mafiaTarget: String? = null,                   // 마피아가 지목한 대상
    var doctorTarget: String? = null,                  // 의사가 살린 대상
    var policeTarget: String? = null,                  // 경찰이 조사한 대상
    var executionTarget: String? = null,              // 처형 대상자 (최다 득표자)
    var lastNightKilled: String? = null,              // 지난 밤 죽은 사람 (발표용)
    var wasSaved: Boolean = false,                     // 의사가 살렸는지 (발표용)
    val deadPlayers: MutableList<String> = mutableListOf(),    // 죽은 플레이어 deviceId 목록
    var winner: MafiaWinner? = null,                   // 승자 (게임 종료 시)
    val mafiaChat: MutableList<MafiaChatMessage> = mutableListOf() // 마피아 채팅
)

data class MafiaChatMessage(
    val senderDeviceId: String,
    val senderNickname: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class MafiaPhase(val durationSeconds: Int) {
    NIGHT(30),              // 밤 - 마피아/경찰/의사 행동 (30초)
    DAY_ANNOUNCEMENT(10),   // 낮 - 결과 발표 (10초)
    DAY_DISCUSSION(240),    // 낮 - 토론 (4분)
    VOTE(60),               // 투표 (1분)
    VOTE_RESULT(5),         // 투표 결과 발표 (5초)
    FINAL_DEFENSE(30),      // 최후의 변론 (30초)
    FINAL_VOTE(30),         // 찬반 투표 (30초)
    FINAL_VOTE_RESULT(5),   // 찬반 결과 발표 (5초)
    GAME_END(0)             // 게임 종료
}

enum class MafiaWinner {
    MAFIA,    // 마피아 승리
    CITIZEN   // 시민 승리
}

// ============================================
// 진실게임 (Truth) 게임 상태
// TruthService.kt에 정의됨 (더 상세한 버전)
// ============================================

// ============================================
// 라이어 게임 (Liar) 게임 상태
// ============================================
data class LiarGameState(
    var phase: LiarPhase = LiarPhase.ROLE_REVEAL,      // 현재 페이즈
    var timerSec: Int = 30,                            // 남은 시간
    var keyword: String = "",                          // 제시어
    var categoryName: String = "",                     // 카테고리 이름
    var liarDeviceId: String = "",                     // 라이어의 deviceId
    val explanationOrder: MutableList<String> = mutableListOf(),  // 설명 순서 (deviceId 리스트)
    var currentExplainerIndex: Int = 0,                // 현재 설명자 인덱스
    var roundCount: Int = 1,                           // 현재 라운드 (1 또는 2)
    val moreRoundVotes: MutableMap<String, Boolean> = mutableMapOf(),  // 한바퀴 더 투표 (deviceId -> true:한바퀴더/false:stop)
    var hasSecondRound: Boolean = false,               // 두 번째 라운드 진행 여부
    // 지목 관련
    val pointingVotes: MutableMap<String, String> = mutableMapOf(),  // 지목 투표 (voterId -> targetDeviceId)
    var pointedDeviceId: String? = null,               // 지목된 플레이어 deviceId
    // 라이어 정답 맞추기
    var liarGuess: String? = null,                     // 라이어의 정답 추측
    var winner: LiarWinner? = null                     // 승자
)

enum class LiarPhase(val durationSeconds: Int) {
    ROLE_REVEAL(30),           // 역할 확인 (30초)
    EXPLANATION(20),           // 설명 시간 (각 20초)
    VOTE_MORE_ROUND(15),       // 한바퀴 더? 투표 (15초)
    POINTING(0),               // 토론 시간 (시간 제한 없음, 호스트가 투표 시작)
    POINTING_VOTE(30),         // 지목 투표 (30초)
    POINTING_RESULT(5),        // 지목 결과 발표 (5초)
    LIAR_GUESS(30),            // 라이어 정답 맞추기 (30초)
    GAME_END(0)                // 게임 종료
}

enum class LiarWinner {
    LIAR,      // 라이어 승리 (지목 실패 또는 정답 맞춤)
    CITIZEN    // 시민 승리 (라이어 지목 + 정답 틀림)
}
