# Kiểm thử tải trước private beta

## Phạm vi

`bash scripts/test-load.sh` dựng một project Docker riêng, database/volume mới, Mailpit cục bộ và HTTPS loopback. Dùng JAR/Next standalone đã build theo [runbook staging](staging-recovery.md); Python >=3.12 và Docker Compose v2 là đủ, không cần thư viện Python hay dịch vụ đo tải bên ngoài. Với cloud home chỉ đọc, đặt `BUILDX_CONFIG=/workspace/.cache/buildx`.

Runner không nhận URL tùy ý hoặc credentials production. Trước mọi mutation, nó kiểm tra container IDs, Compose project/service labels, giới hạn tài nguyên và cổng HTTPS thuộc proxy test trên loopback. Database phải chưa có tài khoản. Không sửa database phát triển hoặc xóa volume ngoài project test. CA được lấy từ Caddy riêng; client xác thực TLS đầy đủ, không tắt kiểm tra certificate hoặc dùng proxy môi trường. Không gửi email ra Internet.

## Kịch bản cố định

- 8 tài khoản được đăng ký, xác minh email và đăng nhập qua API thật; 2 ví và ngân sách mỗi tài khoản. Giới hạn auth vẫn là 30 POST/phút/IP; setup dùng 24 POST auth, không benchmark login hoặc xóa rate-limit buckets.
- Mỗi tài khoản có 2.000 giao dịch chi lịch sử (16.000 tổng), nạp bằng SQL fixture sau khi kiểm tra ownership và parse UUID. Đây là chuẩn bị dữ liệu, không được tính là throughput API. Tất cả giao dịch nằm trong tháng được truy vấn.
- Warmup không tính vào latency. 8 worker bắt đầu mỗi phase bằng barrier. Client có cookie jar riêng theo worker; hotspot sao chép phiên đã xác thực, không chia sẻ jar có thể bị ghi giữa threads.
- Web: 80 lượt tải đầy đủ HTML landing qua Caddy → Next. Không đo JS hydration, tải assets, Core Web Vitals hoặc thao tác browser; tiếp tục dùng bộ browser E2E riêng cho hành vi UI.
- Mixed: 8 tài khoản × 20 vòng. Mỗi vòng đọc ví, hai trang lịch sử cursor, báo cáo, ngân sách; tạo một khoản chi/chuyển ví luân phiên rồi gửi lại cùng key. Tổng 800 read + 320 write/replay.
- Hotspot: 8 worker cùng một tài khoản/hai ví × 12 vòng, luân phiên chi/chuyển ví và replay: 192 write/replay. Key khác nhau giữa mutation; mỗi replay dùng đúng key/body của mutation đó. Kiểm tra contention và không tạo trùng, không mô phỏng nhiều login của cùng tài khoản.
- Sau tải, đối chiếu **từng ví**, tổng thu/chi/số dư báo cáo, số transaction/transfer/idempotency trong DB và một truy cập ví khác chủ phải trả 404. Không chỉ dựa vào HTTP 2xx.

Tổng **1.392 request được đo**, không tính setup/warmup/reconciliation. Mô hình closed-loop, không think time: mỗi worker chờ phản hồi rồi mới gửi tiếp. Python urllib mở kết nối HTTPS mới mỗi request; latency gồm kết nối/TLS và đọc hết body. Phép thử này không giữ tốc độ đến cố định, không dùng để suy ra throughput cực đại, sức chịu burst hoặc số người dùng được bảo đảm.

## Ngưỡng regression ban đầu

| Điều kiện | Gate |
| --- | --- |
| Read, gồm HTML, p95 nearest-rank mỗi phase | ≤1.500 ms |
| Write/replay p95 mỗi phase | ≤2.000 ms |
| p99 mọi nhóm | ≤5.000 ms |
| HTTP sai status, timeout, sai dữ liệu | 0 |
| Thiếu request dự kiến hoặc sai đối chiếu | Thất bại |
| Container app/DB/proxy bị restart, OOM, không còn chạy | Thất bại |

Ngưỡng được định trước khi chạy; không tự nới ngưỡng khi test lỗi. Đây là regression budget kỹ thuật, chưa phải SLO được xác nhận với người dùng. Status 201 cho create, 200 cho replay; không coi 429 là thành công hoặc vô hiệu hóa auth/CSRF để tăng số đo.

Overlay chỉ áp dụng cho test: PostgreSQL 1 CPU/512 MiB, API 1 CPU/768 MiB (Java heap tối đa 65% container RAM, thoát khi OOM), Next 0,5 CPU/384 MiB, Caddy 0,5 CPU/128 MiB. Các giới hạn này **chưa được áp dụng cho staging thực** và không phải đề xuất sizing production. Load generator và services cùng máy; cache, CPU khác/CI khác và mạng ngoài máy làm số đo thay đổi.

## Báo cáo và xử lý lỗi

```bash
bash scripts/test-load.sh
# Kết quả chi tiết, không commit artifacts:
python3 -m json.tool artifacts/load/report.json
```

Báo cáo có cấu hình fixture, resource limits/image IDs thực tế, số request/status, p50/p95/p99/max, thời gian từng phase, reconciliation và kết quả gate. Không chứa email, session/CSRF, mật khẩu, UUID tài khoản, ghi chú hoặc số tiền của người dùng. Khi scenario lỗi, ghi báo cáo với `passed: false`, stage và loại lỗi; wrapper giữ startup/container logs để điều tra. Trường hợp lỗi trước khi Python runner khởi động có thể chỉ có logs; báo cáo cũ được xóa trước mỗi lần chạy để không nhầm kết quả. Mỗi phase có deadline 120 giây, request đang chạy có timeout 8 giây. Dữ liệu thử và CA/private key của Caddy bị xóa cùng project khi kết thúc, kể cả khi gate không đạt.

Workflow Operations chạy test sau build và các diễn tập recovery/reliability. Báo cáo JSON được giữ 14 ngày trên CI dù test đạt hay lỗi; operational logs khi lỗi giữ 7 ngày. Không coi khai báo workflow là bằng chứng CI đã chạy đạt. Khi lỗi: xem group/status/failure stage, đối chiếu resource limits, kiểm tra restart/OOM và truy vấn chậm; sửa nguyên nhân rồi chạy lại cùng fixture và ngưỡng. `python3 scripts/test-load-runner.py` kiểm tra HTTP 200 bị cắt body/JSON lỗi vẫn được đếm thất bại, và project không thuộc test bị từ chối trước khi gọi Docker.

## Bằng chứng cục bộ ngày 27/10/2026

| Nhóm | Requests | p95 | p99 |
| --- | --- | --- | --- |
| HTML landing | 80 | 62,53 ms | 67,56 ms |
| Mixed read | 800 | 91,31 ms | 100,78 ms |
| Mixed write/replay | 320 | 92,22 ms | 103,45 ms |
| Hotspot write/replay | 192 | 81,12 ms | 86,88 ms |

Cả 1.392 request đúng status, đối chiếu ledger/report đạt, không restart/OOM. Đây là một lượt chạy ngắn trên cloud workspace với generator và services cùng máy; không cam kết lặp lại latency này trên Internet hoặc CI khác.

Đã chủ động dừng **container API của project test** ngay sau setup: mixed nhận 502, runner trả exit khác 0, báo cáo `passed: false`/stage `mixed`, giữ logs và cleanup hết container test. Đã thử đưa container database phát triển vào kiểm tra ownership: bị từ chối trước mutation. Ba kiểm tra runner ở trên đạt; database phát triển được giữ nguyên. Các phép thử lỗi này kiểm chứng harness không báo thành công chỉ vì web còn trả 200 hoặc phase trước đã đạt.

## Giới hạn trước beta thật

Đây là smoke/regression ngắn với 8 worker; chưa phải soak test, 20–30 tài khoản hoạt động đồng thời, open-loop arrivals hoặc auth burst. Không đo export dữ liệu lớn, mật khẩu BCrypt đồng thời, backup cạnh tranh tải, mất DB, dung lượng ổ đĩa tăng lâu dài hay môi trường Internet. Chưa có percentile trải nghiệm từ người dùng thật. Cần chạy bộ này trên bản image và cấu hình staging dự định phát hành, bổ sung tải/soak theo lượng dùng dự kiến trước khi kết luận capacity.

Release vẫn bị chặn bởi domain/HTTPS/SMTP/S3/IAM/retention/receiver thật, uptime ngoài máy chủ, người trực và liên hệ hỗ trợ, chính sách beta, bằng chứng discovery; disaster recovery khi mất source vẫn cần authority/journal độc lập theo ADR. Test tải đạt không mở các gate đó.
