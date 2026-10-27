# Staging, HTTPS, monitoring và diễn tập khôi phục

## Phạm vi đã có

Compose riêng `compose.staging.yaml` đóng gói JAR đã build và Next standalone thành image, chạy Caddy HTTPS, PostgreSQL, Prometheus và blackbox exporter. Không dùng chung database/volume với compose phát triển. Đây là cấu hình có thể triển khai và đã diễn tập cục bộ; **chưa có máy chủ/tên miền/SMTP bên ngoài được triển khai hoặc kiểm chứng**.

API/web chạy bằng non-root, filesystem read-only và /tmp riêng. Docker build context chỉ chứa JAR/standalone/static/public, không chứa `.env`, source cache hay credentials. Runtime images dùng Java 21/Node 24; tag base theo major để nhận cập nhật, vì vậy cần lưu digest/image tag chính xác đã kiểm chứng cho mỗi release. Không coi tag `local` là định danh release bất biến. Chưa có registry push hay CD tự động.

Caddy là điểm vào duy nhất cho app; `/api/*` tới API và phần còn lại tới Next. Request body không được ghi vào access log vì chưa bật access logging. `/actuator` và `/actuator/*` trả 404 qua proxy. Backend/DB/blackbox không publish host port. Prometheus UI chỉ bind 127.0.0.1; dùng SSH tunnel nếu cần xem từ máy vận hành.

API staging dùng management port 9090, chỉ bật health/prometheus. Native RemoteIpValve tin **đúng IP private của Caddy**, không tin mọi IP private hoặc mọi X-Forwarded-For. Caddy bỏ header chuyển tiếp không tin cậy từ client. Giới hạn auth theo địa chỉ thực qua Caddy, không gom mọi người dùng vào IP Next. Nếu đổi subnet, CADDY_IP và TRUSTED_PROXY_REGEX phải đổi đồng bộ. Các kết nối nội bộ được thêm NO_PROXY để không đi nhầm qua forward proxy của môi trường build/cloud.

HTTPS bật HSTS, nosniff, DENY framing; cookie phiên luôn Secure. HTTP chuyển 308 sang HTTPS. SMTP staging mặc định AUTH + STARTTLS bắt buộc; provider phải hỗ trợ STARTTLS (thường 587), không mặc nhiên coi cổng SMTPS 465 là tương đương.

## Chuẩn bị bản build

Cần Docker/Compose v2, JDK 21, Node 22–24, Bash, Python >=3.12, OpenSSL >=3, tar và sha256sum. Chỉ định credentials qua file/secret manager được bảo vệ, không commit. Docker daemon là quyền quản trị; chỉ vận hành bằng tài khoản được tin cậy.

```bash
# Repository root; backend đang chạy bằng target/*.jar phải dừng trước khi đóng gói.
./backend/mvnw -f backend/pom.xml --batch-mode --no-transfer-progress verify
npm --prefix frontend ci --no-fund
API_ORIGIN=http://api-internal:8080 NEXT_TELEMETRY_DISABLED=1 npm --prefix frontend run build
npm --prefix frontend run lint
npm --prefix frontend run typecheck
```

Next `output: standalone` được đọc theo hướng dẫn đúng phiên bản. Build script chuẩn bị cả `.next/static` và `public` trong standalone; server chạy bằng `node server.js`, không dùng `next start`. `npm start` bind loopback cho host, Docker đặt HOSTNAME=0.0.0.0 riêng. API_ORIGIN được ghi lúc build; Docker stack dùng tên private api-internal, còn build để chạy host phải dùng origin loopback của host. Caddy định tuyến browser API trực tiếp cùng origin, không cần CORS.

Nếu home chỉ đọc trong cloud: đặt npm_config_cache, MAVEN_USER_HOME, Maven repository và BUILDX_CONFIG ở thư mục workspace có quyền ghi theo README. Proxy/truststore chỉ cấu hình ngoài Git. Các file cấu hình public trong ops/monitoring phải đọc được bởi UID của image Prometheus/blackbox (thường 0644, thư mục 0755); tuyệt đối không đặt secrets vào đó.

## Điền cấu hình để triển khai thực

```bash
# Chỉ copy nếu chưa có file hiện hữu.
cp ops/staging/.env.example ops/staging/.env
# Điền tên miền, mật khẩu ngẫu nhiên >=24 ký tự, SMTP/provider và MAIL_FROM.
# Với public staging: STAGING_BIND_ADDRESS=0.0.0.0, STAGING_HTTP_PORT=80, STAGING_HTTPS_PORT=443.
# TLS_ISSUER là email ACME; hostname cần DNS A/AAAA trỏ đúng máy chủ.
# Dùng nháy đơn cho secret có ký tự $; preflight không thực thi/expand shell.
chmod 600 ops/staging/.env
python3 scripts/staging-preflight.py ops/staging/.env
docker compose --env-file ops/staging/.env -f compose.staging.yaml config --quiet
docker compose --env-file ops/staging/.env -f compose.staging.yaml build
# Chạy lệnh up sau khi đã review host/domain/SMTP và image release.
docker compose --env-file ops/staging/.env -f compose.staging.yaml up -d
```

Mặc định `.env.example` chỉ bind loopback với CA internal để tránh mở Internet ngoài ý muốn, nhưng chưa phải cấu hình SMTP hoạt động. Preflight public từ chối placeholder, TLS internal, SMTP thiếu STARTTLS và proxy regex quá rộng. Preflight `--local-test` chỉ chấp nhận localhost, loopback, internal TLS và Mailpit của test. Nó kiểm tra cấu hình thông dụng, không thay cho xác minh DNS/ACME/SMTP/firewall thực tế. Không dùng `compose config` không có `--quiet` khi cần giữ secrets khỏi terminal/log.

Trước khi mở traffic: kiểm tra certificate chain bằng client tin CA công khai, register/verify/login/reset qua SMTP thật, cookie/CSRF qua HTTPS, confirm API/DB/9090 không có host/public ingress, kiểm tra IP auth limit qua proxy và khôi phục dữ liệu mẫu. Firewall chỉ cho 80/443 và SSH quản trị theo policy riêng; không mở 5432/8080/9090/9115. Nếu đặt thêm CDN/load balancer trước Caddy, chưa được tin tự động: cần thiết kế và kiểm thử lại chuỗi proxy/IP, TLS và cookie.

## Monitoring hiện tại

Prometheus scrape private API metrics, blackbox probe API readiness (gồm DB) và trang frontend. Có bốn rule: API scrape mất 1 phút, readiness fail 1 phút, 5xx >5% trong 5 phút với lưu lượng >20 request, và chờ connection DB >2 phút. Promtool kiểm tra cú pháp và test rule firing/non-firing. Metrics không gắn email, ghi chú, token hay số tiền làm label; HTTP dùng URI template. Scrape endpoint không mở công khai.

Dữ liệu Prometheus giữ 15 ngày trong volume riêng. Docker JSON logs giới hạn 10 MB × 3 file/service, không cam kết số ngày. Không đưa secrets/nội dung tài chính vào log hỗ trợ. Backup hiện chỉ bao gồm PostgreSQL, không bao gồm metrics, certificate Caddy hoặc file cấu hình secrets; các phần này cần quy trình riêng.

**Chưa có Alertmanager/receiver hoặc người trực nhận cảnh báo**, chưa có uptime/certificate probe từ bên ngoài máy chủ, chưa đo tải hay đặt SLO theo người dùng thật. Nếu cả máy chủ hỏng, Prometheus trên cùng máy không gửi được cảnh báo. Đây vẫn là release gate trước beta, không coi dashboard local là giám sát vận hành hoàn chỉnh.

## Backup mã hóa

`backup-db.sh SOURCE_CONTAINER OUTPUT_DIRECTORY RECIPIENT_CERTIFICATE` chạy pg_dump custom PostgreSQL 17, snapshot nhất quán của DB, bỏ dữ liệu session/thuộc tính phiên, identity_tokens và rate limit. Schema vẫn được giữ. Bundle gồm dump và manifest (schema/time/format), được mã hóa **CMS AES-256-GCM** bằng chứng chỉ public của người nhận. Writer không cần private key. File/temporary state dùng umask 0077; plaintext tạm được xóa khi script kết thúc bình thường hoặc trap chạy. Không bảo đảm xóa vật lý khỏi SSD/swap hoặc khi host mất điện; dùng ổ đĩa mã hóa và hạn chế quyền recovery host.

```bash
# Trên máy quản trị/recovery, không phải container ứng dụng; ví dụ tạo khóa offline.
openssl req -x509 -newkey rsa:3072 -sha256 -nodes \
  -keyout /secure/offline/backup-recipient.key -out /secure/offline/backup-recipient.crt \
  -days 3650 -subj /CN=Finstep-Backup-Recipient
# Chỉ chuyển certificate PUBLIC tới máy backup; giữ private key offline có bảo vệ.
bash scripts/backup-db.sh finstep-staging-db-1 /var/lib/finstep/backups /etc/finstep/backup-recipient.crt
```

Không đưa private key vào Git/image/env hay lưu cùng backup. Theo dõi key rotation và giữ khóa cũ đến hết retention của backup tương ứng. Script in đường dẫn file, không in dữ liệu tài chính. Sidecar SHA-256 phát hiện hỏng khi vận chuyển; GCM xác thực ciphertext. SHA-256 không chứng minh nguồn phát hành, và public-key encryption không thay thế ACL/chữ ký người gửi: chỉ restore backup từ kho/nguồn được tin cậy.

`ops/backup/finstep-backup.service` và `.timer` là **mẫu chưa cài đặt/bật**: đề xuất mỗi ngày 07:15 giờ Bangkok (00:15 UTC) với jitter tối đa 15 phút, sau khi review tên container, đường dẫn và quyền. Nó mới tạo bản sao mã hóa cục bộ; chưa có offsite copy, object lock, upload credentials, retention/pruning tự động hoặc cảnh báo backup lỗi. Cần chọn kho ngoài máy chủ, IAM/retention và kiểm chứng recovery key trước khi dữ liệu thật xuất hiện. Không tự xóa backup cũ trong milestone này. RPO/RTO chưa đo trên dữ liệu thật; snapshot không phải zero data loss.

## Restore cách ly và giữ quyền riêng tư

```bash
# Tạo TARGET MỚI, không publish cổng, không attach mạng ứng dụng.
# /secure/recovery.env (0600) chứa POSTGRES_USER=finstep, POSTGRES_DB=finstep
# và POSTGRES_PASSWORD sinh riêng cho recovery, không dùng secret production.
docker run -d --name finstep-recovery-review --network none \
  --label com.finstep.restore-quarantine=true \
  --env-file /secure/recovery.env postgres:17
# Chờ pg_isready, rồi chạy trên máy có private key được bảo vệ.
bash scripts/restore-rehearsal.sh finstep-staging-db-1 finstep-recovery-review \
  /secure/backups/finstep-backup.cms /secure/offline/backup-recipient.crt /secure/offline/backup-recipient.key
```

Tool yêu cầu target có label quarantine, network=none, khác source và chưa có bảng public. Nó không drop/ghi đè database đã có dữ liệu. Giải mã/xác thực toàn bộ bundle trước khi tạo bảng; tar chỉ được chứa hai file thường đúng tên. Nếu ciphertext hỏng, target còn trống. Bất cứ lỗi restore/schema/scrub nào đều phải giữ target cách ly và bỏ target sau khi điều tra; **không dùng database đã restore một phần**.

Hiện scrub chỉ hỗ trợ schema V5. Đối chiếu version từ manifest, source và target; cần review/migrate quarantine riêng khi schema thay đổi. Nguồn được chỉ định phải là database hiện hành có thẩm quyền, không phải DB trống mới dựng hoặc bản backup cũ.

Sau restore, tool lấy một checkpoint **hiện tại** từ source gồm UUID, hồ sơ, password hash, auth_version, trạng thái và email verification. Đó là dữ liệu nhạy cảm: lưu tạm 0600, chuyển vào target bằng Docker admin channel, không in log và xóa file tạm. Dữ liệu tài chính lấy từ snapshot; tài khoản đã mất ở checkpoint bị xóa cùng các bảng phụ, kể cả feedback. Hồ sơ/credentials/status được giữ theo checkpoint, auth_version tăng, tất cả session/reset/verify token và rate limit bị xóa. Nhờ đó backup cũ không đưa tài khoản đã xóa, mật khẩu cũ hay trạng thái ACTIVE cũ trở lại.

**Giới hạn bắt buộc:** đây là diễn tập/restore có source hiện hành còn đọc được, không phải giải pháp disaster recovery khi mất toàn bộ source. Nếu không có source, script dừng trước khi restore. Chưa có deletion/credential journal độc lập, durable, cập nhật sau mọi mutation. Không được dùng một checkpoint cũ để suy ra rằng các yêu cầu xóa/đổi mật khẩu sau checkpoint đã được bảo toàn.

Script không dừng traffic và **không promote/cutover** target. Đối với planned recovery, phải freeze mọi ghi, register/delete/password/profile/admin thay đổi từ trước checkpoint đến cutover; nếu không, các thay đổi sau checkpoint có thể bị bỏ sót. Review số lượng tài khoản, số dư, FK và quyền truy cập bằng dữ liệu mẫu, áp dụng migrations phù hợp, rồi mới lập quyết định chuyển traffic riêng. Nếu mất source, cần một thiết kế journal độc lập được kiểm chứng trước khi cho phép promotion; gate này hiện chưa hoàn tất.

Tài khoản/giao dịch được tạo sau thời điểm pg_dump có thể không có trong snapshot; test đã kiểm tra điều này. Mọi người dùng phải đăng nhập lại sau recovery. Chưa có bảo đảm phục hồi email trong hộp thư, file download, private keys hoặc logs ngoài DB.

## Chạy lại kiểm chứng

```bash
python3 scripts/test-staging-preflight.py
# Build JAR và Next standalone trước theo phần trên.
bash scripts/test-operations.sh
```

Script chỉ tạo project/container/volume riêng, HTTPS loopback với Caddy CA nội bộ và Mailpit, không gửi email Internet. Python tin đúng CA được lấy từ container, không tắt TLS verification và không cài CA vào trust store máy chủ. Nó kiểm tra redirect, static assets/auth page, secure session, header spoofing, public actuator 404, Prometheus/probes thật; tạo hai tài khoản, thu/chi/chuyển ví/budget/feedback; backup mã hóa; xóa một tài khoản và đổi mật khẩu/khóa tài khoản còn lại; restore network=none và đối chiếu số dư, hash/profile/status/version, xóa sessions/tokens. Ciphertext sửa một byte bị từ chối trước ghi target; restore lần hai vào target có bảng bị từ chối. Test rule Prometheus chạy bằng chính image đã ghim. Cleanup chỉ xóa tài nguyên do test tạo, giữ compose phát triển.

Kết quả local mới nhất: 50/50 integration tests backend; 5/5 kiểm tra preflight; build standalone/image và diễn tập HTTPS/encrypted restore/Prometheus đạt. CI mới khai báo workflow Operations, chưa khẳng định kết quả trên GitHub. Bộ browser E2E 11/11 được chạy lại với database riêng; thêm test hệ thống HTTPS có backend/DB thật, không thay bằng mock. Bộ đếm IP dùng chung của proxy test được làm sạch giữa kịch bản sau khi kiểm tra ownership label, không thay đổi giới hạn trong ứng dụng.
