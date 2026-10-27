"use client";
import { createContext, useCallback, useContext, useEffect, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import Link from "next/link";
import { LayoutDashboard, ArrowLeftRight, WalletCards, Tags, UserRound, MessageSquare, LogOut, ArrowUpRight } from "lucide-react";
import { api, ApiError, errorMessage } from "@/lib/api";
import { useResource } from "@/lib/use-resource";
import type { User } from "@/lib/types";
import { Brand } from "./brand";
import { Busy, ErrorNotice } from "./feedback";
const UserContext = createContext<{ user: User; updateUser: (user: User) => void } | null>(null);
export function useUser(): User { const user = useContext(UserContext); if (!user) throw new Error("Missing user context"); return user.user; }
export function useUpdateUser() { const value = useContext(UserContext); if (!value) throw new Error("Missing user context"); return value.updateUser; }
const links = [{ label: "Tổng quan", href: "/app", icon: LayoutDashboard }, { label: "Giao dịch", href: "/app/activity", icon: ArrowLeftRight }, { label: "Ví của tôi", href: "/app/wallets", icon: WalletCards }, { label: "Danh mục", href: "/app/categories", icon: Tags }];
export function ProtectedShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter(), load = useCallback(() => api<User>("/me"), []);
  const resource = useResource("profile", load);
  const [loggingOut, setLoggingOut] = useState(false), [logoutError, setLogoutError] = useState("");
  useEffect(() => { if (resource.error instanceof ApiError && resource.error.status === 401) router.replace("/auth/login"); }, [resource.error, router]);
  async function logout() {
    setLoggingOut(true); setLogoutError("");
    try { await api("/auth/logout", "POST", {}); router.replace("/"); router.refresh(); }
    catch (cause) { setLogoutError(errorMessage(cause)); setLoggingOut(false); }
  }
  if (resource.loading || (resource.error instanceof ApiError && resource.error.status === 401)) return <main id="main" className="screen-center"><Busy label="Đang kiểm tra phiên đăng nhập…"/></main>;
  if (resource.error || !resource.data) return <main id="main" className="screen-center"><ErrorNotice message={errorMessage(resource.error)}/><button className="button button-secondary" onClick={resource.reload}>Thử lại</button></main>;
  const user = resource.data;
  return <UserContext.Provider value={{ user, updateUser: resource.replace }}><div className="app-shell"><aside className="sidebar"><Brand/><span className="nav-caption">Không gian của bạn</span><nav aria-label="Điều hướng ứng dụng">{links.map(({ label, href, icon: Icon }) => <Link key={href} href={href} aria-current={pathname === href ? "page" : undefined} className={`side-link ${pathname === href ? "selected" : ""}`}><Icon size={20}/>{label}{pathname === href && <span className="nav-dot"/>}</Link>)}<Link href="/app/profile" className={`side-link ${pathname === "/app/profile" ? "selected" : ""}`} aria-current={pathname === "/app/profile" ? "page" : undefined}><UserRound size={20}/>Hồ sơ</Link><Link href="/app/feedback" className={`side-link ${pathname === "/app/feedback" ? "selected" : ""}`} aria-current={pathname === "/app/feedback" ? "page" : undefined}><MessageSquare size={20}/>Phản hồi</Link></nav><div className="sidebar-note"><span className="eyebrow">Từng bước nhỏ</span><p>Hiểu thói quen hôm nay.<br/>Chủ động ngày mai.</p><ArrowUpRight size={22}/></div><div className="profile-block"><span className="avatar">{user.fullName.trim().charAt(0).toUpperCase()}</span><div><strong>{user.fullName}</strong><small>{user.email}</small></div><button className="icon-button" aria-label="Đăng xuất" title="Đăng xuất" onClick={logout} disabled={loggingOut}><LogOut size={19}/></button></div></aside><div className="app-body"><header className="mobile-header"><Brand/><Link className="icon-button" href="/app/feedback" aria-label="Gửi phản hồi"><MessageSquare size={20}/></Link><Link className="icon-button" href="/app/profile" aria-label="Hồ sơ"><UserRound size={20}/></Link><button className="icon-button" aria-label="Đăng xuất" onClick={logout} disabled={loggingOut}><LogOut size={20}/></button></header>{logoutError && <ErrorNotice message={logoutError}/>}<main id="main" className="workspace">{children}</main><footer className="workspace-footer">Finstep · Dữ liệu ghi chép của bạn <span>Tiền tệ VND · {user.timezone}</span></footer></div><nav className="mobile-nav" aria-label="Điều hướng di động">{links.map(({ label, href, icon: Icon }) => <Link href={href} key={href} aria-current={pathname === href ? "page" : undefined}><Icon size={20}/><span>{label}</span></Link>)}</nav></div></UserContext.Provider>;
}
