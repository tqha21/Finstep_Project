"use client";
import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowRight, CheckCircle2, LoaderCircle } from "lucide-react";
import { api, ApiError, errorMessage } from "@/lib/api";
import { ErrorNotice } from "./feedback";
import { useHydrated } from "@/lib/use-hydrated";
export type AuthMode = "login" | "register" | "verify" | "forgot" | "reset";
const copy = {
  login: ["Mừng bạn trở lại", "Đăng nhập để tiếp tục hành trình quản lý tiền của bạn.", "Đăng nhập"],
  register: ["Bắt đầu một thói quen tốt", "Tạo tài khoản và chủ động với từng khoản thu chi.", "Tạo tài khoản"],
  verify: ["Xác minh email của bạn", "Mở email và dán mã xác minh để kích hoạt tài khoản.", "Xác minh email"],
  forgot: ["Lấy lại quyền truy cập", "Nhập email tài khoản. Chúng tôi sẽ gửi hướng dẫn đặt lại mật khẩu.", "Gửi hướng dẫn"],
  reset: ["Một mật khẩu mới", "Dán mã trong email và chọn mật khẩu mới cho tài khoản.", "Đặt lại mật khẩu"],
} as const;
export function AuthForm({ mode }: { mode: AuthMode }) {
  const router = useRouter(), ready = useHydrated();
  const [pending, setPending] = useState(false), [error, setError] = useState(""), [success, setSuccess] = useState(false);
  const [resendEmail, setResendEmail] = useState(""), [resent, setResent] = useState(false);
  const [resending, setResending] = useState(false);
  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (pending) return; setPending(true); setError("");
    const data = new FormData(event.currentTarget), value = (name: string) => String(data.get(name) ?? "");
    try {
      if (["register", "reset"].includes(mode)) {
        if (value("password") !== value("confirm")) throw new Error("Hai mật khẩu chưa trùng khớp.");
        if (new TextEncoder().encode(value("password")).length > 72) throw new Error("Mật khẩu quá dài. Hãy chọn mật khẩu ngắn hơn.");
      }
      if (mode === "login") { await api("/auth/login", "POST", { email: value("email").trim(), password: value("password") }); router.replace("/app"); router.refresh(); }
      else if (mode === "register") { await api("/auth/register", "POST", { email: value("email").trim(), password: value("password"), fullName: value("fullName").trim() }); setSuccess(true); }
      else if (mode === "verify") { await api("/auth/verify-email", "POST", { token: value("token").trim() }); setSuccess(true); }
      else if (mode === "forgot") { await api("/auth/forgot-password", "POST", { email: value("email").trim() }); setSuccess(true); }
      else { await api("/auth/reset-password", "POST", { token: value("token").trim(), password: value("password") }); setSuccess(true); }
    } catch (cause) { setError(mode === "login" && cause instanceof ApiError && cause.status === 401 ? "Email hoặc mật khẩu không hợp lệ, hoặc email chưa được xác minh." : errorMessage(cause)); }
    finally { setPending(false); }
  }
  async function resend(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setResending(true); setError("");
    try { await api("/auth/resend-verification", "POST", { email: resendEmail.trim() }); setResent(true); }
    catch (cause) { setError(errorMessage(cause)); } finally { setResending(false); }
  }
  const [title, description, action] = copy[mode];
  if (success) return <div className="auth-success" role="status"><span className="success-icon"><CheckCircle2 size={34}/></span><h1>{mode === "verify" ? "Email đã được xác minh" : mode === "reset" ? "Mật khẩu đã được cập nhật" : "Hãy kiểm tra hộp thư"}</h1><p>{["register", "forgot"].includes(mode) ? "Nếu yêu cầu hợp lệ, bạn sẽ nhận được email hướng dẫn. Hãy kiểm tra cả thư mục thư rác." : "Bạn có thể đăng nhập để tiếp tục sử dụng Finstep."}</p><Link className="button button-primary full-width" href={mode === "register" ? "/auth/verify" : mode === "forgot" ? "/auth/reset" : "/auth/login"}>{mode === "register" ? "Nhập mã xác minh" : mode === "forgot" ? "Nhập mã đặt lại mật khẩu" : "Đến trang đăng nhập"}<ArrowRight size={17}/></Link></div>;
  return <><span className="eyebrow">Từng bước cùng Finstep</span><h1>{title}</h1><p className="auth-description">{description}</p>{error && <ErrorNotice message={error}/>}
    <form method="post" onSubmit={submit} className="stack-form">
      {mode === "register" && <label>Họ và tên<input name="fullName" autoComplete="name" placeholder="Tên bạn muốn được gọi" required maxLength={150}/></label>}
      {["login", "register", "forgot"].includes(mode) && <label>Email<input name="email" type="email" autoComplete="email" placeholder="ban@example.com" required maxLength={255}/></label>}
      {["verify", "reset"].includes(mode) && <label>Mã trong email<input name="token" autoComplete="one-time-code" spellCheck={false} required minLength={43} maxLength={43} placeholder="Dán mã xác nhận"/></label>}
      {["login", "register", "reset"].includes(mode) && <label>Mật khẩu{mode === "reset" ? " mới" : ""}<input name="password" aria-label={mode === "reset" ? "Mật khẩu mới" : "Mật khẩu"} aria-describedby={mode === "login" ? undefined : "password-hint"} type="password" autoComplete={mode === "login" ? "current-password" : "new-password"} required minLength={mode === "login" ? 1 : 12} maxLength={72}/>{mode !== "login" && <span id="password-hint" className="field-hint">Ít nhất 12 ký tự. Chọn mật khẩu chỉ dùng cho Finstep.</span>}</label>}
      {["register", "reset"].includes(mode) && <label>Nhập lại mật khẩu<input name="confirm" type="password" autoComplete="new-password" required minLength={12} maxLength={72}/></label>}
      {mode === "login" && <Link className="form-aside" href="/auth/forgot">Quên mật khẩu?</Link>}
      <button className="button button-primary full-width" disabled={!ready || pending}>{pending ? <LoaderCircle className="spin" size={18}/> : <ArrowRight size={18}/>} {pending ? "Đang xử lý…" : action}</button>
    </form>
    {mode === "register" && <p className="auth-switch"><Link href="/privacy">Đọc thông tin quyền riêng tư trước khi đăng ký</Link></p>}
    {mode === "verify" && <details className="resend"><summary>Chưa nhận được mã?</summary><form method="post" onSubmit={resend} className="stack-form"><label>Email nhận mã<input type="email" required value={resendEmail} onChange={e => setResendEmail(e.target.value)}/></label><button className="button button-secondary" disabled={!ready || resending}>{resending ? "Đang gửi…" : "Gửi lại hướng dẫn"}</button>{resent && <p role="status">Nếu tài khoản phù hợp, email hướng dẫn sẽ được gửi.</p>}</form></details>}
    <p className="auth-switch">{mode === "login" ? <>Chưa có tài khoản? <Link href="/auth/register">Đăng ký miễn phí</Link></> : <>Đã có tài khoản? <Link href="/auth/login">Đăng nhập</Link></>}</p></>;
}
