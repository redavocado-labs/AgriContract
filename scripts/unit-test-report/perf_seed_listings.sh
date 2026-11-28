#!/usr/bin/env bash
# Fill product_db of the agricontract-bruno stack up to about TARGET listings for the load test of Report 5.4
# (SRS IV.2.2: browse/search within 3 s with up to 10,000 listings), by copying the AVAILABLE listings the test runs
# created. Test data only: run it on a test stack, never on a shared database.
#   ./perf_seed_listings.sh [target=10000]
set -euo pipefail
target=${1:-10000}
db=(docker exec -i agricontract-bruno-product-db mysql -uroot -p"${DB_ROOT_PASSWORD:-password}" -N product_db)
count=$("${db[@]}" -e "SELECT COUNT(*) FROM listings" 2>/dev/null)
available=$("${db[@]}" -e "SELECT COUNT(*) FROM listings WHERE status = 'AVAILABLE'" 2>/dev/null)
((available > 0)) || { echo "no AVAILABLE listing to copy" >&2; exit 1; }
copies=$(((target - count + available - 1) / available))
((copies > 0)) || { echo "already $count listings"; exit 0; }
"${db[@]}" 2>/dev/null <<SQL
SET SESSION cte_max_recursion_depth = 100000;
INSERT INTO listings (listing_id, seller_id, product_id, category_id, commodity, crop_year, harvest_season, certifications,
  region_text, quality_type, quality_moisture_percent, quality_foreign_matter_percent, quality_black_broken_percent,
  packaging_declaration, warehouse_location, estimated_availability_date, traceability_evidence_package_id, product_name,
  cover_image_url, quantity, quantity_unit, quantity_available, min_order_quantity, price_floor, currency,
  delivery_deadline, status, close_reason, created_at, updated_at, create_idempotency_key)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < $copies)
SELECT UUID(), l.seller_id, l.product_id, l.category_id, l.commodity, l.crop_year, l.harvest_season, l.certifications,
  l.region_text, l.quality_type, l.quality_moisture_percent, l.quality_foreign_matter_percent, l.quality_black_broken_percent,
  l.packaging_declaration, l.warehouse_location, l.estimated_availability_date, NULL, l.product_name,
  l.cover_image_url, l.quantity, l.quantity_unit, l.quantity_available, l.min_order_quantity, l.price_floor, l.currency,
  l.delivery_deadline, l.status, l.close_reason, l.created_at, NOW(6), NULL
FROM listings l CROSS JOIN seq WHERE l.status = 'AVAILABLE';
SQL
"${db[@]}" -e "SELECT COUNT(*) AS listings FROM listings" 2>/dev/null
