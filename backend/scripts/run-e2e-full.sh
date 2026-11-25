#!/usr/bin/env bash
set -euo pipefail

backend=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
project=${E2E_COMPOSE_PROJECT:-agricontract-bruno}
fresh=false
keep=false
security=true
flows=bf01,bf02,bf03,bf04,bf05,bf06,bf07

usage() {
  echo "Usage: $0 [--flows bf01,bf03,...] [--security|--no-security] [--fresh] [--keep]"
}

while (($#)); do
  case "$1" in
    --flows) flows=${2:?--flows requires a comma-separated list}; shift ;;
    --security) security=true ;;
    --no-security) security=false ;;
    --fresh) fresh=true ;;
    --keep) keep=true ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
  shift
done

[[ $project =~ ^agricontract-[a-z0-9-]+$ ]] || { echo "Use an agricontract-* test project" >&2; exit 2; }
[[ $flows =~ ^bf0[1-7](,bf0[1-7])*$ ]] || { usage >&2; exit 2; }
for tool in docker bru jq curl python3; do command -v "$tool" >/dev/null || { echo "Missing $tool" >&2; exit 2; }; done

umask 077
started=$(date -u +%Y-%m-%dT%H:%M:%SZ)
output_root=${E2E_OUTPUT_DIR:-"$HOME/agricontract-e2e"}
mkdir -p "$output_root"
round="$output_root/run-$(date +%Y%m%d-%H%M)"
if ! mkdir "$round" 2>/dev/null; then
  round=$(mktemp -d "$round-XXXXXX")
fi
round=$(cd "$round" && pwd)
mkdir "$round/logs"
echo "Evidence directory: $round"

export GATEWAY_INTERNAL_SECRET=${GATEWAY_INTERNAL_SECRET:-changeme}
export SERVICE_INTERNAL_SECRET=${SERVICE_INTERNAL_SECRET:-local-dev}
export KEYCLOAK_USER_SERVICE_SECRET=${KEYCLOAK_USER_SERVICE_SECRET:-bruno-local-user-service}
export INTAKE_IMAP_USERNAME=${INTAKE_IMAP_USERNAME:-intake}
export INTAKE_IMAP_PASSWORD=${INTAKE_IMAP_PASSWORD:-bruno-local-intake}
export INSPECTION_FILE_DOWNLOAD_ENABLED=${INSPECTION_FILE_DOWNLOAD_ENABLED:-true}
rabbit_user=${RABBITMQ_DEFAULT_USER:-guest}
rabbit_password=${RABBITMQ_DEFAULT_PASS:-guest}
db_password=${DB_ROOT_PASSWORD:-password}

cd "$backend"
base=(docker compose -p "$project" -f docker-compose.yml)
overlay="$round/compose.e2e-local.json"
suite_names=(bf01-onboarding-kyc bf02-plot-listing bf03-signing-activation bf04-milestone-delivery-settlement
  bf05-breach-force-majeure bf06-mutual-termination-replacement bf07-emergency-security-lock)
suites=()
for folder in "${suite_names[@]}"; do
  flow=${folder%%-*}
  [[ ,$flows, == *",$flow,"* ]] && suites+=("${flow^^}=e2e/$folder")
done
"$security" && suites+=("Security=security")

compose=("${base[@]}" -f "$overlay")
collect() {
  for service in "${services[@]}"; do
    "${compose[@]}" logs --no-color --since "$started" "$service" > "$round/logs/$service.log" 2>&1 || true
  done
  queues "$round/rabbitmq-queues.json" || true
}
queues() {
  curl --silent --show-error --fail --max-time 10 --user "$rabbit_user:$rabbit_password" \
    http://localhost:15672/api/queues > "$1"
}
readiness() {
  local deadline=$((SECONDS + 300))
  local app pattern
  local -A patterns=(
    [api-gateway]='Started ApiGatewayApplication'
    [user-service]='Started UserServiceApplication'
    [product-service]='Started ProductServiceApplication'
    [contract-service]='Started ContractServiceApplication'
    [escrow-service]='Started EscrowServiceApplication'
    [bank-service]='Started BankServiceApplication'
    [audit-service]='Started AuditServiceApplication'
    [inspection-service]='Started InspectionServiceApplication'
    [reputation-service]='Started ReputationServiceApplication'
  )
  for app in "${!patterns[@]}"; do
    pattern=${patterns[$app]}
    until "${compose[@]}" logs --tail 200 "$app" 2>&1 | awk -v p="$pattern" 'index($0, p){found=1} END{exit !found}'; do
      if ((SECONDS >= deadline)); then echo "$app did not report readiness"; return 1; fi
      sleep 2
    done
  done
  until curl -fsS --max-time 5 http://localhost:8180/realms/agricontract/.well-known/openid-configuration >/dev/null; do
    if ((SECONDS >= deadline)); then echo "Keycloak realm is not ready"; return 1; fi
    sleep 2
  done
}
start() {
  if "$fresh"; then
    "${compose[@]}" down -v || return
    "${compose[@]}" up -d --build --wait --wait-timeout 420 || return
  else
    "${compose[@]}" up -d --wait --wait-timeout 420 || return
  fi
  readiness || return
  "${compose[@]}" exec -T user-db mysql --default-character-set=utf8mb4 -uroot "-p$db_password" user_db \
    < scripts/seed/02-user-db.sql || return
  "${compose[@]}" exec -T product-db mysql --default-character-set=utf8mb4 -uroot "-p$db_password" product_db \
    < scripts/seed/01-product-db.sql
}

if ! config=$("${base[@]}" config --services 2>"$round/stack.log"); then
  python3 scripts/e2e/report.py "$round" "$backend/bruno" --setup-failed "${suites[@]}"
  exit 1
fi
mapfile -t services <<< "$config"
printf '%s\n' "${services[@]}" | jq -Rn --arg project "$project" '
  {services: [inputs | select(length > 0) as $name |
    {key: $name, value: ({container_name: ($project + "-" + $name)} +
      (if $name == "bank-service" then {ports: ["127.0.0.1:8086:8086"]}
       elif $name == "audit-service" then {ports: ["127.0.0.1:8092:8092"]}
       elif $name == "product-service" then {environment: {PLOT_GEO_VERIFICATION_TIMEOUT_HOURS: "0", PLOT_GEO_VERIFICATION_TIMEOUT_CHECK_MS: "1000"}}
       elif ($name | endswith("-db")) then {healthcheck: {
         test: ["CMD-SHELL", "MYSQL_PWD=\"$$MYSQL_ROOT_PASSWORD\" mysql --protocol=TCP -h 127.0.0.1 -uroot -D \"$$MYSQL_DATABASE\" -e \"SELECT 1\" >/dev/null"],
         interval: "5s", timeout: "5s", retries: 36}}
       else {} end))}] | from_entries}
' > "$overlay"

cleanup() {
  local code=$?
  trap - EXIT
  collect
  if ! "$keep"; then "${compose[@]}" stop >> "$round/stack.log" 2>&1 || true; fi
  exit "$code"
}
trap cleanup EXIT

if ! start >> "$round/stack.log" 2>&1; then
  python3 scripts/e2e/report.py "$round" "$backend/bruno" --setup-failed "${suites[@]}"
  exit 1
fi

bru_opts=(--env local -r --sandbox developer --bail --reporter-skip-all-headers --reporter-skip-request-body
  --env-var "gateway_secret=$GATEWAY_INTERNAL_SECRET" --env-var "service_internal_secret=$SERVICE_INTERNAL_SECRET"
  --env-var "rabbitmq_username=$rabbit_user" --env-var "rabbitmq_password=$rabbit_password")
if [[ -n ${VIETNAM_HOLIDAYS:-} ]]; then bru_opts+=(--env-var "vietnam_holidays=$VIETNAM_HOLIDAYS"); fi
listing_id=
contract_id=
clean_id=

run_bru() {
  local name=$1 folder=$2
  shift 2
  (
    cd "$backend/bruno"
    bru run "$folder" "${bru_opts[@]}" "$@" \
      --reporter-json "$round/bruno-$name.json" --reporter-html "$round/bruno-$name.html"
  ) > "$round/bruno-$name.log" 2>&1 || true
}
get_id() {
  local reporter=$1 case_id=$2 field=$3
  [[ -s $reporter ]] || return 0
  jq -r --arg id "$case_id" --arg field "$field" '
    .[].results[] | select((.name | startswith($id + " ")) and (.testResults | length > 0)
      and all(.testResults[]; .status == "pass")) | .response.data[$field] // empty' "$reporter"
}
merge_reports() {
  local name=$1
  shift
  local paths=() report
  for report; do
    [[ -s "$round/bruno-$report.json" ]] && paths+=("$round/bruno-$report.json")
  done
  if ((${#paths[@]})); then
    jq -s '[{iterationIndex: 0, results: [.[].[].results[]]}]' "${paths[@]}" > "$round/bruno-$name.json"
  fi
}

for suite in "${suites[@]}"; do
  flow=${suite%%=*}
  folder=${suite#*=}
  name=${flow,,}
  shared_listing=(--env-var "shared_listing_id=$listing_id")
  echo "Running $flow"
  case "$flow" in
    BF02)
      run_bru "$name" "$folder"
      listing_id=$(get_id "$round/bruno-bf02.json" BF02-070 listingId)
      ;;
    BF03)
      run_bru bf03-happy "$folder/happy" "${shared_listing[@]}"
      run_bru bf03-unhappy "$folder/unhappy" "${shared_listing[@]}"
      merge_reports bf03 bf03-happy bf03-unhappy
      contract_id=$(get_id "$round/bruno-bf03.json" BF03-080 contractId)
      ;;
    BF04)
      run_bru bf04-clean "$folder/clean" "${shared_listing[@]}" --env-var "shared_contract_id=$contract_id"
      clean_id=$(get_id "$round/bruno-bf04-clean.json" BF04-070 contractId)
      run_bru bf04-completion "$folder/completion" --env-var "shared_clean_contract_id=$clean_id"
      run_bru bf04-dispute "$folder/dispute" "${shared_listing[@]}"
      merge_reports bf04 bf04-clean bf04-completion bf04-dispute
      ;;
    BF06) ;;
    Security) run_bru "$name" "$folder" --no-bail ;;
    *) run_bru "$name" "$folder" ;;
  esac
  queues "$round/queues-$name.json" 2>> "$round/stack.log" || true
done

python3 scripts/e2e/report.py "$round" "$backend/bruno" "${suites[@]}"
