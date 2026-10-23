export function money(amount: string): string {
  return new Intl.NumberFormat("vi-VN", { style: "currency", currency: "VND", maximumFractionDigits: 0 }).format(BigInt(amount));
}
export function accountMonth(timezone: string): string {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: timezone, year: "numeric", month: "2-digit" }).formatToParts(new Date());
  return `${parts.find(p => p.type === "year")!.value}-${parts.find(p => p.type === "month")!.value}`;
}
export function dateTime(value: string, timezone: string): string {
  return new Intl.DateTimeFormat("vi-VN", { timeZone: timezone, day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}
export function ratio(value: string, total: string): number {
  return BigInt(total) === 0n ? 0 : Number(BigInt(value) * 1000n / BigInt(total)) / 10;
}
