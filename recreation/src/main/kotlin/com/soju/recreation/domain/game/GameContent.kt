package com.soju.recreation.domain.game

import jakarta.persistence.*

@Entity
@Table(name = "game_contents")
class GameContent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val contentId: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    val category: Category,

    @Column(nullable = false, length = 255)
    val textContent: String,  // 제시어, 질문, 벌칙 내용

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    val type: ContentType,

    @Column(nullable = false)
    val difficulty: Int = 1  // 난이도 (1: 쉬움, 2: 보통, 3: 어려움)
)

enum class ContentType {
    KEYWORD,   // 제시어 (몸으로 말해요)
    PENALTY,   // 벌칙 (주루마블)
    QUESTION   // 질문 (진실게임)
}
