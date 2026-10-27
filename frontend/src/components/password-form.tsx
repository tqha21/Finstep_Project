"use client";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { api, ApiError, errorMessage } from "@/lib/api";
import { ErrorNotice } from "./feedback";
export function PasswordForm() {
  const router=useRouter();
  const [pending,setPending]=useState(false),[error,setError]=useState("");
  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if(pending) return; setPending(true); setError("");
    const form=new FormData(event.currentTarget), next=String(form.get("newPassword"));
    try {
      if(next!==String(form.get("confirm"))) throw new Error("Hai mật khẩu mới chưa trùng khớp.");
      if(new TextEncoder().encode(next).length>72) throw new Error("Mật khẩu mới quá dài. Tối đa 72 byte UTF-8.");
      await api("/me/password","POST",{currentPassword:String(form.get("currentPassword")),newPassword:next});
      router.replace("/password-changed"); router.refresh();
    } catch(cause) {
      if(cause instanceof ApiError && (cause.status===0 || cause.status>=500 || cause.status===401 || cause.status===403)) {
        try { await api("/me"); }
        catch(check) { if(check instanceof ApiError && check.status===401) {router.replace("/password-change-unknown");return;} }
        setError("Chưa xác nhận được kết quả đổi mật khẩu. Bạn có thể kiểm tra đăng nhập bằng mật khẩu mới trước khi thử lại.");
      } else setError(errorMessage(cause));
      setPending(false);
    }
  }
  return <section className="panel profile-panel" aria-labelledby="password-heading"><div className="panel-heading"><div><h2 id="password-heading">Đổi mật khẩu</h2><p>Yêu cầu mật khẩu hiện tại của bạn.</p></div></div>{error && <ErrorNotice message={error}/>}<form method="post" onSubmit={save} className="stack-form"><fieldset disabled={pending}><label>Mật khẩu hiện tại<input aria-label="Mật khẩu hiện tại" type="password" name="currentPassword" autoComplete="current-password" maxLength={72} required/></label><label>Mật khẩu mới<input aria-label="Mật khẩu mới" type="password" name="newPassword" autoComplete="new-password" minLength={12} maxLength={72} required/><span className="field-hint">Ít nhất 12 ký tự, tối đa 72 byte UTF-8 và khác mật khẩu hiện tại.</span></label><label>Nhập lại mật khẩu mới<input aria-label="Nhập lại mật khẩu mới" type="password" name="confirm" autoComplete="new-password" minLength={12} maxLength={72} required/></label><p className="field-hint">Sau khi đổi, mọi thiết bị sẽ đăng xuất, kể cả thiết bị này. Mã đặt lại mật khẩu cũ cũng hết hiệu lực.</p></fieldset><button className="button button-primary" disabled={pending}>{pending?"Đang đổi mật khẩu…":"Đổi mật khẩu"}</button></form></section>;
}
