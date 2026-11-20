package com.osori.server.migration

import com.osori.server.db.Database
import com.osori.server.db.TalkRoomRepository
import java.time.Instant
import kotlin.system.exitProcess

/**
 * 사용자 요구사항(2026-09-17): 그룹방 기능이 생기기 전부터 있던 옛날 1:1 대화(talk_messages)를
 * 전부 새 대화방(Room) 시스템으로 이전한다. 이관 후에도 사용자 관점에서는 모든 대화가 방(Room)
 * 하나로 통일되어 초대/이름변경/나가기 메뉴가 동일하게 동작한다.
 *
 * - 동일 두 사람 조합의 방이 이미 있으면(예: 그룹방 테스트 중 먼저 만들어진 1:1 방) 그 방을
 *   재사용하고, 없으면 새로 만든다.
 * - talk_messages 원본 행은 삭제하지 않는다(백업 겸 안전장치) — 이관된 메시지는
 *   talk_room_messages 에 복사되어 새 room_id 로 표시된다.
 * - 재실행해도 안전(idempotent): server_talk_id 를 그대로 재사용해 이관하므로 이미 이관된
 *   메시지는 ON CONFLICT DO NOTHING 으로 중복 삽입되지 않는다.
 *
 * 사용법 (gradle): ./gradlew migrateLegacyTalks --console=plain
 */
fun main() {
    val db = try {
        Database.fromEnv()
    } catch (e: Exception) {
        System.err.println("ERROR: DB 연결 실패: ${e.message}")
        exitProcess(1)
    }

    val roomRepo = TalkRoomRepository(db)
    try {
        val pairs = db.withConnection { conn ->
            conn.prepareStatement("SELECT DISTINCT from_user, to_user FROM talk_messages").use { ps ->
                ps.executeQuery().use { rs ->
                    val result = mutableListOf<Pair<String, String>>()
                    while (rs.next()) {
                        result += rs.getString("from_user") to rs.getString("to_user")
                    }
                    result
                }
            }
        }

        // (a,b) 와 (b,a) 가 둘 다 있을 수 있으므로 정렬된 쌍으로 묶어 중복 제거.
        val uniquePairs = pairs.map { (a, b) -> if (a < b) a to b else b to a }.distinct()
        println("[migrate] 발견된 레거시 1:1 쌍: ${uniquePairs.size}개")

        for ((userA, userB) in uniquePairs) {
            val existingRoomId = roomRepo.findRoomByMemberSet(listOf(userA, userB))
            val roomId = existingRoomId ?: roomRepo.createRoom(
                name = userB,
                ownerUserId = userA,
                createdAt = Instant.now(),
                memberIds = listOf(userB),
            )
            println(
                "[migrate] $userA <-> $userB : " +
                    if (existingRoomId != null) "기존 방 재사용 roomId=$roomId" else "새 방 생성 roomId=$roomId",
            )

            val migrated = db.withConnection { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO talk_room_messages (server_talk_id, room_id, from_user_id, text, server_ts)
                    SELECT talk_id, ?, from_user, text, server_ts
                    FROM talk_messages
                    WHERE (from_user = ? AND to_user = ?) OR (from_user = ? AND to_user = ?)
                    ON CONFLICT (server_talk_id) DO NOTHING
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, roomId)
                    ps.setString(2, userA)
                    ps.setString(3, userB)
                    ps.setString(4, userB)
                    ps.setString(5, userA)
                    ps.executeUpdate()
                }
            }
            println("[migrate] $userA <-> $userB : 메시지 $migrated 건 이관 완료")
        }

        println("[migrate] 전체 완료 — talk_messages 원본은 삭제하지 않고 보존함")
    } catch (e: Exception) {
        System.err.println("ERROR: 이관 실패: ${e.message}")
        e.printStackTrace()
        exitProcess(2)
    } finally {
        runCatching { db.close() }
    }
}
