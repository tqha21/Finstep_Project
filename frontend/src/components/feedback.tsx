import Link from "next/link";
import { AlertCircle, LoaderCircle } from "lucide-react";
export function ErrorNotice({ message }: { message: string }) {
  return <div className="notice notice-error" role="alert"><AlertCircle size={18}/><div>{message}{message.includes("đăng nhập lại") && <><br/><Link href="/auth/login">Đăng nhập lại</Link></>}</div></div>;
}
export function Busy({ label = "Đang tải dữ liệu…" }: { label?: string }) {
  return <div className="busy" role="status"><LoaderCircle className="spin" size={22}/><span>{label}</span></div>;
}
