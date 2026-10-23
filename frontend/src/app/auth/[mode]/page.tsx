import { notFound } from "next/navigation";
import Link from "next/link";
import { ArrowLeft, Sprout } from "lucide-react";
import { Brand } from "@/components/brand";
import { AuthForm, type AuthMode } from "@/components/auth-form";
export default async function AuthPage({ params }: { params: Promise<{ mode: string }> }) {
  const { mode } = await params;
  if (!["login", "register", "verify", "forgot", "reset"].includes(mode)) notFound();
  return <div className="auth-layout"><aside className="auth-story"><Brand light/><div><Sprout size={46} strokeWidth={1.2}/><h2>Chủ động hôm nay.<br/>An tâm ngày mai.</h2><p>Không cần bắt đầu với những kế hoạch lớn.<br/>Chỉ cần một khoản chi được ghi lại hôm nay.</p><div className="story-line"/></div><span>Finstep · Tài chính cá nhân, theo cách của bạn.</span></aside><main id="main" className="auth-main"><Link href="/" className="back-link"><ArrowLeft size={17}/> Trang chủ</Link><div className="auth-card"><AuthForm key={mode} mode={mode as AuthMode}/></div><p className="auth-footnote">Bản phát triển · Hiện chưa mở beta công khai.</p></main></div>;
}
