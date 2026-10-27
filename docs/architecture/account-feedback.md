# Đổi mật khẩu và phản hồi

## Mật khẩu

`POST /api/v1/me/password` cần session đã xác minh và CSRF. Body gồm `currentPassword`, `newPassword`; từ chối trường ngoài DTO (không có owner/email đích). Mật khẩu mới từ 12 ký tự, tối đa 72 byte UTF-8 và khác mật khẩu hiện tại; không trim mật khẩu. Mật khẩu hiện tại cũng bị từ chối nếu quá 72 byte để tránh BCrypt cắt ngầm. Không log hoặc trả lại mật khẩu.

Giới hạn 5 yêu cầu hợp lệ về cấu trúc/15 phút/tài khoản, bao gồm sai mật khẩu hoặc transaction rollback; bộ đếm riêng commit trước transaction nghiệp vụ để không giữ connection trong lúc chờ connection khác.

Khóa users FOR UPDATE theo UUID **và auth_version** hiện tại; kiểm tra ACTIVE/email đã xác minh, BCrypt mật khẩu cũ, rồi cập nhật hash cost 12 và tăng auth_version. Cùng transaction: tiêu thụ mọi RESET token chưa dùng và xóa spring_session theo principal (session attributes cascade). Nếu DB lỗi, hash/version/token/session cùng rollback. Hai lệnh đổi mật khẩu cạnh tranh chỉ một lệnh có thể dùng auth_version cũ thành công. Phiên đăng nhập tạo muộn với principal/version cũ bị AccountSessionFilter từ chối ở yêu cầu tiếp theo.

Thành công 204; invalidate phiên hiện tại, clear SecurityContext, hết hạn SESSION cookie. Tất cả thiết bị phải đăng nhập lại bằng mật khẩu mới. Sai mật khẩu hiện tại hoặc mật khẩu mới không hợp lệ 400, hết phiên/stale principal 401, thiếu CSRF 403, vượt giới hạn 429. Chưa đổi email hay tiền tệ qua endpoint này.

Hồ sơ có form mật khẩu hiện tại/mới/nhập lại; lỗi giữ form. Không đưa mật khẩu vào URL hoặc localStorage. Khi 204 chuyển sang `/password-changed`. Nếu lỗi mạng/5xx/401/403, kiểm tra `/me`: khi không còn phiên, chuyển `/password-change-unknown`, hướng dẫn thử mật khẩu mới hoặc khôi phục qua email; không báo chắc chắn thất bại hay tự gửi lặp. Nếu còn phiên hoặc không kiểm tra được, hiển thị chưa rõ kết quả.

## Phản hồi

- `POST /api/v1/feedback`: authenticated + CSRF + `Idempotency-Key` ASCII `[A-Za-z0-9._:-]{1,128}`. Body `{ "type": "BUG|IDEA|OTHER", "message": "..." }`. Nội dung 10–2.000 ký tự (đếm UTF-16 theo Java/HTML), ít nhất 10 sau strip; lưu plain text.
- 201 khi mới, 200 khi replay cùng key/nội dung sau strip, 409 khi dùng key với nội dung khác. Key riêng cho feedback, không dùng namespace ledger. Không trả request_key/hash hay user_id/email. Không có endpoint GET feedback theo UUID hoặc xem người khác.
- `GET /api/v1/feedback`: chỉ 20 mục mới nhất của principal, thứ tự created_at DESC/id DESC. Query owner của client không ảnh hưởng scope. Tệp xuất cá nhân có toàn bộ phản hồi, kể cả mục cũ ngoài 20 mục.
- 5 POST/15 phút/tài khoản, kể cả retry. Mutation khóa users theo id/auth_version như mật khẩu, tránh tạo lại dữ liệu cho người đã xóa tài khoản hoặc dùng phiên bị thu hồi. UNIQUE(user_id,request_key) và transaction bảo đảm hai retry đồng thời tạo đúng một row.

Flyway V5 thêm bảng feedback, FK users restrict và index owner/date; không sửa migration đã áp dụng. Phản hồi thuộc dữ liệu cá nhân: export JSON schemaVersion 1 được bổ sung collection `feedback` gồm id/type/message/createdAt (mở rộng cộng thêm, không đổi trường cũ); endpoint xóa tài khoản xóa feedback trước users và dọn bucket feedback/password-change. Không xuất khóa/hash nội bộ.

`/app/feedback` có form và danh sách riêng, sidebar và header mobile có lối vào. Hiển thị message bằng React text, không render HTML. Giữ body/key khi lỗi rồi retry chưa sửa nội dung; thay nội dung tạo key mới. Không giữ key qua reload/rời trang; trường hợp chưa rõ kết quả nên thử lại trong form hiện tại hoặc kiểm tra danh sách trước khi gửi lại. Thành công reset form và tải lại danh sách, lỗi tải danh sách có nút thử lại.

Đây là kênh **lưu phản hồi trong ứng dụng**, chưa có console xử lý cho người vận hành, SLA, email trả lời, attachments hoặc gửi tới dịch vụ ngoài. Người dùng được nhắc không đưa mật khẩu/mã xác minh/dữ liệu tài chính nhạy cảm vào nội dung. Nội dung chưa có bộ lọc tự động xóa thông tin nhạy cảm; xóa tài khoản sẽ xóa cả phản hồi. Không tuyên bố có người đang trực hỗ trợ.

## Kiểm thử

AccountFeaturesIntegrationTest dùng PostgreSQL thật: yêu cầu session/CSRF, password UTF-8/độ dài/khác mật khẩu cũ, owner override, hai phiên, RESET cũ, hash cost, throttle rollback, hai lệnh đổi mật khẩu cạnh tranh, rollback khi xóa session lỗi; feedback tenant/replay/conflict/plain text, validation, hai retry đồng thời, throttle, danh sách 20 và export toàn bộ. PrivacyIntegrationTest cũng tạo feedback và kiểm tra xóa dữ liệu tenant/rollback cùng các bảng còn lại.

Browser E2E thêm ba luồng: xác nhận password/mật khẩu cũ/UTF-8 và thu hồi nhiều phiên/reset cũ; mobile feedback, mất phản hồi sau commit rồi retry cùng key, không thực thi HTML và export JSON; mất phản hồi sau đổi mật khẩu với trang chưa rõ kết quả và đăng nhập được bằng mật khẩu mới.
