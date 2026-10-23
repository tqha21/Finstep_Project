"use client";
import { useState } from "react";
import { ArrowDownLeft, ArrowUpRight, ArrowLeftRight, LoaderCircle } from "lucide-react";
import { api, errorMessage } from "@/lib/api";
import { money, dateTime } from "@/lib/format";
import type { Category, History, Period, Wallet } from "@/lib/types";
import { ErrorNotice } from "./feedback";
export function HistoryList({ initial, wallets, categories, period }: { initial: History; wallets: Wallet[]; categories: Category[]; period: Period }) {
  const [history, setHistory] = useState(initial), [pending, setPending] = useState(false), [error, setError] = useState("");
  async function more() {
    if (!history.nextCursor) return; setPending(true); setError("");
    try {
      const next = await api<History>(`/transactions?${new URLSearchParams({ from: period.from, to: period.to, size: "5", cursor: history.nextCursor })}`);
      setHistory(current => ({ items: [...current.items, ...next.items.filter(item => !current.items.some(existing => existing.id === item.id))], nextCursor: next.nextCursor }));
    } catch (cause) { setError(errorMessage(cause)); } finally { setPending(false); }
  }
  if (history.items.length === 0) return <div className="empty-inline"><ArrowLeftRight size={28}/><div><h3>Cuốn sổ của bạn đang chờ</h3><p>Thêm khoản thu hoặc chi để bắt đầu ghi lại hành trình tháng này.</p></div></div>;
  return <>{error && <ErrorNotice message={error}/>}<ul className="history-list">{history.items.map(item => <li key={item.id}><span className={`transaction-icon ${item.type === "INCOME" ? "income" : "expense"}`}>{item.type === "INCOME" ? <ArrowDownLeft size={20}/> : <ArrowUpRight size={20}/>}</span><div className="transaction-main"><strong>{item.note || categories.find(category => category.id === item.categoryId)?.name || "Giao dịch"}</strong><span>{categories.find(category => category.id === item.categoryId)?.name ?? "Danh mục"} · {wallets.find(wallet => wallet.id === item.walletId)?.name ?? "Ví"}</span></div><div className="transaction-meta"><strong className={item.type === "INCOME" ? "text-green" : ""}>{item.type === "INCOME" ? "+" : "−"}{money(item.amount)}</strong><time dateTime={item.occurredAt}>{dateTime(item.occurredAt, period.timezone)}</time></div></li>)}</ul>{history.nextCursor && <button className="button button-quiet full-width" disabled={pending} onClick={more}>{pending && <LoaderCircle size={16} className="spin"/>}{pending ? "Đang tải…" : "Xem thêm giao dịch"}</button>}</>;
}
