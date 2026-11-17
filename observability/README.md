# CartMate 관측 스택 (로컬)

Alloy(수집) → **Loki**(로그 7일) · **Tempo**(trace 3일) → **Grafana**(화면 · 알림)

```
앱 ──OTLP(trace)──▶ Alloy :4318 ──▶ Tempo
앱 ──JSON 로그 파일──▶ observability/logs/*.log ──▶ Alloy ──▶ Loki
                                                     Grafana :3300 (Loki ↔ Tempo 연결, 알림 3개, 대시보드 1개)
```

메트릭(Prometheus)은 아직 없다. 알림은 업무 이벤트 로그(`event=...`) 기반이다.

## 1. 띄우기

```bash
docker compose -f observability/docker-compose.yaml up -d
```

- Grafana: http://localhost:3300 (로컬 기본 계정 admin / admin). 3000 · 3001은 프론트 개발 서버 포트라 피함, 바꾸려면 `GRAFANA_PORT=<포트>`
- Alloy 상태: http://localhost:12345
- 끄기: `docker compose -f observability/docker-compose.yaml down` (데이터까지 지우려면 `down -v`)
- 메모리: Loki 512M · Tempo 512M · Alloy 256M · Grafana 512M 제한 (합계 약 1.8GB)
- Docker Desktop 메모리가 작으면(기본 4GB 안팎) 다른 컨테이너 · Kubernetes와 함께 돌 때 VM이 멈출 수 있다 → 쓰지 않는 컨테이너를 멈추거나 Docker 메모리를 6GB 이상으로

## 2. 앱 연결 (IntelliJ 실행 설정 → 환경 변수)

서비스마다 아래 3개를 넣는다. 로그 파일 이름이 Loki의 `service` 라벨이 된다.

| 환경 변수 | 값 (예: 회원 서비스) |
|---|---|
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `http://localhost:4318/v1/traces` |
| `LOGGING_FILE_NAME` | `C:/team_project/beadv8_7_CartMate_BE/observability/logs/member-service.log` (절대 경로 권장) |
| `LOGGING_STRUCTURED_FORMAT_FILE` | `logstash` |

- 콘솔 출력은 지금 그대로 사람이 읽는 형식, **파일만 JSON**(운영 stdout과 같은 logstash 형식)
- 파일 이름은 `spring.application.name`과 같게: `member-service.log`, `order-service.log`, `payment-service.log`, `product-service.log`, `etc-service.log`
- `application-dev.yaml`(커밋 안 함)에 넣어도 된다:
  ```yaml
  logging:
    file:
      name: C:/team_project/beadv8_7_CartMate_BE/observability/logs/member-service.log
    structured:
      format:
        file: logstash
  ```
- 주소를 안 넣으면 trace를 내보내지 않는다 (앱은 그대로 뜸, traceId는 로그에만)

## 3. 보는 법

**Grafana → 대시보드 → CartMate → CartMate 서비스 개요**

- 결제 보상 실패 · 이벤트 전달 포기 건수, 서비스별 ERROR, 업무 이벤트 수, 실패 이벤트 · 결제 · 인증 로그

**Explore → Loki** 예시 (라벨은 `service` · `env` · `level`만, 나머지는 `| json`으로)

```logql
{service="payment-service"} | json | event="payment.compensate"
{service=~".+"} | json | event=~"outbox.failed|inbox.failed|kafka.dlt"
{service=~".+"} | json | orderId="123"
{service=~".+"} |= "0af7651916cd43dd8448eb211c80319c"     # traceId로 모든 서비스 로그
```

- 로그 줄을 펼쳐 **traceId 옆 "Tempo에서 보기"** → 서비스 간 전체 흐름(HTTP · Kafka · Outbox · Inbox)
- Tempo에서 span을 눌러 **"Logs for this span"** → 같은 traceId 로그

## 4. 알림 (Grafana → 알림 → 경보 규칙 → CartMate)

| 경보 | 조건 (5분 안에) | 심각도 |
|---|---|---|
| 결제 보상(되돌리기) 실패 | `event=~"payment.compensate(.request)?"`, `result="FAILED"` 1건 이상 | critical |
| 이벤트 전달 포기 | `event=~"outbox.failed\|inbox.failed\|kafka.dlt"` 1건 이상 | critical |
| 서비스 ERROR 급증 | 서비스별 `level="ERROR"` 로그 **10건 이상** (서비스마다 따로 울림) | warning |

로컬은 Grafana 화면에서만 확인한다. 운영(Grafana Cloud)은 **Discord** 연락처 `discord-cartmate` + 알림 정책(`grafana_folder=CartMate` → Discord)으로 받는다 (웹훅 주소는 파일에 넣지 말 것).
새 오류 이슈는 Sentry가 이메일 + Discord(같은 채널)로 보낸다. 단 Sentry의 Discord 연동은 유료(Team) 플랜 전용이라 **체험 기간(2026-11-30 무렵)까지만** 오고, 그 뒤에는 이메일만 온다 (Discord 알림이 끊겨도 안내가 없다).

## 5. 수집량 절약 (무료 한도 · 부하 테스트)

앱은 trace를 100% 만들어 보내고(`TRACING_SAMPLING=1.0`), **Alloy가 trace를 다 모아 본 뒤 남길 것을 고른다**(tail sampling).
오류 trace와 느린 trace는 항상 100% 남는다. 로그는 Alloy에서 거르고 상한을 건다.

| Alloy 환경 변수 | 평시 (기본값) | 부하 테스트 |
|---|---|---|
| `OBS_TRACE_SAMPLE_PERCENT` 정상 trace 보관 비율(%) | `10` | `1` |
| `OBS_SLOW_TRACE_MS` 이 시간 이상이면 항상 보관 | `1000` | `3000` (포화 때 전부 "느림"이 되지 않게) |
| `OBS_DROP_PLAIN_LOG_LEVELS` 업무 이벤트 없는 줄을 버릴 레벨 | `DEBUG\|TRACE` | `INFO\|DEBUG\|TRACE` |
| `OBS_LOG_RATE_LIMIT` 서비스별 초당 로그 상한 | `200` | `50` |

부하 테스트 모드로 바꾸기 (Alloy만 다시 뜸, 앱 재시작 없음):

```bash
OBS_TRACE_SAMPLE_PERCENT=1 OBS_SLOW_TRACE_MS=3000 OBS_DROP_PLAIN_LOG_LEVELS="INFO|DEBUG|TRACE" OBS_LOG_RATE_LIMIT=50 \
  docker compose -f observability/docker-compose.yaml up -d --force-recreate alloy
```

앱 쪽 (서비스 공통 `application.yaml`): 보안 필터 span 끔(`management.observations.enable[spring.security]=false`, 요청당 span 5 → 1), JSON 로그 stack trace 2KB 상한

**측정 결과** (2026-11-16 로컬, 회원 서비스, k6 초당 50요청 × 60초, 요청 약 3,000건, 운영과 같은 로그 조건)

| | 요청 1,000건당 trace | 요청 1,000건당 로그 | 초당 200요청 하루 환산 (trace + 로그) |
|---|---|---|---|
| A 기존 (보안 span 켬, 전부 보관) | 3.5 MB | 35 KB | 약 61 GB/일 |
| B 평시 모드 | 48 KB | 35 KB | 약 1.4 GB/일 |
| C 부하 테스트 모드 | 3.9 KB | 0 KB (업무 이벤트 · WARN · ERROR만 남음) | 약 0.07 GB/일 + 이벤트 로그 |

- 확인: 부하 테스트 모드에서도 **오류 trace 5/5 보관**(정상 trace 0/5), 업무 이벤트 있는 INFO · WARN은 통과, 이벤트 없는 INFO만 버림
- 요청 구성: 이메일 중복 확인 70% · 로그인 실패 20% · 휴대폰 인증 발송 10%. 주문 · 결제처럼 서비스 간 호출 · Kafka · 업무 이벤트가 많은 흐름은 위 값보다 몇 배 클 수 있다
- 로컬(DevTools)은 4xx마다 `Resolved [...]` WARN을 남겨 로그가 3~4배로 보인다. 운영 jar에는 없다

## 6. 운영 (k3s)

운영은 이 compose 스택을 쓰지 않는다. **Alloy 1개 + Grafana Cloud 무료 + Kafka UI** → `observability/k8s/README.md`

- 같은 대시보드 파일을 Cloud에도 넣는다 (`observability/grafana-cloud/apply.sh`, 데이터 소스는 대시보드 변수로 자동 선택)
- Claude Code용 Grafana MCP는 **Viewer 권한 서비스 계정 토큰**만 쓴다

## 문제 해결

- 로그가 안 보임: `observability/logs/`에 `.log` 파일이 생기는지 → Alloy 화면(:12345)에서 `loki.source.file` 상태 확인 → 시간 범위
- trace가 안 보임: 앱에 `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`가 들어갔는지, 몇 초 기다렸는지 (5초마다 묶어서 보냄)
- 1초 폴러(Outbox · Inbox)는 처리할 행이 없으면 trace를 만들지 않는다 (정상)
