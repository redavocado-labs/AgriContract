SET NAMES utf8mb4;
-- ==============================================================
-- DEV SEED: user_db
-- The ids are the fixed ids of the realm import (infra/keycloak/agricontract-realm.json).
-- ==============================================================

SET @buyer1_id    = '1de004cd-8493-463c-8f32-6c7b75bcbf16';
SET @buyer1_email = 'buyer1@test.com';
SET @seller1_id   = '1a5a2ddd-56b9-492a-b966-99f436e0fc85';
SET @seller1_email = 'seller1@test.com';
SET @inspector1_id    = '64a24041-1596-4474-b972-3b016f1a194d';
SET @inspector1_email = 'inspector1@test.com';

-- V11 (#344): services read the contact email from verified_email, so the seeded users need it set (#647).
-- inspector1 is a verified INSPECTOR without expiry: the Level 1.5 inspector of the golden-flow E2E (#149).
INSERT IGNORE INTO user_profiles (user_id, organization_name, role, email, phone, address, verification_status, verified_email, email_verified_at, created_at, updated_at)
VALUES
  (@buyer1_id,  'Công ty TNHH Mua Nông Sản Tám', 'BUYER',  @buyer1_email,  '0901234567', '123 Lê Lợi, Q1, TP.HCM',                'VERIFIED', @buyer1_email,  NOW(6), NOW(), NOW()),
  (@seller1_id, 'HTX Nông Nghiệp Xanh Cần Thơ',  'SELLER', @seller1_email, '0907654321', '456 Hùng Vương, Ninh Kiều, Cần Thơ',    'VERIFIED', @seller1_email, NOW(6), NOW(), NOW()),
  (@inspector1_id, 'Công ty Giám định Cà phê Đắk Lắk', 'INSPECTOR', @inspector1_email, '0912345678', '12 Lê Duẩn, Buôn Ma Thuột', 'VERIFIED', @inspector1_email, NOW(6), NOW(), NOW());

-- Rows seeded before #647 have no verified email; INSERT IGNORE keeps them, so fill it in.
UPDATE user_profiles SET verified_email = email, email_verified_at = NOW(6)
WHERE user_id IN (@buyer1_id, @seller1_id, @inspector1_id) AND verified_email IS NULL;
