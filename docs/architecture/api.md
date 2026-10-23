# API dự kiến — `/api/v1`

Đây là bản thiết kế, chưa có HTTP endpoint chạy được.

Session cookie HttpOnly/Secure/SameSite và CSRF token cho mutation. Owner lấy từ session. JSON số tiền là chuỗi nguyên dương VND; thời gian ISO-8601 có offset. Lỗi dùng Problem Details, không lộ stack trace hay dữ liệu tài chính.

| Endpoint | Nghiệp vụ |
| --- | --- |
| POST /auth/register, /login, /logout | Tài khoản và phiên |
| POST /auth/verify-email, /forgot-password, /reset-password | Token một lần, thời hạn, rate limit |
| GET/PATCH /me | Hồ sơ |
| GET/POST /wallets | Ví |
| PATCH /wallets/{id} | Đổi tên/lưu trữ |
| GET/POST /categories | Danh mục riêng |
| GET/POST /transactions | Lịch sử, tạo thu/chi |
| PATCH/DELETE /transactions/{id} | Sửa/soft delete |
| POST /transfers | Chuyển ví nguyên tử |
| GET/PUT /budgets/{yyyy-MM} | Hạn mức tháng |
| GET /reports/summary?month=yyyy-MM | Thu/chi, số dư, cơ cấu |
| GET /exports/transactions.csv | Xuất dữ liệu riêng |
| POST /feedback | Phản hồi |
| DELETE /me | Xóa tài khoản theo workflow xác nhận |

POST giao dịch/chuyển ví yêu cầu `Idempotency-Key` (tối đa 128 ký tự); hash khác với key đã dùng trả 409, replay cùng request trả tài nguyên gốc. Tạo mới trả 201, dữ liệu không hợp lệ 400, chưa đăng nhập 401, tài nguyên không thuộc owner 404.

Lịch sử mặc định page size 20, tối đa 100, thứ tự occurred_at DESC rồi id; cursor bao gồm cả hai. Filter khoảng thời gian `[from,to)`, type, categoryId, walletId. Tất cả query đều thêm owner. CSV phải neutralize ô có thể được bảng tính hiểu là công thức.
