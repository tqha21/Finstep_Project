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
- [ ] Session auth và API nghiệp vụ.
- [ ] Frontend và toàn bộ release gates.

## Sprint kỹ thuật tiếp theo

1. Khởi tạo Spring Boot Java 21 + Flyway + Actuator, health không lộ thông tin nhạy cảm.
2. Session auth, CSRF, BCrypt/Argon2id, email verify/reset với token hash và thời hạn.
3. Endpoint ví và danh mục; principal-scoped queries; test hai người dùng.
4. Thu/chi/chuyển ví, idempotency transactional, kiểm thử concurrent retry và báo cáo tháng.

Không triển khai ngân hàng/AI/PWA trước khi luồng nhập liệu, bảo mật và dữ liệu chính xác. Mỗi milestone chỉ hoàn thành sau khi có kiểm thử phù hợp.
