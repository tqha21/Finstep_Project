"use client";
import { useCallback, useState } from "react";
import { useRouter } from "next/navigation";
import { ArrowRight, Check, WalletCards, LoaderCircle } from "lucide-react";
import { api, errorMessage } from "@/lib/api";
import { useResource } from "@/lib/use-resource";
import type { Wallet } from "@/lib/types";
import { Busy, ErrorNotice } from "@/components/feedback";
export default function Onboarding() {
  const router = useRouter(), load = useCallback(() => api<Wallet[]>("/wallets"), []);
  const resource = useResource("onboarding-wallets", load);
  const [pending, setPending] = useState(false), [error, setError] = useState("");
  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setPending(true); setError("");
    const data = new FormData(event.currentTarget);
    try {
      // After a lost response, checking the existing wallets avoids blindly creating another.
      const existing = await api<Wallet[]>("/wallets");
      if (existing.length === 0) await api("/wallets", "POST", { name: String(data.get("name")).trim(), type: data.get("type"), openingBalance: data.get("openingBalance"), effectiveAt: new Date().toISOString() });
      router.replace("/app");
    } catch (cause) { setError(errorMessage(cause)); setPending(false); }
  }
  if (resource.loading) return <Busy/>;
  if (resource.error) return <><ErrorNotice message={errorMessage(resource.error)}/><button className="button button-secondary" onClick={resource.reload}>Thử lại</button></>;
  if (resource.data && resource.data.length > 0) return <section className="onboarding-card"><h1>Bạn đã có ví đầu tiên</h1><p>Tiếp tục xem số dư và ghi chép thu chi.</p><button className="button button-primary" onClick={() => router.replace("/app")}>Đến tổng quan <ArrowRight size={18}/></button></section>;
  return <div className="onboarding-wrap"><div className="step-label"><span><Check size={14}/></span> Tài khoản đã sẵn sàng <i/> <strong>02 · Tạo ví đầu tiên</strong></div><section className="onboarding-card"><span className="feature-icon"><WalletCards size={28}/></span><span className="eyebrow">Bắt đầu thật đơn giản</span><h1>Mọi hành trình<br/>bắt đầu từ một chiếc ví.</h1><p>Tạo nơi ghi chép thu chi. Đây chỉ là ví theo dõi, không kết nối hay chuyển tiền ngân hàng.</p>{error && <ErrorNotice message={error}/>}<form method="post" className="stack-form" onSubmit={submit}><label>Tên ví<input name="name" placeholder="Ví sinh hoạt" required maxLength={100}/></label><label>Loại ví<select name="type" defaultValue="CASH"><option value="CASH">Tiền mặt</option><option value="BANK">Ngân hàng</option><option value="EWALLET">Ví điện tử</option></select></label><label>Số dư ban đầu (VND)<input name="openingBalance" aria-label="Số dư ban đầu (VND)" aria-describedby="opening-hint" inputMode="numeric" defaultValue="0" pattern="-?(0|[1-9][0-9]{0,14})" required/><span id="opening-hint" className="field-hint">Số dư tại thời điểm tạo ví. Nhập số đồng, không dùng dấu phân cách.</span></label><button className="button button-primary full-width" disabled={pending}>{pending ? <LoaderCircle size={18} className="spin"/> : <ArrowRight size={18}/>} {pending ? "Đang tạo ví…" : "Tạo ví và bắt đầu"}</button></form><span className="onboarding-hint"><Check size={15}/> Các danh mục thu chi được chuẩn bị sẵn cho bạn.</span></section></div>;
}
