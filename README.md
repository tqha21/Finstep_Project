# Finstep · SmartExpense

Ứng dụng web quản lý tài chính cá nhân cho sinh viên và người mới đi làm.
Mục tiêu: public beta sau 16 tuần, một người phát triển; ưu tiên ghi thu chi nhanh và số liệu chính xác.

## Trạng thái

Đã triển khai nền tảng PostgreSQL và khung Spring Boot Java 21: Flyway, health/readiness, cấu hình bảo mật mặc định chặn API, kiểm thử tích hợp PostgreSQL và CI. Đã có API đăng ký, xác minh email, đăng nhập/đăng xuất, khôi phục mật khẩu và hồ sơ riêng. Đã có API ví, số dư ban đầu và danh mục riêng/mặc định. Đã có thu/chi/chuyển ví, sửa/xóa, lịch sử phân trang và chống tạo trùng. Đã có ngân sách tháng, báo cáo theo múi giờ, xuất CSV và đổi tên/múi giờ hồ sơ. Đã có frontend Next.js cho xác thực, ví đầu tiên và dashboard; hỗ trợ thu/chi, chuyển ví, sửa/xóa, quản lý nhiều ví/danh mục, lịch sử có bộ lọc, hồ sơ/múi giờ, ngân sách và CSV. Có xuất toàn bộ ghi chép JSON, xóa tài khoản có xác nhận mật khẩu và trang quyền riêng tư. Hồ sơ hỗ trợ đổi mật khẩu, thu hồi mọi phiên/reset cũ; phản hồi có retry không trùng, được xuất/xóa cùng tài khoản. Đã có cấu hình staging HTTPS, metrics private và backup mã hóa/restore cách ly được kiểm thử cục bộ. Có CLI S3 kiểm chứng readback/receipt và Alertmanager/webhook được diễn tập bằng dịch vụ riêng cục bộ, chưa cấu hình nhà cung cấp bên ngoài. Chưa triển khai production. Không đưa dữ liệu người dùng thật vào phiên bản này.

## Chạy kiểm thử

Yêu cầu Docker Engine đang chạy, Bash; không cần Java để chạy bộ kiểm thử dữ liệu.

```bash
bash scripts/test-database.sh
```

Lệnh tạo PostgreSQL 17 tạm, áp dụng migration vào database mới, chạy kiểm thử có assertion và xóa container khi kết thúc. Không tác động database phát triển. Lỗi SQL làm lệnh thất bại.

## Database phát triển

```bash
cp .env.example .env # chỉ thực hiện nếu chưa có .env; giữ cấu hình hiện hữu
# Có thể đổi mật khẩu và cổng trong .env trước khi chạy.
docker compose up -d --wait db
docker compose up -d mailpit
# Backend sẽ tự áp dụng migration bằng Flyway khi khởi động; không chạy SQL thủ công.
```

`docker compose stop db` dừng dịch vụ, giữ dữ liệu trong volume. Không chạy `down -v` nếu cần giữ dữ liệu. Spring Boot quản lý migration qua Flyway; không sửa migration đã áp dụng. Với database đã được tạo thủ công từ phiên bản cũ, hãy tạo database mới để chạy backend và giữ lại dữ liệu cũ. Không bật baseline-on-migrate để bỏ qua kiểm tra schema.

## Backend Java 21

Yêu cầu JDK 21, Docker cho integration tests, Internet ở lần tải dependencies đầu. Maven 3.9.11 được ghim qua wrapper.

```bash
cd backend
./mvnw --batch-mode --no-transfer-progress verify
# Đặt DB_PASSWORD bằng mật khẩu PostgreSQL trong .env (không commit mật khẩu).
export DB_PASSWORD='local-development-only' # chỉ khớp cấu hình local mẫu
export SESSION_COOKIE_SECURE=false # chỉ khi phát triển qua HTTP loopback
./mvnw spring-boot:run
```

Mặc định backend kết nối `jdbc:postgresql://127.0.0.1:5432/finstep`, user `finstep`.
Có thể đổi bằng `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `PORT`, `SERVER_ADDRESS`. Java không tự đọc file `.env` của Docker Compose. Không có mật khẩu database mặc định trong cấu hình ứng dụng.

Health: `curl --fail http://127.0.0.1:8080/actuator/health/readiness` trả `{"status":"UP"}` khi database hoạt động. Không hiển thị chi tiết kết nối. API identity xem trong [hướng dẫn xác thực](docs/architecture/identity.md); API ví/danh mục xem trong [hướng dẫn ví và danh mục](docs/architecture/wallets-categories.md). Cookie mặc định Secure; chỉ tắt Secure cho HTTP cục bộ.

Trong cloud workspace có home chỉ đọc, dùng `MAVEN_USER_HOME=/workspace/.cache/maven` và `-Dmaven.repo.local=/workspace/.cache/m2`. Nếu mạng yêu cầu proxy, cấu hình Maven settings theo proxy môi trường; không lưu credentials vào repository.

## Frontend Next.js

Yêu cầu Node.js 22–24 (đã kiểm tra Node 24), backend đang chạy và cookie Secure tắt **chỉ cho HTTP cục bộ**.

```bash
cd frontend
npm ci
npm run dev
```

Frontend bind loopback cổng 3000. Build chuẩn bị Next standalone và static/public assets; `npm run start` chạy server standalone. Trình duyệt gọi `/api/v1/*` qua Next rewrites tới backend cùng nguồn; không cần bật CORS. `API_ORIGIN` mặc định `http://127.0.0.1:8080`, chỉ cấu hình phía server. Nếu đổi origin, khởi động lại dev hoặc build lại production; dùng cùng cấu hình khi build/start. Các lệnh kiểm tra:

```bash
npm run lint
npm run build
npm run typecheck
npm run start
```

Nếu home chỉ đọc trong cloud, đặt `npm_config_cache=/workspace/.cache/npm`. Chi tiết màn hình và E2E xem [hướng dẫn frontend](docs/architecture/frontend.md).

## Kiểm chứng hiện tại

- Bộ SQL integrity test đạt trên PostgreSQL 17.
- `./mvnw test`: 50 integration tests đạt; JAR đã đóng gói bằng `verify`. Các kiểm tra gồm kiểm tra migration/readiness, đăng ký/xác minh, phiên cookie, CSRF, reset/revoke session, quyền hồ sơ, rate limit, token hết hạn và lỗi gửi mail; tạo/sửa/lưu trữ ví/danh mục, độ chính xác số tiền, chống IDOR và khởi tạo danh mục đồng thời; thu/chi/chuyển ví, số dư sau sửa/xóa, rollback, idempotency, gửi đồng thời và cursor cùng thời điểm; ngân sách, ranh giới tháng/DST, CSV an toàn và giới hạn 10.000 dòng; export đầy đủ có snapshot/cursor, xóa tài khoản nhiều phiên, chống IDOR, rollback và thao tác cạnh tranh; đổi mật khẩu nhiều phiên và phản hồi có idempotency, tenant/rate limit; metrics cổng riêng và ranh giới trusted proxy.
- Smoke test thực tế qua SMTP Mailpit đã xác minh đăng ký → email → xác minh → đăng nhập → hồ sơ → đăng xuất. Email chỉ gửi vào hộp thư thử nghiệm cục bộ. Bản chạy hiện tại cũng đã smoke test tạo ví/số dư, danh mục mặc định không trùng và lưu trữ ví. Smoke test ledger cũng đã xác minh thu/chi/chuyển ví, replay, cursor, sửa/xóa và đối chiếu số dư. Bản JAR cũng đã smoke test báo cáo tháng, cảnh báo ngân sách 80%, đổi múi giờ và tải CSV có xác thực.
- Frontend: TypeScript, ESLint, build production và **11/11 browser E2E đạt**; bao phủ tài khoản, dashboard, ví/danh mục, thu/chi/chuyển ví, sửa/xóa, ngân sách, CSV, retry, phân trang, DST/múi giờ, quyền riêng tư, export/xóa tài khoản nhiều phiên, đổi mật khẩu, phản hồi và mobile. Xem kết quả thực thi trong tài liệu frontend.
- Operations: HTTPS với CA được xác thực, Secure cookie, chống giả header IP, actuator không lộ qua proxy; backup AES-GCM/restore vào database cách ly giữ đúng số dư và trạng thái xóa/mật khẩu/khóa tài khoản. Prometheus/probe thật và kiểm thử rule đạt; 7/7 preflight guards đạt; 7/7 backup-store và 4/4 alert-config guards đạt. Diễn tập S3 roundtrip/decrypt, từ chối corruption/partial và delivery Bearer/retry/firing/resolved đã đạt. Chi tiết và giới hạn recovery xem runbook.
- Load regression: HTTPS riêng, 8 worker/8 tài khoản, 16.000 giao dịch lịch sử và 1.392 request đo được; đối chiếu số dư/báo cáo và replay đạt. Có resource limits, latency/error/restart gates và báo cáo JSON trên CI. Đây là phép thử ngắn cục bộ, chưa chứng minh capacity/SLO production; xem [kịch bản và giới hạn](docs/operations/load-testing.md).
- Beta metrics: CLI cohort tuần chỉ đọc, activation 7d/thời gian giao dịch đầu và D7 tạo thu/chi/chuyển ví; ẩn cohort nhỏ/chưa trưởng thành, role không đọc email/password/số tiền/ghi chú. Đã kiểm thử bằng dữ liệu tổng hợp PostgreSQL; chưa cài role trên database hiện hữu hoặc đo người dùng thật.
- CI đã khai báo trên GitHub Actions; kết quả CI trên GitHub cần xem riêng sau khi push.

Dừng backend trước khi đóng gói lại JAR đang chạy; khởi động lại bằng JAR mới sau khi build thành công.

## Tài liệu

- [PRD và tiêu chí MVP](docs/requirements/prd.md)
- [Thiết kế dữ liệu, ERD và quyết định kiến trúc](docs/architecture/database.md)
- [Backlog và kế hoạch 16 tuần](docs/requirements/roadmap.md)
- [API và trạng thái triển khai](docs/architecture/api.md)
- [Identity: API, bảo mật, email và giới hạn](docs/architecture/identity.md)
- [Xuất dữ liệu, xóa tài khoản và quyền riêng tư](docs/architecture/privacy.md)
- [Đổi mật khẩu và phản hồi](docs/architecture/account-feedback.md)
- [Ví và danh mục: API và quy tắc](docs/architecture/wallets-categories.md)
- [Thu/chi/chuyển ví và lịch sử](docs/architecture/ledger.md)
- [Ngân sách, báo cáo, múi giờ và CSV](docs/architecture/reporting.md)
- [Frontend và kiểm thử trình duyệt](docs/architecture/frontend.md)
- [Staging HTTPS, monitoring và backup/restore](docs/operations/staging-recovery.md)
- [S3 verified publication và Alertmanager](docs/operations/offsite-alerting.md)
- [Kiểm thử tải HTTPS và ngưỡng regression](docs/operations/load-testing.md)
- [Đo activation và D7 cho beta](docs/operations/beta-metrics.md)
- [ADR disaster recovery của trạng thái tài khoản](docs/decisions/001-disaster-recovery-account-state.md)

Tên sản phẩm đang dùng SmartExpense theo bản kế hoạch; repository giữ tên Finstep_Project. Chưa publish hoặc deploy production. Lịch sử triển khai được ghi trong Git.
