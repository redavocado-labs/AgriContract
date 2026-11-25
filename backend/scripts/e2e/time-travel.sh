#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 2 || $1 != contract-db ]]; then
  echo "Usage: $0 contract-db '<fixture-scoped timestamp update>'" >&2
  exit 2
fi

uuid='[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
allowed="^UPDATE milestone SET buyer_received_at = UTC_TIMESTAMP\(6\) - INTERVAL 14 DAY WHERE contract_id = '($uuid)' AND milestone_id = '($uuid)'$"
if [[ ! $2 =~ $allowed ]]; then
  echo "Only the BF-04 fixture buyer_received_at update is supported" >&2
  exit 2
fi
contract=${BASH_REMATCH[1]}
milestone=${BASH_REMATCH[2]}
[[ $contract == "${E2E_OWNED_CONTRACT_ID:-}" ]] || { echo "Fixture contract does not match" >&2; exit 2; }
project=${E2E_COMPOSE_PROJECT:-agricontract-bruno}
[[ $project =~ ^agricontract-[a-z0-9-]+$ ]] || exit 2

backend=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$backend"
docker compose -p "$project" -f docker-compose.yml exec -T contract-db \
    mysql --batch --skip-column-names -uroot "-p${DB_ROOT_PASSWORD:-password}" contract_db <<SQL
SET time_zone = '+00:00';
START TRANSACTION;
SELECT 'buyer_received_at before', buyer_received_at
  FROM milestone WHERE milestone_id = '$milestone' AND contract_id = '$contract' FOR UPDATE;
$2 AND status = 'BUYER_RECEIVED';
SELECT 'rows changed', ROW_COUNT();
SELECT 'buyer_received_at after', buyer_received_at
  FROM milestone WHERE milestone_id = '$milestone' AND contract_id = '$contract';
COMMIT;
SQL
