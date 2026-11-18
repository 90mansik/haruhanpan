# OCI 배포 순서

```
브라우저 ──HTTPS──▶ [OCI Always Free VM]  Caddy(443) ──▶ 도커 컨테이너 haruhanpan (127.0.0.1:8765)
                                                              └── JDBC(TLS) ──▶ [Autonomous DB Always Free]
                    /var/lib/haruhanpan/  password.hash, remember.key, backup/*.json
```

## 1. OCI 준비 (콘솔에서 직접)

- [ ] **계정**: 가능하면 Pay As You Go 로 업그레이드한다. 무료 한도 안에서는 0원이고, 사용률이 낮은 Always Free VM 이 회수되는 대상에서 빠진다.
- [ ] **Autonomous Database**: Always Free 체크, 워크로드는 Transaction Processing.
  - 네트워크: "보안 액세스: 허용된 IP 및 VCN" → VM 공인 IP 만 허용 → mTLS 필수 해제 → 지갑 없이 TLS 접속 (권장)
  - 또는 mTLS 유지 + 지갑 다운로드 (`haruhanpan.env.example` 의 (B))
- [ ] **DB 계정**: Database Actions(SQL 워크시트)에서 ADMIN 으로 한 번 실행한다. 앱은 ADMIN 으로 돌리지 않는다.
  ```sql
  CREATE USER haruhanpan IDENTIFIED BY "<강한 비밀번호>" DEFAULT TABLESPACE data QUOTA 1G ON data;
  GRANT CREATE SESSION, CREATE TABLE TO haruhanpan;
  ```
  테이블은 앱이 처음 시작할 때 Flyway 가 만든다 (`src/main/resources/db/migration`).
- [ ] **Compute VM**: Ampere A1 Flex (ARM) 1~2 OCPU / 6~12GB, Oracle Linux 8 또는 Ubuntu, 공인 IP. 이미지가 ARM 용이라 ARM VM 이어야 한다.
  "Out of host capacity" 로 만들어지지 않으면 시간을 두고 다시 시도하거나 AMD E2.1.Micro(1GB)로 만든다. 서비스 파일의 `-Xmx384m` 은 1GB 에서도 돌도록 맞춘 값이다.
- [ ] **VCN 보안 목록**: 수신 TCP 80, 443 허용. 22 는 내 IP 만.
- [ ] **도메인 DNS**: A 레코드 → VM 공인 IP.

## 2. 서버 준비 (VM 에 SSH 접속 후, 처음 한 번)

Oracle Linux 8 (aarch64, 사용자 `opc`) 기준. Ubuntu 면 `dnf` → `apt`, `firewall-cmd` → `ufw`/`iptables`.

```bash
# 방화벽: 웹만 연다 (앱 포트 8765 는 열지 않는다)
sudo firewall-cmd --permanent --add-service=http --add-service=https && sudo firewall-cmd --reload

# Docker CE + compose
sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker

# Caddy (HTTPS, 인증서 자동)
sudo dnf --enablerepo=ol8_developer_EPEL install -y caddy

# 데이터 폴더 (백업·로그인 유지 키). 컨테이너는 이 uid:gid 로 돈다 → compose.yml 의 user 와 맞춘다
sudo useradd --system --no-create-home --shell /sbin/nologin haruhanpan
id haruhanpan      # 예: uid=985 gid=983 → deploy/compose.yml 의 user: "985:983"
sudo install -o haruhanpan -g haruhanpan -m 700 -d /var/lib/haruhanpan

# 배포 계정: GitHub Actions 전용. sudo 없음, docker 만. 키는 deploy.sh 만 실행할 수 있다
sudo useradd --create-home deploy && sudo usermod -aG docker deploy && sudo passwd -l deploy
sudo install -o root -g deploy -m 775 -d /opt/haruhanpan
sudo install -o root -g deploy -m 644 compose.yml /opt/haruhanpan/
sudo install -o root -g deploy -m 755 deploy.sh /opt/haruhanpan/
```

## 3. 비밀값 (VM 에만)

```bash
sudo install -o root -g deploy -m 640 haruhanpan.env.example /etc/haruhanpan.env
sudo nano /etc/haruhanpan.env          # ORA_USER / ORA_PASSWORD / ORA_URL (+ Google 로그인 값)
```

사이트 관리자(아이디 `haru`) 비밀번호 (12자 이상). 컨테이너가 시작할 때 적용하고 파일을 지운다.
```bash
sudo docker run --rm -it --user 985:983 -v /var/lib/haruhanpan:/var/lib/haruhanpan \
  -e HARU_DATA_DIR=/var/lib/haruhanpan --entrypoint java ghcr.io/90mansik/haruhanpan:latest \
  -jar /app/haruhanpan.jar hash-password
```

## 4. CI/CD 연결 (GitHub)

1. 배포 키를 만든다 (내 PC): `ssh-keygen -t ed25519 -N "" -f deploy_key`
2. VM `/home/deploy/.ssh/authorized_keys` 에 공개키를 **명령 제한과 함께** 넣는다 (700/600, 소유자 deploy):
   ```
   command="/opt/haruhanpan/deploy.sh",restrict ssh-ed25519 AAAA... github-actions-deploy
   ```
   이 키로는 쉘·포트 포워딩이 안 되고, 넘긴 값(이미지 태그)만 deploy.sh 로 전달된다.
3. 저장소 Settings → Environments → **production**: Required reviewers(나), Deployment branches = `main`
4. production 환경 secrets:
   | 이름 | 값 |
   |---|---|
   | `DEPLOY_SSH_KEY` | `deploy_key` (개인키 전체) |
   | `DEPLOY_KNOWN_HOSTS` | `ssh-keyscan -t ed25519 <VM IP>` 결과 |
   | `DEPLOY_HOST` | VM 공인 IP |
5. 처음 이미지가 올라가면 GitHub → Packages → haruhanpan → Package settings → **Change visibility → Public**
   (서버가 로그인 없이 받도록. 이미지에는 비밀값이 없다)

흐름 (`.github/workflows/ci.yml`):
```
PR·push ─ 테스트
main push ─ 테스트 → ARM 이미지 → ghcr.io/90mansik/haruhanpan:sha-<커밋 7자리>, latest
          → [production 승인] → ssh deploy@VM sha-xxxxxxx
          → deploy.sh: pull → up → /api/health 확인 (최대 90초), 실패하면 직전 태그로 되돌림
```

## 5. HTTPS

```bash
sudo install -m 644 Caddyfile /etc/caddy/Caddyfile
sudo nano /etc/caddy/Caddyfile         # <내 도메인> 을 실제 도메인으로
sudo systemctl enable --now caddy
```

- [ ] `https://<도메인>` → 로그인 화면, `http://` → https 로 이동
- [ ] 아이디 `haru` 로 로그인 → 홈에서 하루한판·하루가계부 동작, 새로고침 후에도 데이터 유지

## 운영

| 작업 | 방법 |
|---|---|
| 업데이트 | `main` 에 push → Actions 에서 production **승인** → 자동 배포·헬스 체크 |
| 지금 버전 | VM `cat /opt/haruhanpan/current-tag`, 기록은 `/opt/haruhanpan/deploy.log` |
| 되돌리기 | `sudo -u deploy /opt/haruhanpan/deploy.sh sha-<예전 커밋>` (GHCR 에 태그별로 남아 있다) |
| 로그 | `sudo docker logs -f haruhanpan` (로그인 성공·실패 IP 도 여기에) |
| 재시작 | `cd /opt/haruhanpan && sudo -u deploy docker compose restart` |
| 비밀번호 변경 | 각자 화면의 **내 계정**에서 바꾼다 |
| 팀원 비밀번호 분실 | 사이트 관리자가 ⚙ 공간 설정 → 멤버 옆 **비밀번호 재설정 링크**(1일·1회용) |
| 관리자 비밀번호 분실 | 3번의 `hash-password` 다시 실행 → `sudo -u deploy docker compose -f /opt/haruhanpan/compose.yml restart` |
| 백업 | 매일 03:10 `/var/lib/haruhanpan/backup/haruhanpan_YYYY-MM-DD.json`, 30일 보관 (모든 공간, 가계부 포함) |
| 서버 밖 백업 | 화면의 **내보내기**로 공간별 JSON 을 가끔 내 PC 에 저장한다 |
| 복원 | 해당 공간을 고른 뒤 백업 JSON 을 화면의 **가져오기**로 올린다 (서버 백업 파일이면 안의 공간을 골라 줌) |

### 서버 보안
- SSH 는 키 전용(비밀번호 로그인 끔), `PermitRootLogin no`
- fail2ban: 10분에 5번 실패하면 1일 차단, 반복하면 1주. 상태 `sudo fail2ban-client status sshd`,
  내가 막혔으면 OCI 콘솔의 인스턴스 콘솔 연결로 들어가 `sudo fail2ban-client set sshd unbanip <IP>`

## Google 로그인 (선택)

두 환경변수(클라이언트 ID/비밀)가 없으면 Google 로그인은 꺼져 있고 버튼도 나오지 않는다.

1. [Google Cloud 콘솔](https://console.cloud.google.com/) → 프로젝트를 만든다 (예: haruhanpan).
2. **API 및 서비스 → OAuth 동의 화면**
   - 사용자 유형: 회사 Google Workspace 사람만 쓰면 **내부**, 개인 gmail 도 쓰면 **외부**
   - 앱 이름 `하루한판`, 지원 이메일, 승인된 도메인에 `<내 도메인>` 입력
   - 범위는 기본(`openid`, `email`, `profile`)만 쓴다 → Google 심사가 필요 없다
   - 외부 + 테스트 상태로 두면 테스트 사용자로 등록한 계정만 로그인할 수 있다. 팀원이 많으면 **앱 게시**를 누른다
3. **사용자 인증 정보 → 사용자 인증 정보 만들기 → OAuth 클라이언트 ID**
   - 애플리케이션 유형: **웹 애플리케이션**
   - 승인된 리디렉션 URI:
     - 운영 `https://<내 도메인>/login/oauth2/code/google`
     - 로컬에서 시험할 때 `http://localhost:8765/login/oauth2/code/google`
4. 나온 클라이언트 ID/비밀을 `/etc/haruhanpan.env` 에 넣고 `sudo systemctl restart haruhanpan`
   ```
   SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID=...apps.googleusercontent.com
   SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET=...
   ```
5. 확인
   - [ ] 로그인 화면에 "Google 계정으로 로그인" 버튼이 보인다
   - [ ] 관리자(`haru`)로 로그인 → 내 계정 → **Google 연결** → 이후 Google 버튼으로 로그인된다
   - [ ] 초대 링크를 열면 "Google 계정으로 가입하고 참여" 버튼이 보인다

| 누가 Google 로 들어올 수 있나 | 방법 |
|---|---|
| 기존 사용자 | 아이디로 로그인 → 내 계정 → Google 연결 (한 번만) |
| 새 팀원 | 초대 링크 → "Google 계정으로 가입하고 참여" (아이디는 이메일 앞부분으로 자동 생성) |
| 회사 Workspace 전원 | `HARU_GOOGLE_ALLOWED_DOMAIN=회사도메인` 설정 시 초대 없이 바로 가입 (승인 불필요) |
| 그 외 Google 계정 | **가입 신청 → 사이트 관리자 승인 후 사용** (기본값). 승인 전에는 "승인 대기 중" 화면만 보임 |

가입 신청 처리:
- 사이트 관리자(`haru`) 화면 상단에 주황색 **승인 대기 N** 버튼이 뜬다 → 누르면 신청자 이름·Google 이메일 목록
- **승인**: 팀 공간과 권한을 골라 바로 넣을 수 있다. **거절**: 계정이 삭제된다 (다시 신청은 가능)
- 승인 대기자가 팀 초대 링크를 열어 참여하면 자동으로 승인된다
- 30일 동안 처리하지 않은 신청은 매일 새벽 백업 때 자동으로 지운다
- 신청 자체를 막으려면 `HARU_GOOGLE_OPEN_SIGNUP=false` (초대 링크로만 가입)

로컬에서 시험하려면 `http://localhost:8765` 로 접속한다 (`127.0.0.1` 은 위 리디렉션 URI 와 주소가 달라 Google 이 거절한다).

```bash
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID=... SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET=... ./gradlew bootRun
```

## 주의: Always Free ADB 자동 정지

Always Free ADB 는 7일간 활동이 없으면 정지되고, 정지된 채로 90일이 지나면 **삭제**된다.

- 앱이 12시간마다 가벼운 쿼리를 보낸다 (`KeepAliveJob`).
- 이 쿼리가 OCI 에서 '활동'으로 인정되는지는 배포 후 1~2주 동안 콘솔에서 확인한다.
- DB 가 정지되면 화면 상단에 "DB 에 연결할 수 없습니다" 가 뜬다. 콘솔에서 ADB 를 시작하면 다시 동작한다.
