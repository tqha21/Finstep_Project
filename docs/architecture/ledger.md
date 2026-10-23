# Thu/chi, chuyển ví và lịch sử — API đã triển khai

Tất cả endpoint cần phiên đăng nhập đã xác minh. POST/PUT/DELETE cần CSRF header. Tiền là chuỗi nguyên dương VND, từ "1" tới "999999999999999". Ngày ISO-8601 có offset, không ở tương lai, không trước mốc số dư ban đầu của ví liên quan. Không nhận user_id từ client.

## Thu/chi

| Method | URL | Kết quả |
| --- | --- | --- |
| POST | /api/v1/transactions | 201 mới, 200 nếu replay; cần Idempotency-Key |
| GET | /api/v1/transactions/{id} | 200 chi tiết, 404 nếu không thuộc owner/đã xóa |
| PUT | /api/v1/transactions/{id} | 200 thay toàn bộ trường nghiệp vụ |
| DELETE | /api/v1/transactions/{id} | 204 soft delete; lặp lại cùng ID vẫn 204 |
| GET | /api/v1/transactions | Lịch sử `{items,nextCursor}` |

JSON cho POST/PUT:

```json
{
  "walletId": "UUID ví",
  "categoryId": "UUID danh mục",
  "type": "EXPENSE",
  "amount": "45000",
  "occurredAt": "2026-02-01T12:00:00+07:00",
  "note": "Ăn trưa"
}
```

INCOME/EXPENSE phải khớp loại danh mục. Ví/danh mục đều cùng chủ và chưa archive. note tùy chọn, tối đa 1000 ký tự; PUT thiếu note sẽ xóa ghi chú. PUT cần đủ trường bắt buộc, không hỗ trợ PATCH một phần. Có thể đổi ví/danh mục/loại/số tiền/thời điểm bằng PUT; id và chủ sở hữu không thay đổi. Không sửa được bản ghi đã xóa. Có thể xóa bản ghi cũ kể cả ví/danh mục archive; không có API khôi phục.

Phản hồi thêm id và deleted; amount luôn là chuỗi. Số dư ví lấy từ lịch sử còn hiệu lực nên sửa/xóa phản ánh ngay, không tích lũy sai số từ cập nhật current_balance.

## Chuyển ví

Cùng bộ method POST/GET/PUT/DELETE ở `/api/v1/transfers` và `/api/v1/transfers/{id}`. JSON:

```json
{
  "fromWalletId": "UUID ví nguồn",
  "toWalletId": "UUID ví đích",
  "amount": "100000",
  "occurredAt": "2026-02-01T12:00:00+07:00",
  "note": "Rút tiền mặt"
}
```

Hai ví khác nhau, cùng chủ, còn hoạt động; ngày hợp lệ đối với cả hai. Lưu một bản ghi nguồn/đích trong một transaction DB, không tạo hai khoản thu/chi giả. Tổng tiền hai ví không thay đổi. PUT có thể đổi nguồn/đích; toàn bộ tác động cũ được thay bằng tác động mới. Đây là công cụ ghi chép, cho phép số dư âm và không thực hiện chuyển tiền ngân hàng.

## Chống tạo trùng

Mỗi POST thu/chi/chuyển ví cần `Idempotency-Key`: 1–128 ký tự ASCII chữ/số hoặc `._:-` (khuyến nghị UUID). Namespace chung cho cả hai loại trong từng tài khoản. Dùng key mới cho mỗi ý định tạo mới; retry giữ nguyên key và nội dung. Khác endpoint hoặc khác nội dung với cùng key trả 409.

Hash SHA-256 có tên operation và các trường nghiệp vụ theo thứ tự cố định; thời điểm chuẩn hóa về UTC, nên hai offset biểu diễn cùng instant được xem là cùng nội dung. Key/hash/resource_id và bản ghi tài chính commit hoặc rollback cùng nhau. Không lưu raw payload tài chính vào bảng key. Request nghiệp vụ/DB lỗi không giữ key; có thể sửa rồi gửi lại cùng key sau lỗi.

Replay trả **trạng thái hiện tại của cùng resource ID** và HTTP 200. Nếu đã sửa, trả bản đã sửa; nếu đã xóa, trả deleted=true. Không dựng lại hoặc tạo mới bản ghi đã xóa. GET thông thường vẫn trả 404 cho bản đã xóa. Đây không phải snapshot phản hồi tạo ban đầu. Chưa tự hết hạn key; chỉ đổi retention khi có chính sách rõ ràng.

Mọi mutation ledger khóa owner row `FOR UPDATE`: một tài khoản ghi tuần tự ở beta, tài khoản khác vẫn độc lập. Ví/danh mục được khóa chia sẻ trong lúc validation để phối hợp với thao tác archive. Chuyển ví khóa ví theo thứ tự cố định. Cần đo hiệu năng trước khi tăng concurrency/đổi chiến lược khóa.

## Lịch sử và cursor

GET hai collection có `from`, `to`, `walletId`, `size` (mặc định 20, từ 1 đến 100), `cursor`. `/transactions` thêm `categoryId` và `type`. `/transfers?walletId=...` khớp cả nguồn hoặc đích.

Khoảng thời gian `[from,to)`; nếu có cả hai thì from < to. Sort occurred_at DESC, id DESC, lấy size+1 để tính nextCursor. Trả nextCursor=null khi hết. Cursor chứa cặp thời điểm/UUID và dùng lại nguyên văn; giữ nguyên các filter khi lấy trang tiếp. Tất cả truy vấn vẫn ràng buộc owner, kể cả khi client sửa cursor. Cursor không phải token xác thực.

Không lặp/mất bản ghi có cùng timestamp khi dữ liệu không đổi. Đây không phải snapshot xuyên nhiều HTTP request: sửa/xóa/thêm giữa các trang có thể làm thay đổi lịch sử. Chưa gộp hai collection thành một timeline. Báo cáo tháng và CSV đã triển khai; xem reporting.md.

Lỗi: validation/header/cursor/range không hợp lệ 400; chưa đăng nhập 401; thiếu CSRF 403; tài nguyên không tồn tại/khác chủ 404; archive hoặc key xung đột 409. Lỗi không trả ghi chú/số tiền của người khác.

## Kiểm thử

8 test integration trên PostgreSQL thật: tính số dư qua thu/chi/chuyển và sửa/xóa; replay/different hash/cross-operation/user scope/offset chuẩn hóa; rollback khi validation/DB thất bại; chống IDOR với ID và FK; CSRF/amount/date/archive; cursor khi timestamp bằng nhau và filter nửa mở; simultaneous retry tạo đúng một giao dịch; chuyển hai chiều đồng thời bảo toàn tiền.

Bản JAR đã smoke test qua SMTP Mailpit và HTTP: thu/chi/chuyển ví, replay, cursor, sửa/xóa và đối chiếu số dư. Database phát triển đã nâng cấp V3 → V4 thành công.

Budget/report/CSV đã triển khai. Còn cần export đầy đủ tài khoản, đo tải, phát hiện/đối soát dữ liệu production, backup/restore, frontend và kiểm thử HTTPS trên staging.
