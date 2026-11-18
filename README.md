# 하루한판

소규모 팀과 함께 쓰는 개인·팀 도구입니다. 로그인하면 홈(`/`)에서 두 앱 중 하나를 고릅니다.

- **하루한판** (`/board`): 할 일, 보드, 달력, 루틴, 메모, 코드 스니펫
- **하루가계부** (`/ledger`): 수입·지출·저축 기록, 돈 달력, 정기결제 자동 기록, 연간결산 (자세한 내용은 [LEDGER.md](LEDGER.md))

로그인·공간(개인/팀)·초대·권한은 두 앱이 같이 씁니다.
PC 와 휴대폰 브라우저에서 쓰고, OCI Always Free 에 올려서 0원으로 운영합니다.

| | |
|---|---|
| 서버 | Java 21, Spring Boot 4 (Web MVC, Security, JdbcClient, Flyway), Gradle |
| DB | 로컬: H2 (`MODE=Oracle`) / 운영: Oracle Autonomous DB |
| 화면 | 순수 HTML/CSS/JS (빌드 도구 없음) + highlight.js 11.11.1 (`static/vendor/`, CDN 아님) |
| 배포 | OCI VM + Caddy(HTTPS) + systemd — [deploy/DEPLOY.md](deploy/DEPLOY.md) |

## 로컬 실행

```bash
./gradlew bootRun
```

`http://127.0.0.1:8765` 로 접속하고, 아이디 `haru`, 비밀번호 `haruhanpan-dev` 로 로그인합니다 (dev 전용 관리자 계정).
데이터는 `./data/` 에 쌓입니다 (git 제외). 처음부터 다시 하려면 이 폴더를 지우면 됩니다.

```bash
./gradlew test        # 통합 테스트 (로그인·API·팀 권한·가져오기·백업)
./gradlew bootJar     # build/libs/haruhanpan.jar
```

## 공간과 팀

모든 데이터는 **공간**에 속합니다.

- **개인 공간**: 가입하면 자동으로 생기고, 나만 봅니다.
- **팀 공간**: 상단 공간 선택에서 "＋ 새 팀 공간"으로 만듭니다. 만든 사람이 관리자가 됩니다.
- **보기 범위** (상단 첫 번째 선택): 홈과 모든 탭이 이 범위를 따릅니다.
  - `👤 내 공간` — 개인 것만
  - `👥 팀` — 그 팀 것만
  - `🔀 내 공간 + 팀` — 개인과 팀 하나를 한 화면에 섞어서 봅니다. 팀은 한 번에 하나만 고를 수 있습니다.
- **같이 볼 때**
  - 팀 항목에는 팀 이름 표시가 붙고, 보드 카드는 왼쪽 색 띠로 공간을 구분합니다.
  - 새 항목은 기본으로 **내 공간**에 저장됩니다. 상단 "저장:" 선택과 입력칸 옆 `→ 내 공간` 표시로 저장될 곳을 항상 볼 수 있습니다.
  - 홈의 팀 할 일은 나에게 배정된 것만 나오고, 그 아래 팀 현황(멤버별 진행·최근 활동)이 나옵니다.
- **공간 옮기기**: 할 일·일정·스니펫은 수정 창의 "공간"에서, 메모는 편집기 위의 공간 선택에서 다른 공간으로 옮길 수 있습니다. 두 공간 모두 편집 권한이 있어야 합니다. 팀에서 개인으로 옮기면 담당자는 비워집니다. 루틴은 체크 기록 때문에 옮기지 않습니다.

| 역할 | 할 수 있는 것 |
|---|---|
| 관리자 | 멤버 초대·역할 변경·내보내기, 공간 이름·색, 가져오기, 공간 삭제 + 편집 |
| 편집 | 할 일·일정·메모·스니펫·루틴·휴무일 추가·수정·삭제 |
| 보기 | 읽기 + 자기 루틴 체크 |

- **가입**: 공개 가입은 없습니다. 팀 관리자가 ⚙ 공간 설정에서 **초대 링크**(7일·1회용)를 만들어 전달하면, 받은 사람이 그 링크로 아이디를 만들고 참여합니다. 이미 계정이 있으면 로그인한 뒤 링크를 엽니다.
- **Google 로그인** (선택, [DEPLOY.md](deploy/DEPLOY.md) 의 Google Cloud 설정이 필요)
  - 기존 사용자는 내 계정에서 Google 을 한 번 연결해 두면 Google 버튼으로 로그인합니다.
  - 새 팀원은 초대 링크에서 "Google 계정으로 가입하고 참여"를 누르면 비밀번호 없이 가입됩니다.
  - 초대 링크가 없는 Google 계정도 **가입 신청**은 할 수 있습니다. 사이트 관리자가 상단 "승인 대기" 버튼에서 승인해야 쓸 수 있습니다. 승인할 때 팀 공간에 바로 넣을 수 있고, 30일 동안 처리하지 않은 신청은 자동으로 지워집니다. 신청을 막으려면 `HARU_GOOGLE_OPEN_SIGNUP=false` 로 바꿉니다.
  - `HARU_GOOGLE_ALLOWED_DOMAIN` 을 정하면 그 회사 Workspace 계정은 승인 없이 바로 가입됩니다.
  - 계정은 Google 의 고유 ID(`sub`)로 구분합니다. 이메일이 바뀌어도 같은 계정으로 인식합니다.
- **비밀번호 분실**
  - 팀원: 사이트 관리자(아이디 `haru`)가 멤버 목록에서 **재설정 링크**(1일·1회용)를 만들어 줍니다.
  - 관리자 본인: 서버에서 `hash-password` 를 실행합니다 ([DEPLOY.md](deploy/DEPLOY.md)).
- **루틴**: 루틴 목록은 공간에서 함께 관리하고, 체크는 사람별로 따로 남습니다. 팀 공간의 기록 표에는 다른 멤버의 체크가 이니셜로 보입니다.
- **휴일**: 공휴일은 모든 공간에 공통입니다. 휴무일은 공간마다 따로 지정합니다.

### 동시 수정

- **변경 받아오기**: 30초마다, 그리고 탭으로 돌아올 때 서버에서 다시 받아옵니다. 입력 중일 때는 건너뜁니다.
- **충돌 처리**: 항목마다 `version` 이 있습니다. 다른 사람이 먼저 고친 항목을 저장하면 409 가 오고, 화면은 최신 내용으로 바뀝니다. 메모는 내가 쓴 내용을 "(내 수정본)" 메모로 따로 남깁니다.

## 코드 구조

```
src/main/java/com/haruhanpan/
├─ HaruhanpanApplication.java   진입점 (+ hash-password 명령)
├─ config/   SecurityConfig(로그인·CSRF·보안 헤더), WebConfig, HaruProperties
├─ auth/     LoginAttemptService(시도 제한), RememberKey, PasswordSetup
├─ account/  AppUser, UserRepository, OwnerBootstrap(관리자 비밀번호), AccountController(내 계정)
├─ space/    SpaceService(권한 검사 require), SpaceRepository, SpaceController(멤버·초대)
├─ invite/   InviteService(초대·재설정 링크), InviteController(로그인 없이 호출)
├─ google/   GoogleLoginService(Google 계정 → 사용자), GoogleLoginSuccessHandler, GoogleController(연결·가입 시작)
├─ data/     Collection(화면↔테이블 매핑), *Repository, DashboardService, DataController(REST API)
├─ common/   ApiErrors(403·404·409), Ids
└─ ops/      BackupService(일일 JSON 백업), KeepAliveJob(ADB 정지 방지)
src/main/resources/
├─ application.yml               dev / prod 프로파일
├─ db/migration/                 Flyway (V1 스키마, V2 공휴일·기본 루틴, V3 사용자·공간·팀)
└─ static/                       index.html, app.js, style.css, login.*, invite.*, theme.js, vendor/highlight/
```

### 설계 메모

- **모든 데이터 API 는 `/api/spaces/{공간}/…` 형태입니다.** 맨 앞에서 `SpaceService.require(나, 공간, 최소역할)` 로 멤버인지, 역할이 충분한지 확인합니다. 수정·삭제 SQL 은 항상 `WHERE id = ? AND space_id = ?` 이라서, 다른 공간의 id 를 보내도 그 공간의 데이터는 바뀌지 않습니다.
- **할 일·일정·메모·스니펫·루틴은 SQL 을 정의에서 만듭니다.** 테이블마다 Repository 를 만들지 않고, `Collection` enum 에 화면 필드와 컬럼의 매핑을 한 번 적어 두고 SQL 을 생성합니다. 컬럼을 추가할 때는 Flyway `V4__…sql` 을 만들고 enum 에 한 줄을 넣으면 됩니다.
- **Oracle 은 `''` 를 NULL 로 저장합니다.** 그래서 조회할 때 텍스트 NULL 을 `''` 로 바꿔 돌려줍니다. H2 도 Oracle 모드로 띄워서 로컬에서도 같은 동작을 재현합니다.
- **로그인**
  - 비밀번호는 BCrypt 해시로 `users` 테이블에 저장합니다.
  - 30일 로그인 유지 쿠키를 씁니다. 쿠키 서명에 비밀번호 해시가 들어가므로, 비밀번호를 바꾸면 그 사람의 다른 기기는 로그아웃됩니다.
  - 로그인 실패는 IP당 15분에 5회, 전체 1시간에 30회까지 허용하고, 넘으면 429 를 돌려줍니다.
- **초대·재설정 토큰**: 256비트 난수입니다. DB 에는 SHA-256 해시만 저장하고, 링크는 `/invite.html#토큰` 형태라 서버 접근 로그와 Referer 에 남지 않습니다.
- **스니펫 강조**: SQL 은 자체 강조기를 씁니다. Oracle 힌트 `/*+ */`, 바인드 변수 `:name` 등을 구분합니다. 나머지 언어(Java, Python, Shell, PowerShell, JS/TS, Go, Kotlin, JSON, YAML, XML, Properties, Dockerfile, Markdown)는 highlight.js 로 강조하고, "자동 판단"이면 언어도 추측합니다.
- **보드의 드래그는 HTML5 드래그 앤 드롭이라 터치 기기에서는 동작하지 않습니다.** 휴대폰에서는 카드의 체크박스나 수정 창의 "상태"로 옮깁니다.

`guide.md` 는 처음 구상했던 Python 1인용 버전의 지시서입니다. 화면과 기능 명세(2~3장)는 이 문서를 따랐고, 서버는 Java 로 새로 작성했으며, 이후 팀 공유 기능을 더했습니다.
