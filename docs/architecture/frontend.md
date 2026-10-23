# Frontend v0.3 — Next.js, TypeScript và Tailwind

## Đã triển khai

- Trang giới thiệu; hình ví có nhãn Minh họa. Dashboard không dùng dữ liệu giả.
- `/auth/register`, `/auth/verify`, `/auth/login`, `/auth/forgot`, `/auth/reset`: email/token/password thực sự gọi Spring API. Có gửi lại xác minh; mã lấy từ email rồi dán vào form, chưa có deep link.
- `/app`: xác thực bằng GET /me, dashboard tổng số dư hiện tại và báo cáo tháng theo múi giờ tài khoản, biểu đồ ngày, cơ cấu chi, lịch sử cursor, các ví và trạng thái ngân sách.
- `/app/onboarding`: ví đầu tiên, loại ví và số dư tại thời điểm tạo. Chuẩn bị danh mục riêng qua backend. Nếu mất phản hồi, lần thử lại kiểm tra ví đã có trước khi tạo thêm; POST ví chưa có bảo đảm idempotency như ledger.
- Thu/chi nhanh và sửa giao dịch: chọn ví/danh mục, số tiền, ghi chú, thời điểm. Chọn ngày theo **múi giờ thiết bị** hiển thị bên cạnh input; chuyển thành UTC trước khi gửi. Ngày/giờ không tồn tại khi đổi DST bị từ chối, ngày tương lai/trước số dư ban đầu bị backend từ chối. Khi sửa mà không đổi thời điểm, giữ nguyên instant gốc (kể cả phần mili giây và giờ DST trùng). Giờ DST lặp nhập mới dùng lần xuất hiện đầu theo Date của trình duyệt.
- Tạo thu/chi/chuyển ví giữ payload/time/key qua retry chưa đổi nội dung. Không giữ key qua đóng hộp thoại hoặc reload; khi chưa rõ kết quả, nên thử lại trong form hiện tại. Replay của khoản đã bị xóa ở phiên khác được báo lỗi, không thông báo lưu thành công.
- `/app/wallets`: tạo nhiều ví, đổi tên, lưu trữ/khôi phục và chuyển ví; số dư ban đầu ghi tại lúc tạo, không sửa lại. `/app/categories`: tạo danh mục thu/chi, đổi tên, lưu trữ/khôi phục. Xác nhận trước khi lưu trữ, lịch sử và số dư vẫn được giữ.
- POST ví/danh mục chưa có idempotency: nếu mất kết nối/5xx, khóa nút tạo tiếp trong form; đóng form sẽ tải lại danh sách để kiểm tra kết quả trước khi tạo lại. PATCH đổi tên/lưu trữ có thể thử lại.
- `/app/activity`: thu/chi và chuyển ví tách danh sách; lọc theo tháng tài khoản hoặc toàn thời gian, ví (kể cả archive), loại/danh mục cho thu/chi. Cursor 20 mục/trang, trước/sau; đổi bộ lọc hoặc lưu/xóa quay về trang đầu. Có sửa toàn bộ và xác nhận xóa; backend tính lại số dư/báo cáo/ngân sách. Cursor không bảo đảm snapshot nếu dữ liệu thay đổi giữa các trang.
- `/app/profile`: đổi tên và múi giờ IANA; cập nhật context dùng chung ngay sau PATCH, không cần đăng nhập lại. Thay múi giờ chỉ thay cách chia ngày/tháng báo cáo, không đổi instant lịch sử.
- Ngân sách theo tháng với hạn mức danh mục, cập nhật qua PUT. Danh mục archive có hạn mức cũ sẽ chặn chỉnh sửa để tránh xóa ngầm; cần khôi phục qua trang Danh mục trước khi sửa ngân sách.
- CSV dùng chính from/to backend trả cho tháng, tải có cookie xác thực; hiển thị lỗi nếu quá 10.000 dòng.
- Hồ sơ có xuất toàn bộ JSON và xóa tài khoản bằng mật khẩu/xác nhận. Tệp JSON được kiểm tra đầy đủ trước download; 204 kết thúc phiên, mất phản hồi sau xóa có trang riêng mô tả chưa rõ kết quả. `/privacy` công khai mô tả dữ liệu, retention hiện có và giới hạn môi trường phát triển. Xem [thiết kế privacy](privacy.md).
- Responsive, modal dùng dialog focus trap/Escape và trả focus, trường nhập có nhãn/hint, trạng thái tải/rỗng/lỗi, giữ form khi API lỗi. Chưa tuyên bố đạt chuẩn accessibility đầy đủ.

Sidebar và thanh điều hướng mobile dẫn tới các trang độc lập, đánh dấu trang hiện tại; hồ sơ có nút riêng trên header mobile. Ngân sách vẫn chỉnh tại dashboard. Giao dịch gần đây trên dashboard gồm INCOME/EXPENSE; chuyển ví xem tại Lịch sử giao dịch.

## Session và dữ liệu

Browser gọi đường dẫn tương đối `/api/v1/*`, Next rewrites tới API_ORIGIN. Cookie HttpOnly do backend quản lý; không lưu token/password/dữ liệu tài chính vào localStorage. Mỗi mutation lấy CSRF mới bằng cùng cookie jar, kể cả login/logout. API fetch không cache. Gác trang phía client phục vụ UX; authorization thực sự nằm ở backend.

Form auth chặn submit trước khi hydrate; form dùng method POST để không rơi về native GET chứa dữ liệu nhạy cảm trong URL. Bản HTML public chỉ chứa UI, không chứa dữ liệu tài khoản. Auth có email/password/token chưa gửi trong URL. Chưa có CORS hoặc third-party analytics.

Tiền giữ kiểu string/BigInt từ API đến format VND; chỉ chuẩn hóa tỷ lệ biểu đồ thành number sau phép chia BigInt. Các tổng hiển thị từ backend, không cộng số tiền bằng float.

API_ORIGIN chỉ ở next.config, không dùng NEXT_PUBLIC cho credentials. Mặc định backend loopback 8080 và frontend loopback 3000. Next rewrites được chuẩn bị khi build; đổi origin cần build lại production. Không triển khai static export cho ứng dụng cần proxy API này. Trên staging/production phải kiểm tra same-origin qua HTTPS với SESSION_COOKIE_SECURE=true; hiện mới kiểm tra HTTP cục bộ.

## Chạy lại

Backend: Java 21 và PostgreSQL theo README. Local SMTP dùng Mailpit (chỉ hộp thư thử nghiệm, không gửi ra Internet).

```bash
cd frontend
npm ci
npm run lint
npm run build
npm run typecheck
npm run start
```

Node 24 đã dùng để kiểm chứng; package-lock khóa dependency. Không gọi Google Fonts hoặc tải ảnh ngoài khi build. AGENTS.md trong frontend do Next tạo, tham chiếu hướng dẫn đúng phiên bản trong node_modules/next/dist/docs.

## Browser E2E với dữ liệu tách riêng

Cần Docker, Node, JDK/JRE 21, backend JAR đã build và frontend dependencies. Lần đầu cài browser bằng `cd frontend && npx playwright install --with-deps chromium` trên máy/CI có quyền cài dependencies. Có thể dùng Chromium đã cài bằng biến PLAYWRIGHT_CHROMIUM_EXECUTABLE.

```bash
# Từ repository root; backend JAR phải có trước, ví dụ backend/./mvnw verify.
bash scripts/test-web.sh
```

Có thể truyền bộ lọc Playwright sau script, ví dụ `bash scripts/test-web.sh tests/dates.spec.ts`.

Script tạo PostgreSQL/Mailpit tạm với cổng ngẫu nhiên, chạy JAR trên cổng riêng và truyền API_ORIGIN cho Next dev server cổng 3100. Playwright Chromium chạy UI thực; không mock thay backend. Sau test chỉ dừng process/container do script tạo và xóa dữ liệu volume test; không đụng database phát triển.

Kết quả mới nhất: **8/8 E2E đạt**, không bỏ qua; TypeScript, ESLint và production build đạt. Smoke test riêng bằng `next start` cũng đã đăng nhập qua proxy/cookie, kiểm tra số dư/lịch sử, tải JSON và xóa tài khoản thử nghiệm với API thật; chụp desktop/mobile, không có page error. Đây là kiểm chứng local, chưa deploy Internet.

Tám kịch bản: (1) đăng ký/email/verify/login/ví, giả lập mất phản hồi **sau khi backend commit**, retry cùng key tạo đúng một giao dịch, ngân sách, download CSV, logout; (2) viewport 390px, empty state, focus/Escape, thu nhập thật và đổi tháng không giữ dữ liệu cũ; (3) forgot/reset qua email, mật khẩu cũ bị từ chối và form login giữ email; (4) thiết bị America/New_York, ngày không tồn tại khi đổi DST, ngày trước số dư ban đầu/tương lai, sửa giữ instant và đổi múi giờ đưa khoản tiền sang đúng tháng; (5) tạo/đổi tên/lưu trữ/khôi phục ví và danh mục, mất phản hồi sau commit tạo ví (khóa tạo lặp), chuyển ví retry cùng key, sửa/xóa thu chi/chuyển ví tính lại số dư, giữ lịch sử archive và hồ sơ; (6) 23 khoản cùng timestamp, trang 20/3 không trùng, lọc loại/danh mục/ví/tháng/toàn thời gian và điều hướng mobile; (7) privacy, tệp JSON đầy đủ, tệp bị cắt không download, hủy/sai mật khẩu, xóa tài khoản và phiên thứ hai/mã reset cũ, đăng nhập lại bị từ chối; (8) mất phản hồi sau commit DELETE đưa về trạng thái chưa xác nhận, không tự gửi lặp.

E2E chạy Next dev theo hướng dẫn webServer Playwright; build production được kiểm tra riêng. Chưa coi đây là bằng chứng HTTPS production, Safari/WebKit hoặc Firefox. Backend có 39 integration tests (gồm 7 privacy tests), kiểm chứng riêng với PostgreSQL thật, không thay bằng test frontend.

Kết quả/trace ở frontend/playwright-report và frontend/test-results (ignored); ảnh dashboard-desktop.png/dashboard-mobile.png, wallets-desktop.png và history-mobile.png dùng dữ liệu fixture. CI khai báo lint/build/typecheck và E2E full-stack, chưa khẳng định kết quả CI trên GitHub.

## Còn lại trước beta

Đổi mật khẩu trong hồ sơ, feedback, chính sách vận hành beta; HTTPS/SMTP staging, backup/restore, monitoring, đo tải. Không đưa dữ liệu tài chính thật vào bản phát triển này.
