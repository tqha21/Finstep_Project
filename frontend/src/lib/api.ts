export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); this.name = "ApiError"; }
}
async function request(path: string, init: RequestInit = {}): Promise<Response> {
  let response: Response;
  try { response = await fetch(`/api/v1${path}`, { ...init, credentials: "same-origin", cache: "no-store" }); }
  catch { throw new ApiError(0, "Chưa kết nối được máy chủ. Nội dung bạn nhập vẫn được giữ lại; vui lòng thử lại."); }
  if (!response.ok) {
    let message = response.status === 401 ? "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại." :
      response.status === 429 ? "Bạn thao tác quá nhanh. Vui lòng đợi một lát rồi thử lại." : "Chưa thể hoàn tất thao tác. Vui lòng thử lại.";
    try { const error = await response.json(); if (typeof error.detail === "string") message = error.detail; } catch { /* Empty security responses are valid. */ }
    throw new ApiError(response.status, message);
  }
  return response;
}
export async function api<T>(path: string, method = "GET", body?: unknown, key?: string): Promise<T> {
  const headers = new Headers();
  if (method !== "GET") {
    const csrf = await api<{ token: string; headerName: string }>("/auth/csrf");
    headers.set(csrf.headerName, csrf.token);
    headers.set("Content-Type", "application/json");
  }
  if (key) headers.set("Idempotency-Key", key);
  const response = await request(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  if (response.status === 204 || response.status === 202) return undefined as T;
  return response.json() as Promise<T>;
}
export async function downloadCsv(from: string, to: string): Promise<void> {
  const response = await request(`/exports/transactions.csv?${new URLSearchParams({ from, to })}`);
  const url = URL.createObjectURL(await response.blob());
  const anchor = document.createElement("a"); anchor.href = url; anchor.download = "finstep-transactions.csv";
  document.body.append(anchor); anchor.click(); anchor.remove();
  // Allow the browser to start the download before releasing the object URL.
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
}
export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "Đã có lỗi xảy ra. Vui lòng thử lại.";
}
