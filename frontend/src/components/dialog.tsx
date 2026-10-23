"use client";
import { useEffect, useRef } from "react";
import { X } from "lucide-react";
export function Dialog({ title, children, onClose, pending = false }: { title: string; children: React.ReactNode; onClose: () => void; pending?: boolean }) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current;
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    dialog?.showModal();
    dialog?.querySelector<HTMLElement>("[data-autofocus]")?.focus();
    return () => { dialog?.close(); if (opener?.isConnected) opener.focus(); };
  }, []);
  return <dialog ref={ref} className="modal" aria-labelledby="dialog-title" onCancel={event => { event.preventDefault(); if (!pending) onClose(); }} onClick={event => { if (event.target === event.currentTarget && !pending) onClose(); }}><div className="modal-heading"><div><span className="eyebrow">Finstep · Theo cách của bạn</span><h2 id="dialog-title">{title}</h2></div><button type="button" className="icon-button" aria-label="Đóng" onClick={onClose} disabled={pending}><X size={22}/></button></div>{children}</dialog>;
}
