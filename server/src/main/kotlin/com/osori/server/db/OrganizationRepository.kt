package com.osori.server.db

import org.slf4j.LoggerFactory
import java.sql.Timestamp
import java.time.Instant

private val logger = LoggerFactory.getLogger("com.osori.server.OrganizationRepository")

/**
 * 조직도 Repository — `org_units` (부서 트리) + `org_members` (사용자 메타) 두 테이블 담당.
 *
 * 핵심 정책:
 *   - **path 자체가 자연키**. 예: '오소리랩/연구개발본부/플랫폼개발팀'.
 *   - 트리 구조는 `parent_path` 자기 참조로 표현. ROOT 노드는 `parent_path = NULL`.
 *   - upsert 는 모두 멱등 (`ON CONFLICT ... DO UPDATE` 또는 `DO NOTHING`).
 *   - `auth_users` 의 인증 정보와는 깔끔히 분리 — 메타데이터 DB 인스턴스 분리 시 이 테이블 두 개만 옮기면 됨.
 *
 * Phase 6 첫 단계로, CSV 임포트는 다음 단계 에서 별도 ImportService 가 사용한다.
 */
open class OrganizationRepository(private val db: Database) {

    data class OrgUnit(
        val path: String,
        val parentPath: String?,
        val name: String,
        val depth: Int,
        val sortOrder: Int,
    )

    data class OrgMember(
        val userId: String,
        val orgPath: String?,
        val email: String,
        val phone: String,
        val mobile: String,
        val title: String,
        val sortOrder: Int,
    )

    // ──────────────────────────── org_units ────────────────────────────

    /** 부서 노드 단건 upsert. 같은 path 가 있으면 name/parent/depth/sort 만 갱신. */
    open fun upsertUnit(unit: OrgUnit): Unit = db.withConnection { conn ->
        conn.prepareStatement(
            """
            INSERT INTO org_units (path, parent_path, name, depth, sort_order, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (path) DO UPDATE SET
                parent_path = EXCLUDED.parent_path,
                name        = EXCLUDED.name,
                depth       = EXCLUDED.depth,
                sort_order  = EXCLUDED.sort_order
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, unit.path)
            if (unit.parentPath == null) ps.setNull(2, java.sql.Types.VARCHAR)
            else ps.setString(2, unit.parentPath)
            ps.setString(3, unit.name)
            ps.setInt(4, unit.depth)
            ps.setInt(5, unit.sortOrder)
            ps.setTimestamp(6, Timestamp.from(Instant.now()))
            ps.executeUpdate()
        }
    }

    open fun findUnit(path: String): OrgUnit? = db.withConnection { conn ->
        conn.prepareStatement(
            "SELECT path, parent_path, name, depth, sort_order FROM org_units WHERE path = ?",
        ).use { ps ->
            ps.setString(1, path)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    OrgUnit(
                        path = rs.getString(1),
                        parentPath = rs.getString(2),
                        name = rs.getString(3),
                        depth = rs.getInt(4),
                        sortOrder = rs.getInt(5),
                    )
                } else null
            }
        }
    }

    /** 트리 전체 조회 — depth ASC, sort_order ASC, path ASC. 트리 빌드는 호출자 책임. */
    open fun listAllUnits(): List<OrgUnit> = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT path, parent_path, name, depth, sort_order
                FROM org_units
                ORDER BY depth ASC, sort_order ASC, path ASC
                """.trimIndent(),
            ).use { rs ->
                val out = mutableListOf<OrgUnit>()
                while (rs.next()) {
                    out.add(
                        OrgUnit(
                            path = rs.getString(1),
                            parentPath = rs.getString(2),
                            name = rs.getString(3),
                            depth = rs.getInt(4),
                            sortOrder = rs.getInt(5),
                        ),
                    )
                }
                out
            }
        }
    }

    /** 직속 자식만 조회 (한 단계). parentPath = NULL 이면 ROOT 노드 목록. */
    open fun listChildren(parentPath: String?): List<OrgUnit> = db.withConnection { conn ->
        val sql =
            if (parentPath == null) {
                "SELECT path, parent_path, name, depth, sort_order FROM org_units WHERE parent_path IS NULL ORDER BY sort_order, path"
            } else {
                "SELECT path, parent_path, name, depth, sort_order FROM org_units WHERE parent_path = ? ORDER BY sort_order, path"
            }
        conn.prepareStatement(sql).use { ps ->
            if (parentPath != null) ps.setString(1, parentPath)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<OrgUnit>()
                while (rs.next()) {
                    out.add(
                        OrgUnit(
                            path = rs.getString(1),
                            parentPath = rs.getString(2),
                            name = rs.getString(3),
                            depth = rs.getInt(4),
                            sortOrder = rs.getInt(5),
                        ),
                    )
                }
                out
            }
        }
    }

    open fun countUnits(): Long = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM org_units").use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

    // ──────────────────────────── org_members ────────────────────────────

    /**
     * 사용자 메타 단건 upsert.
     * 호출 전 `auth_users` 와 `org_units(orgPath)` 가 존재해야 한다 (FK 제약).
     * `orgPath = null` 이면 무소속.
     */
    open fun upsertMember(member: OrgMember): Unit = db.withConnection { conn ->
        conn.prepareStatement(
            """
            INSERT INTO org_members
                (user_id, org_path, email, phone, mobile, title, sort_order, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id) DO UPDATE SET
                org_path   = EXCLUDED.org_path,
                email      = EXCLUDED.email,
                phone      = EXCLUDED.phone,
                mobile     = EXCLUDED.mobile,
                title      = EXCLUDED.title,
                sort_order = EXCLUDED.sort_order,
                updated_at = EXCLUDED.updated_at
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, member.userId)
            if (member.orgPath == null) ps.setNull(2, java.sql.Types.VARCHAR)
            else ps.setString(2, member.orgPath)
            ps.setString(3, member.email)
            ps.setString(4, member.phone)
            ps.setString(5, member.mobile)
            ps.setString(6, member.title)
            ps.setInt(7, member.sortOrder)
            ps.setTimestamp(8, Timestamp.from(Instant.now()))
            ps.executeUpdate()
        }
    }

    open fun findMember(userId: String): OrgMember? = db.withConnection { conn ->
        conn.prepareStatement(
            "SELECT user_id, org_path, email, phone, mobile, title, sort_order FROM org_members WHERE user_id = ?",
        ).use { ps ->
            ps.setString(1, userId)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    OrgMember(
                        userId = rs.getString(1),
                        orgPath = rs.getString(2),
                        email = rs.getString(3),
                        phone = rs.getString(4),
                        mobile = rs.getString(5),
                        title = rs.getString(6),
                        sortOrder = rs.getInt(7),
                    )
                } else null
            }
        }
    }

    /** 전체 멤버 목록 (org_path, sort_order, user_id 순). 조직도 snapshot API 용. */
    open fun listAllMembers(): List<OrgMember> = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT user_id, org_path, email, phone, mobile, title, sort_order
                FROM org_members
                ORDER BY COALESCE(org_path, ''), sort_order, user_id
                """.trimIndent(),
            ).use { rs ->
                val out = mutableListOf<OrgMember>()
                while (rs.next()) {
                    out.add(
                        OrgMember(
                            userId = rs.getString(1),
                            orgPath = rs.getString(2),
                            email = rs.getString(3),
                            phone = rs.getString(4),
                            mobile = rs.getString(5),
                            title = rs.getString(6),
                            sortOrder = rs.getInt(7),
                        ),
                    )
                }
                out
            }
        }
    }

    /** 특정 부서에 속한 멤버 목록 (sort_order, user_id 순). */
    open fun listMembersByOrg(orgPath: String): List<OrgMember> = db.withConnection { conn ->
        conn.prepareStatement(
            """
            SELECT user_id, org_path, email, phone, mobile, title, sort_order
            FROM org_members
            WHERE org_path = ?
            ORDER BY sort_order, user_id
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, orgPath)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<OrgMember>()
                while (rs.next()) {
                    out.add(
                        OrgMember(
                            userId = rs.getString(1),
                            orgPath = rs.getString(2),
                            email = rs.getString(3),
                            phone = rs.getString(4),
                            mobile = rs.getString(5),
                            title = rs.getString(6),
                            sortOrder = rs.getInt(7),
                        ),
                    )
                }
                out
            }
        }
    }

    open fun countMembers(): Long = db.withConnection { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM org_members").use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

    /** 테스트/리셋용. 외부에서 신중히 호출. */
    open fun deleteAll() {
        db.withConnection { conn ->
            conn.createStatement().use { st ->
                st.execute("DELETE FROM org_members")
                st.execute("DELETE FROM org_units")
            }
            logger.warn("OrganizationRepository.deleteAll — org_members + org_units 전체 삭제")
        }
    }
}
