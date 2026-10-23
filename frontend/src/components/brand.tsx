import Link from "next/link";
import { Layers2 } from "lucide-react";
export function Brand({ light = false }: { light?: boolean }) {
  return <Link href="/" className={`brand ${light ? "brand-light" : ""}`} aria-label="Finstep — Trang chủ"><span className="brand-mark"><Layers2 size={22} strokeWidth={2.2}/></span>finstep<span className="brand-dot">.</span></Link>;
}
