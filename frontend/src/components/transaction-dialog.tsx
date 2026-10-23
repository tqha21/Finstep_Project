"use client";
import { useRef, useState } from "react";
import type { Category, Transaction, Transfer, Wallet } from "@/lib/types";
import { api, errorMessage } from "@/lib/api";
import { instantFromLocal, localDateTime } from "@/lib/format";
import { Dialog } from "./dialog";
import { ErrorNotice } from "./feedback";
type Common = { wallets: Wallet[]; onClose: () => void; onSaved: () => void };
export function TransactionDialog(props: Common & { categories: Category[]; initial?: Transaction }) {
  return <LedgerDialog {...props} mode="transactions"/>;
}
export function TransferDialog(props: Common & { initial?: Transfer }) {
  return <LedgerDialog {...props} categories={[]} mode="transfers"/>;
}
function LedgerDialog({ wallets, categories, onClose, onSaved, mode, initial }: Common & { categories: Category[]; mode: "transactions" | "transfers"; initial?: Transaction | Transfer }) {
  const transaction = initial && "categoryId" in initial ? initial : undefined;
  const transfer = initial && "fromWalletId" in initial ? initial : undefined;
  const [type, setType] = useState<"EXPENSE" | "INCOME">(transaction?.type ?? "EXPENSE");
  const [pending, setPending] = useState(false), [error, setError] = useState("");
  const [startedAt] = useState(() => initial?.occurredAt ?? new Date().toISOString());
  const retry = useRef<{ fingerprint: string; key: string } | null>(null);
  const moving = mode === "transfers";
  const active = wallets.filter(wallet => !wallet.archived);
  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (pending) return; setPending(true); setError("");
    try {
      const data = new FormData(event.currentTarget);
      const body = { amount: String(data.get("amount")), note: String(data.get("note") ?? ""),
        occurredAt: instantFromLocal(String(data.get("occurredAt")), startedAt),
        ...(moving ? { fromWalletId: String(data.get("fromWalletId")), toWalletId: String(data.get("toWalletId")) }
          : { walletId: String(data.get("walletId")), categoryId: String(data.get("categoryId")), type }) };
      if ("fromWalletId" in body && body.fromWalletId === body.toWalletId) throw new Error("Hãy chọn hai ví khác nhau.");
      const walletIds = "fromWalletId" in body ? [body.fromWalletId, body.toWalletId] : [body.walletId];
      if (walletIds.some(id => !wallets.some(wallet => wallet.id === id && !wallet.archived))) throw new Error("Hãy chọn ví đang sử dụng hoặc khôi phục ví đã lưu trữ.");
      if ("categoryId" in body && !categories.some(category => category.id === body.categoryId && category.type === type && !category.archived)) throw new Error("Hãy chọn danh mục đang sử dụng hoặc khôi phục danh mục đã lưu trữ.");
      const fingerprint = JSON.stringify(body);
      if (!retry.current || retry.current.fingerprint !== fingerprint) retry.current = { fingerprint, key: crypto.randomUUID() };
      const result = await api<Transaction | Transfer>(`/${mode}${initial ? `/${initial.id}` : ""}`, initial ? "PUT" : "POST", body, initial ? undefined : retry.current.key);
      if (result.deleted) throw new Error("Khoản này đã được tạo rồi xóa trong phiên khác. Đóng form và kiểm tra lịch sử trước khi ghi tiếp.");
      onSaved();
    } catch (cause) { setError(errorMessage(cause)); setPending(false); }
  }
  function walletSelect(name: string, label: string, original?: string, fallback = active[0]?.id) {
    return <label>{label}<select aria-label={label} name={name} required defaultValue={original ?? fallback ?? ""}><option value="" disabled>Chọn ví</option>{wallets.filter(w => !w.archived || w.id === original).map(wallet => <option key={wallet.id} value={wallet.id} disabled={wallet.archived}>{wallet.name}{wallet.archived ? " (đã lưu trữ)" : ""}</option>)}</select></label>;
  }
  return <Dialog title={moving ? (initial ? "Sửa chuyển ví" : "Chuyển tiền giữa các ví") : (initial ? "Sửa giao dịch" : "Ghi lại một khoản tiền")} pending={pending} onClose={onClose}>
    {error && <ErrorNotice message={error}/>}
    <form method="post" onSubmit={save} className="stack-form"><fieldset disabled={pending}>
      {!moving && <div className="segmented" aria-label="Loại giao dịch">{(["EXPENSE", "INCOME"] as const).map(value => <button type="button" key={value} className={type === value ? "active" : ""} aria-pressed={type === value} onClick={() => setType(value)}>{value === "EXPENSE" ? "Khoản chi" : "Khoản thu"}</button>)}</div>}
      <label>Số tiền (VND)<input name="amount" aria-label="Số tiền (VND)" aria-describedby="money-hint" className="amount-input" inputMode="numeric" pattern="[1-9][0-9]{0,14}" defaultValue={initial?.amount} placeholder="45000" required data-autofocus/><span id="money-hint" className="field-hint">Nhập số đồng, không có dấu chấm hoặc dấu phẩy.</span></label>
      <div className="form-grid">{moving ? <>{walletSelect("fromWalletId", "Từ ví", transfer?.fromWalletId)}{walletSelect("toWalletId", "Đến ví", transfer?.toWalletId, active[1]?.id)}</> : <>
        <label>Danh mục<select aria-label="Danh mục" key={type} name="categoryId" required defaultValue={transaction?.type === type ? transaction.categoryId : ""}><option value="" disabled>Chọn danh mục</option>{categories.filter(c => c.type === type && (!c.archived || c.id === transaction?.categoryId)).map(category => <option value={category.id} key={category.id} disabled={category.archived}>{category.name}{category.archived ? " (đã lưu trữ)" : ""}</option>)}</select></label>
        {walletSelect("walletId", "Ví", transaction?.walletId)}</>}</div>
      <label>Thời điểm<input aria-label="Thời điểm" name="occurredAt" type="datetime-local" step="1" required defaultValue={localDateTime(startedAt)}/><span className="field-hint">Giờ thiết bị: {Intl.DateTimeFormat().resolvedOptions().timeZone}. Không chọn thời điểm trong tương lai hoặc trước số dư ban đầu của ví.</span></label>
      <label>Ghi chú <span className="optional">(không bắt buộc)</span><input name="note" maxLength={1000} defaultValue={initial?.note ?? ""} placeholder="Khoản tiền này dành cho điều gì?"/></label>
      <p className="field-hint">{moving ? "Chuyển ví cập nhật hai số dư, không tính vào thu nhập hoặc chi tiêu. " : ""}Nếu kết nối lỗi, giữ nguyên nội dung và thử lại trong form này để tránh tạo trùng. Ví hoặc danh mục lưu trữ cần được khôi phục trước khi sử dụng.</p>
    </fieldset><button className="button button-primary full-width" disabled={pending}>{pending ? "Đang lưu…" : moving ? "Lưu chuyển ví" : "Lưu giao dịch"}</button></form>
  </Dialog>;
}
