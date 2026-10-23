# Roadmap 16 tuần

Kế hoạch tính theo tuần tương đối, 20–25 giờ/tuần. Lịch 19/10/2026–07/02/2027 trong tài liệu là dự kiến; không coi tuần đã qua là đã hoàn thành.

| Tuần | Đầu ra |
| --- | --- |
| 1 | Phỏng vấn 8 người, tổng hợp pain points và hành động cốt lõi |
| 2 | Chốt PRD, user stories, acceptance criteria, backlog |
| 3 | Wireframe mobile/desktop, ERD, API contract |
| 4 | Spring Boot, PostgreSQL, Flyway, error handling, health |
| 5 | Session auth, verify/reset email, CSRF, rate limit |
| 6 | Ví, danh mục, số dư khởi tạo, thu/chi |
| 7 | Transfer, retry an toàn, ngân sách, query báo cáo |
| 8 | Next.js layout, login/onboarding, ví |
| 9 | Giao dịch, lịch sử, tích hợp API |
| 10 | Dashboard, ngân sách, báo cáo responsive |
| 11 | Integration/E2E, IDOR, concurrency, kiểm thử dữ liệu |
| 12 | Staging, CI/CD, HTTPS, metrics và logs |
| 13 | Backup/restore, export/delete, privacy, landing |
| 14 | Beta 10–15 người, sửa P0/P1 |
| 15 | Beta 20–30 người, tối ưu activation |
| 16 | Đánh giá retention, public beta nếu vượt release gates |

## Đã làm trong lần khởi tạo

- [x] PRD ban đầu và phạm vi VND-only.
- [x] ERD, migration tài chính, view số dư và constraints tenant.
- [x] Docker workflow và SQL integrity test; CI khai báo cùng lệnh test.
- [ ] Phỏng vấn và xác nhận giả thuyết với người dùng thật.
- [x] Khung Spring Boot, health/readiness và Flyway integration.
- [x] Session auth JDBC, xác minh/reset email, CSRF, rate limit và API hồ sơ.
- [x] Ví/danh mục: tạo, đọc, sửa tên, lưu trữ/khôi phục, số dư ban đầu, danh mục mặc định và kiểm thử chống IDOR.
- [x] Thu/chi/chuyển ví, sửa/xóa, idempotency transactional, kiểm thử đồng thời và lịch sử cursor.
- [x] Ngân sách và báo cáo theo tháng/múi giờ; CSV xác thực, chống công thức và giới hạn dữ liệu.
- [x] Đổi tên/múi giờ hồ sơ; report và budget dùng múi giờ đã lưu.
- [ ] Frontend và toàn bộ release gates.

## Sprint kỹ thuật tiếp theo

1. Frontend Next.js: layout mobile/desktop, đăng nhập/xác minh/reset và onboarding.
2. Ví/danh mục, giao dịch, ngân sách, dashboard/báo cáo, xử lý lỗi/empty/loading và xuất CSV qua frontend same-origin.
3. Hoàn thiện account/privacy/export đầy đủ/delete/feedback, staging SMTP/HTTPS, backup/restore và release gates.

Không triển khai ngân hàng/AI/PWA trước khi luồng nhập liệu, bảo mật và dữ liệu chính xác. Mỗi milestone chỉ hoàn thành sau khi có kiểm thử phù hợp.
