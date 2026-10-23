"use client";
import { useState } from "react";
import type { Category, Wallet } from "@/lib/types";
import { api, ApiError, errorMessage } from "@/lib/api";
import { Dialog } from "./dialog";
import { ErrorNotice } from "./feedback";
export function ResourceEditor({ kind, initial, onSaved, onClose }: { kind: "wallets" | "categories"; initial?: Wallet | Category; onSaved: () => void; onClose: () => void }) {
  const [pending, setPending] = useState(false), [error, setError] = useState("");
  const [uncertain, setUncertain] = useState(false);
  const wallet = kind === "wallets", label = wallet ? "ví" : "danh mục";
  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (pending || uncertain) return; setPending(true); setError("");
    const data = new FormData(event.currentTarget);
    const body = initial ? { name: String(data.get("name")) } : { name: String(data.get("name")), type: String(data.get("type")),
      ...(wallet ? { openingBalance: String(data.get("openingBalance")), effectiveAt: new Date().toISOString() } : {}) };
    try { await api(`/${kind}${initial ? `/${initial.id}` : ""}`, initial ? "PATCH" : "POST", body); onSaved(); }
    catch (cause) {
      const ambiguous = !initial && cause instanceof ApiError && (cause.status === 0 || cause.status >= 500);
      setUncertain(ambiguous);
      setError(`${errorMessage(cause)}${ambiguous ? " Chưa rõ kết quả tạo mới. Đóng form để tải lại danh sách trước khi tạo tiếp." : ""}`); setPending(false);
    }
  }
  return <Dialog title={`${initial ? "Đổi tên" : "Thêm"} ${label}`} pending={pending} onClose={onClose}>{error && <ErrorNotice message={error}/>}<form method="post" onSubmit={save} className="stack-form"><fieldset disabled={pending}>
    <label>Tên {label}<input aria-label={`Tên ${label}`} name="name" maxLength={100} required defaultValue={initial?.name} data-autofocus/></label>
    {!initial && <label>Loại {label}<select aria-label={`Loại ${label}`} name="type">{(wallet ? [["CASH", "Tiền mặt"], ["BANK", "Ngân hàng"], ["EWALLET", "Ví điện tử"]] : [["EXPENSE", "Khoản chi"], ["INCOME", "Khoản thu"]]).map(([value, name]) => <option key={value} value={value}>{name}</option>)}</select></label>}
    {!initial && wallet && <label>Số dư ban đầu (VND)<input aria-label="Số dư ban đầu (VND)" name="openingBalance" inputMode="numeric" pattern="-?(0|[1-9][0-9]{0,14})" defaultValue="0" required/><span className="field-hint">Số dư tại thời điểm tạo ví, có thể âm. Không sửa lại số dư ban đầu sau khi tạo.</span></label>}
    <p className="field-hint">{initial ? "Loại đã chọn và số dư ban đầu được giữ cố định để bảo toàn lịch sử." : "Sau khi tạo, bạn có thể đổi tên hoặc lưu trữ khi không còn sử dụng."}</p>
  </fieldset><button className="button button-primary" disabled={pending || uncertain}>{pending ? "Đang lưu…" : `Lưu ${label}`}</button></form></Dialog>;
}
