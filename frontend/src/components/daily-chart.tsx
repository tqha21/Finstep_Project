import { ChartNoAxesCombined } from "lucide-react";
import type { Summary } from "@/lib/types";
import { ratio } from "@/lib/format";
export function DailyChart({ report }: { report: Summary }) {
  const max = report.daily.reduce((largest, day) => [BigInt(day.income), BigInt(day.expense)].reduce((a, b) => a > b ? a : b, largest), 0n);
  if (max === 0n) return <div className="chart-empty"><ChartNoAxesCombined size={32} strokeWidth={1.3}/><h3>Chưa có thu chi trong tháng</h3><p>Ghi lại khoản tiền đầu tiên hoặc chọn tháng khác để xem dòng tiền.</p></div>;
  const step = 600 / report.daily.length;
  return <><div className="chart-legend"><span><i className="legend-income"/>Thu nhập</span><span><i className="legend-expense"/>Chi tiêu</span></div><svg className="daily-chart" viewBox="0 0 640 210" role="img" aria-label="Thu nhập và chi tiêu từng ngày trong tháng"><title>Thu chi theo ngày. Số liệu tổng và chi tiết có trong phần giao dịch.</title>{[30, 80, 130, 180].map(y => <line key={y} x1="20" x2="620" y1={y} y2={y} stroke="#e7eeeb" strokeDasharray="4 5"/>)}{report.daily.map((day, index) => { const income = ratio(day.income, max.toString()) * 1.5, expense = ratio(day.expense, max.toString()) * 1.5; return <g key={day.date}><rect x={20 + index * step} y={180 - income} width={Math.max(2, step * .3)} height={income} rx="2" fill="#18816c"/><rect x={20 + index * step + step * .34} y={180 - expense} width={Math.max(2, step * .3)} height={expense} rx="2" fill="#edb774"/>{[0, 14, report.daily.length - 1].includes(index) && <text x={20 + index * step} y="204" fill="#788980" fontSize="11">{day.date.slice(-2)}</text>}</g>; })}</svg></>;
}
