# Identity — hợp đồng API đã triển khai

## Session và CSRF

API same-origin dự kiến; chưa mở CORS cho frontend khác origin. Session lưu PostgreSQL qua Spring Session JDBC, idle timeout 30 phút. Cookie `SESSION`: HttpOnly, SameSite=Lax, mặc định Secure. Đổi `SESSION_COOKIE_SECURE=false` chỉ cho HTTP loopback khi phát triển.

1. GET `/api/v1/auth/csrf` với cookie jar nhận `{token, headerName}` và cookie phiên.
2. Mỗi POST gửi JSON, cookie và header có tên/giá trị đã nhận. Thiếu/sai CSRF trả 403, kể cả login/register/logout.
3. Sau login/logout lấy CSRF mới; login xoay ID phiên chống session fixation.
4. GET `/api/v1/me` lấy chủ tài khoản từ phiên, không nhận `user_id` từ người gọi. Chưa đăng nhập trả 401.

Không lưu cookie/token vào localStorage hoặc logs. API trả `Cache-Control: no-store`. Các đường dẫn chưa được triển khai mặc định bị từ chối.

## Endpoint và JSON

| Endpoint POST | Body | Thành công |
| --- | --- | --- |
| /api/v1/auth/register | email, password, fullName | 202; tài khoản mới gửi email xác minh |
| /api/v1/auth/login | email, password | 204 + cookie; cần email đã xác minh và tài khoản ACTIVE |
| /api/v1/auth/logout | không cần body | 204, hủy phiên hiện tại và cookie |
| /api/v1/auth/verify-email | token | 204 |
| /api/v1/auth/resend-verification | email | 202 |
| /api/v1/auth/forgot-password | email | 202 |
| /api/v1/auth/reset-password | token, password | 204, đổi mật khẩu và hủy mọi phiên |

GET `/api/v1/me`: `{id, email, fullName}`; không trả password hash/token.
Đăng ký email trùng, gửi lại xác minh và quên mật khẩu đều có phản hồi 202 rỗng để hạn chế dò tài khoản. Sai credentials, chưa xác minh hoặc bị vô hiệu đều trả cùng lỗi 401. Validation/token không hợp lệ trả 400; SMTP lỗi trả 503 và rollback thay đổi tài khoản/token của request đó.

Mật khẩu ít nhất 12 ký tự, tối đa 72 byte UTF-8; BCrypt cost 12. Email trim/lowercase. Token ngẫu nhiên 32 byte, base64url 43 ký tự; DB chỉ lưu SHA-256. VERIFY hết hạn 24 giờ, RESET 30 phút. Tiêu thụ token khóa chủ tài khoản, kiểm tra lại chưa dùng và hạn dùng; token cùng mục đích còn lại bị hủy. Reset tăng auth_version để cả phiên tạo cạnh tranh với reset cũng bị từ chối ở request kế tiếp. Mỗi request có phiên kiểm tra lại trạng thái ACTIVE, xác minh và auth_version.

## Email cục bộ

Chạy `docker compose up -d mailpit`. Backend mặc định SMTP `127.0.0.1:1025`; Mailpit lưu email thử nghiệm, API/UI ở cổng 8025 chỉ bind loopback. Email chứa mã để gọi API xác minh/reset; chưa có màn hình nhập mã hoặc đường dẫn frontend. Không gửi email tới dịch vụ bên ngoài trong smoke test.

SMTP thật được cấu hình qua `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH=true`, `SMTP_STARTTLS=true`, `MAIL_FROM`. Credentials chỉ nhập qua môi trường/secret manager, không commit. Đây là cấu hình chưa được kiểm chứng trên nhà cung cấp bên ngoài.

## Rate limiting

Dùng PostgreSQL để chia sẻ giới hạn giữa các instance, không reset bộ đếm khi request nghiệp vụ rollback:

- 30 POST identity/phút theo địa chỉ kết nối, kể cả request sai CSRF; vượt trả 429 và Retry-After 60.
- Login: 10 lần/15 phút theo email chuẩn hóa; register/resend/forgot: 5 lần/15 phút theo email và mục đích.
- Bucket chỉ lưu hash, dọn cửa sổ cũ hơn một ngày. Không tin X-Forwarded-For tùy ý. Cần cấu hình trusted proxy/rate limit tại ingress trước production để tránh mọi người dùng bị gom vào một địa chỉ proxy.

## Kiểm thử và giới hạn

`./mvnw verify` chạy PostgreSQL thật qua Testcontainers. Test email dùng adapter ghi token trong bộ nhớ test; smoke test riêng đã chạy với SMTP Mailpit thật. Kiểm tra CSRF, xác minh bắt buộc, token hết hạn/một lần, duplicate email, Unicode/password byte limits, rate limit, cookie rotation, HttpOnly, logout/replay cookie, reset nhiều phiên, disabled account, hồ sơ của hai người dùng, rollback khi mail lỗi.

Chưa sẵn sàng mở public beta: chưa có UI, API giao dịch/ngân sách/báo cáo, đổi mật khẩu trong hồ sơ, export/delete, audit, backup/restore, kiểm thử đồng thời reset/login và cấu hình hosting thực. Cần xác minh session/CSRF/Secure cookie qua HTTPS trên staging và chính sách retention token.

SMTP hiện gửi đồng bộ trong transaction; timeout bị giới hạn. Nếu SMTP chấp nhận email nhưng commit DB thất bại, mã trong email không dùng được và người dùng cần resend. Trước khi vận hành lớn cần thiết kế giao hàng đáng tin cậy; không lưu raw reset token tùy tiện trong outbox/log. Phản hồi SMTP lỗi và thời gian phản hồi vẫn có thể cho tín hiệu về tài khoản; không tuyên bố chống enumeration tuyệt đối.
