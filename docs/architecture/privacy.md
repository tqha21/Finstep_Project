# Quyền riêng tư, xuất dữ liệu và xóa tài khoản

## API

- `GET /api/v1/me/export`: cần phiên ACTIVE, email đã xác minh, auth_version hiện hành. Không nhận owner ID; mọi truy vấn lấy UUID từ principal. Trả attachment `finstep-personal-data.json`, UTF-8 JSON, `Cache-Control: no-store`. Giới hạn 3 lần/5 phút/tài khoản, sau đó 429; dùng cùng bộ đếm PostgreSQL có transaction riêng, kiểm tra trước khi mở transaction export/xóa để tránh giữ connection trong lúc chờ connection khác.
- `DELETE /api/v1/me`: cần phiên, CSRF và JSON `{ "password": "<mật khẩu hiện tại>", "confirmation": "XÓA TÀI KHOẢN" }`. Không nhận email/UUID đích; trường ngoài DTO bị từ chối. Mật khẩu được đối chiếu BCrypt, không ghi log hay trả lại. Sai mật khẩu 400, sai xác nhận/body 400, thiếu CSRF 403, không còn tài khoản/phiên hợp lệ 401. Giới hạn 5 lần kiểm tra mật khẩu/15 phút/tài khoản, kể cả rollback; lần vượt trả 429.
- Thành công 204 sau khi commit, clear security context, invalidate phiên hiện tại và hết hạn cookie SESSION. Không cung cấp API khôi phục tài khoản.

## Tệp xuất phiên bản 1

Envelope có `schemaVersion: 1`, `exportedAt`, `account` và bảy collection: `wallets`, `categories`, `transactions`, `transfers`, `budgets`, `budgetItems`, `feedback`. Tiền là chuỗi số nguyên VND; timestamp có offset, month là ngày đầu tháng. Tham chiếu UUID giữa các collection được giữ lại.

`account` gồm id/email/fullName/currency/timezone/emailVerifiedAt/createdAt. Ví gồm số dư ban đầu, effectiveAt, số dư hiện tại, loại, trạng thái archive và ngày tạo. Các khoản thu chi/chuyển ví gồm cả `deletedAt` và ghi chú nguyên gốc; budgets/items gồm month và hạn mức. Collection feedback gồm id/type/message/createdAt, không có khóa/hash retry. Đây là mở rộng cộng thêm của schemaVersion 1. Không lọc mất dữ liệu archive, không giới hạn vào tháng hiện tại hoặc 10.000 dòng như CSV. CSV là báo cáo thu/chi đã lọc, JSON là bản sao toàn bộ ghi chép; chưa có chức năng nhập lại.

Không đưa password/hash, auth_version, mã xác minh/reset (kể cả hash), cookie/thuộc tính phiên, bucket rate limit, khóa/hash idempotency vào tệp.

Service xuất **đồng bộ trong transaction REPEATABLE_READ read-only**. JDBC PreparedStatement fetchSize=200 sử dụng cursor PostgreSQL; Jackson ghi từng row thay vì giữ toàn bộ collection trên server. Một snapshot bao phủ hồ sơ, số dư và các collection. Không khóa dài hạn các thao tác ghi; một bản xuất bắt đầu trước thao tác xóa tài khoản vẫn có thể hoàn tất với snapshot cũ, còn yêu cầu mới sau xóa bị từ chối.

Không bật tự đóng nội dung JSON khi lỗi. Nếu stream lỗi, tệp thiếu phần kết thúc và không phải JSON hợp lệ. Giao diện đọc/kiểm tra JSON và các collection bắt buộc trước khi tạo download, không báo tải thành công với tệp bị cắt. Trình duyệt vẫn cần bộ nhớ cho Blob/text đã tải; export lớn/chậm và giới hạn timeout tại ingress phải được đo trước beta.

## Xóa nguyên tử

Khóa row users FOR UPDATE, kiểm tra ACTIVE/email_verified_at/auth_version và mật khẩu bên trong cùng transaction. Khóa cùng chủ tài khoản như ledger/budget; FK key-share của các thao tác tạo ví/danh mục cũng xếp hàng với khóa này.

Xóa theo thứ tự FK: feedback → budget_items → budgets → idempotency_requests → transactions → transfers → opening_balances → wallets → categories → identity_tokens; xóa spring_session theo principal UUID (attributes cascade), rồi users. Dọn các bucket theo email/tài khoản, kể cả feedback/password-change. Không sửa các FK tài chính thành cascade; không đụng bảng/người dùng khác. Lỗi DB ở bất cứ bước nào rollback toàn bộ, gồm dữ liệu và session. Mỗi request kiểm tra sự tồn tại của users; đăng nhập đang chạy cạnh tranh dù lưu một phiên muộn cũng không có quyền đọc/ghi sau khi tài khoản đã mất.

Bộ đếm IP chia sẻ không xóa theo tài khoản. Email đã có trong Mailpit/SMTP, tệp download, logs hoặc bản sao do người vận hành tạo ngoài DB không thể bị thu hồi bằng endpoint này. Chưa có lịch giữ/xóa bản sao lưu production; không tuyên bố xóa các bản sao ngoài hệ thống. Release gate staging/backup/restore phải xác định cả cách tránh khôi phục lại tài khoản đã xóa.

## Giao diện

Hồ sơ có khu vực Dữ liệu và quyền riêng tư, tải JSON, liên kết `/privacy` và hộp thoại xóa cần mật khẩu/xác nhận. Có hủy, khóa nút và Escape khi đang gửi, giữ form khi mật khẩu sai. Form POST, không đưa mật khẩu vào URL/localStorage. 204 đưa về trang công khai xác nhận xóa; ProtectedShell và dữ liệu người dùng được unmount.

Nếu mất phản hồi sau DELETE hoặc nhận 401, kiểm tra GET /me: khi phiên đã mất, chuyển trang công khai “Chưa xác nhận được kết quả xóa”, không khẳng định thao tác thất bại hoặc tự tạo lại tài khoản. Nếu vẫn còn phiên hoặc mất kết nối khi kiểm tra, báo chưa rõ kết quả và cho kiểm tra/thử lại. Endpoint xóa không có idempotency sau khi tài khoản biến mất; retry hợp lệ chỉ khi tài khoản còn tồn tại.

`/privacy` mô tả cơ chế hiện có và giới hạn bản phát triển, có thể đọc từ landing, đăng ký hoặc hồ sơ. Chưa phải chính sách vận hành hoàn chỉnh cho beta: còn thiếu đầu mối hỗ trợ, nhà cung cấp hosting/SMTP thực tế, retention/backup và quy trình xử lý yêu cầu ngoài ứng dụng.

## Kiểm thử

PrivacyIntegrationTest dùng PostgreSQL thật: hai tenant, field whitelist, tiền nguyên lớn, ghi chú Unicode/quote/newline, archive/deleted, thiếu session/CSRF, sai mật khẩu/xác nhận/owner override, throttle, xóa toàn bộ/session/cookie cũ, rollback bằng trigger lỗi, snapshot stream khi cập nhật song song, và ledger tạo cạnh tranh với xóa. Browser E2E dùng SMTP/API/DB tách riêng: tải JSON, từ chối stream bị cắt, hủy/sai mật khẩu, xóa nhiều phiên, mã reset cũ vô hiệu, đăng nhập lại bị từ chối và mất phản hồi sau commit.

Diễn tập backup/restore đã có trong [runbook staging/recovery](../operations/staging-recovery.md): backup mã hóa không chứa phiên/token; restore cách ly đối chiếu source hiện tại, bỏ tài khoản đã xóa và giữ credentials/profile/status mới. Tool không hỗ trợ mất toàn bộ source hoặc tự promote. Journal độc lập, offsite retention và freeze/cutover vẫn là gate vận hành.
