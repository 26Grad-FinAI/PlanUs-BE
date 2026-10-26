#!/usr/bin/env bash
set -euo pipefail

GH="/c/Program Files/GitHub CLI/gh.exe"
BASE_BRANCH="develop"

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

echo -e "${CYAN}=== PlanUs 작업 완료 ===${NC}"
echo ""

# 1. 현재 브랜치에서 이슈 번호 추출
CURRENT_BRANCH=$(git branch --show-current)

if [[ ! "$CURRENT_BRANCH" =~ \#([0-9]+) ]]; then
  echo -e "${RED}현재 브랜치(${CURRENT_BRANCH})에서 이슈 번호를 찾을 수 없다.${NC}"
  echo -e "${RED}feature/#번호-설명 형식의 브랜치에서 실행해야 한다.${NC}"
  exit 1
fi

ISSUE_NUMBER="${BASH_REMATCH[1]}"
echo -e "이슈 번호: ${CYAN}#${ISSUE_NUMBER}${NC}"
echo -e "브랜치:    ${CYAN}${CURRENT_BRANCH}${NC}"

# 2. 커밋 프리픽스 결정
ISSUE_LABELS=$("$GH" issue view "$ISSUE_NUMBER" --json labels --jq '.labels[].name' 2>/dev/null || echo "")

if echo "$ISSUE_LABELS" | grep -q "feat"; then
  PREFIX="[Feat]"
elif echo "$ISSUE_LABELS" | grep -q "fix"; then
  PREFIX="[Fix]"
elif echo "$ISSUE_LABELS" | grep -q "refactor"; then
  PREFIX="[Refactor]"
elif echo "$ISSUE_LABELS" | grep -q "chore"; then
  PREFIX="[Chore]"
elif echo "$ISSUE_LABELS" | grep -q "hotfix"; then
  PREFIX="[Hotfix]"
else
  PREFIX="[Feat]"
fi

echo -e "커밋 접두사: ${CYAN}${PREFIX}${NC}"
echo ""

# 3. 변경사항 확인
CHANGED_FILES=$(git status --short)
if [[ -z "$CHANGED_FILES" ]]; then
  echo -e "${YELLOW}변경된 파일이 없다. 커밋할 내용이 있는지 확인해라.${NC}"

  # 이미 커밋이 있는지 확인
  COMMIT_COUNT=$(git rev-list --count "${BASE_BRANCH}..HEAD" 2>/dev/null || echo "0")
  if [[ "$COMMIT_COUNT" -eq 0 ]]; then
    echo -e "${RED}커밋도 없다. 작업 후 다시 실행해라.${NC}"
    exit 1
  fi

  echo -e "${GREEN}이미 ${COMMIT_COUNT}개의 커밋이 있다. PR 생성으로 진행한다.${NC}"
else
  echo -e "${YELLOW}변경된 파일:${NC}"
  echo "$CHANGED_FILES"
  echo ""

  # 4. 커밋 메시지 입력
  read -rp "커밋 메시지 (예: 소셜 로그인 구현): " COMMIT_MSG

  if [[ -z "$COMMIT_MSG" ]]; then
    echo -e "${RED}커밋 메시지를 입력해야 한다.${NC}"
    exit 1
  fi

  FULL_COMMIT_MSG="${PREFIX}#${ISSUE_NUMBER} ${COMMIT_MSG}"

  echo ""
  echo -e "${CYAN}커밋 중: ${FULL_COMMIT_MSG}${NC}"
  git add -A
  git commit -m "$FULL_COMMIT_MSG"
fi

# 5. 원격 푸시
echo ""
echo -e "${CYAN}원격 저장소에 푸시 중...${NC}"
git push -u origin "$CURRENT_BRANCH"

# 6. PR 본문 생성
ISSUE_TITLE=$("$GH" issue view "$ISSUE_NUMBER" --json title --jq '.title' 2>/dev/null || echo "")

# develop와의 커밋 목록으로 변경 사항 생성
CHANGES=$(git log --oneline "${BASE_BRANCH}..HEAD" 2>/dev/null | sed 's/^/- /' || echo "- 작업 내용 확인 필요")

PR_BODY=$(cat <<EOF
## 작업 내용

#${ISSUE_NUMBER} ${ISSUE_TITLE} 작업을 완료했다.

## 관련 이슈

- Close #${ISSUE_NUMBER}

## 변경 사항

${CHANGES}

## 체크리스트

- [x] develop 브랜치를 기준으로 작업했습니다.
- [x] 이슈 번호를 브랜치명 또는 PR에 연결했습니다.
- [ ] 테스트를 실행했습니다.
- [ ] 에러 처리를 확인했습니다.
- [ ] 불필요한 로그/주석을 제거했습니다.

## 테스트 결과



## 리뷰 요청 사항

EOF
)

# 7. PR 생성
echo ""
echo -e "${CYAN}PR 생성 중...${NC}"

PR_URL=$("$GH" pr create \
  --base "$BASE_BRANCH" \
  --title "${PREFIX}#${ISSUE_NUMBER} ${ISSUE_TITLE##*] }" \
  --body "$PR_BODY" 2>&1)

echo ""
echo -e "${GREEN}=== PR 생성 완료 ===${NC}"
echo -e "  이슈: #${ISSUE_NUMBER}"
echo -e "  PR:   ${PR_URL}"
echo ""
echo -e "${GREEN}CI/CD 파이프라인이 자동으로 실행된다.${NC}"
