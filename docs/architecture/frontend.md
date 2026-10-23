# Frontend v0.1 — Next.js, TypeScript và Tailwind

## Đã triển khai

- Trang giới thiệu; hình ví có nhãn Minh họa. Dashboard không dùng dữ liệu giả.
- `/auth/register`, `/auth/verify`, `/auth/login`, `/auth/forgot`, `/auth/reset`: email/token/password thực sự gọi Spring API. Có gửi lại xác minh; mã lấy từ email rồi dán vào form, chưa có deep link.
- `/app`: xác thực bằng GET /me, dashboard tổng số dư hiện tại và báo cáo tháng theo múi giờ tài khoản, biểu đồ ngày, cơ cấu chi, lịch sử cursor, các ví và trạng thái ngân sách.
- `/app/onboarding`: ví đầu tiên, loại ví và số dư tại thời điểm tạo. Chuẩn bị danh mục riêng qua backend. Nếu mất phản hồi, lần thử lại kiểm tra ví đã có trước khi tạo thêm; POST ví chưa có bảo đảm idempotency như ledger.
- Thu/chi nhanh: chọn ví/danh mục, số tiền, ghi chú; thời điểm hiện tại khi bắt đầu lưu. Giữ payload/time/key qua retry chưa đổi nội dung. Không giữ key qua đóng hộp thoại hoặc reload; khi chưa rõ kết quả, nên thử lại trong form hiện tại.
- Ngân sách theo tháng với hạn mức danh mục, cập nhật qua PUT. Danh mục archive có hạn mức cũ sẽ chặn chỉnh sửa để tránh xóa ngầm; cần khôi phục danh mục (UI quản lý danh mục sẽ bổ sung).
- CSV dùng chính from/to backend trả cho tháng, tải có cookie xác thực; hiển thị lỗi nếu quá 10.000 dòng.
- Responsive, modal dùng dialog focus trap/Escape và trả focus, trường nhập có nhãn/hint, trạng thái tải/rỗng/lỗi, giữ form khi API lỗi. Chưa tuyên bố đạt chuẩn accessibility đầy đủ.

Điều hướng sidebar hiện dẫn tới các phần dashboard; chưa phải các trang quản lý lịch sử/ví độc lập. Giao dịch gần đây chỉ gồm INCOME/EXPENSE, chưa hiển thị transfers.

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

Script tạo PostgreSQL/Mailpit tạm với cổng ngẫu nhiên, chạy JAR trên cổng riêng và truyền API_ORIGIN cho Next dev server cổng 3100. Playwright Chromium chạy UI thực; không mock thay backend. Sau test chỉ dừng process/container do script tạo và xóa dữ liệu volume test; không đụng database phát triển.

Kết quả mới nhất: **3/3 E2E đạt**, không bỏ qua; TypeScript, ESLint và production build đạt. Smoke test riêng bằng `next start` cũng đã đăng nhập qua proxy/cookie, đọc dashboard từ API thật và chụp desktop/mobile, không có page error. Đây là kiểm chứng local, chưa deploy Internet.

Ba kịch bản: (1) đăng ký/email/verify/login/ví, giả lập mất phản hồi **sau khi backend commit**, retry cùng key tạo đúng một giao dịch, ngân sách, download CSV, logout; (2) viewport 390px, empty state, focus/Escape, thu nhập thật và đổi tháng không giữ dữ liệu cũ; (3) forgot/reset qua email, mật khẩu cũ bị từ chối và form login giữ email.

E2E chạy Next dev theo hướng dẫn webServer Playwright; build production được kiểm tra riêng. Chưa coi đây là bằng chứng HTTPS production, Safari/WebKit hoặc Firefox. Backend 32 integration tests của milestone trước vẫn là kiểm chứng riêng, không thay bằng test frontend.

Kết quả/trace ở frontend/playwright-report và frontend/test-results (ignored); ảnh dashboard-desktop.png/dashboard-mobile.png dùng dữ liệu fixture. CI khai báo lint/build/typecheck và E2E full-stack, chưa khẳng định kết quả CI trên GitHub.

## Còn lại trước beta

UI quản lý nhiều ví/danh mục, nhập ngày giao dịch tùy chọn, chuyển ví, sửa/xóa giao dịch, filters/history đầy đủ, hồ sơ/múi giờ; privacy, export đầy đủ/delete, feedback; HTTPS/SMTP staging, backup/restore, monitoring, đo tải. Không đưa dữ liệu tài chính thật vào bản phát triển này.
