import Link from "next/link";
import { Brand } from "@/components/brand";
export default function UnknownPasswordChangePage() {
  return <main id="main" className="account-result"><Brand/><span className="eyebrow">Kiểm tra yêu cầu của bạn</span><h1>Chưa xác nhận được kết quả đổi mật khẩu</h1><p>Phiên đăng nhập đã kết thúc trong khi gửi yêu cầu. Mật khẩu có thể đã được đổi dù trình duyệt chưa nhận được phản hồi.</p><p>Hãy thử đăng nhập bằng mật khẩu mới trước. Nếu chưa truy cập được, bạn có thể dùng chức năng quên mật khẩu để lấy lại tài khoản.</p><div className="actions"><Link className="button button-primary" href="/auth/login">Kiểm tra đăng nhập</Link><Link className="button button-secondary" href="/auth/forgot">Quên mật khẩu</Link></div></main>;
}
