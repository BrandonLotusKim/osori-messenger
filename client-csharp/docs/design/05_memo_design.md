# 쪽지(Memo) 기능 재설계 — 답장/전체회신/전달 + 첨부파일 (OC-D04)

> 배경: 쪽지 화면 점검 중 "오소리엔 쪽지함 리스트 창이 없다"고
> 잘못 판단했으나, 실제로는 `MainWindow.xaml` 의 쪽지 탭(`MemoTabContent`)에 받은/보낸함 리스트·배지·빈 상태까지
> **이미 구현되어 있었다.** (2026-07-10 사용자 지적으로 정정)
>
> 실제 공백은 리스트 창의 유무가 아니라 **상세창의 액션 폭**이었다:
> - 회신 계열 버튼(답장/전체회신/전달)이 전혀 없었음 — 삭제/닫기만 존재
> - `has_attachment` 필드가 있는데도 상세창에 첨부파일을 보여주거나 받을 방법이 없었음
> - `MemoListResponse` 의 INBOX 엔트리는 `recipient_ids` 가 항상 "나 1명" 이라, 그룹 쪽지를 받아도
>   원래 몇 명에게 갔는지 클라이언트가 알 방법이 없어 "전체회신" 자체가 성립 불가능했음(서버 쿼리 한계)

이 문서는 그 세 가지를 메운 변경 사항을 기록한다. **우클릭 메뉴가 아니라 상세창 액션바에 상시 아이콘 버튼**으로
노출한다 — 이런 보조 액션은 보통 대화상대/말풍선 우클릭 메뉴로 노출하지만, 쪽지 상세창은 "본문을 다 읽은 뒤 다음 행동을 고르는" 단일 목적 화면이라 리스트/말풍선과 달리 상시
노출 액션바가 자연스럽다고 판단.

---

## 1. 서버 변경

### 1.1 `MemoRepository.fetchInbox` — 전체 수신자 반환
기존: `recipientIds = listOf(userId)` (본인 1명 고정)
변경: `memo_recipients` 서브쿼리로 `string_agg` 하여 해당 쪽지의 **전체 수신자**를 반환 (스키마 변경 없음, 쿼리만 확장)

`sql
(SELECT string_agg(mr2.recipient_id, ',' ORDER BY mr2.recipient_id)
 FROM memo_recipients mr2 WHERE mr2.server_memo_id = m.server_memo_id) AS all_recipients
MemoEntry.recipient_ids` (proto 필드 자체는 기존 그대로, 값 채우는 방식만 변경 — 하위호환).

### 1.2 검증
`MemoFullStackTest` 에 케이스 추가: alice → [bob, carol] 동시 발송 → bob 이 받은쪽지함 조회 시 `recipient_ids` 에 bob·carol 둘 다 포함되는지 확인. 로컬 Postgres 16 portable(`osori_test`) 기동 후 `gradlew test` 전체 스위트(서버) 통과 확인 완료 (2026-07-10).

---

## 2. 클라이언트 변경

### 2.1 `MemoRowVm` 필드 확장
`
FromUserId       string              // 원 발신자 (INBOX/SENT 공통)
RecipientIds     IReadOnlyList<string> // 해당 쪽지의 전체 수신자
AttachmentVisibility Visibility      // HasAttachment 바인딩용
FromReceived`(실시간 푸시)는 서버가 sibling recipient 를 안 주므로 `RecipientIds=[me]` 로 임시 채움 — 새로고침(F5) 또는 박스 재조회 시 `FromEntry` 로 교체되며 정확한 값으로 갱신됨. **알려진 제약**: 방금 푸시로 받은 그룹 쪽지를 새로고침 전에 바로 열면 전체회신이 일시적으로 비활성일 수 있음.

### 2.2 `MemoDetailWindow` — 액션바 확장
`
╔═══════════════════════════════════╗
║ 쪽지                          [×] ║
╠═══════════════════════════════════╣
║ 보낸 사람 · alice        14:02    ║
║ 팀 워크샵 공지                     ║
╠═══════════════════════════════════╣
║ 📎 첨부파일 1개      [⬇ 다운로드] ║  ← HasAttachment 일 때만
╠═══════════════════════════════════╣
║ (본문 스크롤 영역)                 ║
║                                     ║
╠═══════════════════════════════════╣
║[↩][↩↩][↪]      상태...  [삭제][닫기]║
╚═══════════════════════════════════╝
`
- **답장(↩)**: INBOX 전용, 항상 활성. 대상 = `FromUserId` 1명.
- **전체회신(↩↩)**: INBOX 전용. `RecipientIds` 에 나 말고 다른 사람이 있을 때만 활성(=그룹 쪽지). 대상 = `FromUserId` + (`RecipientIds` \ 나).
- **전달(↪)**: INBOX/SENT 공통 항상 활성. 대상 = 빈 칸(직접 입력).
- SENT 함(내가 보낸 쪽지)에서는 답장/전체회신 버튼을 비활성 처리 — 자기 자신에게 회신하는 흐름은 만들지 않음. 전달만 허용.
- 첨부파일: 클릭 시 바로 `DownloadFileAsync` 실행(파일명은 서버 응답에서만 알 수 있어 사전 표시 불가 — 다운로드 완료 후 `SaveFileDialog` 기본값으로 채움) → 저장 → 탐색기에서 하이라이트.

### 2.3 `MemoComposeWindow.SetPrefill(recipients, title, quotedBody)`
답장/전체회신/전달 시 MainWindow 가 호출. 수신자 칩·제목("답장: "/"전달: " 접두)·인용 본문(`> ` 줄 인용 + 원본 발신자/시각 구분선)을 미리 채우고 포커스를 본문으로 이동.

### 2.4 `MainWindow` 배선
`MemoDetailWindow.RequestedAction` (`None/Reply/ReplyAll/Forward`) 을 다이얼로그 종료 후 확인 → `OpenMemoComposeForAction` 이 `MemoComposeWindow` 를 새로 열고 프리필. 쪽지 리스트 아이템에는 `HasAttachment` 바인딩으로 📎 아이콘을 제목 앞에 인라인 표시(문서 04 P0 항목 반영).

### 2.5 아이콘 추가 (`Themes/Icons.xaml`)
`IconReply` / `IconForward` / `IconDownload` 3개 신규 지오메트리 (24x24 기준, 기존 아이콘과 동일한 F1 fill-rule 스타일로 손그림).

---

## 3. Out of scope (이번엔 안 함)
- 첨부파일 **작성**(Compose 단계에서 파일 업로드) — `UploadFileAsync(filePath, toUserId, ...)` 가 단일 수신자 기준으로 설계돼 있어 다중 수신자 쪽지에 그대로 못 씀. 서버 파일 모델 확장이 필요한 별도 과제.
- 예약전송/참조(CC)/서식 — 보류.
- 대화방 폴더 관리 — 비채택.

---

## 4. AutomationId 추가
`
MemoReplyButton, MemoReplyAllButton, MemoForwardButton
MemoAttachmentDownloadButton
`
