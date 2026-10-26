#!/usr/bin/env bash
set -euo pipefail

GH="${PLANUS_GH:-$(command -v gh 2>/dev/null || echo "")}"
if [[ -z "$GH" || ! -x "$GH" ]]; then
  if [[ -x "/c/Program Files/GitHub CLI/gh.exe" ]]; then
    GH="/c/Program Files/GitHub CLI/gh.exe"
  else
    echo "gh CLI를 찾을 수 없다. PATH에 추가하거나 PLANUS_GH 환경변수를 설정해라." >&2
    exit 1
  fi
fi

REPO="26Grad-FinAI/PlanUs-BE"
BASE_BRANCH="develop"

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}=== PlanUs 작업 시작 ===${NC}"
echo ""

# 1. 이슈 타입 선택
echo -e "${YELLOW}이슈 타입을 선택해라:${NC}"
echo "  1) feat     - 새로운 기능 개발"
echo "  2) fix      - 버그 수정"
echo "  3) refactor - 리팩토링"
echo "  4) chore    - 기타 작업"
echo "  5) hotfix   - 긴급 수정"
echo ""
read -rp "번호 입력: " type_num

case "$type_num" in
  1) TYPE="feat";     PREFIX="[Feat]";     TEMPLATE="feature_request" ;;
  2) TYPE="fix";      PREFIX="[Fix]";      TEMPLATE="bug_report" ;;
  3) TYPE="refactor"; PREFIX="[Refactor]"; TEMPLATE="" ;;
  4) TYPE="chore";    PREFIX="[Chore]";    TEMPLATE="" ;;
  5) TYPE="hotfix";   PREFIX="[Hotfix]";   TEMPLATE="" ;;
  *) echo -e "${RED}잘못된 입력이다.${NC}"; exit 1 ;;
esac

# 2. 작업 제목 입력
echo ""
read -rp "작업 제목 (예: 소셜 로그인 구현): " TITLE

if [[ -z "$TITLE" ]]; then
  echo -e "${RED}제목을 입력해야 한다.${NC}"
  exit 1
fi

# 3. 작업 설명 입력
echo ""
echo -e "${YELLOW}작업 설명을 입력해라 (빈 줄 입력 시 종료):${NC}"
DESCRIPTION=""
while IFS= read -r line; do
  [[ -z "$line" ]] && break
  DESCRIPTION="${DESCRIPTION}${line}
"
done

# 4. 필요 이유 입력
echo ""
echo -e "${YELLOW}필요 이유를 입력해라 (빈 줄 입력 시 종료):${NC}"
REASON=""
while IFS= read -r line; do
  [[ -z "$line" ]] && break
  REASON="${REASON}${line}
"
done

# 5. 작업 내용 입력
echo ""
echo -e "${YELLOW}작업 내용을 입력해라 (체크리스트 항목, 빈 줄 입력 시 종료):${NC}"
echo -e "  예: API 설계"
echo -e "  예: 로직 구현"
TASKS=""
while IFS= read -r line; do
  [[ -z "$line" ]] && break
  TASKS="${TASKS}- [ ] ${line}
"
done

if [[ -z "$TASKS" ]]; then
  TASKS="- [ ] 작업 진행
- [ ] 테스트
"
fi

# 5. 이슈 본문 생성
if [[ "$TYPE" == "feat" ]]; then
  BODY=$(cat <<EOF
## 기능 설명

${DESCRIPTION:-작업 설명 없음}

## 필요 이유

${REASON:-${TITLE} 기능이 필요하다.}

## 작업 내용

${TASKS}
## 완료 기준

- [ ] 기능이 정상 동작한다.
- [ ] 관련 테스트가 통과한다.
- [ ] PR이 develop 브랜치로 생성된다.
EOF
)
elif [[ "$TYPE" == "fix" ]]; then
  BODY=$(cat <<EOF
## 버그 설명

${DESCRIPTION:-작업 설명 없음}

## 재현 방법

1.

## 예상 동작

정상 동작해야 한다.

## 실제 동작

${TITLE}

## 작업 내용

${TASKS}
## 완료 기준

- [ ] 버그가 재현되지 않는다.
- [ ] 관련 테스트가 통과한다.
EOF
)
else
  BODY=$(cat <<EOF
## 작업 설명

${DESCRIPTION:-작업 설명 없음}

## 작업 내용

${TASKS}
## 완료 기준

- [ ] 작업이 정상 완료된다.
- [ ] PR이 develop 브랜치로 생성된다.
EOF
)
fi

# 5. 이슈 생성
echo ""
echo -e "${CYAN}이슈 생성 중...${NC}"

ISSUE_URL=$("$GH" issue create \
  --repo "$REPO" \
  --title "${PREFIX} ${TITLE}" \
  --label "$TYPE" \
  --body "$BODY" 2>&1)

ISSUE_NUMBER=$(echo "$ISSUE_URL" | grep -oE '[0-9]+$')

echo -e "${GREEN}이슈 생성 완료: ${ISSUE_URL}${NC}"

# 6. 브랜치 생성 및 체크아웃
SLUG=$(echo "$TITLE" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9가-힣]/-/g' | sed 's/--*/-/g' | sed 's/^-//;s/-$//')
BRANCH="feature/#${ISSUE_NUMBER}-${SLUG}"

echo ""
echo -e "${CYAN}브랜치 생성 중: ${BRANCH}${NC}"

git fetch origin "$BASE_BRANCH" || {
  echo -e "${RED}origin/${BASE_BRANCH}를 가져올 수 없다. 네트워크 상태를 확인해라.${NC}"
  exit 1
}
git checkout -b "$BRANCH" "origin/${BASE_BRANCH}"

echo ""
echo -e "${GREEN}=== 작업 준비 완료 ===${NC}"
echo -e "  이슈:   #${ISSUE_NUMBER} - ${PREFIX} ${TITLE}"
echo -e "  브랜치: ${BRANCH}"
echo -e "  라벨:   ${TYPE}"
echo ""
echo -e "${YELLOW}작업 완료 후 finish-task.sh를 실행해라.${NC}"
