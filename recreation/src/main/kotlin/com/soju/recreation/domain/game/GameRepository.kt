package com.soju.recreation.domain.game

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface GameRepository : JpaRepository<Game, Long> {
    fun findByCode(code: GameCode): Game?
}

@Repository
interface CategoryRepository : JpaRepository<Category, Long> {
    fun findByGame(game: Game): List<Category>
    fun findByGameCode(gameCode: GameCode): List<Category>
}

@Repository
interface GameContentRepository : JpaRepository<GameContent, Long> {

    // 특정 카테고리의 콘텐츠 조회
    fun findByCategory(category: Category): List<GameContent>

    // 카테고리 ID로 콘텐츠 조회
    fun findByCategoryCategoryId(categoryId: Long): List<GameContent>

    // 타입별 콘텐츠 조회 (예: 벌칙만)
    fun findByCategoryAndType(category: Category, type: ContentType): List<GameContent>

    // 난이도별 랜덤 조회 (몸으로 말해요 등에서 활용)
    @Query("SELECT gc FROM GameContent gc WHERE gc.category.categoryId = :categoryId ORDER BY FUNCTION('RAND')")
    fun findRandomByCategoryId(categoryId: Long): List<GameContent>

    // 특정 개수만 랜덤 조회
    @Query(value = "SELECT * FROM game_contents WHERE category_id = :categoryId ORDER BY RAND() LIMIT :limit", nativeQuery = true)
    fun findRandomByCategoryIdWithLimit(categoryId: Long, limit: Int): List<GameContent>
}
