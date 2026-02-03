
package com.soju.recreation.domain.game

import jakarta.persistence.*

@Entity
@Table(name = "games")
class Game(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val gameId: Long? = null,

    @Column(nullable = false, unique = true, length = 20)
    @Enumerated(EnumType.STRING)
    val code: GameCode,

    @Column(nullable = false, length = 50)
    val name: String,

    @Column(columnDefinition = "TEXT")
    val description: String? = null,

    @OneToMany(mappedBy = "game", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    val categories: MutableList<Category> = mutableListOf()
)

enum class GameCode {
    MARBLE,      // 주루마블
    MAFIA,       // 마피아
    SPEED_QUIZ,  // 몸으로 말해요
    TRUTH,       // 진실게임
    LIAR         // 라이어 게임
}
