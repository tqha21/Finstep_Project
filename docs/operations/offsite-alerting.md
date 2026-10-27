# Backup S3 có kiểm chứng và gửi cảnh báo

## Trạng thái

Có CLI xuất bản/tải lại bundle mã hóa lên bucket S3 có sẵn, worker backup + metrics, profile Alertmanager với webhook Bearer, và kiểm thử tích hợp thật trong các container tách riêng. **Chưa cấu hình bucket, IAM, retention, webhook hay người trực bên ngoài.** Garage là S3 fixture cục bộ dùng trong test, không phải đề xuất đặt backup trên cùng máy chủ ứng dụng.

Không thay đổi API tài chính hoặc cấu trúc database. Restore hiện vẫn cần source đọc được theo [runbook recovery](staging-recovery.md); tải được một backup ngoài máy chủ không giải quyết journal xóa/đổi mật khẩu khi mất toàn bộ source. Thiết kế cần chốt được ghi trong [ADR DR](../decisions/001-disaster-recovery-account-state.md).

## Chuẩn bị S3

Cần Python >=3.12, OpenSSL 3, Docker cho producer và rclone trong PATH. Local đã dùng rclone 1.60.1 của môi trường; production cần chọn bản stable còn được bảo trì và kiểm chứng với provider thực tế. Chưa đo tải file lớn/multipart hoặc đo tốc độ đường truyền ngoài máy chủ.

1. Tạo bucket **ngoài failure domain của app**, bật versioning/retention/Object Lock phù hợp bằng tài khoản quản trị riêng. Không có lệnh tự tạo bucket hoặc áp lifecycle trong CLI backup.
2. Cấp quyền publisher theo đúng prefix: Put/Get/List và các quyền multipart cần thiết, không DeleteObject. Reader recovery chỉ đọc. Hai mẫu IAM trong `ops/offsite/` là tài liệu để review, chưa được áp dụng hoặc xác minh trên AWS. Provider S3 khác có thể cần quyền khác; fixture Garage không chứng minh IAM AWS.
3. Copy `ops/offsite/rclone.conf.example` ra `/secure/finstep/rclone.conf`, mode 0600. Đặt key riêng cho môi trường, không dùng root credentials. Vị trí bucket/prefix thuộc tham số, endpoint/credentials trong file private. Không truyền giá trị secret qua argv hoặc Git. CLI chỉ hỗ trợ explicit S3 credentials ở file, chưa dùng IAM role/env_auth hay encrypted rclone config.
4. Endpoint custom phải là HTTPS origin không userinfo/query/path; provider AWS có thể dùng endpoint mặc định theo region. Không tắt TLS verification. CLI loại các biến RCLONE_/AWS_ kế thừa để chúng không âm thầm đổi endpoint/credentials, đồng thời ép kiểm tra chứng chỉ. `--local-test` chỉ cho HTTP loopback của test, không cho một máy chủ HTTP ngoài mạng.

```bash
# Bundle đã được tạo bằng backup-db.sh; chỉ certificate public có trên writer.
python3 scripts/backup_store.py --config /secure/finstep/rclone.conf \
  --remote offsite:YOUR_BUCKET/finstep-staging upload /secure/backups/finstep-bundle.cms
# CLI in backupId và trạng thái readback, không in credentials hoặc dữ liệu tài chính.
python3 scripts/backup_store.py --config /secure/recovery/reader-rclone.conf \
  --remote offsite:YOUR_BUCKET/finstep-staging fetch BACKUP_UUID /secure/recovery/download.cms
```

### Giao thức xuất bản

Mỗi lần upload có UUID mới, không ghi đè một backup có ID cũ. Dưới `prefix/UUID/`:

- `data.cms`: bundle đã mã hóa. Guard kiểm tra CMS AuthEnvelopedData, từ chối dump/key PEM/plaintext gửi nhầm; không coi đây là kiểm tra recipient/GCM tag đầy đủ.
- Sau PUT, writer GET lại toàn bộ object và đối chiếu SHA-256/size với file nguồn. Chỉ khi khớp mới PUT `receipt.json` chứa format, UUID, SHA-256, size và thời điểm xuất bản.
- Receipt là dấu hoàn tất. Object không có receipt không phải backup đã xuất bản; upload gián đoạn có thể để lại orphan data, được xử lý bởi retention quản trị riêng.
- Fetch yêu cầu receipt đúng định dạng/UUID, GET về file tạm 0600, kiểm tra hash/size/header, rồi mới xuất bản file đích bằng thao tác không ghi đè. File đích đã có bị từ chối, kể cả race tạo file. File tải lỗi không được để lại dưới tên đích.

CLI không giải mã trên writer/transfer host và không yêu cầu private key. Giải mã/xác thực GCM và review database vẫn diễn ra trên recovery host, không tự promote. Metadata receipt không chứng minh nguồn phát hành; kẻ có quyền Put có thể thay cả object lẫn receipt. UUID/`--immutable` phía client tránh lỗi ghi đè thông thường, **không thay thế IAM, Object Lock hoặc chữ ký người gửi**. Chỉ nhận backup từ kho được tin cậy.

`ops/offsite/retention.example.json` đề xuất 30 ngày cho prefix staging, phiên bản cũ và 7 ngày cho multipart dở dang. Đây là mẫu **chưa áp dụng** và phải review pháp lý/chi phí trước khi bật vì expiration xóa dữ liệu. Object Lock có thể làm retention dài hơn lifecycle. Chưa có công cụ tự prune. Cần giữ private key đến khi hết tất cả backup/version cần giải mã, và công bố retention thật trước beta.

## Worker và metrics

```bash
python3 scripts/backup_store.py --config /secure/finstep/rclone.conf \
  --remote offsite:YOUR_BUCKET/finstep-staging backup finstep-staging-db-1 \
  /var/lib/finstep/backups /secure/finstep/backup-recipient.crt \
  --metrics-file /var/lib/finstep/backup-metrics/offsite.prom
```

Worker chạy producer PostgreSQL hiện có, rồi upload/readback/receipt. Lỗi ở bất cứ bước nào trả nonzero. Có thể dùng `upload ... --metrics-file ...` cho bundle đã tạo. Mẫu service/timer `finstep-offsite-backup.*` chưa được cài đặt; review paths/prefix/quyền rồi chọn một lịch phù hợp, không mặc định bật cả timer local và offsite trùng nhau. Mẫu đề xuất 07:15 Bangkok (00:15 UTC), jitter 15 phút, timeout job 2 giờ; chưa đo RPO/RTO thực tế.

Metrics textfile chỉ có số, không có bucket/key/email/ghi chú/số tiền:

- `finstep_backup_offsite_last_success_timestamp_seconds`: chỉ đổi sau upload và readback thành công.
- `finstep_backup_offsite_last_attempt_timestamp_seconds`: lần hoàn tất attempt gần nhất.
- `finstep_backup_offsite_last_attempt_success`: 1 hoặc 0.

Attempt lỗi giữ nguyên last-success, không làm heartbeat giả xanh. Ghi `.prom` atomic; file 0644, thư mục collector 0755 và không cho group/others ghi. Đừng đặt metrics trong thư mục secrets. Profile `offsite` bật node-exporter **chỉ textfile collector**, không mount host proc/filesystem. Nếu producer bị SIGKILL/mất điện thì chưa chắc kịp ghi failure; cảnh báo overdue/missing vẫn cần thiết.

## Alertmanager và webhook

Profile `alerting` bật Alertmanager private, không publish cổng. Prometheus có target alertmanager:9093; profile chưa bật thì không có delivery. Config mặc định receiver `unconfigured` không gửi đi đâu. Tạo riêng private config và token; tài khoản chạy container phải trùng owner UID/GID của config/token/state (non-root).

```bash
# Tạo qua secret manager/editor an toàn, không đặt endpoint/token trong argv/history.
# /secure/finstep/alert-webhook-url: HTTPS endpoint nhận JSON chuẩn Alertmanager, không query/userinfo.
# /secure/finstep/alert-webhook-token: Bearer token riêng >=24 ký tự.
chmod 600 /secure/finstep/alert-webhook-url /secure/finstep/alert-webhook-token
python3 scripts/configure-alerts.py --url-file /secure/finstep/alert-webhook-url \
  --token-file /secure/finstep/alert-webhook-token --output /secure/finstep/alertmanager.generated.json
```

Generator không in endpoint/token. Config chứa URL và đường dẫn token trong container, không chép giá trị token vào config. HTTP client không theo redirect; production dùng HTTPS verification mặc định. Bật `send_resolved`. Group wait 30 giây, group interval 5 phút và repeat 4 giờ; không phải cam kết latency giao cảnh báo. Test local dùng group wait/interval 1 giây. Generator từ chối ghi đè file đang có, cần tạo file mới rồi review/đổi cấu hình có chủ ý.

Đây là webhook JSON Alertmanager, **không tự tương thích payload Slack/Telegram/email**. Nếu cần các dịch vụ đó phải cấu hình receiver/adapter đúng loại và người nhận được chấp thuận. Hiện không có người trực, chưa gửi tới người thật. Token chỉ bảo vệ kênh; receiver phải lưu/xử lý tín hiệu đúng và có chính sách bảo vệ endpoint. State Alertmanager giữ silences/dedup trên bind directory private, cần quản lý quyền và recovery riêng; không nằm trong backup PostgreSQL.

Các rule thêm vào bốn rule cũ: offsite attempt lỗi >1 phút, không có upload verified trong 26 giờ >5 phút, thiếu metric trạng thái >15 phút. Các profile chưa cấu hình sẽ tạo điều kiện missing status; đây là dấu chưa hoàn tất backup chứ không phải bằng chứng đã có offsite. Prometheus/Alertmanager trên cùng host không báo được nếu cả host mất. Uptime/backup freshness monitor từ hệ thống khác và thông báo đúng người trực vẫn là gate.

## Bật profile sau khi có cấu hình thật

Điền các biến mới trong `ops/staging/.env.example`, tạo state dir private đúng UID và metrics dir chỉ chứa số liệu public, rồi:

```bash
python3 scripts/staging-preflight.py ops/staging/.env --with-alerting --with-offsite
# Không in compose config/secrets ra terminal.
docker compose --env-file ops/staging/.env -f compose.staging.yaml config --quiet
docker compose --env-file ops/staging/.env -f compose.staging.yaml --profile alerting --profile offsite up -d
```

Đây là bước triển khai cần host/provider/recipient thật; repository hiện chỉ chuẩn bị, chưa thực hiện. Kiểm chứng IAM prefix, endpoint TLS, multipart, lifecycle/Object Lock, key recovery, restore source/journal, failure notification và escalation trước beta. Không copy credentials fixture Garage vào cấu hình thực.

## Kiểm thử lại

```bash
python3 scripts/test-staging-preflight.py   # 7 kiểm tra, gồm optional profiles
python3 scripts/test-backup-store.py        # 7 kiểm tra format/config/metrics
python3 scripts/test-alert-config.py        # 4 kiểm tra TLS/auth/redirect/file permissions
bash scripts/test-reliability.sh
```

Integration test không cần cloud credentials hoặc backend build: tạo Garage S3 riêng, bucket/key giới hạn trong fixture, CMS của dữ liệu giả, Prometheus/Alertmanager/collector và webhook receiver riêng. Nó kiểm tra upload/GET/checksum/decrypt, từ chối file đích đã có, object hỏng, object thiếu receipt; receiver 401 khi thiếu Bearer, cố tình 503 lần đầu để kiểm chứng retry, và nhận cả firing/resolved. Một upload S3 sai key ghi metric lỗi; production rule chờ thật 1 phút rồi gửi cảnh báo, upload tốt sau đó gửi resolved. Test không tắt bảo vệ TLS production hoặc liên hệ người nhận Internet. HTTP chỉ ở loopback/private fixture.

CI Operations cài rclone và chạy cả suite này lẫn diễn tập PostgreSQL/HTTPS cũ. Backend/11 browser E2E của milestone trước vẫn là kiểm chứng riêng; không thêm hoặc thay đổi API ứng dụng trong milestone này. Chưa khẳng định kết quả CI trên GitHub hoặc compatibility với S3/provider thật trước khi được cấu hình.
