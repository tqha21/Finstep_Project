# API và trạng thái triển khai — `/api/v1`

Đã triển khai nhóm identity: register, login, logout, verify-email, resend-verification, forgot-password, reset-password, csrf và GET /me. Xem [hợp đồng identity](identity.md). Đã triển khai GET/POST/PATCH ví và danh mục, GET chi tiết và POST /categories/defaults; xem [hợp đồng ví/danh mục](wallets-categories.md). Đã triển khai thu/chi/chuyển ví, GET chi tiết, PUT sửa toàn bộ, DELETE soft delete và lịch sử cursor; xem [hợp đồng ledger](ledger.md). Đã triển khai ngân sách tháng, báo cáo tổng hợp, CSV và PATCH hồ sơ tên/múi giờ; xem [hợp đồng báo cáo](reporting.md). Feedback và xóa tài khoản vẫn là thiết kế.

Session cookie HttpOnly/Secure/SameSite và CSRF token cho mutation. Owner lấy từ session. JSON số tiền là chuỗi nguyên dương VND; thời gian ISO-8601 có offset. Lỗi dùng Problem Details, không lộ stack trace hay dữ liệu tài chính.

| Endpoint | Nghiệp vụ |
| --- | --- |
| POST /auth/register, /login, /logout | Tài khoản và phiên |
| POST /auth/verify-email, /auth/forgot-password, /auth/reset-password | Token một lần, thời hạn, rate limit |
| GET/PATCH /me | Hồ sơ |
| GET/POST /wallets | Ví |
| PATCH /wallets/{id} | Đổi tên/lưu trữ |
| GET/POST /categories | Danh mục riêng |
| GET/POST /transactions | Lịch sử, tạo thu/chi |
| GET/PUT/DELETE /transactions/{id} | Sửa/soft delete |
| GET/POST /transfers | Lịch sử, chuyển ví nguyên tử |
| GET/PUT/DELETE /transfers/{id} | Chi tiết, sửa toàn bộ, soft delete |
| GET/PUT /budgets/{yyyy-MM} | Hạn mức tháng |
| GET /reports/summary?month=yyyy-MM | Thu/chi, số dư, cơ cấu |
| GET /exports/transactions.csv | Xuất dữ liệu riêng |
| POST /feedback | Phản hồi |
| DELETE /me | Xóa tài khoản theo workflow xác nhận |

POST giao dịch/chuyển ví yêu cầu `Idempotency-Key` (tối đa 128 ký tự); hash khác với key đã dùng trả 409, replay cùng request trả trạng thái hiện tại của cùng tài nguyên (200, kể cả đã sửa/xóa), không tạo lại. Tạo mới trả 201, dữ liệu không hợp lệ 400, chưa đăng nhập 401, tài nguyên không thuộc owner 404; ví/danh mục archive trả 409. Identity dùng mã phản hồi mô tả trong tài liệu riêng.

Lịch sử mặc định page size 20, tối đa 100, thứ tự occurred_at DESC rồi id DESC; cursor bao gồm cả hai. Filter khoảng thời gian `[from,to)`, type, categoryId, walletId. Tất cả query đều thêm owner. CSV đã quote và neutralize ô có thể được bảng tính hiểu là công thức, giới hạn 10.000 dòng/lần.
