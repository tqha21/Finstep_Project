# Ví và danh mục — API đã triển khai

Cần đăng nhập bằng phiên đã xác minh email. Gửi CSRF header cho mọi POST/PATCH (xem identity.md). Principal cung cấp owner; không nhận owner từ JSON. Mọi đọc/sửa lọc theo `(user_id,id)`; ID không tồn tại hoặc thuộc tài khoản khác cùng trả 404. JSON có trường không hỗ trợ hoặc sai kiểu trả 400.

## Ví

| Method | URL | Kết quả |
| --- | --- | --- |
| GET | /api/v1/wallets?includeArchived=false | Danh sách ví riêng; mặc định ẩn ví lưu trữ |
| GET | /api/v1/wallets/{id} | Chi tiết, kể cả ví đã lưu trữ |
| POST | /api/v1/wallets | 201, Location và ví mới |
| PATCH | /api/v1/wallets/{id} | 200, sửa tên và/hoặc trạng thái lưu trữ |

Body tạo ví:

```json
{
  "name": "Tiền mặt",
  "type": "CASH",
  "openingBalance": "2000000",
  "effectiveAt": "2026-01-01T00:00:00+07:00"
}
```

Tên trim, không rỗng, tối đa 100 ký tự. Type CASH/BANK/EWALLET; tiền tệ luôn VND.
Số dư ban đầu là chuỗi nguyên đồng: từ -999999999999999 đến 999999999999999, cho phép âm; không dùng JSON number, dấu phân cách hàng nghìn, số thập phân hoặc ký pháp mũ. effectiveAt cần offset và không được ở tương lai.

Phản hồi: `id, name, type, currency, archived, openingBalance, effectiveAt, balance`. Hai trường tiền luôn là chuỗi, giữ chính xác số nguyên lớn. Balance lấy từ view tổng hợp lịch sử, không lưu current_balance riêng. Ví legacy chưa có opening_balances có openingBalance="0", effectiveAt=null.

Tạo ví, số dư ban đầu và các danh mục mặc định nằm trong cùng transaction DB. POST tạo ví chưa có idempotency key: gửi lại có thể tạo ví mới; client tránh gửi lặp trong khi chờ phản hồi. Idempotency đã bắt buộc cho API giao dịch/chuyển ví.

PATCH chỉ nhận `name` và/hoặc `archived`:

```json
{"name":"Ví sinh hoạt","archived":true}
```

Có thể khôi phục bằng archived=false. Body rỗng trả 400. Số dư ban đầu, effectiveAt, currency và type cố định sau khi tạo; không có endpoint xóa ví. Lưu trữ không xóa hoặc làm thay đổi lịch sử/số dư. API giao dịch đã từ chối ghi mới/cập nhật vào ví archive; có thể soft delete giao dịch cũ khi ví archive.

## Danh mục

| Method | URL | Kết quả |
| --- | --- | --- |
| GET | /api/v1/categories?type=EXPENSE&includeArchived=false | Danh sách riêng; type tùy chọn INCOME/EXPENSE |
| GET | /api/v1/categories/{id} | Chi tiết riêng, kể cả lưu trữ |
| POST | /api/v1/categories | 201, Location và danh mục mới |
| PATCH | /api/v1/categories/{id} | 200, đổi tên và/hoặc archived |
| POST | /api/v1/categories/defaults | 200, tạo phần mặc định còn thiếu và trả danh sách mặc định |

Tạo danh mục: `{"name":"Học phí","type":"EXPENSE"}`. Tên không rỗng, tối đa 100 ký tự; loại không đổi sau khi tạo để bảo toàn lịch sử. Chỉ hỗ trợ lưu trữ/khôi phục, không xóa danh mục.

Phản hồi: `id, name, type, archived, templateKey`. templateKey=null với danh mục tự tạo. Có 9 template thuộc riêng mỗi người dùng: ăn uống, đi lại, nhà ở, mua sắm, sức khỏe, giải trí, chi tiêu khác, lương, thu nhập khác.

Tạo ví tự bổ sung danh mục mặc định nếu thiếu. Có thể gọi /defaults cho tài khoản cũ, gọi lại hoặc đồng thời; UNIQUE(user_id,template_key) + ON CONFLICT DO NOTHING giữ một bản cho mỗi template. Tên đã sửa và danh mục đã archive không bị ghi đè/khôi phục. Danh mục tự tạo có thể trùng tên: định danh bằng UUID.

Danh sách trả thứ tự created_at rồi id; hiện chưa phân trang do số ví/danh mục nhỏ ở beta. Lịch sử giao dịch sẽ có phân trang riêng. Không coi đây là API đã kiểm chứng cho số lượng danh mục không giới hạn.

## Kiểm chứng

6 integration tests trên PostgreSQL thật bao phủ tiền nguyên lớn/số âm, validation dữ liệu, quyền đọc/sửa giữa hai người dùng, CSRF, bảo toàn lịch sử khi archive, tên/default không bị ghi đè và seeding đồng thời. Cả bộ backend có 32 tests. Migration V3 được Flyway chạy sau V1/V2; không thay đổi migration đã áp dụng.
