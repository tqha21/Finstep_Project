import Link from "next/link";
import { Brand } from "@/components/brand";
export default function PasswordChangedPage() {
  return <main id="main" className="account-result"><Brand/><span className="eyebrow">Bảo vệ tài khoản</span><h1>Mật khẩu đã được đổi</h1><p>Mọi phiên đăng nhập đã kết thúc và mã đặt lại mật khẩu cũ đã hết hiệu lực.</p><Link className="button button-primary" href="/auth/login">Đăng nhập bằng mật khẩu mới</Link></main>;
}
