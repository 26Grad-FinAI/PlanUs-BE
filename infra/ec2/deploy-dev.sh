#!/usr/bin/env bash
set -euo pipefail

readonly AWS_REGION="${AWS_REGION:-ap-northeast-2}"
readonly PARAMETER_PATH="${PLANUS_SSM_PARAMETER_PATH:-/planus/dev/app}"
readonly APP_IMAGE="${PLANUS_APP_IMAGE:?PLANUS_APP_IMAGE is required}"
readonly BASE_DIR="${PLANUS_BASE_DIR:-/opt/planus}"
readonly COMPOSE_FILE="${BASE_DIR}/docker-compose.dev.yml"
readonly COMPOSE_BACKUP="${COMPOSE_FILE}.previous"
readonly NGINX_CONFIG="${BASE_DIR}/infra/nginx/planus-dev.conf"
readonly NGINX_BACKUP="${NGINX_CONFIG}.previous"
readonly ECR_REGISTRY="${APP_IMAGE%%/*}"
readonly IMAGE_REPOSITORY="${APP_IMAGE%:*}"
readonly HEALTH_CHECK_HOST="planus-dev.p-e.kr"

previous_image=""
deployment_started=false

wait_for_health() {
  local timeout_seconds="$1"
  local deadline=$((SECONDS + timeout_seconds))
  local remaining
  local sleep_seconds

  while (( SECONDS < deadline )); do
    if curl --fail --silent --show-error --connect-timeout 2 --max-time 5 \
      --resolve "${HEALTH_CHECK_HOST}:443:127.0.0.1" \
      "https://${HEALTH_CHECK_HOST}/actuator/health" >/dev/null; then
      return 0
    fi

    remaining=$((deadline - SECONDS))
    (( remaining > 0 )) || break
    echo "애플리케이션 시작을 기다립니다. 남은 시간: ${remaining}초"
    sleep_seconds=$((remaining < 5 ? remaining : 5))
    sleep "$sleep_seconds"
  done

  return 1
}

restore_configuration_files() {
  if [[ -f "$COMPOSE_BACKUP" ]]; then
    cp "$COMPOSE_BACKUP" "$COMPOSE_FILE"
  fi
  if [[ -f "$NGINX_BACKUP" ]]; then
    cp "$NGINX_BACKUP" "$NGINX_CONFIG"
  fi
}

rollback_deployment() {
  local rollback_failed=false

  echo "[Rollback] 이전 배포 상태로 복구합니다."
  restore_configuration_files

  if [[ -n "$previous_image" ]]; then
    docker pull "$previous_image" >/dev/null 2>&1 || true
    PLANUS_APP_IMAGE="$previous_image" \
      docker compose -f "$COMPOSE_FILE" up -d --no-deps app || rollback_failed=true

    docker compose -f "$COMPOSE_FILE" run --rm --no-deps --entrypoint nginx nginx -t \
      || rollback_failed=true
    docker compose -f "$COMPOSE_FILE" up -d --no-deps --force-recreate nginx \
      || rollback_failed=true

    if ! wait_for_health 60; then
      rollback_failed=true
    fi
  elif [[ "$deployment_started" == true ]]; then
    docker compose -f "$COMPOSE_FILE" stop app >/dev/null 2>&1 || true
  fi

  docker logout "$ECR_REGISTRY" >/dev/null 2>&1 || true

  if [[ "$rollback_failed" == true ]]; then
    echo "[Rollback] 이전 배포 상태 복구에 실패했습니다." >&2
  elif [[ -n "$previous_image" ]]; then
    echo "[Rollback] 이전 이미지로 복구했습니다: $previous_image"
  else
    echo "[Rollback] 복구할 이전 이미지가 없습니다." >&2
  fi
}

handle_exit() {
  local exit_code=$?

  trap - EXIT
  set +e
  if (( exit_code != 0 )); then
    rollback_deployment
  fi
  exit "$exit_code"
}

trap handle_exit EXIT

echo "[1/9] 배포 실행 조건을 확인합니다."
for command_name in aws jq base64 docker curl; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "필수 명령어가 설치되어 있지 않습니다: $command_name" >&2
    exit 1
  }
done
docker compose version >/dev/null
[[ -f "$COMPOSE_FILE" ]] || {
  echo "Docker Compose 파일이 없습니다: $COMPOSE_FILE" >&2
  exit 1
}
[[ -f "$NGINX_CONFIG" ]] || {
  echo "Nginx 설정 파일이 없습니다: $NGINX_CONFIG" >&2
  exit 1
}

echo "[2/9] Parameter Store에서 DEV 설정을 불러옵니다."
parameter_response="$(aws ssm get-parameters-by-path \
  --path "${PARAMETER_PATH%/}/" \
  --no-recursive \
  --with-decryption \
  --region "$AWS_REGION" \
  --output json)"

parameter_rows="$(jq -er '
  .Parameters
  | if length == 0 then error("no parameters") else .[] end
  | [(.Name | split("/") | last), (.Value | @base64)]
  | @tsv
' <<<"$parameter_response")"

parameter_count=0
while IFS=$'\t' read -r parameter_name encoded_value; do
  [[ "$parameter_name" =~ ^[A-Z_][A-Z0-9_]*$ ]] || {
    echo "잘못된 SSM 환경변수 이름입니다: $parameter_name" >&2
    exit 1
  }

  parameter_value="$(printf '%s' "$encoded_value" | base64 --decode)"
  [[ "$parameter_value" != *$'\n'* && "$parameter_value" != *$'\r'* && "$parameter_value" != *$'\t'* ]] || {
    echo "SSM 환경변수 값에 줄바꿈 또는 탭을 사용할 수 없습니다: $parameter_name" >&2
    exit 1
  }

  export "$parameter_name=$parameter_value"
  parameter_count=$((parameter_count + 1))
done <<<"$parameter_rows"

unset parameter_response parameter_rows parameter_name parameter_value encoded_value
echo "DEV 애플리케이션 파라미터 ${parameter_count}개를 확인했습니다."

required_parameters=(
  JWT_SECRET
  ANTHROPIC_API_KEY
  GOOGLE_CLIENT_ID
  GOOGLE_CLIENT_SECRET
  GOOGLE_ALLOWED_REDIRECT_URIS
  KAKAO_CLIENT_ID
  KAKAO_CLIENT_SECRET
  KAKAO_ALLOWED_REDIRECT_URIS
  DB_HOST
  DB_PORT
  DB_NAME
  DB_USERNAME
  DB_PASSWORD
)
for parameter_name in "${required_parameters[@]}"; do
  [[ -n "${!parameter_name:-}" ]] || {
    echo "필수 DEV 파라미터가 없습니다: $parameter_name" >&2
    exit 1
  }
done
unset required_parameters parameter_name

echo "[3/9] 현재 배포 상태와 새 구성을 확인합니다."
cd "$BASE_DIR"
docker compose -f "$COMPOSE_FILE" config -q

previous_container_id="$(docker compose -f "$COMPOSE_FILE" ps -q --all app 2>/dev/null || true)"
if [[ -n "$previous_container_id" ]]; then
  previous_image="$(docker inspect --format '{{.Config.Image}}' "$previous_container_id")"
  echo "이전 실행 이미지: $previous_image"
else
  echo "현재 실행 중인 app 컨테이너가 없습니다."
fi
unset previous_container_id

echo "[4/9] ECR에 로그인하고 배포 이미지를 내려받습니다."
aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin "$ECR_REGISTRY" >/dev/null
docker compose -f "$COMPOSE_FILE" pull app nginx

echo "[5/9] 애플리케이션 컨테이너를 시작합니다."
deployment_started=true
docker compose -f "$COMPOSE_FILE" up -d --no-deps app

echo "[6/9] Nginx 설정을 검증하고 적용합니다."
docker compose -f "$COMPOSE_FILE" run --rm --no-deps --entrypoint nginx nginx -t
docker compose -f "$COMPOSE_FILE" up -d --no-deps --force-recreate nginx

echo "[7/9] HTTPS 서비스 Health Check를 수행합니다."
wait_for_health 120
echo "HTTPS 서비스 Health Check에 성공했습니다."

echo "[8/9] EC2의 이전 PlanUs 이미지를 정리합니다."
removed_image_count=0
while IFS= read -r image_ref; do
  [[ -n "$image_ref" ]] || continue
  if [[ "$image_ref" == "$APP_IMAGE" || "$image_ref" == "$previous_image" ]]; then
    continue
  fi

  if docker image rm "$image_ref" >/dev/null 2>&1; then
    removed_image_count=$((removed_image_count + 1))
  fi
done < <(docker image ls "$IMAGE_REPOSITORY" --format '{{.Repository}}:{{.Tag}}' | sort -u)
docker image prune -f >/dev/null || echo "사용하지 않는 이미지 레이어 정리에 실패했습니다." >&2
echo "사용하지 않는 이전 PlanUs 이미지 ${removed_image_count}개를 정리했습니다."

echo "[9/9] DEV 배포를 완료했습니다."
docker compose -f "$COMPOSE_FILE" ps
docker logout "$ECR_REGISTRY" >/dev/null 2>&1 || true
rm -f "$COMPOSE_BACKUP" "$NGINX_BACKUP"
trap - EXIT
