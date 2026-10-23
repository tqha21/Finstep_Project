"use client";
import { useCallback, useState } from "react";
import { Plus, ArrowLeftRight } from "lucide-react";
import { api, errorMessage } from "@/lib/api";
import { accountMonth, dateTime, money } from "@/lib/format";
import type { Category, PageResult, Summary, Transaction, Transfer, Wallet } from "@/lib/types";
import { useResource } from "@/lib/use-resource";
import { useUser } from "@/components/protected-shell";
import { Busy, ErrorNotice } from "@/components/feedback";
import { TransactionDialog, TransferDialog } from "@/components/transaction-dialog";
import { ConfirmDialog } from "@/components/confirm-dialog";
type Entry = Transaction | Transfer;
type Editor = { kind: "transactions"; initial?: Transaction } | { kind: "transfers"; initial?: Transfer };
export default function ActivityPage() {
  const user = useUser();
  const [filters, setFilters] = useState({ kind: "transactions", month: accountMonth(user.timezone), walletId: "", categoryId: "", type: "" });
  const [cursors, setCursors] = useState<string[]>([""]), [editor, setEditor] = useState<Editor | null>(null);
  const [removing, setRemoving] = useState<Entry | null>(null), [message, setMessage] = useState("");
  const cursor = cursors.at(-1)!;
  const load = useCallback(async () => {
    const query = new URLSearchParams({ size: "20" });
    if (filters.month) {
      const report = await api<Summary>(`/reports/summary?month=${filters.month}`);
      query.set("from", report.period.from); query.set("to", report.period.to);
    }
    if (cursor) query.set("cursor", cursor);
    if (filters.walletId) query.set("walletId", filters.walletId);
    if (filters.kind === "transactions") {
      if (filters.categoryId) query.set("categoryId", filters.categoryId);
      if (filters.type) query.set("type", filters.type);
    }
    const [history, wallets, categories] = await Promise.all([api<PageResult<Entry>>(`/${filters.kind}?${query}`), api<Wallet[]>("/wallets?includeArchived=true"), api<Category[]>("/categories?includeArchived=true")]);
    return { history, wallets, categories };
  }, [filters, cursor]);
  const resource = useResource(JSON.stringify({ filters, cursor }), load), data = resource.data;
  function filter(name: keyof typeof filters, value: string) { setFilters(previous => ({ ...previous, [name]: value })); setCursors([""]); setMessage(""); }
  function saved(text: string) { setEditor(null); setRemoving(null); setMessage(text); setCursors([""]); resource.reload(); }
  return <><header className="dashboard-header"><div><span className="eyebrow">Cuốn sổ của bạn</span><h1>Lịch sử giao dịch</h1><p>Thời gian hiển thị theo {user.timezone}. Chuyển ví có danh sách riêng.</p></div><div className="actions"><button className="button button-secondary" onClick={() => setEditor({ kind: "transfers" })} disabled={(data?.wallets.filter(w => !w.archived).length ?? 0) < 2}><ArrowLeftRight size={16}/>Chuyển ví</button><button className="button button-primary" onClick={() => setEditor({ kind: "transactions" })} disabled={!data?.wallets.some(w => !w.archived)}><Plus size={16}/>Thêm giao dịch</button></div></header>
    <div className="ledger-toolbar" aria-label="Loại lịch sử">{[["transactions", "Thu và chi"], ["transfers", "Chuyển ví"]].map(([value, label]) => <button key={value} className={`button ${filters.kind === value ? "button-primary" : "button-secondary"}`} aria-pressed={filters.kind === value} onClick={() => filter("kind", value)}>{label}</button>)}</div>
    <div className="filters"><label>Tháng<input aria-label="Tháng lịch sử" type="month" min="0001-01" max="9999-12" value={filters.month} onChange={event => filter("month", event.target.value)}/></label><label>Ví<select aria-label="Lọc theo ví" value={filters.walletId} onChange={event => filter("walletId", event.target.value)}><option value="">Tất cả ví</option>{data?.wallets.map(wallet => <option value={wallet.id} key={wallet.id}>{wallet.name}{wallet.archived ? " (đã lưu trữ)" : ""}</option>)}</select></label>
      {filters.kind === "transactions" && <><label>Loại<select aria-label="Lọc theo loại" value={filters.type} onChange={event => { setFilters(previous => ({ ...previous, type: event.target.value, categoryId: "" })); setCursors([""]); setMessage(""); }}><option value="">Thu và chi</option><option value="INCOME">Khoản thu</option><option value="EXPENSE">Khoản chi</option></select></label><label>Danh mục<select aria-label="Lọc theo danh mục" value={filters.categoryId} onChange={event => filter("categoryId", event.target.value)}><option value="">Tất cả danh mục</option>{data?.categories.filter(c => !filters.type || c.type === filters.type).map(category => <option key={category.id} value={category.id}>{category.name}{category.archived ? " (đã lưu trữ)" : ""}</option>)}</select></label></>}
      <button className="button button-secondary" onClick={() => filter("month", "")}>Toàn thời gian</button>
    </div>{message && <p className="success-message" role="status">{message}</p>}
    {resource.loading ? <Busy/> : resource.error || !data ? <><ErrorNotice message={errorMessage(resource.error)}/><button className="button button-secondary" onClick={resource.reload}>Thử lại</button></> : <section className="panel" aria-label="Danh sách lịch sử"><p className="field-hint">{filters.month ? `Tháng ${filters.month} · ${user.timezone}` : "Toàn thời gian"} · Mới nhất trước · Tối đa 20 mục mỗi trang</p>{!data.history.items.length ? <div className="chart-empty"><h2>Chưa có giao dịch phù hợp</h2><p>Thử đổi tháng hoặc bộ lọc.</p></div> : <ul className="ledger-list">{data.history.items.map(entry => {
      const tx = "categoryId" in entry ? entry : undefined, transfer = "fromWalletId" in entry ? entry : undefined;
      const walletName = (id: string) => data.wallets.find(w => w.id === id)?.name ?? "Ví đã ghi";
      const title = tx ? data.categories.find(c => c.id === tx.categoryId)?.name ?? "Danh mục đã ghi" : `${walletName(transfer!.fromWalletId)} → ${walletName(transfer!.toWalletId)}`;
      return <li key={entry.id}><div className="ledger-row"><div><h2>{title}</h2><p>{tx ? `${tx.type === "INCOME" ? "Khoản thu" : "Khoản chi"} · ${walletName(tx.walletId)} · ` : "Chuyển ví · "}<time dateTime={entry.occurredAt}>{dateTime(entry.occurredAt, user.timezone)}</time></p>{entry.note && <p>{entry.note}</p>}</div><strong className={tx?.type === "INCOME" ? "text-green" : ""}>{tx ? tx.type === "INCOME" ? "+" : "−" : ""}{money(entry.amount)}</strong></div><div className="actions"><button className="button button-secondary small" aria-label={`Sửa ${entry.note || title}`} onClick={() => setEditor(tx ? { kind: "transactions", initial: tx } : { kind: "transfers", initial: transfer! })}>Sửa</button><button className="button button-quiet small" aria-label={`Xóa ${entry.note || title}`} onClick={() => setRemoving(entry)}>Xóa</button></div></li>;
    })}</ul>}<div className="dashboard-toolbar"><button className="button button-secondary" disabled={cursors.length === 1} onClick={() => setCursors(values => values.slice(0, -1))}>Trang trước</button><span className="muted">Trang {cursors.length}</span><button className="button button-secondary" disabled={!data.history.nextCursor} onClick={() => { if (data.history.nextCursor) setCursors(values => [...values, data.history.nextCursor!]); }}>Trang sau</button></div></section>}
    {editor && data && (editor.kind === "transactions" ? <TransactionDialog wallets={data.wallets} categories={data.categories} initial={editor.initial} onClose={() => setEditor(null)} onSaved={() => saved("Đã lưu giao dịch. Số dư đã được cập nhật.")}/> : <TransferDialog wallets={data.wallets} initial={editor.initial} onClose={() => setEditor(null)} onSaved={() => saved("Đã lưu chuyển ví. Hai số dư đã được cập nhật.")}/>)}
    {removing && <ConfirmDialog title={"fromWalletId" in removing ? "Xóa chuyển ví?" : "Xóa giao dịch?"} description={"fromWalletId" in removing ? "Khoản chuyển bị loại khỏi ghi chép và số dư của cả hai ví sẽ được tính lại. Không thể khôi phục khoản này trên giao diện." : "Khoản tiền bị loại khỏi ghi chép, báo cáo và ngân sách. Số dư ví sẽ được tính lại. Không thể khôi phục khoản này trên giao diện."} buttonLabel="Xóa khỏi ghi chép" action={() => api(`/${"fromWalletId" in removing ? "transfers" : "transactions"}/${removing.id}`, "DELETE")} onClose={() => setRemoving(null)} onDone={() => saved("Đã xóa khỏi ghi chép và cập nhật số dư.")}/>}
  </>;
}
