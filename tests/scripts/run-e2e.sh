#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SEED_DIR="$SCRIPT_DIR/../../backend/scripts/seed"
API_DIR="$SCRIPT_DIR/../api"

echo "==> Resetting seed data..."
docker exec -i user-db     mysql --default-character-set=utf8mb4 -uroot -ppassword user_db     < "$SEED_DIR/02-user-db.sql"
docker exec -i product-db  mysql --default-character-set=utf8mb4 -uroot -ppassword product_db  < "$SEED_DIR/01-product-db.sql"
# contract_db / escrow_db have no seed (#450): the Bruno run creates its own contract (06-create-contract).

echo "==> Running Bruno e2e..."
cd "$API_DIR"
bru run contract/ --env local -r
