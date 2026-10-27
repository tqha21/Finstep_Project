# Đo activation và D7 cho private beta

## Trạng thái và cách đọc

Đã có CLI chỉ đọc `scripts/beta-metrics.py` và query PostgreSQL tạo báo cáo cohort tuần. Không có tracking pixel, SDK analytics, bảng event mới, ID theo dõi hoặc API công khai. Chưa có báo cáo từ người dùng thật, chưa thực hiện phỏng vấn và chưa đạt mục tiêu activation/retention của PRD. Những tỷ lệ trong test là fixture để kiểm chứng phép tính.

Đây là công cụ nội bộ cho người vận hành có quyền database. Dữ liệu cá nhân/tài chính vẫn nằm trong database ứng dụng; query chỉ trả aggregate đã áp dụng suppression. Không gửi báo cáo tới dịch vụ ngoài hoặc dùng kết quả làm bằng chứng người dùng hài lòng.

## Định nghĩa phiên bản 1

| Chỉ số | Định nghĩa |
| --- | --- |
| Cohort | Tuần bắt đầu thứ Hai theo **Asia/Bangkok**, dựa trên `users.created_at`; chỉ lấy ngày đăng ký trong `[from-date, to-date)` và thời điểm `< as-of` |
| Registered | Tài khoản hiện còn tồn tại, gồm chưa xác minh và đang DISABLED; không dùng trạng thái hiện tại để cải thiện mẫu số |
| Verified | Registered có `email_verified_at < as-of` |
| Activation 7d | Có ví được tạo trước/cùng lúc với khoản thu/chi đầu tiên, khoản đó được ghi nhận trong `[signup, signup + 168 giờ)`; chỉ tính tài khoản đã hết cửa sổ 168 giờ |
| Thời gian tới giao dịch đầu | Median số giây từ signup đến khoản thu/chi đầu tiên của nhóm đã activated ở trên; không phải thời gian gõ form; chỉ hiện khi số activated đạt minimum cohort |
| D7 financial creation | Có tạo thu/chi **hoặc chuyển ví** trong ngày lịch thứ 7 sau ngày đăng ký theo Asia/Bangkok; mỗi tài khoản tối đa một lượt; chỉ tính khi toàn bộ ngày D7 đã kết thúc |

Activation và D7 có **mẫu số trưởng thành riêng**. Tài khoản mới chưa đủ thời gian quan sát không được tính là thất bại. D7 không bắt buộc đã activated D0; mẫu số là tất cả registered đã hết ngày D7. Ví dụ đăng ký 06/10/2026 00:30 Bangkok: D7 là `[13/10 00:00, 14/10 00:00)`, tương ứng `[12/10 17:00, 13/10 17:00) UTC`. Activation kết thúc 13/10 00:30 Bangkok, khác ranh giới ngày D7.

Thời gian hành động dùng **`created_at` bất biến của bản ghi**, không dùng `occurred_at` do người dùng nhập. Nhập một khoản từ tháng trước hôm nay không tạo bằng chứng đã quay lại tháng trước. Retry cùng key không tạo row mới; nhiều giao dịch/chuyển ví trong D7 vẫn chỉ là một tài khoản quay lại. Khoản soft-delete vẫn chứng minh đã tạo hành động; không đọc ghi chú, số tiền hoặc thời điểm xóa. Ví archive không làm mất lịch sử activation.

Đây là proxy hẹp của retention: không đo lần mở app, đăng nhập, xem dashboard, sửa/xóa giao dịch, phản hồi, hay sử dụng offline. Có thể bỏ sót người quay lại chỉ xem số liệu. Không gọi chỉ số này là DAU/session retention; cần xác nhận định nghĩa D7 hữu ích qua discovery. Activation 7d là cách vận hành cụ thể hóa mục tiêu ban đầu; chưa chứng minh ngưỡng ≥40% và D7 ≥30% phù hợp với beta.

## Giới hạn dữ liệu và quyền riêng tư

- Query chỉ đọc các cột ID/thời điểm tạo/xác minh cần thiết; không có quyền SELECT email, password hash, số tiền, ghi chú, session, token hoặc feedback. ID chỉ dùng join/đếm trong DB, không xuất ra report.
- Cohort dưới 5 registered mặc định chỉ trả tuần và `suppressed: true`; không trả số chính xác, kể cả cohort rỗng. Mỗi mẫu số activation/D7 cũng phải đạt minimum. Có thể nâng `--min-cohort`, không hạ dưới 5. Median còn đòi hỏi số activated đạt minimum.
- Suppression hạn chế vô ý lộ nhóm nhỏ; **không bảo đảm ẩn danh** khi đối chiếu nhiều cửa sổ/nguồn khác. Báo cáo vẫn chỉ dành nội bộ, không publish dashboard người dùng hoặc gửi bên ngoài.
- Tài khoản đã xóa cùng dữ liệu biến mất khỏi lần báo cáo tiếp theo. Không giữ identity/tombstone riêng vì analytics. Vì vậy có survivorship bias: denominator/tỷ lệ quá khứ có thể thay đổi sau xóa, không phải cohort đăng ký bất biến.
- `as-of` lọc thời điểm bản ghi hiện có; không phục dựng database tại thời điểm quá khứ. Không thể biết tài khoản đã bị xóa, thời điểm các lần xem/sửa, hoặc các bản ghi ngoài snapshot hiện tại. Backup/restore/import và clock sai có thể thay đổi kết quả; ghi nhận nguồn và thời điểm report khi so sánh.
- CLI không tự lưu báo cáo. Nếu redirect ra file, dùng umask 077, nơi nội bộ có kiểm soát và xóa trong vòng 7 ngày theo quy trình beta đề xuất; lịch xóa chưa được cài tự động. Bản report cũ đã lưu không tự thay đổi khi người dùng xóa tài khoản. Không commit báo cáo dữ liệu thật vào Git.

## Cài role và chạy

Role là cấu hình vận hành **chưa được áp dụng lên DB phát triển hoặc staging thật**, không thuộc Flyway. Chạy một lần bằng administrator có CREATEROLE và quyền grant trên các bảng sau khi review; role NOLOGIN, không superuser/create role/create DB. CREATE và grants cùng transaction, lỗi rollback toàn bộ. Nếu role đã tồn tại, CREATE báo lỗi để người vận hành kiểm tra quyền hiện có, không tự tái sử dụng một role quá rộng. Không chạy bằng `--echo-all` hoặc bật log query chứa dữ liệu nhạy cảm.

```bash
# Ví dụ container staging đã review; role thuộc cluster, grants áp dụng DB hiện tại.
docker exec -i finstep-staging-db-1 sh -ceu \
  'exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  < ops/analytics/reader-role.sql
python3 scripts/beta-metrics.py finstep-staging-db-1 \
  --from-date 2026-10-05 --to-date 2026-10-27
# Tùy chọn cutoff có timezone, mặc định thời điểm chạy UTC:
python3 scripts/beta-metrics.py finstep-staging-db-1 \
  --from-date 2026-10-05 --to-date 2026-10-27 --as-of 2026-10-27T00:00:00Z --min-cohort 5
```

Script dùng Docker exec qua tài khoản vận hành được tin cậy; không nhận SQL, URL database hoặc secret trên CLI. Kết nối bằng user của container rồi bắt buộc `SET LOCAL ROLE finstep_metrics_reader` trong transaction **REPEATABLE READ READ ONLY**, giới hạn statement 30 giây/command 45 giây. User kết nối cần quyền SET ROLE: bootstrap superuser trong Docker đã có quyền; với operator không phải superuser, administrator cần grant membership riêng sau review. Không cấp reader role hoặc quyền đọc cohort cho người dùng web. Thiếu role/grants, schema không phù hợp hoặc query lỗi trả exit khác 0 và không in stderr DB. Ngày được validate rồi truyền thành psql value có quote/cast; khoảng signup tối đa 366 ngày, cutoff phải có timezone và không ở tương lai. Query không tự tạo role, table, index hay sửa dữ liệu.

Mỗi report dùng một snapshot. Cửa sổ đầu/cuối có thể chỉ chứa một phần tuần, nên không so sánh cohort từ hai phạm vi ngày khác nhau như cùng mẫu số. Chưa benchmark query trên database production lớn; có thể scan lịch sử hiện có, cần chạy ngoài giờ cao điểm hoặc đọc replica đã review độ trễ. Không chuyển sang đọc replica cũ mà bỏ ghi nhận sự chậm trễ/xóa dữ liệu.

Role PostgreSQL thuộc cluster, grants gắn với từng database. Backup/restore hiện dùng `--no-acl`, không mang role/grants sang recovery: sau recovery hợp lệ cần review và cấp lại grants riêng, không bỏ bước SET ROLE hoặc chạy report bằng quyền rộng hơn để vượt lỗi. Không chạy lại CREATE mù quáng nếu cluster đã có role cùng tên.

## Kiểm chứng

```bash
python3 scripts/test-beta-metrics.py
```

Bộ test tạo PostgreSQL 17 riêng `--network none`, áp dụng migration V1–V5, role và fixture; không publish cổng hoặc dùng volume phát triển. Kiểm tra ranh giới Bangkok/D7 và 168 giờ, backdated occurred_at, soft-delete, DISABLED, cohort nhỏ/chưa trưởng thành, thay đổi sau xóa, không xuất payload/identity và quyền bị từ chối khi đọc email/password/amount/note/session hoặc DELETE. Thiếu reader role phải lỗi; arguments không hợp lệ phải bị chặn trước query. Cleanup xóa đúng container test. CI Database chạy cùng lệnh.

Kết quả cục bộ ngày 27/10/2026: **9/9 kiểm tra đạt**, gồm cả CLI JSON/sanitized errors; bộ SQL financial integrity hiện có cũng đạt. Không thay schema, không thêm dữ liệu hoặc role vào database phát triển.

## Dùng trong vòng beta

Trước khi mời người thật, vẫn cần hoàn tất release gates về hạ tầng, SMTP, backup/DR, người trực và chính sách. Khi beta được phép chạy, đánh giá cohort đã trưởng thành mỗi tuần cùng phản hồi tự nguyện. Với nhóm nhỏ bị suppression, tiếp tục phỏng vấn và chờ đủ mẫu; không hạ ngưỡng hay dựng số liệu. Khi activation thấp, kiểm tra luồng xác minh/tạo ví/giao dịch đầu; khi D7 thấp, xác nhận nhu cầu ghi chép qua phỏng vấn trước khi bổ sung P1/P2.
