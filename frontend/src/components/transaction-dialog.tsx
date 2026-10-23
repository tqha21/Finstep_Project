"use client";
import { useRef, useState } from "react";
import { Check, LoaderCircle } from "lucide-react";
import type { Category, Transaction, Wallet } from "@/lib/types";
import { api, errorMessage } from "@/lib/api";
import { Dialog } from "./dialog";
import { ErrorNotice } from "./feedback";
export function TransactionDialog({ wallets, categories, onClose, onSaved }: { wallets: Wallet[]; categories: Category[]; onClose: () => void; onSaved: () => void }) {
  const [type, setType] = useState<"EXPENSE" | "INCOME">("EXPENSE"), [pending, setPending] = useState(false), [error, setError] = useState("");
  const retry = useRef<{ fingerprint: string; key: string; body: Record<string, string> } | null>(null);
  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (pending) return; setPending(true); setError("");
    const data = new FormData(event.currentTarget);
    const fields = { walletId: String(data.get("walletId")), categoryId: String(data.get("categoryId")), type, amount: String(data.get("amount")), note: String(data.get("note") ?? "") };
    const fingerprint = JSON.stringify(fields);
    if (!retry.current || retry.current.fingerprint !== fingerprint) retry.current = { fingerprint, key: crypto.randomUUID(), body: { ...fields, occurredAt: new Date().toISOString() } };
    try { await api<Transaction>("/transactions", "POST", retry.current.body, retry.current.key); onSaved(); }
    catch (cause) { setError(errorMessage(cause)); setPending(false); }
  }
  return <Dialog title="Ghi lại một khoản tiền" pending={pending} onClose={onClose}>{error && <ErrorNotice message={error}/>}<form method="post" onSubmit={save} className="stack-form"><fieldset disabled={pending}><legend className="sr-only">Loại giao dịch</legend><div className="segmented">{(["EXPENSE", "INCOME"] as const).map(value => <button type="button" key={value} className={type === value ? "active" : ""} aria-pressed={type === value} onClick={() => setType(value)}>{value === "EXPENSE" ? "Khoản chi" : "Khoản thu"}</button>)}</div><label>Số tiền (VND)<input name="amount" aria-label="Số tiền (VND)" aria-describedby="money-hint" className="amount-input" inputMode="numeric" pattern="[1-9][0-9]{0,14}" placeholder="45000" required data-autofocus/><span id="money-hint" className="field-hint">Nhập số đồng, không có dấu chấm hoặc dấu phẩy.</span></label><div className="form-grid"><label>Danh mục<select aria-label="Danh mục" key={type} name="categoryId" required defaultValue=""><option value="" disabled>Chọn danh mục</option>{categories.filter(c => !c.archived && c.type === type).map(category => <option value={category.id} key={category.id}>{category.name}</option>)}</select></label><label>Ví<select aria-label="Ví" name="walletId" required>{wallets.filter(w => !w.archived).map(wallet => <option key={wallet.id} value={wallet.id}>{wallet.name}</option>)}</select></label></div><label>Ghi chú <span className="optional">(không bắt buộc)</span><input name="note" maxLength={1000} placeholder="Khoản tiền này dành cho điều gì?"/></label><p className="field-hint">Ghi nhận tại thời điểm lưu. Nếu kết nối lỗi, giữ nguyên nội dung và thử lại để tránh tạo trùng.</p></fieldset><button className="button button-primary full-width" disabled={pending}>{pending ? <LoaderCircle size={18} className="spin"/> : <Check size={18}/>} {pending ? "Đang lưu…" : "Lưu giao dịch"}</button></form></Dialog>;
}
