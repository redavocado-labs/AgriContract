# Phase F — Màn Nhật ký bằng chứng (D97)

Frontend dùng API Gateway qua Axios. Chế độ `VITE_MOCK_API=bf04` hỗ trợ giả lập đầy đủ nhật ký hợp đồng nguyên vẹn, chuỗi hỏng, bản ghi đọc lỗi và ghi vết lượt xem `AUDIT_VIEWED` trong DEV.

## Thao tác và API

| Màn / Thao tác | Endpoint | Backend thật và mock |
|---|---|---|
| Buyer/Seller — tab "Bằng chứng" trong chi tiết hợp đồng | `GET /api/v1/audit/contracts/{id}/records` | Mock đầy đủ (sắp xếp mới nhất trên cùng, không hiển thị `prevHashSubject`/`prevHashGlobal`/`globalSeq`). Cần route gateway `#630`. |
| Buyer/Seller — tự kiểm tra & "Kiểm tra lại" chuỗi hợp đồng | `GET /api/v1/audit/contracts/{id}/verification` | Mock hỗ trợ `INTACT` và `BROKEN` (`RECORD_HASH_MISMATCH`, `SUBJECT_LINK_BROKEN`), tooltip khi `NOT_ANCHORED`. |
| ADMIN — tra cứu theo hợp đồng (`/admin/audit`) | `GET /api/v1/admin/audit/contracts/{id}/records`, `GET .../verification` | Gateway đã có route `/api/v1/admin/audit/**`. Mock thêm cột `globalSeq`, `prevHashSubject`, `prevHashGlobal` và tự ghi vết `AUDIT_VIEWED`. |
| ADMIN — danh sách toàn hệ thống & lọc chủ thể | `GET /api/v1/admin/audit/records?subjectType=&page=&size=20` | Mock phân trang 20, lọc `CONTRACT`/`USER_PAIR`/`SYSTEM`, click hợp đồng nhảy sang tab Theo hợp đồng, xử lý bản ghi null field ("Bản ghi không đọc được"). |
| ADMIN — kiểm tra toàn chuỗi | `GET /api/v1/admin/audit/verification` | Mock hỗ trợ `INTACT` và `BROKEN` (`GLOBAL_LINK_BROKEN`). |

## BE cần bổ sung / lưu ý tích hợp

1. **Gateway route `#630`:** `backend/api-gateway/src/main/resources/application.yml` hiện mới route `/api/v1/admin/audit/**` (ADMIN) và `/api/v1/security/audit-hash`; chưa có route `/api/v1/audit/**` cho `BUYER`/`SELLER`.
2. **OpenTimestamps Anchor `#256`:** `audit-service` hiện trả `anchorStatus: "NOT_ANCHORED"`. FE hiển thị `"Đóng dấu thời gian: chưa có"` kèm tooltip giải thích giới hạn theo D97.

## Kiểm thử

| Lệnh | Kết quả |
|---|---|
| `npm run lint` | 0 lỗi (4 cảnh báo Fast Refresh cũ trong `shared/ui`). |
| `npm run typecheck` | Pass (`tsc -b` với `noUncheckedIndexedAccess`). |
| `npm run check:architecture` | Pass (199 modules, `audit` cô lập đúng boundary). |
| `npm run check:contracts` | Pass (3/3). |
| `npm test` | 15/15 files, 167/167 tests pass (gồm `audit.test.ts` và `audit-components.test.tsx`). |
| `npm run build` & `npm run check:bundle` | Pass. |
| `npm run test:e2e` | 21/21 Playwright QA tests pass. |

## Ảnh chụp (lưu ngoài repo tại `~/Documents/agricontract-fe-evidence-log-screenshots/`)

- `01-party-intact-desktop.png`, `02-party-intact-mobile.png`: Buyer/Seller tab Bằng chứng (chuỗi nguyên vẹn `INTACT`, `NOT_ANCHORED`).
- `03-party-detail-modal-desktop.png`: Chi tiết bản ghi bằng chứng (full hash + bảng `content`).
- `04-party-broken-desktop.png`, `05-party-broken-mobile.png`: Chuỗi hỏng (`BROKEN`, tô đỏ bản ghi hỏng + lý do tiếng Việt).
- `06-admin-contract-desktop.png`, `07-admin-contract-mobile.png`: ADMIN tra cứu theo hợp đồng (có `Seq`, `Nối cùng chủ thể`, `Nối toàn hệ thống`).
- `08-admin-global-desktop.png`, `09-admin-global-mobile.png`: ADMIN toàn hệ thống (bản ghi `AUDIT_VIEWED`, bản ghi lỗi đọc, kiểm tra toàn chuỗi `GLOBAL_LINK_BROKEN`).
