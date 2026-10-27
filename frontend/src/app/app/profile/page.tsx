"use client";
import { useState } from "react";
import { api, errorMessage } from "@/lib/api";
import type { User } from "@/lib/types";
import { useUpdateUser, useUser } from "@/components/protected-shell";
import { PasswordForm } from "@/components/password-form";
import { PrivacyControls } from "@/components/privacy-controls";
import { ErrorNotice } from "@/components/feedback";
export default function ProfilePage() {
  const user = useUser(), updateUser = useUpdateUser();
  const [pending, setPending] = useState(false), [error, setError] = useState(""), [message, setMessage] = useState("");
  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (pending) return; setPending(true); setError(""); setMessage("");
    const form = new FormData(event.currentTarget);
    try { const result = await api<User>("/me", "PATCH", { fullName: String(form.get("fullName")), timezone: String(form.get("timezone")) }); updateUser(result); setMessage("Đã cập nhật hồ sơ. Báo cáo tháng tiếp theo sử dụng múi giờ mới."); }
    catch (cause) { setError(errorMessage(cause)); } finally { setPending(false); }
  }
  return <><header className="dashboard-header"><div><span className="eyebrow">Thiết lập cá nhân</span><h1>Hồ sơ của bạn</h1><p>Múi giờ quyết định ngày và tháng trong báo cáo; thời điểm đã ghi không thay đổi.</p></div></header><section className="panel profile-panel">{message && <p role="status" className="success-message">{message}</p>}{error && <ErrorNotice message={error}/>}<form method="post" onSubmit={save} className="stack-form"><fieldset disabled={pending}><label>Họ và tên<input name="fullName" maxLength={150} defaultValue={user.fullName} autoComplete="name" required/></label><label>Email<input value={user.email} readOnly type="email"/><span className="field-hint">Địa chỉ đăng nhập đã xác minh.</span></label><label>Múi giờ<input aria-label="Múi giờ" name="timezone" list="timezones" defaultValue={user.timezone} maxLength={100} required/><datalist id="timezones">{["Asia/Ho_Chi_Minh", "Asia/Bangkok", "Asia/Tokyo", "Asia/Singapore", "Europe/London", "America/New_York", "UTC"].map(zone => <option key={zone} value={zone}/>)}</datalist><span className="field-hint">Ví dụ: Asia/Ho_Chi_Minh hoặc UTC. Có thể nhập múi giờ IANA khác.</span></label><p className="field-hint">Tiền tệ: VND. Số tiền được ghi bằng đồng Việt Nam.</p></fieldset><button className="button button-primary" disabled={pending}>{pending ? "Đang lưu…" : "Lưu hồ sơ"}</button></form></section><PasswordForm/><PrivacyControls/></>;
}
