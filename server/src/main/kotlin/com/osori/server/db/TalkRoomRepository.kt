package com.osori.server.db

import java.security.MessageDigest
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Room repository for group chat (Phase 6, ; 멤버 관리 확장 2026-09-16).
 *
 * Tables: talk_rooms, talk_room_members, talk_room_messages.
 *
 * "방장" 개념 없음 — owner_user_id 는 최초 생성자를 기록만 할 뿐 권한상 의미가 없다.
 * 초대/나가기/이름변경은 전부 "현재 멤버인지" 만 확인한다(TalkRoomHandler 에서 체크).
 */
open class TalkRoomRepository(private val db: Database) {

    data class RoomRow(
        val roomId: UUID,
        val name: String,
        val ownerUserId: String,
        val createdAt: Instant,
        val nameIsCustom: Boolean,
    )

    enum class AddMemberResult { OK, ROOM_NOT_FOUND, ALREADY_MEMBER }

    data class AddMembersResult(
        val roomFound: Boolean,
        val added: List<String>,
        val alreadyMembers: List<String>,
    )

    enum class RemoveMemberResult { OK, ROOM_NOT_FOUND, NOT_MEMBER }

    data class RemoveMemberOutcome(
        val result: RemoveMemberResult,
        val roomDeleted: Boolean,
        val remainingMembers: List<String>,
    )

    /**
     * Creates a new room with the owner + any additional initial members. Returns the new room id.
     * `memberIds` 는 본인 제외 초기 멤버(선택) — 기존 호출부(테스트 등)와의 호환을 위해 기본값 빈 리스트.
     */
    fun createRoom(name: String, ownerUserId: String, createdAt: Instant, memberIds: List<String> = emptyList()): UUID {
        val roomId = UUID.randomUUID()
        val allMembers = (listOf(ownerUserId) + memberIds).distinct()
        db.inTransaction { conn ->
            conn.prepareStatement(
                "INSERT INTO talk_rooms (room_id, name, owner_user_id, member_set_hash, created_at) VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, roomId)
                ps.setString(2, name)
                ps.setString(3, ownerUserId)
                ps.setString(4, computeMemberSetHash(allMembers))
                ps.setTimestamp(5, Timestamp.from(createdAt))
                ps.executeUpdate()
            }
            for (userId in allMembers) {
                insertMemberRow(conn, roomId, userId, createdAt)
            }
        }
        return roomId
    }

    /** 정확히 같은 멤버 구성(본인 포함)의 방이 이미 있으면 그 room_id 를 반환한다. */
    fun findRoomByMemberSet(memberIds: Collection<String>): UUID? {
        val hash = computeMemberSetHash(memberIds)
        return db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT room_id FROM talk_rooms WHERE member_set_hash = ? LIMIT 1",
            ).use { ps ->
                ps.setString(1, hash)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getObject("room_id", UUID::class.java) else null
                }
            }
        }
    }

    fun getRoom(roomId: UUID): RoomRow? {
        return db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT room_id, name, owner_user_id, created_at, name_is_custom FROM talk_rooms WHERE room_id = ?",
            ).use { ps ->
                ps.setObject(1, roomId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@withConnection null
                    RoomRow(
                        roomId = rs.getObject("room_id", UUID::class.java),
                        name = rs.getString("name"),
                        ownerUserId = rs.getString("owner_user_id"),
                        createdAt = rs.getTimestamp("created_at").toInstant(),
                        nameIsCustom = rs.getBoolean("name_is_custom"),
                    )
                }
            }
        }
    }

    data class RoomSummary(
        val roomId: UUID,
        val name: String,
        val memberCount: Int,
        val lastMessageText: String?,
        val lastMessageTs: Instant?,
        val nameIsCustom: Boolean,
        val memberIds: List<String>,
        /** 내가 아직 안 읽은 메시지 수(남이 보낸 것, 읽음 위치가 없으면 입장 시각 이후). */
        val unreadCount: Int = 0,
    )

    /**
     * 로그인 시 사이드바 복원용 — 이 사용자가 현재 멤버인 모든 방을 이름/인원수/마지막 메시지와
     * 함께 반환한다. 마지막 메시지가 없던 로그인 시엔 사이드바 미리보기가 안 보이던 문제(2026-09-17
     * 사용자 신고)의 원인이 이 목록에 마지막 메시지 정보가 아예 없었던 것이라, LATERAL JOIN 으로
     * 방마다 가장 최근 메시지 1건을 같이 실어 보낸다.
     */
    fun findRoomsForUser(userId: String): List<RoomSummary> {
        return db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT r.room_id, r.name, r.name_is_custom,
                       (SELECT COUNT(*) FROM talk_room_members m2 WHERE m2.room_id = r.room_id) AS member_count,
                       (SELECT array_agg(m3.user_id) FROM talk_room_members m3 WHERE m3.room_id = r.room_id) AS member_ids,
                       lm.text AS last_text, lm.server_ts AS last_ts,
                       (SELECT COUNT(*) FROM talk_room_messages um
                         WHERE um.room_id = r.room_id AND um.from_user_id <> m1.user_id
                           -- 클라이언트는 ms 단위로 읽음을 보고하므로 비교도 ms 로 자른다
                           AND date_trunc('milliseconds', um.server_ts) > COALESCE(
                               (SELECT rr.last_read_ts FROM talk_room_reads rr WHERE rr.room_id = r.room_id AND rr.user_id = m1.user_id),
                               m1.joined_at)) AS unread_count
                FROM talk_rooms r
                JOIN talk_room_members m1 ON m1.room_id = r.room_id AND m1.user_id = ?
                LEFT JOIN LATERAL (
                    SELECT text, server_ts FROM talk_room_messages
                    WHERE room_id = r.room_id
                    ORDER BY server_ts DESC LIMIT 1
                ) lm ON true
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, userId)
                ps.executeQuery().use { rs ->
                    val result = mutableListOf<RoomSummary>()
                    while (rs.next()) {
                        val lastTs = rs.getTimestamp("last_ts")
                        @Suppress("UNCHECKED_CAST")
                        val memberIds = (rs.getArray("member_ids")?.array as? Array<String>)?.toList() ?: emptyList()
                        result += RoomSummary(
                            roomId = rs.getObject("room_id", UUID::class.java),
                            name = rs.getString("name"),
                            memberCount = rs.getInt("member_count"),
                            lastMessageText = rs.getString("last_text"),
                            lastMessageTs = lastTs?.toInstant(),
                            nameIsCustom = rs.getBoolean("name_is_custom"),
                            memberIds = memberIds,
                            unreadCount = rs.getInt("unread_count"),
                        )
                    }
                    result
                }
            }
        }
    }

    fun addMember(roomId: UUID, userId: String, joinedAt: Instant): AddMemberResult {
        val outcome = addMembers(roomId, listOf(userId), joinedAt)
        if (!outcome.roomFound) return AddMemberResult.ROOM_NOT_FOUND
        return if (outcome.added.isNotEmpty()) AddMemberResult.OK else AddMemberResult.ALREADY_MEMBER
    }

    /** 여러 명을 한 번에 초대(추가)한다. 이미 멤버인 대상은 조용히 건너뛰고 alreadyMembers 로 보고한다. */
    fun addMembers(roomId: UUID, userIds: List<String>, joinedAt: Instant): AddMembersResult {
        return db.inTransaction { conn ->
            if (!roomExists(conn, roomId)) {
                return@inTransaction AddMembersResult(roomFound = false, added = emptyList(), alreadyMembers = emptyList())
            }
            val added = mutableListOf<String>()
            val alreadyMembers = mutableListOf<String>()
            for (userId in userIds.distinct()) {
                if (isMemberInternal(conn, roomId, userId)) {
                    alreadyMembers += userId
                } else {
                    insertMemberRow(conn, roomId, userId, joinedAt)
                    added += userId
                }
            }
            if (added.isNotEmpty()) {
                updateMemberSetHash(conn, roomId)
                maybeAutoRenameInternal(conn, roomId)
            }
            AddMembersResult(roomFound = true, added = added, alreadyMembers = alreadyMembers)
        }
    }

    /**
     * 멤버 한 명을 방에서 제거한다. 제거 후 남은 멤버가 0명이면 방 자체(talk_rooms row)를
     * 삭제한다 — talk_room_messages.room_id 는 FK ON DELETE SET NULL 이라 대화 기록은
     * 지워지지 않고 room_id 만 NULL 이 된다(요구사항: 방/멤버십 데이터만 삭제, 대화 기록 보존).
     */
    fun removeMember(roomId: UUID, userId: String): RemoveMemberOutcome {
        return db.inTransaction { conn ->
            if (!roomExists(conn, roomId)) {
                return@inTransaction RemoveMemberOutcome(RemoveMemberResult.ROOM_NOT_FOUND, roomDeleted = false, remainingMembers = emptyList())
            }
            if (!isMemberInternal(conn, roomId, userId)) {
                return@inTransaction RemoveMemberOutcome(RemoveMemberResult.NOT_MEMBER, roomDeleted = false, remainingMembers = emptyList())
            }
            conn.prepareStatement("DELETE FROM talk_room_members WHERE room_id = ? AND user_id = ?").use { ps ->
                ps.setObject(1, roomId)
                ps.setString(2, userId)
                ps.executeUpdate()
            }
            val remaining = listMembersInternal(conn, roomId)
            if (remaining.isEmpty()) {
                conn.prepareStatement("DELETE FROM talk_rooms WHERE room_id = ?").use { ps ->
                    ps.setObject(1, roomId)
                    ps.executeUpdate()
                }
                RemoveMemberOutcome(RemoveMemberResult.OK, roomDeleted = true, remainingMembers = emptyList())
            } else {
                updateMemberSetHash(conn, roomId)
                maybeAutoRenameInternal(conn, roomId)
                RemoveMemberOutcome(RemoveMemberResult.OK, roomDeleted = false, remainingMembers = remaining)
            }
        }
    }

    /** 방 이름을 수동으로 바꾼다 — 그 뒤로는 인원 추가/삭제와 무관하게 이 이름을 그대로 유지한다. */
    fun renameRoom(roomId: UUID, newName: String): Boolean {
        return db.withConnection { conn ->
            conn.prepareStatement("UPDATE talk_rooms SET name = ?, name_is_custom = TRUE WHERE room_id = ?").use { ps ->
                ps.setString(1, newName)
                ps.setObject(2, roomId)
                ps.executeUpdate() > 0
            }
        }
    }

    /**
     * 사용자 요구사항(2026-09-17): 이름을 한 번도 수동으로 바꾼 적 없는 방은 인원이 바뀔 때마다
     * 자동 명명 규칙(솔로→"나와의 대화", ≤3명→쉼표조합, 초과→"첫 사람 외 N명")으로 이름을
     * 재계산한다. 방장(owner_user_id)을 계산에서 제외하는 기준은 최초 생성 시 명명과 동일 —
     * 방장이 이미 나가고 없어도 그 id 는 애초에 목록에 없으므로 제외 연산은 그대로 안전하다.
     */
    private fun maybeAutoRenameInternal(conn: Connection, roomId: UUID) {
        val ownerAndCustom = conn.prepareStatement(
            "SELECT owner_user_id, name_is_custom FROM talk_rooms WHERE room_id = ?",
        ).use { ps ->
            ps.setObject(1, roomId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return
                rs.getString("owner_user_id") to rs.getBoolean("name_is_custom")
            }
        }
        val (ownerUserId, nameIsCustom) = ownerAndCustom
        if (nameIsCustom) return
        val newName = computeAutoRoomName(listMembersInternal(conn, roomId), ownerUserId)
        conn.prepareStatement("UPDATE talk_rooms SET name = ? WHERE room_id = ?").use { ps ->
            ps.setString(1, newName)
            ps.setObject(2, roomId)
            ps.executeUpdate()
        }
    }

    companion object {
        /** 숫자 부분은 크기로 비교하는 아이디 순서(대소문자 무시). */
        val naturalIdOrder: Comparator<String> = Comparator { x, y ->
            var i = 0
            var j = 0
            var result = 0
            while (result == 0 && i < x.length && j < y.length) {
                if (x[i].isDigit() && y[j].isDigit()) {
                    val si = i; while (i < x.length && x[i].isDigit()) i++
                    val sj = j; while (j < y.length && y[j].isDigit()) j++
                    val a = x.substring(si, i).trimStart('0')
                    val b = y.substring(sj, j).trimStart('0')
                    result = if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
                } else {
                    result = x[i].uppercaseChar().compareTo(y[j].uppercaseChar())
                    i++; j++
                }
            }
            if (result != 0) result else (x.length - i).compareTo(y.length - j).takeIf { it != 0 } ?: x.compareTo(y)
        }

        /** 클라이언트 [MainWindow.ComputeAutoRoomName] 과 동일한 규칙 — 서버가 최종 권위를 가진다. */
        fun computeAutoRoomName(allMemberIds: Collection<String>, ownerUserId: String): String {
            // 가입 순서가 아니라 사람이 읽는 아이디 순서(test7, test9, test10) — 클라이언트 IdOrder 와 같은 규칙.
            val others = allMemberIds.filterNot { it == ownerUserId }.distinct().sortedWith(naturalIdOrder)
            return when {
                others.isEmpty() -> "나와의 대화"
                others.size <= 3 -> others.joinToString(", ")
                // 2026-09-17 버그 수정: "외 N명"의 N은 이름이 표시된 1명을 뺀 방 전체 인원이어야
                // 한다. others 는 이미 owner(나)를 뺀 목록이라 others.size 가 곧
                // "전체 인원 - 이름 표시된 1명" 이다 — 예전엔 여기서 한 번 더 -1 을 해서
                // owner 가 카운트에서 통째로 누락됐다(전체 8명이면 "외 7명"이어야 하는데
                // "외 6명"으로 나옴).
                else -> "${others[0]} 외 ${others.size}명"
            }
        }
    }

    open fun isMember(roomId: UUID, userId: String): Boolean =
        db.withConnection { conn -> isMemberInternal(conn, roomId, userId) }

    fun listMembers(roomId: UUID): List<String> =
        db.withConnection { conn -> listMembersInternal(conn, roomId) }

    fun insertRoomTalk(
        roomId: UUID,
        fromUserId: String,
        text: String,
        serverTs: Instant,
        serverTalkId: UUID = UUID.randomUUID(),
        attachmentUploadId: String? = null,
    ): UUID {
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO talk_room_messages (server_talk_id, room_id, from_user_id, text, server_ts, attachment_upload_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, serverTalkId)
                ps.setObject(2, roomId)
                ps.setString(3, fromUserId)
                ps.setString(4, text)
                ps.setTimestamp(5, Timestamp.from(serverTs))
                ps.setString(6, attachmentUploadId)
                ps.executeUpdate()
            }
        }
        return serverTalkId
    }

    /**
     * 방 히스토리 조회 — sinceMillis 이후 메시지 중 가장 최근 `limit` 건.
     *
     * 1:1 [com.osori.server.db.TalkRepository.fetchRecentForUser] 와 동일한 버그가 있었다:
     * `ORDER BY server_ts ASC LIMIT ?` 는 가장 오래된 N건만 반환해, 클라가 항상 sinceMillis=0
     * 으로 부르는 이 API 특성상 방 메시지가 limit(기본 50)을 넘으면 최근 메시지가 영원히
     * 안 보였다. 최신순으로 limit 건을 먼저 뽑은 뒤 오래된 순으로 재정렬하도록 수정.
     *
     * @param sinceMillis 이 시각 이후 (exclusive). 0 이면 전체 범위.
     * @param limit 호출자는 has_more 판정용으로 limit+1 을 넘겨도 됨. 초과분이 있으면
     *   반환 리스트에서 가장 오래된 항목부터 잘라내야 한다(takeLast).
     * @return sinceMillis 이후 메시지 중 최신 `limit` 건, 오래된 순으로 정렬해서 반환
     */
    open fun fetchRoomHistory(roomId: UUID, sinceMillis: Long, limit: Int): List<TalkRoomRow> =
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT server_talk_id, room_id, from_user_id, text, server_ts, attachment_upload_id
                FROM (
                    SELECT server_talk_id, room_id, from_user_id, text, server_ts, attachment_upload_id
                    FROM talk_room_messages
                    WHERE room_id = ? AND server_ts > ?
                    ORDER BY server_ts DESC
                    LIMIT ?
                ) recent
                ORDER BY server_ts ASC
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, roomId)
                ps.setTimestamp(2, Timestamp.from(Instant.ofEpochMilli(sinceMillis)))
                ps.setInt(3, limit)
                ps.executeQuery().use { rs ->
                    val result = mutableListOf<TalkRoomRow>()
                    while (rs.next()) {
                        result += TalkRoomRow(
                            serverTalkId = rs.getObject(1) as UUID,
                            roomId = rs.getObject(2) as UUID,
                            fromUserId = rs.getString(3),
                            text = rs.getString(4),
                            serverTs = rs.getTimestamp(5).toInstant(),
                            attachmentUploadId = rs.getString(6),
                        )
                    }
                    result
                }
            }
        }

    data class RoomSearchRow(
        val serverTalkId: UUID,
        val roomId: UUID,
        val roomName: String,
        val nameIsCustom: Boolean,
        val memberIds: List<String>,
        val fromUserId: String,
        val text: String,
        val serverTs: Instant,
    )

    /**
     * 대화방 메시지 본문 검색 — userId 가 "현재" 멤버인 방의 메시지만(나간 방·남의 방 제외).
     * ILIKE 부분 일치, sinceMillis/untilMillis = 0 이면 제한 없음, 최신순.
     * 데이터가 커지면 `CREATE EXTENSION pg_trgm` + `text gin_trgm_ops` 인덱스로 ILIKE 를 가속할 수 있다
     * (30인 규모에서는 room_id 인덱스 + 멤버 조인으로 충분해 기본 스키마에는 넣지 않음).
     */
    open fun searchRoomTalks(
        userId: String,
        keyword: String,
        sinceMillis: Long,
        untilMillis: Long,
        limit: Int,
    ): List<RoomSearchRow> = db.withConnection { conn ->
        val pattern = "%${keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
        val sinceClause = if (sinceMillis > 0) "AND t.server_ts >= ? " else ""
        val untilClause = if (untilMillis > 0) "AND t.server_ts <= ? " else ""
        val sql = """
            SELECT t.server_talk_id, t.room_id, r.name, r.name_is_custom,
                   (SELECT array_agg(m2.user_id ORDER BY m2.joined_at, m2.user_id) FROM talk_room_members m2 WHERE m2.room_id = t.room_id) AS member_ids,
                   t.from_user_id, t.text, t.server_ts
            FROM talk_room_messages t
            JOIN talk_room_members m ON m.room_id = t.room_id AND m.user_id = ?
            JOIN talk_rooms r ON r.room_id = t.room_id
            WHERE t.text ILIKE ? ESCAPE '\'
              $sinceClause
              $untilClause
            ORDER BY t.server_ts DESC
            LIMIT ?
            """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            var idx = 1
            ps.setString(idx++, userId)
            ps.setString(idx++, pattern)
            if (sinceMillis > 0) ps.setTimestamp(idx++, Timestamp.from(Instant.ofEpochMilli(sinceMillis)))
            if (untilMillis > 0) ps.setTimestamp(idx++, Timestamp.from(Instant.ofEpochMilli(untilMillis)))
            ps.setInt(idx, limit)
            ps.executeQuery().use { rs ->
                val result = mutableListOf<RoomSearchRow>()
                while (rs.next()) {
                    @Suppress("UNCHECKED_CAST")
                    val memberIds = (rs.getArray("member_ids")?.array as? Array<String>)?.toList() ?: emptyList()
                    result += RoomSearchRow(
                        serverTalkId = rs.getObject(1) as UUID,
                        roomId = rs.getObject(2) as UUID,
                        roomName = rs.getString(3),
                        nameIsCustom = rs.getBoolean(4),
                        memberIds = memberIds,
                        fromUserId = rs.getString(6),
                        text = rs.getString(7),
                        serverTs = rs.getTimestamp(8).toInstant(),
                    )
                }
                result
            }
        }
    }

    data class TalkRoomRow(
        val serverTalkId: UUID,
        val roomId: UUID,
        val fromUserId: String,
        val text: String,
        val serverTs: Instant,
        val attachmentUploadId: String? = null,
    )

    /**
     * 읽음 위치를 앞으로만 옮긴다(더 과거 값이 오면 무시). 실제로 바뀌었으면 true.
     */
    open fun advanceLastRead(roomId: UUID, userId: String, lastRead: Instant): Boolean =
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO talk_room_reads (room_id, user_id, last_read_ts)
                VALUES (?, ?, ?)
                ON CONFLICT (room_id, user_id) DO UPDATE
                    SET last_read_ts = EXCLUDED.last_read_ts
                    WHERE talk_room_reads.last_read_ts < EXCLUDED.last_read_ts
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, roomId)
                ps.setString(2, userId)
                ps.setTimestamp(3, Timestamp.from(lastRead))
                ps.executeUpdate() > 0
            }
        }

    /** 현재 멤버들의 읽음 위치(기록 없는 멤버는 빠짐). */
    open fun listReadStates(roomId: UUID): Map<String, Instant> =
        db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT r.user_id, r.last_read_ts
                FROM talk_room_reads r
                JOIN talk_room_members m ON m.room_id = r.room_id AND m.user_id = r.user_id
                WHERE r.room_id = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, roomId)
                ps.executeQuery().use { rs ->
                    val out = linkedMapOf<String, Instant>()
                    while (rs.next()) out[rs.getString(1)] = rs.getTimestamp(2).toInstant()
                    out
                }
            }
        }

    fun countRoomTalks(roomId: UUID): Long {
        return db.withConnection { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM talk_room_messages WHERE room_id = ?",
            ).use { ps ->
                ps.setObject(1, roomId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }
    }

    private fun insertMemberRow(conn: Connection, roomId: UUID, userId: String, joinedAt: Instant) {
        conn.prepareStatement(
            """
            INSERT INTO talk_room_members (room_id, user_id, joined_at)
            VALUES (?, ?, ?)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, roomId)
            ps.setString(2, userId)
            ps.setTimestamp(3, Timestamp.from(joinedAt))
            ps.executeUpdate()
        }
    }

    private fun roomExists(conn: Connection, roomId: UUID): Boolean {
        conn.prepareStatement("SELECT 1 FROM talk_rooms WHERE room_id = ?").use { ps ->
            ps.setObject(1, roomId)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    private fun isMemberInternal(conn: Connection, roomId: UUID, userId: String): Boolean {
        conn.prepareStatement(
            "SELECT 1 FROM talk_room_members WHERE room_id = ? AND user_id = ?",
        ).use { ps ->
            ps.setObject(1, roomId)
            ps.setString(2, userId)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    private fun listMembersInternal(conn: Connection, roomId: UUID): List<String> {
        conn.prepareStatement(
            "SELECT user_id FROM talk_room_members WHERE room_id = ? ORDER BY joined_at",
        ).use { ps ->
            ps.setObject(1, roomId)
            ps.executeQuery().use { rs ->
                val result = mutableListOf<String>()
                while (rs.next()) result.add(rs.getString("user_id"))
                return result
            }
        }
    }

    private fun updateMemberSetHash(conn: Connection, roomId: UUID) {
        val members = listMembersInternal(conn, roomId)
        conn.prepareStatement("UPDATE talk_rooms SET member_set_hash = ? WHERE room_id = ?").use { ps ->
            ps.setString(1, computeMemberSetHash(members))
            ps.setObject(2, roomId)
            ps.executeUpdate()
        }
    }

    /** 순서 무관하게 같은 멤버 집합이면 같은 해시가 나오도록 정렬 후 join 해서 SHA-256. */
    private fun computeMemberSetHash(memberIds: Collection<String>): String {
        val sorted = memberIds.distinct().sorted().joinToString("")
        val digest = MessageDigest.getInstance("SHA-256").digest(sorted.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
