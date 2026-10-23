"use client";
import { useCallback, useState } from "react";
import Link from "next/link";
import { Plus, Download, ArrowDownLeft, ArrowUpRight, ArrowLeftRight, WalletCards, ChartPie, CircleDollarSign, ArrowRight } from "lucide-react";
import { api, ApiError, downloadCsv, errorMessage } from "@/lib/api";
import { accountMonth, money, ratio } from "@/lib/format";
import type { Budget, Category, History, Summary, Wallet } from "@/lib/types";
import { useResource } from "@/lib/use-resource";
import { useUser } from "@/components/protected-shell";
import { Busy, ErrorNotice } from "@/components/feedback";
import { DailyChart } from "@/components/daily-chart";
import { HistoryList } from "@/components/history-list";
import { TransactionDialog } from "@/components/transaction-dialog";
import { BudgetDialog } from "@/components/budget-dialog";
const statusLabels: Record<string, string> = { OK: "Trong hạn mức", NEAR_LIMIT: "Gần hạn mức", AT_LIMIT: "Đã dùng hết", EXCEEDED: "Vượt hạn mức" };
export default function Dashboard() {
  const user = useUser(), [month, setMonth] = useState(() => accountMonth(user.timezone));
  const [dialog, setDialog] = useState<"transaction" | "budget" | null>(null), [message, setMessage] = useState("");
  const [exporting, setExporting] = useState(false), [exportError, setExportError] = useState("");
  const load = useCallback(async () => {
    const summary = await api<Summary>(`/reports/summary?month=${month}`);
    const [wallets, categories, history, budget] = await Promise.all([
      api<Wallet[]>("/wallets?includeArchived=true"), api<Category[]>("/categories?includeArchived=true"),
      api<History>(`/transactions?${new URLSearchParams({ from: summary.period.from, to: summary.period.to, size: "5" })}`),
      api<Budget>(`/budgets/${month}`).catch(cause => { if (cause instanceof ApiError && cause.status === 404) return null; throw cause; }),
    ]);
    return { summary, wallets, categories, history, budget };
  }, [month]);
  const resource = useResource(month, load);
  function saved(label: string) { setDialog(null); setMessage(label); resource.reload(); }
  async function exportMonth() {
    if (!resource.data) return; setExporting(true); setExportError("");
    try { await downloadCsv(resource.data.summary.period.from, resource.data.summary.period.to); setMessage("Tệp CSV đã được tải xuống."); }
    catch (cause) { setExportError(errorMessage(cause)); } finally { setExporting(false); }
  }
  const data = resource.data;
  if (resource.loading) return <Busy label="Đang tổng hợp tài chính của bạn…"/>;
  if (resource.error || !data) return <><ErrorNotice message={errorMessage(resource.error)}/><button className="button button-secondary" onClick={resource.reload}>Thử lại</button></>;
  if (data.wallets.length === 0) return <section className="first-wallet"><span className="feature-icon"><WalletCards size={30}/></span><span className="eyebrow">Chào {user.fullName}</span><h1>Bắt đầu với ví đầu tiên.</h1><p>Chỉ cần một chiếc ví và số dư hiện tại.<br/>Finstep sẽ giúp bạn ghi lại những khoản tiền tiếp theo.</p><Link className="button button-primary" href="/app/onboarding">Tạo ví đầu tiên <ArrowRight size={18}/></Link></section>;
  const activeWallets = data.wallets.filter(w => !w.archived);
  const expenses = data.summary.categories.filter(c => c.type === "EXPENSE").sort((a, b) => BigInt(a.amount) > BigInt(b.amount) ? -1 : BigInt(a.amount) < BigInt(b.amount) ? 1 : 0);
  return <><header className="dashboard-header"><div><span className="eyebrow">Một góc nhìn rõ ràng hơn</span><h1>Tổng quan tài chính<span className="title-dot">.</span></h1><p>Chào {user.fullName}, hôm nay bạn đã ghi chép thu chi chưa?</p></div><button className="button button-primary" onClick={() => setDialog("transaction")} disabled={!activeWallets.length}><Plus size={18}/>Thêm giao dịch</button></header><div className="dashboard-toolbar"><label className="month-picker">Tháng báo cáo<input aria-label="Tháng báo cáo" type="month" value={month} min="0001-01" max="9999-12" onChange={e => { if (/^\d{4}-\d{2}$/.test(e.target.value)) { setMonth(e.target.value); setMessage(""); } }}/></label><button className="button button-secondary" onClick={exportMonth} disabled={exporting}><Download size={16}/>{exporting ? "Đang xuất…" : "Xuất CSV"}</button></div>{message && <p className="success-message" role="status">{message}</p>}{exportError && <ErrorNotice message={exportError}/>}
    <section className="stat-grid" aria-label="Tổng hợp tài chính"><article className="stat-card balance-card"><div className="stat-heading"><span>Tổng số dư hiện tại</span><WalletCards size={20}/></div><strong>{money(data.summary.currentTotalBalance)}</strong><span className="stat-foot">Tất cả ví · Toàn thời gian</span><div className="balance-decoration"/></article>{[{ label: "Thu nhập tháng", value: data.summary.totalIncome, icon: ArrowDownLeft, style: "income" }, { label: "Chi tiêu tháng", value: data.summary.totalExpense, icon: ArrowUpRight, style: "expense" }, { label: "Dòng tiền tháng", value: data.summary.netCashFlow, icon: ArrowLeftRight, style: "net" }].map(({ label, value, icon: Icon, style }) => <article key={label} className="stat-card"><div className="stat-heading"><span>{label}</span><span className={`stat-icon ${style}`}><Icon size={18}/></span></div><strong>{money(value)}</strong><span className="stat-foot">{style === "net" ? "Thu nhập trừ chi tiêu" : `Tháng ${month.slice(5)}/${month.slice(0, 4)}`}</span></article>)}</section>
    <div className="overview-grid"><section className="panel"><div className="panel-heading"><div><h2>Nhịp điệu dòng tiền</h2><p>Thu và chi mỗi ngày trong tháng</p></div><span className="tag">{month.slice(5)}/{month.slice(0, 4)}</span></div><DailyChart report={data.summary}/></section><section className="panel"><div className="panel-heading"><div><h2>Tiền đi đâu?</h2><p>Các danh mục chi tiêu trong tháng</p></div><ChartPie size={20} className="muted"/></div>{expenses.length ? <div className="category-breakdown">{expenses.slice(0, 5).map((category, index) => <div key={category.categoryId}><div className="breakdown-heading"><span><i style={{ background: ["#18816c", "#6caa94", "#edb774", "#bbc8ab", "#a5b7be"][index] }}/>{category.name}</span><strong>{money(category.amount)}</strong></div><div className="progress-track"><span style={{ width: `${ratio(category.amount, data.summary.totalExpense)}%`, background: ["#18816c", "#6caa94", "#edb774", "#bbc8ab", "#a5b7be"][index] }}/></div></div>)}</div> : <div className="chart-empty compact"><CircleDollarSign size={30} strokeWidth={1.3}/><h3>Chưa có khoản chi</h3><p>Danh mục sẽ xuất hiện khi bạn ghi chép.</p></div>}</section></div>
    <div className="detail-grid"><section id="activity" className="panel"><div className="panel-heading"><div><h2>Giao dịch gần đây</h2><p>Thu chi trong tháng đã chọn</p></div><Link className="button button-quiet small" href="/app/activity">Xem tất cả</Link></div><HistoryList initial={data.history} wallets={data.wallets} categories={data.categories} period={data.summary.period}/></section><section id="budget" className="panel"><div className="panel-heading"><div><h2>Ngân sách tháng</h2><p>Đi đúng hướng, từng danh mục</p></div><button className="button button-quiet small" onClick={() => setDialog("budget")}>{data.budget?.items.length ? "Chỉnh sửa" : "Thiết lập"}</button></div>{data.budget?.items.length ? <div className="budget-list">{data.budget.items.map(item => <div key={item.categoryId}><div className="budget-row"><strong>{item.name}</strong><span className={`budget-status ${item.status.toLowerCase()}`}>{statusLabels[item.status] ?? item.status}</span></div><div className={`progress-track ${["EXCEEDED", "AT_LIMIT"].includes(item.status) ? "over-budget" : ""}`}><span style={{ width: `${Math.min(100, ratio(item.spent, item.limitAmount))}%` }}/></div><div className="budget-amounts"><span>{money(item.spent)} / {money(item.limitAmount)}</span><strong>{item.percentageUsed}%</strong></div></div>)}</div> : <div className="empty-inline"><ChartPie size={28}/><div><h3>Một kế hoạch vừa sức</h3><p>Đặt hạn mức để biết khi nào khoản chi gần chạm ngân sách.</p><button className="text-button" onClick={() => setDialog("budget")}>Đặt ngân sách đầu tiên <ArrowRight size={14}/></button></div></div>}</section></div>
    <section id="wallets" className="wallet-section"><div className="panel-heading"><div><h2>Ví của tôi</h2><p>Số dư hiện tại theo ghi chép</p></div><Link href="/app/wallets" className="button button-secondary">Quản lý ví</Link></div><div className="wallet-grid">{data.wallets.map(wallet => <article key={wallet.id} className="wallet-card"><span className="wallet-card-icon"><WalletCards size={21}/></span><div><h3>{wallet.name}</h3><span>{wallet.archived ? "Đã lưu trữ" : { CASH: "Tiền mặt", BANK: "Ngân hàng", EWALLET: "Ví điện tử" }[wallet.type]}</span></div><strong>{money(wallet.balance)}</strong></article>)}</div></section>
    {dialog === "transaction" && <TransactionDialog wallets={data.wallets} categories={data.categories} onClose={() => setDialog(null)} onSaved={() => saved("Đã lưu giao dịch. Số dư đã được cập nhật.")}/>}{dialog === "budget" && <BudgetDialog month={month} categories={data.categories} budget={data.budget} onClose={() => setDialog(null)} onSaved={() => saved("Đã cập nhật ngân sách tháng.")}/>}</>;
}
