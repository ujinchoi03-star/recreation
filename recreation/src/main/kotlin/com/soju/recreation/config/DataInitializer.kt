package com.soju.recreation.config

import com.soju.recreation.domain.game.*
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class DataInitializer(
    private val gameRepository: GameRepository,
    private val categoryRepository: CategoryRepository,
    private val gameContentRepository: GameContentRepository
) : CommandLineRunner {

    @Transactional
    override fun run(vararg args: String?) {
        // 이미 데이터가 있으면 스킵
        if (gameRepository.count() > 0) {
            return
        }

        initializeGames()
    }

    private fun initializeGames() {
        // 1. 게임 메타 정보 생성
        val marble = gameRepository.save(Game(
            code = GameCode.MARBLE,
            name = "주루마블",
            description = "커스텀 벌칙이 있는 술자리 보드게임"
        ))

        val speedQuiz = gameRepository.save(Game(
            code = GameCode.SPEED_QUIZ,
            name = "몸으로 말해요",
            description = "제시어를 몸으로 설명하는 게임"
        ))

        // 마피아는 역할 배정만 하므로 별도 콘텐츠 불필요
        gameRepository.save(Game(
            code = GameCode.MAFIA,
            name = "마피아",
            description = "AI 사회자와 함께하는 마피아 게임"
        ))

        val truth = gameRepository.save(Game(
            code = GameCode.TRUTH,
            name = "진실게임",
            description = "웹캠으로 거짓말 탐지하는 진실게임"
        ))

        // 2. 스피드퀴즈 제시어만 생성 (주루마블/진실게임은 참가자가 직접 제출)
        initializeSpeedQuizContent(speedQuiz)
    }

    private fun initializeSpeedQuizContent(game: Game) {
        // 1. 동물 카테고리 (25개)
        val animals = categoryRepository.save(Category(game = game, name = "동물"))
        listOf("사자", "호랑이", "기린", "코끼리", "원숭이", "펭귄", "북극곰", "캥거루", "악어", "하마",
            "고릴라", "치타", "표범", "늑대", "여우", "토끼", "다람쥐", "고양이", "강아지", "햄스터",
            "돌고래", "상어", "독수리", "공작새", "두더지")
            .forEach { word ->
                gameContentRepository.save(GameContent(
                    category = animals,
                    textContent = word,
                    type = ContentType.KEYWORD,
                    difficulty = 1
                ))
            }

        // 2. 영화 카테고리 (25개)
        val movies = categoryRepository.save(Category(game = game, name = "영화"))
        listOf("타이타닉", "아바타", "어벤져스", "겨울왕국", "기생충", "해리포터", "반지의제왕",
            "스타워즈", "인셉션", "매트릭스", "라이온킹", "토이스토리", "쥬라기공원", "터미네이터",
            "미션임파서블", "분노의질주", "킹스맨", "존윅", "범죄도시", "극한직업",
            "인터스텔라", "아이언맨", "스파이더맨", "배트맨", "슈렉")
            .forEach { word ->
                gameContentRepository.save(GameContent(
                    category = movies,
                    textContent = word,
                    type = ContentType.KEYWORD,
                    difficulty = 2
                ))
            }

        // 3. 직업 카테고리 (25개)
        val jobs = categoryRepository.save(Category(game = game, name = "직업"))
        listOf("의사", "소방관", "경찰관", "요리사", "선생님", "과학자", "우주비행사", "배우",
            "가수", "축구선수", "야구선수", "농부", "어부", "사진작가", "마술사", "광대",
            "파일럿", "간호사", "변호사", "건축가", "치과의사", "수의사", "바리스타", "DJ", "유튜버")
            .forEach { word ->
                gameContentRepository.save(GameContent(
                    category = jobs,
                    textContent = word,
                    type = ContentType.KEYWORD,
                    difficulty = 1
                ))
            }

        // 4. 스포츠 카테고리 (25개) - 새로 추가
        val sports = categoryRepository.save(Category(game = game, name = "스포츠"))
        listOf("축구", "농구", "야구", "배구", "테니스", "탁구", "배드민턴", "골프", "수영", "스키",
            "스노보드", "스케이트", "복싱", "태권도", "유도", "레슬링", "양궁", "펜싱", "역도", "체조",
            "마라톤", "사이클", "럭비", "아이스하키", "승마")
            .forEach { word ->
                gameContentRepository.save(GameContent(
                    category = sports,
                    textContent = word,
                    type = ContentType.KEYWORD,
                    difficulty = 1
                ))
            }

        // 5. 노래/가수 카테고리 (25개) - 새로 추가
        val music = categoryRepository.save(Category(game = game, name = "노래/가수"))
        listOf("BTS", "블랙핑크", "아이유", "싸이", "빅뱅", "소녀시대", "엑소", "트와이스", "뉴진스", "세븐틴",
            "강남스타일", "다이너마이트", "버터", "좋은날", "밤양갱", "벚꽃엔딩", "봄날", "에잇", "Love Dive", "Hype Boy",
            "비틀즈", "마이클잭슨", "퀸", "아델", "테일러스위프트")
            .forEach { word ->
                gameContentRepository.save(GameContent(
                    category = music,
                    textContent = word,
                    type = ContentType.KEYWORD,
                    difficulty = 2
                ))
            }

        // 6. 속담/관용구 카테고리 (25개) - 새로 추가
        val proverbs = categoryRepository.save(Category(game = game, name = "속담"))
        listOf("꿩 먹고 알 먹고", "누워서 떡 먹기", "도토리 키 재기", "등잔 밑이 어둡다", "뛰는 놈 위에 나는 놈",
            "바늘 도둑이 소 도둑 된다", "백지장도 맞들면 낫다", "빈 수레가 요란하다", "사공이 많으면 배가 산으로 간다", "소 잃고 외양간 고친다",
            "식은 죽 먹기", "아니 땐 굴뚝에 연기 나랴", "우물 안 개구리", "원숭이도 나무에서 떨어진다", "윗물이 맑아야 아랫물이 맑다",
            "자라 보고 놀란 가슴 솥뚜껑 보고 놀란다", "제 눈에 안경", "쥐구멍에도 볕 들 날 있다", "천 리 길도 한 걸음부터", "콩 심은 데 콩 나고 팥 심은 데 팥 난다",
            "하늘의 별 따기", "호랑이도 제 말 하면 온다", "가는 말이 고와야 오는 말이 곱다", "고래 싸움에 새우 등 터진다", "낮말은 새가 듣고 밤말은 쥐가 듣는다")
            .forEach { word ->
                gameContentRepository.save(GameContent(
                    category = proverbs,
                    textContent = word,
                    type = ContentType.KEYWORD,
                    difficulty = 3
                ))
            }

        // 7. 음식 카테고리 (25개) - 새로 추가
        val food = categoryRepository.save(Category(game = game, name = "음식"))
        listOf("김치찌개", "된장찌개", "삼겹살", "치킨", "피자", "햄버거", "짜장면", "짬뽕", "탕수육", "냉면",
            "비빔밥", "불고기", "갈비찜", "떡볶이", "순대", "라면", "김밥", "초밥", "돈까스", "스테이크",
            "파스타", "샐러드", "샌드위치", "타코", "카레")
            .forEach { word ->
                gameContentRepository.save(GameContent(
                    category = food,
                    textContent = word,
                    type = ContentType.KEYWORD,
                    difficulty = 1
                ))
            }

        // 8. 고난이도 카테고리 (25개)
        val hard = categoryRepository.save(Category(game = game, name = "고난이도"))
        listOf("비트코인", "인플레이션", "메타버스", "양자역학", "철학자", "민주주의",
            "자본주의", "상대성이론", "블랙홀", "르네상스", "산업혁명", "세계대전",
            "올림픽", "월드컵", "노벨상", "그래미상", "아카데미상", "인공지능", "가상현실",
            "증강현실", "빅데이터", "사물인터넷", "지속가능발전", "탄소중립", "기후변화")
            .forEach { word ->
                gameContentRepository.save(GameContent(
                    category = hard,
                    textContent = word,
                    type = ContentType.KEYWORD,
                    difficulty = 3
                ))
            }
    }

}
