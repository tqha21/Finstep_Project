import Link from "next/link";
import { Brand } from "@/components/brand";
export default function AccountDeletedPage() {
  return <main id="main" className="account-result"><Brand/><span className="eyebrow">Quyền kiểm soát của bạn</span><h1>Tài khoản đã được xóa</h1><p>Hồ sơ và ghi chép trong cơ sở dữ liệu ứng dụng đã được xóa. Các phiên đăng nhập đã kết thúc.</p><p>Tệp đã tải xuống và email trong hộp thư của bạn vẫn do bạn quản lý.</p><Link className="button button-primary" href="/">Về trang chủ</Link></main>;
}
