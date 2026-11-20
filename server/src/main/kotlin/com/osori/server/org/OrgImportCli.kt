package com.osori.server.org

import com.osori.server.db.Database
import com.osori.server.db.OrganizationRepository
import com.osori.server.db.UserRepository
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("com.osori.server.OrgImportCli")

/**
 * 조직도 CSV 일괄 임포트 CLI 진입점.
 *
 * 사용법:
 *   gradle (서버 모듈에서):
 *     ./gradlew.bat -q org-import --args="<CSV 경로>"
 *
 *   직접 java:
 *     java -cp build/classes/kotlin/main:... com.osori.server.org.OrgImportCliKt <CSV 경로>
 *
 * 환경변수: 일반 서버와 동일한 OSORI_DB_URL/OSORI_DB_USER/OSORI_DB_PASS/OSORI_DB_POOL_SIZE 사용.
 *
 * 종료 코드:
 *   0 — 성공
 *   1 — 인자 부족 / 파일 없음
 *   2 — 임포트 중 실패 (CSV 형식, DB FK 등)
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        System.err.println("Usage: org-import <csv-path>")
        exitProcess(1)
    }
    val csvPath = Paths.get(args[0])
    logger.info("OrgImportCli 시작 csv={}", csvPath)

    val database = try {
        Database.fromEnv()
    } catch (e: Exception) {
        System.err.println("DB 연결 실패: ${e.message}. OSORI_DB_URL/USER/PASS 환경변수를 확인하세요.")
        exitProcess(2)
    }
    val orgRepo = OrganizationRepository(database)
    val userRepo = UserRepository(database)
    val service = OrgImportService(database, orgRepo, userRepo)

    try {
        val result = service.importFromFile(csvPath)
        logger.info(
            "OrgImportCli 완료 rows={} units={} members={} usersAutoCreated={}",
            result.rowsRead, result.unitsUpserted, result.membersUpserted, result.usersAutoCreated,
        )
        println("OK rows=${result.rowsRead} units=${result.unitsUpserted} members=${result.membersUpserted} usersAutoCreated=${result.usersAutoCreated}")
    } catch (e: OrgImportService.ImportException) {
        System.err.println("FAIL: ${e.message}")
        exitProcess(2)
    } finally {
        runCatching { database.close() }
    }
}
