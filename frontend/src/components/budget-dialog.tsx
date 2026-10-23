"use client";
import { useState } from "react";
import { Check, LoaderCircle } from "lucide-react";
import type { Budget, Category } from "@/lib/types";
import { api, errorMessage } from "@/lib/api";
import { Dialog } from "./dialog";
import { ErrorNotice } from "./feedback";
export function BudgetDialog({ month, categories, budget, onClose, onSaved }: { month: string; categories: Category[]; budget: Budget | null; onClose: () => void; onSaved: () => void }) {
  const [pending, setPending] = useState(false), [error, setError] = useState("");
  const active = categories.filter(c => c.type === "EXPENSE" && !c.archived);
  const archived = budget?.items.some(item => !active.some(c => c.id === item.categoryId));
  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setPending(true); setError("");
    const data = new FormData(event.currentTarget);
    const items = active.map(category => ({ categoryId: category.id, limitAmount: String(data.get(category.id) ?? "") })).filter(item => item.limitAmount !== "");
    try { await api(`/budgets/${month}`, "PUT", { items }); onSaved(); }
    catch (cause) { setError(errorMessage(cause)); setPending(false); }
  }
  return <Dialog title={`Ngân sách tháng ${month.slice(5)}/${month.slice(0, 4)}`} pending={pending} onClose={onClose}><p className="muted">Đặt hạn mức cho từng danh mục. Để trống những danh mục bạn chưa muốn đặt ngân sách.</p>{error && <ErrorNotice message={error}/>} {archived && <ErrorNotice message="Ngân sách có danh mục đã lưu trữ. Cần khôi phục danh mục trước khi chỉnh sửa để giữ nguyên hạn mức cũ."/>}<form method="post" onSubmit={save} className="stack-form"><fieldset disabled={pending || archived}><div className="budget-inputs">{active.map(category => <label key={category.id}>{category.name}<input name={category.id} inputMode="numeric" pattern="[1-9][0-9]{0,14}" placeholder="Chưa đặt hạn mức" defaultValue={budget?.items.find(item => item.categoryId === category.id)?.limitAmount ?? ""}/></label>)}</div></fieldset><span className="field-hint">Đơn vị VND, không dùng dấu phân cách. Để trống tất cả sẽ xóa các hạn mức của tháng.</span><button className="button button-primary full-width" disabled={pending || archived}>{pending ? <LoaderCircle size={18} className="spin"/> : <Check size={18}/>} {pending ? "Đang lưu…" : "Lưu ngân sách"}</button></form></Dialog>;
}
