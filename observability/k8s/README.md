# CartMate 운영 관측 (k3s) — Alloy 1개 + Grafana Cloud 무료

```
[k3s 클러스터 · monitoring 네임스페이스]
  앱 Pod 로그(stdout JSON) ──(쿠버네티스 API)──▶ Alloy ──▶ Grafana Cloud Loki      로그
  앱 trace(OTLP :4318) ─────────────────────────▶ Alloy ──▶ Grafana Cloud Tempo     trace (오류 · 느린 trace 100%)
  kubelet · kube-state-metrics · kafka-exporter · MySQL 6 · Redis 2 · ES ─▶ Alloy ──▶ Grafana Cloud Prometheus
  Kafka UI (로그인 + 읽기 전용, port-forward로만)
[외부] Grafana Cloud(화면 · 알림) · Sentry(예외)
```

로컬 개발 · 시연용 스택은 `observability/README.md` (docker compose). 로그 처리 규칙(라벨 · 필터)은 두 곳이 같다.

## 1. 서버 계획 대비 메모리 (limit 기준)

| 서버 | 빠지는 것 | 들어오는 것 | 변화 |
|---|---|---|---|
| 서버1 | Prometheus 384 · Grafana 128 · node-exporter 32 · mysqld-exporter 32 (= 576Mi) | Alloy 256 · Kafka UI 384 · kafka-exporter 32 (= 672Mi) | +96Mi (남는 메모리 0.27 → 약 0.18Gi) |
| 서버2 | node-exporter 32 · kafka/redis/es exporter 96 (= 128Mi) | — (노드 지표는 kubelet에서 가져옴) | −128Mi (0.17 → 약 0.30Gi) |
| 유지 | kube-state-metrics 64 | | |
| **모니터링 합계** | 0.76Gi (메트릭만) | **약 0.72Gi** (메트릭 + 로그 + trace + Kafka UI) | 약간 줄어듦 |

- 실측 (로컬 쿠버네티스): Alloy 약 170MiB / 256Mi, Kafka UI 약 240~330MiB / 384Mi
- 서버1이 빠듯하면 kafka-exporter(32Mi)를 서버2로 옮긴다 (nodeSelector)

## 2. 준비 (한 번)

### Grafana Cloud (팀 공용 계정)

1. https://grafana.com 에서 **팀 계정**으로 무료 스택을 만든다 (가까운 아시아 리전)
2. 포털 → 스택 → 각 항목의 **Details**에서 주소와 User(숫자)를 적어 둔다

   | 항목 | Secret 키 | 값 |
   |---|---|---|
   | Loki | `GC_LOKI_URL` / `GC_LOKI_USER` | `https://logs-prod-…grafana.net/loki/api/v1/push` / 숫자 |
   | Prometheus | `GC_PROM_URL` / `GC_PROM_USER` | `https://prometheus-prod-…grafana.net/api/prom/push` / 숫자 |
   | OpenTelemetry | `GC_OTLP_URL` / `GC_OTLP_USER` | `https://otlp-gateway-prod-….grafana.net/otlp` / Instance ID |

3. **Access Policies**에서 정책을 만든다 (scope: `logs:write`, `metrics:write`, `traces:write`) → 토큰 발급 → `GC_TOKEN`
4. Grafana(스택) → Administration → Service accounts
   - `cartmate-setup` (**Admin**): 아래 4절 스크립트용. Cloud에서는 Editor로 알림 규칙 API가 403이 난다. 실행 후 토큰은 지운다
   - `cartmate-mcp` (**Viewer**): Claude Code용 Grafana MCP

### Sentry

팀 프로젝트(Spring Boot)를 만들고 DSN을 받는다 → 앱 환경 변수 `SENTRY_DSN`

### MySQL 6개에 지표용 계정 (각 DB)

```sql
CREATE USER 'exporter'@'%' IDENTIFIED BY '<비밀번호>' WITH MAX_USER_CONNECTIONS 2;
GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO 'exporter'@'%';
```

### kube-state-metrics

서버 계획대로 따로 설치해 둔다 (기본 주소 `kube-state-metrics.monitoring.svc:8080`, 다르면 `KSM_ADDR`)

## 3. 배포

```bash
# Secret 3개 (값은 파일 · 저장소에 남기지 않는다)
kubectl create namespace monitoring
kubectl -n monitoring create secret generic alloy-grafana-cloud \
  --from-literal=GC_TOKEN=... \
  --from-literal=GC_LOKI_URL=... --from-literal=GC_LOKI_USER=... \
  --from-literal=GC_PROM_URL=... --from-literal=GC_PROM_USER=... \
  --from-literal=GC_OTLP_URL=... --from-literal=GC_OTLP_USER=...
kubectl -n monitoring create secret generic alloy-targets \
  --from-literal=MYSQL_PRODUCT_DSN='exporter:<비번>@(mysql-product.cartmate.svc:3306)/' \
  --from-literal=MYSQL_ORDER_DSN=... --from-literal=MYSQL_MEMBER_DSN=... --from-literal=MYSQL_PAYMENT_DSN=... \
  --from-literal=MYSQL_ETC_DSN=... --from-literal=MYSQL_AI_DSN=... \
  --from-literal=REDIS_CACHE_ADDR=redis-cache.cartmate.svc:6379 --from-literal=REDIS_CORE_ADDR=redis-core.cartmate.svc:6379 \
  --from-literal=REDIS_PASSWORD=... --from-literal=ES_URL='http://elastic:<비번>@elasticsearch.cartmate.svc:9200'
kubectl -n monitoring create secret generic kafka-ui-auth \
  --from-literal=KAFKA_BOOTSTRAP=cartmate-kafka-bootstrap.cartmate.svc:9092 \
  --from-literal=USERNAME=admin --from-literal=PASSWORD=...

# (서버1에 고정하려면) alloy.yaml · kafka-ui.yaml의 nodeSelector 주석 해제
#   서버1(k3s server 노드)에는 앱 배포 때 cartmate/node=server 라벨을 붙인다 (k8s/infra/kustomization.yaml 참고)
#   붙어 있는지 확인: kubectl get nodes -L cartmate/node

kubectl apply -k observability/k8s
kubectl -n monitoring get pods          # alloy · kafka-ui · kafka-exporter Running
```

- 주소(서비스 이름 · 네임스페이스)는 실제 배포에 맞게 바꾼다. 앱 네임스페이스가 `cartmate`가 아니면 `alloy.yaml`의 `APP_NAMESPACES`
- Elasticsearch는 비밀번호 인증이 켜져 있어서(`k8s/infra/elasticsearch.yaml`) `ES_URL`에 계정을 넣는다. 지표 조회만 하므로 `elastic` 대신 `monitor` 권한만 있는 계정을 만들어 쓰는 게 안전하다
- ai-service는 아직 배포하지 않아 `MYSQL_AI_DSN`은 접속에 실패한다(ai MySQL 지표만 비고 나머지 수집에는 영향 없음)
- Alloy 설정만 바꿀 땐 `observability/k8s/alloy/config.alloy` 수정 후 `kubectl apply -k observability/k8s` (ConfigMap 이름이 바뀌어 자동 재시작)
- Kafka가 죽어 있어도 Alloy는 뜬다 (Kafka 지표만 kafka-exporter가 따로 실패). MySQL · Redis · ES는 조회할 때만 접속

## 4. 앱 배포 설정 (kim san님 매니페스트)

| 항목 | 값 |
|---|---|
| Pod 라벨 | `app: member-service` 같이 **서비스 이름** (Loki `service` 라벨이 됨, `-service`로 끝나야 수집) |
| `SPRING_PROFILES_ACTIVE` | `prod` (로그가 JSON으로 나옴) |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://alloy.monitoring.svc:4318/v1/traces` |
| `TRACING_SAMPLING` | `1.0` (그대로. 남길 trace는 Alloy가 고름) |
| `SENTRY_DSN` · `SENTRY_ENVIRONMENT` | Sentry DSN · `prod` |

## 5. Grafana Cloud 화면 설정

```bash
GRAFANA_URL=https://<스택>.grafana.net GRAFANA_TOKEN=<cartmate-setup 토큰> LOKI_UID=<Loki 데이터 소스 uid> \
  bash observability/grafana-cloud/apply.sh
```

- 폴더 CartMate · 대시보드 "CartMate 서비스 개요" · 알림 3개를 넣는다 (다시 실행해도 덮어쓰기)
- Loki 데이터 소스 uid는 보통 `grafanacloud-logs` (Connections → Data sources → `grafanacloud-…-logs` → 주소창 `/datasources/edit/<uid>`)
- 로그의 traceId → Tempo 링크는 Cloud 기본 데이터 소스에 이미 있다 (Cloud 관리 데이터 소스라 수정 불가 · 불필요)

알림 받는 곳 (화면에서 한 번만):

1. 팀 Discord 알림 채널 → 채널 편집 → 연동 → 웹후크 → 새 웹후크 → URL 복사 (비밀 값, 파일 · 채팅에 남기지 않음)
2. Alerting → Contact points → `discord-cartmate`, Integration **Discord**, Webhook URL 붙여 넣기
   - Optional Discord settings → **Title** `{{ template "cartmate.discord.title" . }}`, **Message Content** `{{ template "cartmate.discord.message" . }}` (한국어 서식, `grafana-cloud/templates/cartmate-discord.tmpl`을 `apply.sh`가 올림)
   - Test → Save
3. Alerting → Notification policies → 하위 정책: `grafana_folder = CartMate` → `discord-cartmate`
- Slack은 교육과정 공용 워크스페이스의 앱 개수 한도 때문에 못 씀
- Sentry → Discord: Sentry Settings → Integrations → Discord → Add Installation(팀 서버 승인) → 알림 규칙(Alerts)에 Discord 액션 + 채널 ID. **유료(Team) 플랜 전용이라 체험 기간(2026-11-30 무렵)까지만 동작**, 그 뒤엔 이메일만

## 6. 평시 / 부하 테스트 모드

| Alloy 환경 변수 | 평시 | 부하 테스트 |
|---|---|---|
| `OBS_TRACE_SAMPLE_PERCENT` | `10` | `1` |
| `OBS_SLOW_TRACE_MS` | `1000` | `3000` |
| `OBS_DROP_PLAIN_LOG_LEVELS` | `DEBUG\|TRACE` | `INFO\|DEBUG\|TRACE` |
| `OBS_LOG_RATE_LIMIT` | `200` | `50` |

```bash
# 부하 테스트 시작 전 (Alloy만 다시 뜸, 앱 재시작 없음)
kubectl -n monitoring set env deploy/alloy OBS_TRACE_SAMPLE_PERCENT=1 OBS_SLOW_TRACE_MS=3000 OBS_DROP_PLAIN_LOG_LEVELS='INFO|DEBUG|TRACE' OBS_LOG_RATE_LIMIT=50
# 끝나면 평시로
kubectl -n monitoring set env deploy/alloy OBS_TRACE_SAMPLE_PERCENT=10 OBS_SLOW_TRACE_MS=1000 OBS_DROP_PLAIN_LOG_LEVELS='DEBUG|TRACE' OBS_LOG_RATE_LIMIT=200
```

무료 한도(로그 · trace 각 50GB/월, 메트릭 1만 시계열)와 실측치는 `observability/README.md` 5절. 사용량은 Grafana Cloud → Usage에서 확인. 넘어도 과금은 없고 수집만 막힌다.

## 7. Kafka UI

```bash
kubectl -n monitoring port-forward svc/kafka-ui 8080:8080   # → http://localhost:8080 (kafka-ui-auth 계정)
```

- 외부 노출 없음 (ClusterIP). Ingress · NodePort를 만들지 않는다
- 읽기 전용: 토픽 · 메시지 · 컨슈머 그룹 보기만

**DLT 메시지 다시 보내기** (알림 "이벤트 전달 포기" → 원인 해결 후)

1. Loki에서 `event="kafka.dlt"` 로그의 `topic` · `offset` 확인 → Kafka UI에서 `<topic>-dlt` 메시지 확인
2. 잠깐 쓰기 허용: `kubectl -n monitoring set env deploy/kafka-ui KAFKA_CLUSTERS_0_READONLY=false`
3. Kafka UI에서 그 메시지를 원래 토픽으로 다시 보냄 (Inbox · 처리 쪽이 멱등이라 중복은 걸러짐)
4. **바로 되돌리기**: `kubectl -n monitoring set env deploy/kafka-ui KAFKA_CLUSTERS_0_READONLY=true`

## 8. 확인

```bash
kubectl -n monitoring port-forward deploy/alloy 12345:12345   # → http://localhost:12345 컴포넌트가 모두 healthy
```

- Grafana Cloud → Explore → Loki `{env="prod"}` 로그, Tempo trace, Prometheus `up`
- 로컬 쿠버네티스(docker-desktop)에서 검증함 (2026-11-16): 컴포넌트 28개 healthy, Pod 로그 → Loki(라벨 service · env · level), 클러스터 안 오류 span → Tempo 보관, kubelet 지표 수집, Kafka · MySQL 없이도 Alloy 기동
