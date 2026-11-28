#!/usr/bin/env bash
# One round of the Report-5.2 integration tests: runs the Bruno folders of api-gateway, user-service and
# product-service against the running Compose stack and writes one Bruno JSON report per run, plus a run.json with
# the commit and date, under results/<round>/. fill_bruno_report.py reads them into the Round column.
#
#   GATEWAY_INTERNAL_SECRET=... scripts/unit-test-report/run_bruno_report52.sh round1 "Nguyen Hai Long"
#
# Needs: the stack from backend/docker-compose.yml (api-gateway, user-service, product-service, contract-service and
# their dependencies), product-service started with PLOT_GEO_VERIFICATION_TIMEOUT_HOURS=0 and
# PLOT_GEO_VERIFICATION_TIMEOUT_CHECK_MS=1000, Node.js (npx), and the user-db / product-db containers.
set -euo pipefail

ROUND="${1:?round name, e.g. round1}"
TESTER="${2:?tester name}"
: "${GATEWAY_INTERNAL_SECRET:?export GATEWAY_INTERNAL_SECRET (backend/.env) so direct calls pass the gateway check}"
BRU_VERSION="3.4.2"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
# SEED_DIR: seed from another checkout, e.g. a fix not merged yet; run.json records its commit
SEED="${SEED_DIR:-$REPO/backend/scripts/seed}"
SEED_COMMIT="$(git -C "$SEED" rev-parse --short HEAD)"
OUT="$HERE/results/$ROUND"
mkdir -p "$OUT"
# a report or run.json left from an earlier run must never be read as this run's result (defects.json is written by
# hand and stays)
rm -f "$OUT/run.json" "$OUT"/gateway.json "$OUT"/user-service-*.json "$OUT"/product-service.json

reseed() {
  docker exec -i user-db mysql --default-character-set=utf8mb4 -uroot -ppassword user_db < "$SEED/02-user-db.sql"
  docker exec -i product-db mysql --default-character-set=utf8mb4 -uroot -ppassword product_db \
    < "$SEED/01-product-db.sql"
}

REPORTS=()
bru_run() {  # <folder> <report name>
  # bru exits non-zero when an assertion fails: that is a result to record, so the exit status is not checked here;
  # a run that wrote no report is caught by check_reports
  (cd "$REPO/tests/api" && npx -y "@usebruno/cli@$BRU_VERSION" run "$1" --env local --sandbox developer \
    --env-var "gateway_secret=$GATEWAY_INTERNAL_SECRET" --reporter-json "$OUT/$2.json") || true
  REPORTS+=("$2")
}

check_reports() {  # every run of this round left a JSON report with results, or the round is not published
  local name ok=1
  for name in "${REPORTS[@]}"; do
    if ! python -c 'import json, sys; d = json.load(open(sys.argv[1])); sys.exit(0 if d and all(i.get("results") for i in d) else 1)' \
        "$OUT/$name.json" 2>/dev/null; then
      echo "no Bruno report for $name: $OUT/$name.json missing or empty, run.json not written" >&2
      ok=0
    fi
  done
  [ "$ok" = 1 ]
}

reseed
bru_run gateway gateway

# user-service, first without seller1's seeded profile (register → 201), then with it (register → 200)
drop_seller1_profile() {
  docker exec -i user-db mysql -uroot -ppassword user_db \
    -e "DELETE FROM user_profiles WHERE user_id = '1a5a2ddd-56b9-492a-b966-99f436e0fc85';"
}
drop_seller1_profile
bru_run user-service user-service-new-profile
# the seed uses INSERT IGNORE, so the PENDING profile the run created must go before the VERIFIED one comes back
drop_seller1_profile
reseed
bru_run user-service user-service-seeded-profile

bru_run product-service product-service

check_reports || exit 1
cat > "$OUT/run.json" <<EOF
{
  "round": "$ROUND",
  "date": "$(date +%d/%m/%Y)",
  "tester": "$TESTER",
  "commit": "$(git -C "$REPO" rev-parse --short HEAD)",
  "bruno_cli": "$BRU_VERSION",
  "seed_commit": "$SEED_COMMIT"
}
EOF
python "$HERE/redact_reports.py" "$OUT"
echo "reports in $OUT"
