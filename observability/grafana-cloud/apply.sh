#!/usr/bin/env bash
# Grafana Cloud(또는 아무 Grafana)에 CartMate 폴더 · 대시보드 · 알림 2개를 넣는다. 다시 실행해도 같은 결과(덮어씀)
#
#   GRAFANA_URL=https://<스택>.grafana.net \
#   GRAFANA_TOKEN=<Editor 권한 서비스 계정 토큰> \
#   LOKI_UID=<Cloud Loki 데이터 소스 uid, 예: grafanacloud-logs> \
#   bash observability/grafana-cloud/apply.sh
#
# 필요한 것: bash, curl, sed (Git Bash 가능). 토큰은 출력하지 않는다
set -euo pipefail

: "${GRAFANA_URL:?GRAFANA_URL이 필요합니다}"
: "${GRAFANA_TOKEN:?GRAFANA_TOKEN이 필요합니다}"
: "${LOKI_UID:?LOKI_UID가 필요합니다 (Cloud Loki 데이터 소스 uid)}"

DIR="$(cd "$(dirname "$0")" && pwd)"
DASHBOARD="$DIR/../grafana/dashboards/cartmate-overview.json"
FOLDER_UID="cartmate"
AUTH=(-H "Authorization: Bearer ${GRAFANA_TOKEN}")
JSON=(-H "Content-Type: application/json")

call() { # method path [body-file]  → 응답 코드만 반환, 본문은 /tmp
  local method="$1" path="$2" body="${3:-}"
  if [ -n "$body" ]; then
    curl -s -o "$TMP/resp.json" -w "%{http_code}" -X "$method" "${AUTH[@]}" "${JSON[@]}" \
      -H "X-Disable-Provenance: true" --data-binary "@$body" "${GRAFANA_URL%/}$path"
  else
    curl -s -o "$TMP/resp.json" -w "%{http_code}" -X "$method" "${AUTH[@]}" "${GRAFANA_URL%/}$path"
  fi
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# 1. 폴더
code=$(call GET "/api/folders/$FOLDER_UID")
if [ "$code" != "200" ]; then
  printf '{"uid":"%s","title":"CartMate"}' "$FOLDER_UID" > "$TMP/folder.json"
  code=$(call POST "/api/folders" "$TMP/folder.json")
  [ "$code" = "200" ] || { echo "폴더 만들기 실패 ($code): $(cat "$TMP/resp.json")"; exit 1; }
fi
echo "폴더 CartMate: OK"

# 2. 대시보드 (데이터 소스는 대시보드 변수 loki_ds가 Cloud Loki를 자동으로 고른다)
{ printf '{"folderUid":"%s","overwrite":true,"dashboard":' "$FOLDER_UID"; cat "$DASHBOARD"; printf '}'; } > "$TMP/dash.json"
code=$(call POST "/api/dashboards/db" "$TMP/dash.json")
[ "$code" = "200" ] || { echo "대시보드 실패 ($code): $(cat "$TMP/resp.json")"; exit 1; }
echo "대시보드 CartMate 서비스 개요: OK"

# 3. 알림 규칙 (있으면 덮어쓰기)
for f in "$DIR"/alerts/*.json; do
  sed -e "s/__LOKI_UID__/${LOKI_UID}/g" -e "s/__FOLDER_UID__/${FOLDER_UID}/g" "$f" > "$TMP/rule.json"
  uid=$(sed -n 's/.*"uid": *"\([^"]*\)".*/\1/p' "$TMP/rule.json" | head -1)
  code=$(call GET "/api/v1/provisioning/alert-rules/$uid")
  if [ "$code" = "200" ]; then
    code=$(call PUT "/api/v1/provisioning/alert-rules/$uid" "$TMP/rule.json")
  else
    code=$(call POST "/api/v1/provisioning/alert-rules" "$TMP/rule.json")
  fi
  case "$code" in 200|201) echo "알림 $uid: OK" ;; *) echo "알림 $uid 실패 ($code): $(cat "$TMP/resp.json")"; exit 1 ;; esac
done

# 알림 평가 주기는 새 그룹 기본값 1분을 쓴다 (그룹 PUT API는 규칙 목록까지 통째로 바꿔서 쓰지 않는다)
echo "완료. 알림 받는 곳(Slack 등)은 Grafana에서 연락처를 만들어 알림 정책에 연결하세요 (README)"
