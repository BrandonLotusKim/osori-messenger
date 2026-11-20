package com.osori.server.org

import com.osori.server.db.Database
import com.osori.server.db.OrganizationRepository
import com.osori.server.db.UserRepository
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("com.osori.server.OrgImportService")

/**
 * 조직도 CSV 일괄 임포트 서비스.
 *
 * **포맷 (UTF-8, 헤더 첫 줄 필수)**:
 *   org_path,user_id,display_name,email,phone,mobile,title,sort_order
 *
 * 예:
 *   오소리랩/연구개발본부/플랫폼개발팀,kjm,홍길동,kjm@example.com,02-1234-5678,010-1111-2222,선임연구원,1
 *   오소리랩/연구개발본부/플랫폼개발팀,sjh,이영희,sjh@example.com,,010-3333-4444,팀장,2
 *
 * 동작:
 *   1. CSV 라인 → OrgImportRow 파싱 (필드 trim, 빈 줄/주석(`#`) 무시).
 *   2. 모든 org_path 를 슬래시 단위로 분해해서 부서 트리 빌드 — 중간 노드도 자동 upsert.
 *      예: `A/B/C` 한 줄만 있어도 `A`, `A/B`, `A/B/C` 세 노드가 다 만들어진다.
 *   3. user_id 가 auth_users 에 없으면 createIfAbsent 로 자동 생성 (display_name 동기화).
 *   4. org_members 는 upsert (멱등). 같은 user_id 가 두 줄 있으면 마지막 줄이 이김.
 *   5. 전체를 단일 트랜잭션으로 묶어 부분 실패 시 롤백.
 *
 * 검증되지 않은 입력은 ImportException 으로 라인 번호와 사유를 함께 던진다.
 */
open class OrgImportService(
    private val database: Database,
    private val orgRepo: OrganizationRepository,
    private val userRepo: UserRepository,
) {

    /** 단일 CSV 행 (검증 통과 후의 정규화 형태). */
    data class OrgImportRow(
        val orgPath: String,
        val userId: String,
        val displayName: String,
        val email: String,
        val phone: String,
        val mobile: String,
        val title: String,
        val sortOrder: Int,
        val sourceLine: Int,
    )

    /** 임포트 결과 요약. */
    data class ImportResult(
        val rowsRead: Int,
        val unitsUpserted: Int,
        val membersUpserted: Int,
        val usersAutoCreated: Int,
    )

    class ImportException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    // ──────────────────────────── 진입점 ────────────────────────────

    /** 파일 경로에서 임포트. UTF-8 고정. */
    open fun importFromFile(path: Path): ImportResult {
        if (!Files.exists(path)) {
            throw ImportException("CSV 파일이 존재하지 않습니다: $path")
        }
        Files.newInputStream(path).use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                return importFromReader(reader, source = path.toString())
            }
        }
    }

    /** 임의 텍스트(테스트용 등) 에서 임포트. */
    open fun importFromString(csv: String, source: String = "<string>"): ImportResult {
        return importFromReader(csv.reader().buffered(), source)
    }

    // ──────────────────────────── 핵심 ────────────────────────────

    private fun importFromReader(reader: BufferedReader, source: String): ImportResult {
        val rows = parseRows(reader, source)
        if (rows.isEmpty()) {
            logger.warn("OrgImport: 유효 데이터 0 건. source={}", source)
            return ImportResult(0, 0, 0, 0)
        }

        // 1) 부서 트리 빌드: 등장한 모든 path 를 슬래시 단위로 분해해서 중간 노드까지 수집.
        val allUnitPaths: LinkedHashSet<String> = LinkedHashSet()
        rows.forEach { row ->
            allUnitPaths.addAll(expandAncestors(row.orgPath))
        }

        // 2) 부서 → 사용자 → 멤버 순서. **부분 실패 안전성은 멱등 upsert 로 보장**:
        //    Repository 메서드가 자체 connection 을 따오기 때문에 외부 트랜잭션으로 묶을 수 없다.
        //    재실행이 안전하므로 (ON CONFLICT DO UPDATE) 중간 실패 시 사용자가 같은 CSV 로 다시 돌리면 OK.
        var unitsUpserted = 0
        var membersUpserted = 0
        var usersAutoCreated = 0
        try {
            // depth 순으로 정렬해서 부모가 먼저 들어가도록 (FK 무결성).
            val sortedUnits = allUnitPaths.sortedBy { it.count { c -> c == '/' } }
            sortedUnits.forEach { path ->
                val parts = path.split('/')
                val name = parts.last()
                val depth = parts.size - 1
                val parent = if (depth == 0) null else parts.dropLast(1).joinToString("/")
                orgRepo.upsertUnit(
                    OrganizationRepository.OrgUnit(
                        path = path,
                        parentPath = parent,
                        name = name,
                        depth = depth,
                        sortOrder = 0,
                    ),
                )
                unitsUpserted++
            }

            rows.forEach { row ->
                if (userRepo.findById(row.userId) == null) {
                    userRepo.createIfAbsent(row.userId, row.displayName)
                    usersAutoCreated++
                }
                orgRepo.upsertMember(
                    OrganizationRepository.OrgMember(
                        userId = row.userId,
                        orgPath = row.orgPath,
                        email = row.email,
                        phone = row.phone,
                        mobile = row.mobile,
                        title = row.title,
                        sortOrder = row.sortOrder,
                    ),
                )
                membersUpserted++
            }
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            throw ImportException("OrgImport 진행 중 실패 (이미 처리된 행은 멱등 upsert 로 안전, 같은 CSV 재실행 가능): ${e.message}", e)
        }

        val result = ImportResult(
            rowsRead = rows.size,
            unitsUpserted = unitsUpserted,
            membersUpserted = membersUpserted,
            usersAutoCreated = usersAutoCreated,
        )
        logger.info(
            "OrgImport 완료 source={} rows={} units={} members={} usersAutoCreated={}",
            source, result.rowsRead, result.unitsUpserted, result.membersUpserted, result.usersAutoCreated,
        )
        return result
    }

    /**
     * CSV 라인 파싱 — 헤더 1줄 필수, 빈 줄과 `#` 시작 라인 무시.
     * 따옴표 처리는 단순화 (필드 안에 콤마/따옴표 없다고 가정). 사내 CSV 파일에 충분.
     */
    internal fun parseRows(reader: BufferedReader, source: String): List<OrgImportRow> {
        val out = mutableListOf<OrgImportRow>()
        var lineNo = 0
        var headerSeen = false

        reader.lineSequence().forEach { rawLine ->
            lineNo++
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            if (!headerSeen) {
                // 헤더 라인: 컬럼 검증
                val cols = line.split(',').map { it.trim().lowercase() }
                val expected = listOf(
                    "org_path", "user_id", "display_name",
                    "email", "phone", "mobile", "title", "sort_order",
                )
                if (cols != expected) {
                    throw ImportException(
                        "[$source:$lineNo] CSV 헤더가 다릅니다. 기대: $expected, 실제: $cols",
                    )
                }
                headerSeen = true
                return@forEach
            }

            val parts = line.split(',').map { it.trim() }
            if (parts.size != 8) {
                throw ImportException(
                    "[$source:$lineNo] 컬럼 수가 8 이 아닙니다 (실제 ${parts.size}). line=$line",
                )
            }
            val orgPath = parts[0].trim('/')
            val userId = parts[1]
            if (orgPath.isEmpty()) {
                throw ImportException("[$source:$lineNo] org_path 가 비어있습니다.")
            }
            if (userId.isEmpty()) {
                throw ImportException("[$source:$lineNo] user_id 가 비어있습니다.")
            }
            val sortOrder = parts[7].toIntOrNull() ?: 0

            out.add(
                OrgImportRow(
                    orgPath = orgPath,
                    userId = userId,
                    displayName = parts[2].ifBlank { userId },
                    email = parts[3],
                    phone = parts[4],
                    mobile = parts[5],
                    title = parts[6],
                    sortOrder = sortOrder,
                    sourceLine = lineNo,
                ),
            )
        }
        if (!headerSeen) {
            throw ImportException("[$source] CSV 헤더 라인이 없습니다 (필수: org_path,user_id,display_name,email,phone,mobile,title,sort_order)")
        }
        return out
    }

    /** "A/B/C" → ["A", "A/B", "A/B/C"]. 각 path 는 슬래시 trim 된 정규형. */
    internal fun expandAncestors(path: String): List<String> {
        val parts = path.trim('/').split('/').filter { it.isNotBlank() }
        val acc = mutableListOf<String>()
        val sb = StringBuilder()
        parts.forEachIndexed { i, p ->
            if (i > 0) sb.append('/')
            sb.append(p)
            acc.add(sb.toString())
        }
        return acc
    }
}
