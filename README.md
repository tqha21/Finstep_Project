# Finstep · SmartExpense

Ứng dụng web quản lý tài chính cá nhân cho sinh viên và người mới đi làm.
Mục tiêu: public beta sau 16 tuần, một người phát triển; ưu tiên ghi thu chi nhanh và số liệu chính xác.

## Trạng thái

Đã triển khai nền tảng PostgreSQL và khung Spring Boot Java 21: Flyway, health/readiness, cấu hình bảo mật mặc định chặn API, kiểm thử tích hợp PostgreSQL và CI. Chưa triển khai đăng nhập, API nghiệp vụ, frontend hoặc production. Không đưa dữ liệu người dùng thật vào phiên bản này.

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
./mvnw spring-boot:run
```

Mặc định backend kết nối `jdbc:postgresql://127.0.0.1:5432/finstep`, user `finstep`.
Có thể đổi bằng `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `PORT`, `SERVER_ADDRESS`. Java không tự đọc file `.env` của Docker Compose. Không có mật khẩu database mặc định trong cấu hình ứng dụng.

Health: `curl --fail http://127.0.0.1:8080/actuator/health/readiness` trả `{"status":"UP"}` khi database hoạt động. Không hiển thị chi tiết kết nối. API nghiệp vụ chưa tồn tại; mọi đường dẫn ngoài health bị chặn, chưa phải authentication hoàn chỉnh.

Trong cloud workspace có home chỉ đọc, dùng `MAVEN_USER_HOME=/workspace/.cache/maven` và `-Dmaven.repo.local=/workspace/.cache/m2`. Nếu mạng yêu cầu proxy, cấu hình Maven settings theo proxy môi trường; không lưu credentials vào repository.

## Kiểm chứng hiện tại

- Bộ SQL integrity test đạt trên PostgreSQL 17.
- `./mvnw verify`: 3 integration tests đạt, không có test bỏ qua: migration chạy lần đầu/chạy lại, readiness có database, chặn truy cập API và management ngoài health.
- CI đã khai báo trên GitHub Actions; kết quả CI trên GitHub cần xem riêng sau khi push.

## Tài liệu

- [PRD và tiêu chí MVP](docs/requirements/prd.md)
- [Thiết kế dữ liệu, ERD và quyết định kiến trúc](docs/architecture/database.md)
- [Backlog và kế hoạch 16 tuần](docs/requirements/roadmap.md)
- [API dự kiến](docs/architecture/api.md)

Tên sản phẩm đang dùng SmartExpense theo bản kế hoạch; repository giữ tên Finstep_Project. Chưa publish hoặc deploy production. Lịch sử triển khai được ghi trong Git.
