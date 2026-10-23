"use client";
import { useCallback, useState } from "react";
import Link from "next/link";
import { Plus, WalletCards, Tags } from "lucide-react";
import { api, errorMessage } from "@/lib/api";
import type { Wallet, Category } from "@/lib/types";
import { money } from "@/lib/format";
import { useResource } from "@/lib/use-resource";
import { ResourceEditor } from "./resource-editor";
import { ConfirmDialog } from "./confirm-dialog";
import { TransferDialog } from "./transaction-dialog";
import { Busy, ErrorNotice } from "./feedback";
export function ResourceManager({ kind }: { kind: "wallets" | "categories" }) {
  const load = useCallback(() => api<(Wallet | Category)[]>(`/${kind}?includeArchived=true`), [kind]);
  const resource = useResource(kind, load);
  const [editor, setEditor] = useState<{ initial?: Wallet | Category } | null>(null);
  const [archive, setArchive] = useState<Wallet | Category | null>(null), [moving, setMoving] = useState(false);
  const [showArchived, setShowArchived] = useState(false), [message, setMessage] = useState("");
  const wallet = kind === "wallets", label = wallet ? "ví" : "danh mục";
  function saved(text: string) { setEditor(null); setArchive(null); setMoving(false); setMessage(text); resource.reload(); }
  return <><header className="dashboard-header"><div><span className="eyebrow">Sắp xếp theo cách của bạn</span><h1>{wallet ? "Ví của tôi" : "Danh mục thu chi"}</h1><p>{wallet ? "Số dư hiện tại, cập nhật từ các khoản thu, chi và chuyển ví." : "Danh mục riêng của bạn. Lưu trữ để ngừng sử dụng mà vẫn giữ lịch sử."}</p></div><div className="actions">{wallet && <button className="button button-secondary" onClick={() => setMoving(true)} disabled={(resource.data?.filter(w => !w.archived).length ?? 0) < 2}>Chuyển ví</button>}<button className="button button-primary" onClick={() => setEditor({})}><Plus size={18}/>Thêm {label}</button></div></header>
    <div className="dashboard-toolbar"><label className="checkbox-label"><input type="checkbox" checked={showArchived} onChange={event => setShowArchived(event.target.checked)}/>Hiện mục đã lưu trữ</label>{wallet && <Link className="button button-quiet small" href="/app/activity">Xem lịch sử giao dịch</Link>}</div>
    {message && <p role="status" className="success-message">{message}</p>}
    {resource.loading ? <Busy/> : resource.error ? <><ErrorNotice message={errorMessage(resource.error)}/><button className="button button-secondary" onClick={resource.reload}>Thử lại</button></> : <div className="management-grid">{resource.data?.filter(item => showArchived || !item.archived).map(item => <article key={item.id} className={`panel management-card ${item.archived ? "archived" : ""}`} aria-label={item.name}><div className="panel-heading"><span className="feature-icon">{wallet ? <WalletCards size={22}/> : <Tags size={22}/>}</span><span className="tag">{item.archived ? "Đã lưu trữ" : ({ CASH: "Tiền mặt", BANK: "Ngân hàng", EWALLET: "Ví điện tử", INCOME: "Khoản thu", EXPENSE: "Khoản chi" }[item.type])}</span></div><h2>{item.name}</h2>{"balance" in item && <strong className="managed-balance">{money(item.balance)}</strong>}<div className="actions"><button className="button button-secondary small" onClick={() => setEditor({ initial: item })}>Đổi tên</button><button className="button button-quiet small" onClick={() => setArchive(item)}>{item.archived ? "Khôi phục" : "Lưu trữ"}</button></div></article>)}{!resource.data?.some(item => showArchived || !item.archived) && <div className="panel empty-inline"><p>Chưa có {label} đang sử dụng. Thêm mới hoặc hiện mục đã lưu trữ để khôi phục.</p></div>}</div>}
    {editor && <ResourceEditor kind={kind} initial={editor.initial} onClose={() => { setEditor(null); resource.reload(); }} onSaved={() => saved(`Đã lưu ${label}.`)}/>}
    {archive && <ConfirmDialog title={`${archive.archived ? "Khôi phục" : "Lưu trữ"} ${archive.name}?`} description={archive.archived ? "Mục này sẽ xuất hiện trở lại khi ghi giao dịch mới." : "Lịch sử và số dư được giữ nguyên. Bạn có thể khôi phục sau; không thể ghi mới bằng mục đã lưu trữ."} action={() => api(`/${kind}/${archive.id}`, "PATCH", { archived: !archive.archived })} onClose={() => setArchive(null)} onDone={() => saved(`Đã ${archive.archived ? "khôi phục" : "lưu trữ"} ${label}.`)}/>}
    {moving && resource.data && <TransferDialog wallets={resource.data as Wallet[]} onClose={() => setMoving(false)} onSaved={() => saved("Đã chuyển ví. Hai số dư đã được cập nhật.")}/>}
  </>;
}
