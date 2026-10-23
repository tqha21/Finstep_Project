import type { Metadata } from "next";
import "./globals.css";
export const metadata: Metadata = { title: { default: "Finstep — Chủ động từng khoản tiền", template: "%s · Finstep" }, description: "Ghi chép thu chi, quản lý ví và ngân sách cá nhân với Finstep." };
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="vi" data-scroll-behavior="smooth"><body><a href="#main" className="skip-link">Đến nội dung chính</a>{children}</body></html>;
}
