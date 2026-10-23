# Ngân sách, báo cáo và CSV — API đã triển khai

Tất cả endpoint dùng owner từ phiên xác thực. PUT ngân sách và PATCH hồ sơ cần CSRF. Tiền/tổng tiền luôn là chuỗi số nguyên VND; tỷ lệ phần trăm là chuỗi có hai chữ số thập phân. Không dùng float để tính tiền.

## Múi giờ hồ sơ

GET `/api/v1/me` thêm trường timezone. PATCH `/api/v1/me` nhận tên và/hoặc múi giờ:

```json
{"fullName":"Nguyễn An","timezone":"Asia/Ho_Chi_Minh"}
```

Tên trim, không rỗng, tối đa 150 ký tự; timezone tối đa 50 ký tự, hợp lệ với ZoneId Java và pg_timezone_names của PostgreSQL. UTC và America/New_York được kiểm thử. Không đổi email, password, currency qua API này; body rỗng hoặc trường không hỗ trợ trả 400. Không thay đổi thời điểm UTC của giao dịch khi đổi timezone.

Tháng dùng `yyyy-MM`, năm 0001–9999. Từ nửa đêm đầu tháng tới nửa đêm tháng kế tiếp theo **múi giờ tài khoản**, khoảng `[from,to)`. Java tính riêng hai mốc, không cộng cứng 30 ngày hoặc giữ một UTC offset qua DST. Đổi timezone sẽ thay đổi cách nhóm các giao dịch vào tháng/ngày, bao gồm ngân sách cũ.

## Báo cáo

GET `/api/v1/reports/summary?month=2026-02` trả:

- period: month, timezone, from, to với offset của hai biên.
- totalIncome, totalExpense, netCashFlow: chỉ thu/chi còn hiệu lực trong tháng.
- currentTotalBalance: tổng số dư **hiện tại toàn thời gian**, gồm số dư khởi tạo và ví archive; không phải số dư cuối tháng được chọn.
- categories: categoryId, name, type, amount. Tên hiện tại của danh mục được dùng cho lịch sử.
- daily: từng ngày trong tháng, có cả ngày không phát sinh (giá trị 0); income, expense, netCashFlow.

Chuyển ví không phải thu/chi. Giao dịch đã xóa không được tính. Ví/danh mục archive vẫn giữ lịch sử. Báo cáo rỗng trả 200 và chuỗi 0, daily vẫn đủ ngày. Các truy vấn một báo cáo chạy trong snapshot REPEATABLE READ, tránh tổng/biểu đồ đọc hai trạng thái khác nhau.

## Ngân sách tháng

GET `/api/v1/budgets/2026-02`: 200 hoặc 404 nếu chưa thiết lập. Không nhận budget ID của người khác; khóa nghiệp vụ là owner + tháng.

PUT cùng URL thay toàn bộ hạn mức:

```json
{
  "items": [
    {"categoryId":"UUID danh mục chi tiêu riêng","limitAmount":"1500000"}
  ]
}
```

Tối đa 100 danh mục, không trùng; limitAmount là chuỗi nguyên dương tối đa 999999999999999. Chỉ danh mục EXPENSE thuộc owner và chưa archive; không tồn tại/khác chủ trả 404, INCOME/giá trị sai trả 400, archive trả 409. `items: []` xóa mọi hạn mức nhưng giữ bản ngân sách tháng. Danh mục đã archive sau khi đặt ngân sách vẫn hiện trong GET và tính lịch sử; muốn lưu PUT mới cần bỏ danh mục archive hoặc khôi phục trước.

PUT trả 200 cả tạo/cập nhật, giữ nguyên budget ID khi lặp. Khóa owner row đồng bộ với mutation ledger; kiểm tra rồi thay cấu hình trong một transaction. Lỗi không làm mất cấu hình cũ. Hai PUT đồng thời giữ nguyên một bộ thay thế hoàn chỉnh, không trộn items. GET dùng snapshot REPEATABLE READ.

Phản hồi: id, period, items, totalLimit, budgetedSpent, remaining, unbudgetedSpent. Các item gồm categoryId/name/archived, limitAmount/spent/remaining, percentageUsed và status. Tổng budgetedSpent chỉ cộng chi của các danh mục có hạn mức; unbudgetedSpent là phần chi còn lại trong tháng. Vì vậy totalExpense của báo cáo có thể lớn hơn budgetedSpent.

Ngưỡng theo tỷ lệ chính xác (không theo phần trăm đã làm tròn): dưới 80% OK; từ 80% tới dưới 100% NEAR_LIMIT; đúng 100% AT_LIMIT; trên 100% EXCEEDED. remaining được âm nếu vượt. Đây là trạng thái để giao diện hiển thị, chưa gửi push/email cảnh báo. Sửa/xóa/đổi danh mục giao dịch phản ánh ở lần đọc tiếp theo; chuyển ví và thu nhập không tính vào spent.

## CSV

GET `/api/v1/exports/transactions.csv?from=2026-02-01T00:00:00%2B07:00&to=2026-03-01T00:00:00%2B07:00`. Hai mốc bắt buộc, from < to, khoảng nửa mở. Các filter tùy chọn walletId, categoryId, type=INCOME/EXPENSE; luôn giới hạn owner. Nhớ URL-encode dấu + của offset. Đây là export thu/chi; chưa xuất transfers hoặc toàn bộ tài khoản.

Tối đa 10.000 giao dịch, vượt trả 413 JSON để yêu cầu thu hẹp filter; không trả tệp bị cắt âm thầm. Không có dữ liệu vẫn có header. Chỉ giao dịch chưa xóa, có cả lịch sử ví/danh mục archive. Dữ liệu lấy bằng một câu SELECT nhất quán, tên ví/danh mục hiện tại.

Tệp UTF-8 BOM, text/csv, attachment tên cố định finstep-transactions.csv, Cache-Control no-store. Cột: id, occurred_at_utc, type, amount, currency, wallet, category, note. Thời gian xuất UTC ISO-8601, amount luôn dương cùng type phân biệt thu/chi.

Mỗi ô quote theo CSV, quote trong nội dung được nhân đôi; giữ xuống dòng trong ô. Thêm dấu nháy đơn vào giá trị bắt đầu bằng dấu công thức `= + - @` (kể cả sau khoảng trắng) hoặc ký tự điều khiển/BOM ở đầu, để được hiểu là văn bản. Dấu nháy đơn có thể hiện trong trình đọc CSV khác. Kiểm thử đã đọc lại tệp có tiếng Việt, dấu quote, comma và CR/LF; không tuyên bố đã thử mọi phần mềm bảng tính.

## Kiểm chứng và phần còn lại

9 integration tests dùng PostgreSQL thật: biên tháng giờ Việt Nam; DST New York (tháng 3 dài 743 giờ); sửa timezone; trạng thái ngân sách/chi chưa phân bổ; sửa/xóa; quyền owner; PUT concurrent/rollback; số nguyên lớn; CSV round trip, bảo vệ công thức, filter và giới hạn 10.000/10.001; auth/CSRF và validation. Bộ backend hiện có 32 tests.

Smoke test bản JAR với PostgreSQL và SMTP Mailpit đã đạt: đăng nhập, ledger, tổng tháng, ngân sách 80%, đổi múi giờ và CSV được xác thực. Chưa triển khai production hoặc xác nhận kết quả GitHub Actions.

Không cần migration mới: dùng schema budgets/budget_items/users.timezone đã có. Chưa có frontend, export đầy đủ/delete tài khoản, feedback, monitoring production hoặc backup/restore drill. Cần staging HTTPS/SMTP và load test trước beta.
