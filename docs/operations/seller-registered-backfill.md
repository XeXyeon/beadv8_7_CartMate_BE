# 판매자 등록 이벤트 백필 (배포 후 한 번)

## 왜 필요한가

서비스를 나눈 뒤에도 판매자 등록 이벤트(`SellerRegisteredEvent`)는 회원 서비스 안의 Spring 이벤트로만 나갔다.
그래서 그 사이 등록된 판매자는 아래 복제본에 빠져 있다.

| 서비스 | 테이블 | 빠지면 |
|---|---|---|
| 결제 | `payout_seller` | 정산 지급이 `PAYOUT_SELLER_NOT_FOUND`로 실패, 판매자 정산 화면 `PAYOUT_SELLER_ONLY` |
| 상품 | `POST_SELLER` | 판매글의 판매자 표시 정보 없음 |

이번 변경으로 새 등록은 Outbox → Kafka로 간다. **이미 등록된 판매자**는 아래 API로 한 번 다시 보낸다.

## 실행 전 확인

1. 회원 · 결제 · 상품 서비스가 이번 버전으로 배포됨 (결제 · 상품에 `cartmate.member.seller-registered` 수신 바인딩이 있어야 함)
2. 세 서비스 모두 `cartmate.kafka.enabled=true`
3. 토픽 `cartmate.member.seller-registered`가 있음 (자동 생성이 꺼져 있으면 먼저 만든다)
4. 상품 DB에 `ALTER TABLE POST_SELLER MODIFY phone_number VARCHAR(20) NULL` 적용됨 (전화번호를 더 이상 복제하지 않음)

## 실행

회원 서비스 안(또는 내부망)에서, 내부 API 키를 붙여 호출한다. Gateway는 `/internal/**`을 막으므로 회원 서비스에 직접 보낸다.

```bash
curl -X POST "http://<member-service>:8981/internal/members/sellers/republish-registered" \
  -H "X-Internal-Api-Key: $INTERNAL_API_KEY"
```

응답 `data`는 Outbox에 다시 넣은 판매자 수다. 500명씩 트랜잭션을 나눠 저장한다.

## 실행 후 확인

```sql
-- 회원 DB
SELECT COUNT(*) FROM seller;
-- 결제 DB: 위와 같아야 한다
SELECT COUNT(*) FROM payout_seller;
-- 상품 DB: 위와 같아야 한다
SELECT COUNT(*) FROM POST_SELLER;
```

- 숫자가 모자라면 각 서비스 `inbox_event`에서 `event_type = 'SellerRegisteredEvent'`이고 `FAILED`인 행의 오류를 본다.
- 받는 쪽이 멱등(이미 있으면 갱신 · 건너뜀)이라 **여러 번 실행해도 안전**하다. 중간에 실패하면 그냥 다시 실행한다.
