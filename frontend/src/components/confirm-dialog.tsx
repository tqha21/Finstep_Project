"use client";
import { useState } from "react";
import { Dialog } from "./dialog";
import { ErrorNotice } from "./feedback";
import { errorMessage } from "@/lib/api";
export function ConfirmDialog({ title, description, action, onClose, onDone, buttonLabel = "Xác nhận" }: { title: string; description: string; action: () => Promise<unknown>; onClose: () => void; onDone: () => void; buttonLabel?: string }) {
  const [pending, setPending] = useState(false), [error, setError] = useState("");
  async function confirm() { if (pending) return; setPending(true); setError(""); try { await action(); onDone(); } catch (cause) { setError(errorMessage(cause)); setPending(false); } }
  return <Dialog title={title} pending={pending} onClose={onClose}>{error && <ErrorNotice message={error}/>}<p className="muted">{description}</p><div className="actions"><button className="button button-secondary" disabled={pending} onClick={onClose}>Hủy</button><button className="button button-primary" disabled={pending} onClick={confirm}>{pending ? "Đang xử lý…" : buttonLabel}</button></div></Dialog>;
}
