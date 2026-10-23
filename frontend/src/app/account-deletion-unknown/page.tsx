import Link from "next/link";
import { Brand } from "@/components/brand";
export default function UnknownDeletionPage() {
  return <main id="main" className="account-result"><Brand/><span className="eyebrow">Kiểm tra yêu cầu của bạn</span><h1>Chưa xác nhận được kết quả xóa</h1><p>Phiên đăng nhập đã kết thúc trong khi gửi yêu cầu. Tài khoản có thể đã được xóa dù trình duyệt chưa nhận được phản hồi.</p><p>Bạn có thể thử đăng nhập lại để kiểm tra. Nếu vẫn đăng nhập được, mở Hồ sơ để xem dữ liệu và thực hiện lại yêu cầu khi cần.</p><div className="actions"><Link className="button button-primary" href="/auth/login">Kiểm tra đăng nhập</Link><Link className="button button-secondary" href="/">Về trang chủ</Link></div></main>;
}
