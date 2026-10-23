# PRD v0.1 — SmartExpense

Nguồn: bản kế hoạch người dùng cung cấp ngày 23/10/2026. Đây là phạm vi triển khai, chưa phải kết quả khảo sát thị trường.

## Người dùng và giá trị

Sinh viên/người mới đi làm muốn ghi một khoản chi nhanh, biết số tiền còn lại và tránh vượt ngân sách. Mục tiêu beta 20–30 người; không coi số lượt đăng ký là bằng chứng giải quyết đúng nhu cầu.

Vai trò: khách xem giới thiệu; người dùng đã xác thực quản lý dữ liệu riêng; người vận hành xem sức khỏe dịch vụ và xử lý hỗ trợ, không mặc nhiên được đọc giao dịch của người dùng.

## P0 và tiêu chí chấp nhận

| Nhóm | Điều kiện hoàn thành |
| --- | --- |
| Identity | Đăng ký, xác minh email, đăng nhập/đăng xuất, reset một lần có hạn dùng; cookie HttpOnly/Secure, CSRF, rate limit |
| Onboarding | Tạo ví VND, số dư ban đầu và danh mục mặc định riêng; tạo giao dịch đầu tiên |
| Ví | Tạo/sửa/lưu trữ; ví có lịch sử không được xóa; ví lưu trữ không nhận giao dịch mới |
| Thu/chi | Số tiền nguyên dương VND, ví và danh mục cùng chủ; loại danh mục khớp loại giao dịch; sửa/xóa phản ánh tức thì vào số dư |
| Chuyển ví | Hai ví khác nhau cùng chủ, một transaction DB; không làm đổi tổng thu/chi; thử lại không tạo trùng |
| Lịch sử | Lọc thời gian/ví/danh mục/loại; phân trang ổn định, chỉ trả dữ liệu chủ tài khoản |
| Ngân sách | Một ngân sách/tháng, hạn mức danh mục chi; chi tiêu chỉ tính EXPENSE còn hiệu lực |
| Báo cáo | Số dư, thu, chi, dòng tiền ròng; phạm vi tháng theo timezone tài khoản; xuất CSV an toàn với công thức bảng tính |
| Tài khoản | Hồ sơ, đổi mật khẩu, xuất/xóa dữ liệu, chính sách riêng tư và phản hồi |

P1: định kỳ, tiết kiệm, PWA. P2: ngân hàng, OCR, AI, đa tiền tệ, chia sẻ gia đình. Chưa xây dựng các nhóm này.

## UX và đo lường

Mobile-first; thêm giao dịch dễ tiếp cận, input số tiền rõ ràng, giữ form khi lỗi; empty/loading/error state; chart kèm số liệu chữ. Không dùng dữ liệu mẫu làm số liệu thật.

Theo dõi activation (tạo ví + giao dịch), D7 retention và thời gian tới giao dịch đầu. Event không chứa email, số tiền hay ghi chú. Mục tiêu ban đầu activation ≥40%, D7 ≥30%; phải đo thực tế.

## Release gates

Kiểm thử thu/chi/chuyển ví và chống IDOR; authentication hoàn chỉnh; HTTPS; backup và restore đã thử; xuất/xóa dữ liệu; monitoring; không còn lỗi nghiêm trọng ảnh hưởng tiền hoặc riêng tư; luồng mobile hoạt động. Chưa gate nào được suy ra từ việc schema chạy thành công.

## Discovery cần người phát triển thực hiện

Phỏng vấn ít nhất 8 người: đang ghi chép bằng gì, lần gần nhất bỏ ghi chép vì sao, có bao nhiêu ví, muốn biết điều gì cuối tháng? Ghi nhận quan sát có đồng ý; không yêu cầu số dư thật. Chưa có phỏng vấn nào được thực hiện trong repository này.
