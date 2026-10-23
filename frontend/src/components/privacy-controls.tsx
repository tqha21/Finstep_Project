"use client";
import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Download, ShieldCheck, Trash2 } from "lucide-react";
import { api, ApiError, downloadPersonalData, errorMessage } from "@/lib/api";
import { Dialog } from "./dialog";
import { ErrorNotice } from "./feedback";
export function PrivacyControls() {
  const [deleting, setDeleting] = useState(false), [exporting, setExporting] = useState(false);
  const [error, setError] = useState(""), [message, setMessage] = useState("");
  async function download() {
    if (exporting) return; setExporting(true); setError(""); setMessage("");
    try { await downloadPersonalData(); setMessage("Đã tải tệp dữ liệu cá nhân. Hãy giữ tệp ở nơi riêng tư."); }
    catch (cause) { setError(errorMessage(cause)); } finally { setExporting(false); }
  }
  return <section className="panel profile-panel privacy-panel" aria-labelledby="privacy-heading"><div className="panel-heading"><div><h2 id="privacy-heading">Dữ liệu và quyền riêng tư</h2><p>Bạn có thể giữ một bản sao hoặc xóa tài khoản.</p></div><ShieldCheck size={23}/></div><p>Xuất hồ sơ, tất cả ví, danh mục, thu chi, chuyển ví và ngân sách thành tệp JSON. Tệp gồm cả mục lưu trữ và giao dịch đã xóa khỏi ghi chép.</p>{error && <ErrorNotice message={error}/>} {message && <p className="success-message" role="status">{message}</p>}<button className="button button-secondary" disabled={exporting} onClick={download}><Download size={17}/>{exporting ? "Đang xuất dữ liệu…" : "Xuất toàn bộ dữ liệu"}</button><Link className="privacy-link" href="/privacy">Đọc thông tin quyền riêng tư</Link><div className="danger-zone"><h3>Xóa tài khoản</h3><p>Xóa vĩnh viễn hồ sơ và mọi ghi chép trong cơ sở dữ liệu ứng dụng, đồng thời đăng xuất mọi thiết bị. Hãy xuất dữ liệu trước nếu bạn muốn giữ bản sao.</p><button className="button button-danger" disabled={exporting} onClick={() => setDeleting(true)}><Trash2 size={16}/>Xóa tài khoản</button></div>{deleting && <DeletionDialog onClose={() => setDeleting(false)}/>}</section>;
}
function DeletionDialog({ onClose }: { onClose: () => void }) {
  const router = useRouter();
  const [pending, setPending] = useState(false), [error, setError] = useState("");
  async function remove(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (pending) return; setPending(true); setError("");
    const form = new FormData(event.currentTarget);
    try {
      await api("/me", "DELETE", { password: String(form.get("password")), confirmation: String(form.get("confirmation")) });
      router.replace("/account-deleted"); router.refresh();
    } catch (cause) {
      // A lost response can happen after commit. Never label this as a failed deletion or retry blindly.
      if (cause instanceof ApiError && (cause.status === 0 || cause.status >= 500 || cause.status === 401)) {
        try { await api("/me"); }
        catch (check) {
          if (check instanceof ApiError && check.status === 401) { router.replace("/account-deletion-unknown"); return; }
        }
        setError("Chưa xác nhận được kết quả xóa. Kiểm tra kết nối rồi thử lại; bạn có thể đăng nhập lại để kiểm tra tài khoản.");
      } else setError(errorMessage(cause));
      setPending(false);
    }
  }
  return <Dialog title="Xóa tài khoản vĩnh viễn?" onClose={onClose} pending={pending}>{error && <ErrorNotice message={error}/>}<form method="post" className="stack-form" onSubmit={remove}><fieldset disabled={pending}><p>Hồ sơ, ví, danh mục, thu chi, chuyển ví và ngân sách sẽ bị xóa. Không thể khôi phục bằng Finstep. Mọi phiên đăng nhập và mã đặt lại mật khẩu sẽ bị thu hồi.</p><label>Mật khẩu hiện tại<input aria-label="Mật khẩu hiện tại" type="password" name="password" maxLength={72} autoComplete="current-password" required data-autofocus/></label><label>Nhập XÓA TÀI KHOẢN<input aria-label="Nhập XÓA TÀI KHOẢN" name="confirmation" pattern="XÓA TÀI KHOẢN" autoComplete="off" required/></label></fieldset><div className="actions"><button className="button button-secondary" disabled={pending} type="button" onClick={onClose}>Hủy</button><button className="button button-danger" disabled={pending}>{pending ? "Đang xóa…" : "Xóa vĩnh viễn"}</button></div></form></Dialog>;
}
