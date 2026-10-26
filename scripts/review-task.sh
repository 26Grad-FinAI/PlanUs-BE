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

BASE_BRANCH="develop"

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

echo -e "${CYAN}=== PlanUs 코드 리뷰 반영 ===${NC}"
echo ""

# 1. 현재 브랜치에서 이슈 번호 추출
CURRENT_BRANCH=$(git branch --show-current)

if [[ ! "$CURRENT_BRANCH" =~ \#([0-9]+) ]]; then
  echo -e "${RED}현재 브랜치(${CURRENT_BRANCH})에서 이슈 번호를 찾을 수 없다.${NC}"
  exit 1
fi

ISSUE_NUMBER="${BASH_REMATCH[1]}"

# 작업 트리 상태 확인 (무관한 파일이 커밋에 섞이는 것을 방지)
DIRTY_FILES=$(git status --short)
if [[ -n "$DIRTY_FILES" ]]; then
  echo -e "${RED}작업 트리에 변경사항이 있다. 리뷰 반영 전에 커밋하거나 stash해라.${NC}"
  echo "$DIRTY_FILES"
  exit 1
fi

# 2. PR 번호 조회
PR_NUMBER=$("$GH" pr view "$CURRENT_BRANCH" --json number --jq '.number' 2>/dev/null || echo "")

if [[ -z "$PR_NUMBER" ]]; then
  echo -e "${RED}현재 브랜치에 연결된 PR을 찾을 수 없다.${NC}"
  echo -e "${RED}먼저 finish-task.sh로 PR을 생성해야 한다.${NC}"
  exit 1
fi

echo -e "이슈:   ${CYAN}#${ISSUE_NUMBER}${NC}"
echo -e "PR:     ${CYAN}#${PR_NUMBER}${NC}"
echo -e "브랜치: ${CYAN}${CURRENT_BRANCH}${NC}"
echo ""

# 3. 코드 레빗 리뷰 코멘트 수집
echo -e "${CYAN}코드 레빗 리뷰 코멘트를 수집 중...${NC}"
echo ""

# PR 리뷰 코멘트 (파일별 인라인 코멘트) 중 봇이 남긴 것만 필터링
COMMENTS=$("$GH" api "repos/{owner}/{repo}/pulls/${PR_NUMBER}/comments" --paginate \
  --jq '.[] | select(.user.type == "Bot") | {id: .id, path: .path, line: .line, body: .body, created_at: .created_at}') || {
  echo -e "${RED}PR 리뷰 코멘트 조회에 실패했다. 네트워크 또는 인증을 확인해라.${NC}" >&2
  exit 1
}

# PR 리뷰 (일반 리뷰 코멘트)에서 봇 리뷰도 수집
REVIEW_COMMENTS=$("$GH" api "repos/{owner}/{repo}/pulls/${PR_NUMBER}/reviews" --paginate \
  --jq '.[] | select(.user.type == "Bot" and .body != "") | {id: .id, body: .body, state: .state}') || {
  echo -e "${RED}PR 리뷰 조회에 실패했다. 네트워크 또는 인증을 확인해라.${NC}" >&2
  exit 1
}

if [[ -z "$COMMENTS" && -z "$REVIEW_COMMENTS" ]]; then
  echo -e "${GREEN}코드 레빗 리뷰 코멘트가 없다. 리뷰를 기다리거나 PR을 확인해라.${NC}"
  exit 0
fi

# 4. 코멘트 파싱 및 표시
APPROVED_LIST=""
APPROVED_COUNT=0
REJECTED_COUNT=0
TOTAL=0

# 인라인 코멘트 처리 (파일별 코멘트)
if [[ -n "$COMMENTS" ]]; then
  echo -e "${BOLD}=== 파일별 리뷰 코멘트 ===${NC}"
  echo ""

  # 코멘트를 개별로 파싱
  while IFS= read -r comment_json; do
    [[ -z "$comment_json" ]] && continue

    TOTAL=$((TOTAL + 1))

    # JSON에서 필드 추출 (jq로 안전하게 파싱)
    FILE_PATH=$(echo "$comment_json" | "$GH" api --input - --method GET --jq '.path' 2>/dev/null || echo "unknown")
    LINE=$(echo "$comment_json" | "$GH" api --input - --method GET --jq '.line // "?"' 2>/dev/null || echo "?")
    BODY=$(echo "$comment_json" | "$GH" api --input - --method GET --jq '.body' 2>/dev/null || echo "$comment_json")

    echo -e "${YELLOW}───────────────────────────────────────${NC}"
    echo -e "${BOLD}[${TOTAL}] ${CYAN}${FILE_PATH}:${LINE}${NC}"
    echo ""
    echo -e "$BODY" | fold -s -w 80
    echo ""

    read -rp "이 리뷰를 반영할까? (y/n): " answer
    if [[ "$answer" =~ ^[Yy]$ ]]; then
      APPROVED_COUNT=$((APPROVED_COUNT + 1))
      APPROVED_LIST="${APPROVED_LIST}\n  - ${FILE_PATH}:${LINE}"
      echo -e "${GREEN}→ 반영 예정${NC}"
    else
      REJECTED_COUNT=$((REJECTED_COUNT + 1))
      echo -e "${RED}→ 건너뜀${NC}"
    fi
    echo ""
  done < <("$GH" api "repos/{owner}/{repo}/pulls/${PR_NUMBER}/comments" --paginate \
    --jq '.[] | select(.user.type == "Bot") | @json' 2>/dev/null)
fi

# 일반 리뷰 코멘트 처리
if [[ -n "$REVIEW_COMMENTS" ]]; then
  echo -e "${BOLD}=== 일반 리뷰 코멘트 ===${NC}"
  echo ""

  while IFS= read -r review_json; do
    [[ -z "$review_json" ]] && continue

    TOTAL=$((TOTAL + 1))

    BODY=$(echo "$review_json" | "$GH" api --input - --method GET --jq '.body' 2>/dev/null || echo "$review_json")

    echo -e "${YELLOW}───────────────────────────────────────${NC}"
    echo -e "${BOLD}[${TOTAL}] 일반 리뷰${NC}"
    echo ""
    echo -e "$BODY" | fold -s -w 80
    echo ""

    read -rp "이 리뷰를 반영할까? (y/n): " answer
    if [[ "$answer" =~ ^[Yy]$ ]]; then
      APPROVED_COUNT=$((APPROVED_COUNT + 1))
      APPROVED_LIST="${APPROVED_LIST}\n  - 일반 리뷰 #${TOTAL}"
      echo -e "${GREEN}→ 반영 예정${NC}"
    else
      REJECTED_COUNT=$((REJECTED_COUNT + 1))
      echo -e "${RED}→ 건너뜀${NC}"
    fi
    echo ""
  done < <("$GH" api "repos/{owner}/{repo}/pulls/${PR_NUMBER}/reviews" --paginate \
    --jq '.[] | select(.user.type == "Bot" and .body != "") | @json' 2>/dev/null)
fi

# 5. 결과 요약
echo -e "${YELLOW}───────────────────────────────────────${NC}"
echo -e "${BOLD}리뷰 요약${NC}"
echo -e "  전체: ${TOTAL}건"
echo -e "  ${GREEN}반영: ${APPROVED_COUNT}건${NC}"
echo -e "  ${RED}건너뜀: ${REJECTED_COUNT}건${NC}"

if [[ -n "$APPROVED_LIST" ]]; then
  echo -e "\n${BOLD}반영 대상:${NC}"
  echo -e "$APPROVED_LIST"
fi
echo ""

if [[ "$APPROVED_COUNT" -eq 0 ]]; then
  echo -e "${GREEN}반영할 리뷰가 없다. 종료한다.${NC}"
  exit 0
fi

# 6. 코드 수정 대기
echo -e "${YELLOW}반영할 리뷰를 확인했다. 코드를 수정해라.${NC}"
echo -e "${YELLOW}수정 완료 후 아무 키나 눌러라.${NC}"
read -rp ""

# 7. 변경사항 확인
CHANGED_FILES=$(git status --short)
if [[ -z "$CHANGED_FILES" ]]; then
  echo -e "${RED}변경된 파일이 없다. 수정 후 다시 실행해라.${NC}"
  exit 1
fi

echo -e "${YELLOW}변경된 파일:${NC}"
echo "$CHANGED_FILES"
echo ""

# 8. 커밋 및 푸시
COMMIT_MSG="[Refactor]#${ISSUE_NUMBER} 코드 리뷰 반영"

echo -e "${CYAN}커밋 중: ${COMMIT_MSG}${NC}"
git add -A
git commit -m "$COMMIT_MSG"

echo ""
echo -e "${CYAN}원격 저장소에 푸시 중...${NC}"
git push

echo ""
echo -e "${GREEN}=== 코드 리뷰 반영 완료 ===${NC}"
echo -e "  커밋: ${COMMIT_MSG}"
echo -e "  PR #${PR_NUMBER}이 자동 업데이트되었다."
echo -e "${GREEN}CI/CD 파이프라인이 자동으로 실행된다.${NC}"
