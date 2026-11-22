# 정식 TLS 인증서 설정 —

서버 wss://7443 을 외부망에 노출할 때 인증서 발급 + JKS 변환 + docker-compose 적용 절차.

## 옵션 A. Let's Encrypt (무료, 추천)

도메인이 있고 인터넷에 노출된 서버라면 가장 빠른 길.

### 1. certbot 으로 인증서 발급 (포트 80 임시 사용)

```bash
sudo certbot certonly --standalone -d osori.example.com
# 발급 결과 위치:
#   /etc/letsencrypt/live/osori.example.com/fullchain.pem
#   /etc/letsencrypt/live/osori.example.com/privkey.pem
```

### 2. PEM → PKCS12 → JKS 변환

오소리 서버는 JKS keystore 만 받는다. 변환 한 줄:

```bash
# PEM 두 개 → p12 합성
sudo openssl pkcs12 -export \
  -in  /etc/letsencrypt/live/osori.example.com/fullchain.pem \
  -inkey /etc/letsencrypt/live/osori.example.com/privkey.pem \
  -name osori \
  -out  /tmp/osori.p12 \
  -password pass:CHANGE_ME_STRONG_PW

# p12 → JKS
keytool -importkeystore \
  -srckeystore /tmp/osori.p12 -srcstoretype PKCS12 \
  -srcstorepass CHANGE_ME_STRONG_PW \
  -destkeystore /path/to/repo/docker/certs/keystore.jks \
  -deststoretype JKS \
  -deststorepass CHANGE_ME_STRONG_PW \
  -destkeypass CHANGE_ME_STRONG_PW

rm /tmp/osori.p12  # p12 는 더 이상 필요 없음
chmod 600 /path/to/repo/docker/certs/keystore.jks
```

### 3. `.env` 업데이트 + 재기동

```env
OSORI_TLS_ENABLED=true
OSORI_KEYSTORE_PASSWORD=CHANGE_ME_STRONG_PW
OSORI_KEY_ALIAS=osori
OSORI_KEY_PASSWORD=CHANGE_ME_STRONG_PW
```

```bash
docker compose -f docker/docker-compose.prod.yml --env-file docker/.env up -d
```

확인:
```bash
openssl s_client -connect osori.example.com:7443 -showcerts < /dev/null | head -20
```

### 4. 자동 갱신 (90일마다)

Let's Encrypt 는 90일 만료. cron 또는 systemd timer 로:

```bash
# /etc/cron.d/osori-cert-renew (매주 일요일 03:00)
0 3 * * 0 root certbot renew --quiet \
  --post-hook "bash /path/to/repo/docker/scripts/refresh-keystore.sh && docker restart osori-server"
```

`refresh-keystore.sh` 는 위 2번 단계를 자동화한 스크립트로 별도 작성.

---

## 옵션 B. 사내 CA / 자체 서명 인증서

기업 내부망 전용이면:

```bash
# 자체 서명 keystore 생성 — 도메인이 server.internal 라고 가정
keytool -genkeypair \
  -alias osori \
  -keyalg RSA \
  -keysize 2048 \
  -validity 1095 \
  -keystore docker/certs/keystore.jks \
  -storepass CHANGE_ME_STRONG_PW \
  -keypass CHANGE_ME_STRONG_PW \
  -dname "CN=server.internal, OU=IT, O=Osori, L=Seoul, ST=Seoul, C=KR" \
  -ext "SAN=DNS:server.internal,IP:10.0.0.5"
```

클라이언트 측: 자체 서명이라 사용자 PC 신뢰 저장소에 해당 cert 등록 필요. 또는 클라 코드의 `acceptSelfSignedTls=true` 옵션 활성 (운영에선 비권장).

---

## 옵션 C. 리버스 프록시 (nginx/Caddy) 앞단

WebSocket 통과시키는 nginx/Caddy 를 앞에 두고 TLS termination 거기서 처리. 오소리 서버는 평문 ws 그대로.

장점: 인증서 갱신/관리가 표준 nginx 워크플로우.
단점: 컴포넌트 하나 늘어남.

nginx 설정 스니펫:

```nginx
location /ws {
  proxy_pass http://osori-server:7070;
  proxy_http_version 1.1;
  proxy_set_header Upgrade $http_upgrade;
  proxy_set_header Connection "upgrade";
  proxy_set_header Host $host;
  proxy_read_timeout 86400s;
}
```

---

## 트러블슈팅

| 증상 | 원인 | 해결 |
|---|---|---|
| `Server failed to start` / `keystore wrong password` | `OSORI_KEYSTORE_PASSWORD` 와 keystore 실제 password 불일치 | `keytool -list -keystore keystore.jks` 로 비번 검증 |
| `SSLHandshakeException` 클라 쪽 | 자체 서명인데 신뢰 저장소 미등록 | OS 신뢰 루트에 cert 임포트 또는 `acceptSelfSignedTls=true` |
| Let's Encrypt `renew` 실패 | DNS A 레코드 변경 / 포트 80 닫힘 | `certbot renew --dry-run` 으로 사전 검증 |
