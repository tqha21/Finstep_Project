export function money(amount: string): string {
  return new Intl.NumberFormat("vi-VN", { style: "currency", currency: "VND", maximumFractionDigits: 0 }).format(BigInt(amount));
}
export function accountMonth(timezone: string): string {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: timezone, year: "numeric", month: "2-digit" }).formatToParts(new Date());
  return `${parts.find(p => p.type === "year")!.value}-${parts.find(p => p.type === "month")!.value}`;
}
export function dateTime(value: string, timezone: string): string {
  return new Intl.DateTimeFormat("vi-VN", { timeZone: timezone, day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}
export function ratio(value: string, total: string): number {
  return BigInt(total) === 0n ? 0 : Number(BigInt(value) * 1000n / BigInt(total)) / 10;
}
// datetime-local uses the device's timezone, which is named beside the input.
export function localDateTime(value: string): string {
  const date = new Date(value), pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}
export function instantFromLocal(value: string, original?: string): string {
  const normalized = value.length === 16 ? `${value}:00` : value;
  if (original && normalized === localDateTime(original)) return original;
  const date = new Date(value);
  if (Number.isNaN(date.getTime()) || localDateTime(date.toISOString()) !== normalized) {
    throw new Error("Thời điểm không tồn tại trong múi giờ thiết bị. Vui lòng chọn lại.");
  }
  return date.toISOString();
}
