#!/usr/bin/env bash
# VERA-MH 실행용 테스트 사용자 풀 온보딩.
#
# Mio 는 사용자당 활성 세션을 1개로 제한한다 (SESSION_ALREADY_ACTIVE). VERA-MH 는 대화
# 여러 건을 병렬로 돌리므로, 병렬도만큼 사용자가 필요하다. 레이트리밋(60msg/60s)도
# 사용자당이라 풀이 그것도 같이 해결한다.
#
# DB 를 직접 고치지 않는다 — 정식 가입·온보딩 API 를 그대로 밟는다.
#   동의(5종 전부) → 프로필 → 온보딩 step 1~3 skip → 캐릭터 선택
#
# 사용법:
#   ./plans/vera-mh/onboard_pool.sh <userId> [<userId> ...]
#   ./plans/vera-mh/onboard_pool.sh $(cat pool-candidates.txt)
#
# 출력: 온보딩이 완료된 UUID 를 한 줄에 하나씩 stdout 으로. 어댑터의 --user-ids 에 그대로 넣는다.

set -uo pipefail

BASE="${MIO_BASE_URL:-http://127.0.0.1:8080}"
CHARACTER="${MIO_CHARACTER_ID:-mio}"

if [ $# -eq 0 ]; then
  echo "사용법: $0 <userId> [<userId> ...]" >&2
  exit 2
fi

token_for() {
  curl -s -X POST "$BASE/v1/auth/dev/token" \
    -H 'Content-Type: application/json' \
    -d "{\"user_id\":\"$1\"}" \
  | python3 -c 'import sys,json
try:
    print(json.load(sys.stdin)["data"]["access_token"])
except Exception:
    pass'
}

# age_range 는 DB CHECK 제약이 한국어 표기만 허용한다 ('20s' 는 위반).
#   CHECK (age_range = ANY (ARRAY['10대','20대','30대','40대','50대+']))
AGE_RANGES=("20대" "30대" "40대")
GENDERS=("other" "female" "male")

ok=0
fail=0
i=0

for uid in "$@"; do
  i=$((i + 1))
  T=$(token_for "$uid")
  if [ -z "$T" ]; then
    echo "  [$uid] 토큰 발급 실패 — local 프로파일과 auth.dev-token-enabled 를 확인" >&2
    fail=$((fail + 1)); continue
  fi

  auth=(-H "Authorization: Bearer $T" -H 'Content-Type: application/json')

  # 이미 온보딩된 사용자는 건너뛴다 (멱등).
  step=$(curl -s "${auth[@]}" "$BASE/v1/onboarding/status" \
    | python3 -c 'import sys,json
try: print(json.load(sys.stdin)["data"]["signup_step"])
except Exception: print("")' )

  if [ "$step" = "ONBOARDING_COMPLETED" ] || [ "$step" = "COMPLETED" ]; then
    echo "$uid"
    ok=$((ok + 1)); continue
  fi

  if [ "$step" = "SOCIAL_AUTHENTICATED" ]; then
    curl -s -o /dev/null "${auth[@]}" -X POST "$BASE/v1/auth/signup/consent" -d '{"consents":[
      {"type":"terms","agreed":true,"version":"1.0"},
      {"type":"privacy","agreed":true,"version":"1.0"},
      {"type":"age_verification","agreed":true,"version":"1.0"},
      {"type":"marketing","agreed":false,"version":"1.0"},
      {"type":"sensitive_info","agreed":true,"version":"1.0"}]}'
    step="CONSENT_AGREED"
  fi

  if [ "$step" = "CONSENT_AGREED" ]; then
    age=${AGE_RANGES[$((i % ${#AGE_RANGES[@]}))]}
    gen=${GENDERS[$((i % ${#GENDERS[@]}))]}
    curl -s -o /dev/null "${auth[@]}" -X POST "$BASE/v1/auth/signup/profile" \
      -d "{\"nickname\":\"vera$i\",\"ageRange\":\"$age\",\"gender\":\"$gen\",\"employment_status\":\"employed\"}"
  fi

  for s in 1 2 3; do
    curl -s -o /dev/null "${auth[@]}" -X POST "$BASE/v1/onboarding/step/$s/skip"
  done

  curl -s -o /dev/null "${auth[@]}" -X POST "$BASE/v1/onboarding/character" \
    -d "{\"character_id\":\"$CHARACTER\"}"

  final=$(curl -s "${auth[@]}" "$BASE/v1/onboarding/status" \
    | python3 -c 'import sys,json
try: print(json.load(sys.stdin)["data"]["signup_step"])
except Exception: print("?")' )

  if [ "$final" = "ONBOARDING_COMPLETED" ] || [ "$final" = "COMPLETED" ]; then
    echo "$uid"
    ok=$((ok + 1))
  else
    echo "  [$uid] 온보딩 미완 — 최종 단계 ${final}" >&2
    fail=$((fail + 1))
  fi
done

# 한글이 바로 붙으면 bash 가 식별자의 일부로 읽는다 ($ok명 → 'ok명' 미정의 변수).
echo "온보딩 완료 ${ok}명 / 실패 ${fail}명" >&2
[ "$ok" -gt 0 ] || exit 1
