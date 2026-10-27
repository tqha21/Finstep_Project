# ADR 001 — Trạng thái tài khoản khi mất toàn bộ source

Status: **Proposed; chưa triển khai journal, chưa mở gate promotion khi mất source.**

## Vấn đề

Một financial backup ở S3 dù mã hóa, checksum và restore đúng vẫn có thể chứa tài khoản đã xóa, mật khẩu cũ hoặc trạng thái ACTIVE cũ. Checkpoint hiện tại chỉ lấy được từ source đang đọc được. Một file checkpoint định kỳ/S3 object mới nhất không chứng minh mọi yêu cầu xóa/reset/password/profile đã được ghi nhận trước sự cố.

Vì vậy CLI restore vẫn dừng nếu không đọc được authoritative source. Upload/fetch S3 không tạo đường bypass cho scrub, không tự chọn checkpoint cũ và không tự promote target.

## Invariants cần đạt trước khi mở gate

1. Mọi mutation ảnh hưởng quyền truy cập/erasure phải có trạng thái durable độc lập: register/verify, password/reset, profile/email nếu có, disable/reactivate và delete. Chỉ theo dõi DELETE là không đủ.
2. Tính đầy đủ dùng sequence/transaction position và watermark được kiểm chứng; timestamp giữa hai máy không đủ. Nguồn journal/replica phải nằm ngoài failure domain của primary và có ACL/retention độc lập với writer tài chính.
3. Crash ở mỗi ranh giới intent/DB commit/journal acknowledgement phải có quy tắc recovery rõ. Không được coi outbox chưa replicate là đã durable ngoài source; không được coi “không thấy tombstone” là chưa có yêu cầu xóa.
4. Nếu không thể chứng minh trạng thái tài khoản hiện hành, quarantine giữ khóa truy cập. Không áp mật khẩu cũ chỉ vì dữ liệu tài chính restore được. Delete intent cần semantics bảo thủ; password/profile intent chưa rõ commit có thể cần khóa recovery và quy trình xác minh, không mặc nhiên áp credential chưa commit.
5. Chuyển authority cần fencing primary cũ, freeze/cutover plan, audit độc lập và kiểm chứng split-brain. Recovery tool không tự quyết authority dựa vào một backup hoặc một operator timestamp.
6. Secrets/profile trong journal phải được mã hóa, hạn chế quyền, không dùng làm event telemetry. Tombstone/credential retention phải bao phủ mọi backup/version còn có thể restore; cần chính sách xóa và key rotation được review.

## Lựa chọn cần proof-of-concept

- Journal append-only độc lập với intent/commit rõ ràng và replay có thứ tự; account mutations phải xử lý journal unavailable và crash giữa hai hệ thống. Bổ sung HTTP call đơn giản sau DB commit không đạt invariants.
- Replica đồng bộ/authority recovery của database với fencing và vị trí replication được kiểm chứng. Cần thử thực tế semantics acknowledgement, promotion, source loss và erasure/credential state; không suy ra an toàn chỉ từ việc có replica.

Chưa chọn nhà cung cấp, ngân sách, topology hoặc consistency contract. Cần quyết định đó trước khi viết phần chặn/ack mutation vào API; không gắn một remote journal chưa vận hành vào luồng tiền/người dùng một cách ngầm định.

## Acceptance cho milestone DR tiếp theo

Bài test phải backup, đổi mật khẩu/disable/xóa rồi làm primary thực sự không đọc được; replay từ nguồn durable độc lập, restore financial snapshot, xóa tài khoản mất, giữ credentials/status mới, thu hồi mọi phiên và từ chối promotion khi journal stale/mất/đứt chuỗi. Fault injection gồm crash trước/sau từng acknowledgement, duplicate/reorder, split-brain, rollback và thay schema. Đo RPO/RTO, kiểm chứng key recovery và drill trên hạ tầng riêng trước beta.

Hiện chỉ có test source còn sống, encrypted S3 transfer và alert delivery. Chưa có bằng chứng đạt các acceptance DR này.
