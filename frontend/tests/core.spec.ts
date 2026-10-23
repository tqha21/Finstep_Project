import { test, expect, type Page, type APIRequestContext } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
const password = "Finstep-Browser-Test-123!";
async function mailToken(request: APIRequestContext, email: string, purpose: "VERIFY" | "RESET") {
  if (!process.env.MAILPIT_API) throw new Error("Run E2E through scripts/test-web.sh with an isolated local mailbox");
  const origin = new URL(process.env.MAILPIT_API);
  if (origin.hostname !== "127.0.0.1") throw new Error("E2E mailbox must be local");
  let id = "";
  await expect.poll(async () => {
    const response = await request.get(`${origin.origin}/api/v1/messages`);
    const body = await response.json();
    const match = body.messages.find((message: { ID: string; Subject: string; To: { Address: string }[] }) =>
      message.To.some(to => to.Address === email) && message.Subject.includes(purpose === "VERIFY" ? "Xác minh" : "Đặt lại"));
    id = match?.ID ?? ""; return Boolean(id);
  }).toBe(true);
  const message = await (await request.get(`${origin.origin}/api/v1/message/${id}`)).json();
  const token = String(message.Text).match(/[A-Za-z0-9_-]{43}/)?.[0];
  if (!token) throw new Error("Expected a one-time token in the local fixture email");
  return token;
}
async function registerAndVerify(page: Page, request: APIRequestContext) {
  const email = `browser-${randomUUID()}@example.test`;
  await page.goto("/auth/register");
  await page.getByLabel("Họ và tên").fill("An Nguyễn");
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Mật khẩu", { exact: true }).fill(password);
  await page.getByLabel("Nhập lại mật khẩu").fill(password);
  await page.getByRole("button", { name: "Tạo tài khoản", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Hãy kiểm tra hộp thư" })).toBeVisible();
  const token = await mailToken(request, email, "VERIFY");
  await page.getByRole("link", { name: "Nhập mã xác minh" }).click();
  await page.getByLabel("Mã trong email").fill(token);
  await page.getByRole("button", { name: "Xác minh email", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Email đã được xác minh" })).toBeVisible();
  await page.getByRole("link", { name: "Đến trang đăng nhập" }).click();
  return email;
}
async function login(page: Page, email: string, value = password) {
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Mật khẩu", { exact: true }).fill(value);
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Bắt đầu với ví đầu tiên." })).toBeVisible();
}
async function createWallet(page: Page, balance = "3000000") {
  await page.getByRole("link", { name: "Tạo ví đầu tiên" }).click();
  await page.getByLabel("Tên ví").fill("Ví sinh hoạt");
  await page.getByLabel("Số dư ban đầu (VND)").fill(balance);
  await page.getByRole("button", { name: "Tạo ví và bắt đầu" }).click();
  await expect(page.getByRole("heading", { name: "Tổng quan tài chính." })).toBeVisible();
}

test("desktop: full account flow, lost response retry, budget, CSV and logout", async ({ page, request }) => {
  const email = await registerAndVerify(page, request);
  await login(page, email); await createWallet(page);
  await expect(page.getByRole("article").filter({ hasText: "Tổng số dư hiện tại" })).toContainText("3.000.000");
  const keys: string[] = []; let dropped = false;
  await page.route("**/api/v1/transactions", async route => {
    if (route.request().method() !== "POST") return route.continue();
    keys.push(route.request().headers()["idempotency-key"]);
    if (!dropped) {
      dropped = true; const response = await route.fetch(); expect(response.status()).toBe(201);
      await route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ detail: "Kết nối gián đoạn trong kiểm thử. Vui lòng thử lại." }) });
    } else await route.continue();
  });
  await page.getByRole("button", { name: "Thêm giao dịch" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Số tiền (VND)").fill("45000");
  await dialog.getByLabel("Danh mục", { exact: true }).selectOption({ label: "Ăn uống" });
  await dialog.getByLabel(/Ghi chú/).fill("Ăn trưa");
  await dialog.getByRole("button", { name: "Lưu giao dịch", exact: true }).click();
  await expect(dialog.getByRole("alert")).toContainText("Kết nối gián đoạn");
  await expect(dialog.getByLabel("Số tiền (VND)")).toHaveValue("45000");
  await expect(dialog.getByLabel(/Ghi chú/)).toHaveValue("Ăn trưa");
  await dialog.getByRole("button", { name: "Lưu giao dịch", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.locator("#activity")).toContainText("Ăn trưa");
  expect(keys).toHaveLength(2); expect(keys[0]).toBeTruthy(); expect(keys[0]).toBe(keys[1]);
  const history = await (await page.request.get("/api/v1/transactions")).json(); expect(history.items).toHaveLength(1);
  await expect(page.getByRole("article").filter({ hasText: "Tổng số dư hiện tại" })).toContainText("2.955.000");
  await page.getByRole("button", { name: "Thiết lập", exact: true }).click();
  await page.getByRole("dialog").getByLabel("Ăn uống", { exact: true }).fill("50000");
  await page.getByRole("button", { name: "Lưu ngân sách", exact: true }).click();
  await expect(page.locator("#budget")).toContainText("Gần hạn mức");
  await expect(page.locator("#budget")).toContainText("90.00%");
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "Xuất CSV" }).click();
  const download = await downloadPromise; expect(download.suggestedFilename()).toBe("finstep-transactions.csv");
  const csv = await readFile((await download.path())!, "utf8"); expect(csv).toContain("Ăn trưa"); expect(csv).toContain("45000");
  await page.screenshot({ path: "test-results/dashboard-desktop.png", fullPage: true });
  await page.getByRole("button", { name: "Đăng xuất", exact: true }).filter({ visible: true }).click();
  await expect(page.getByRole("heading", { name: /Hiểu tiền của bạn/ })).toBeVisible();
  await page.goto("/app"); await expect(page).toHaveURL(/\/auth\/login$/);
});

test("mobile: onboarding, empty states, accessible dialog and real income", async ({ page, request }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const email = await registerAndVerify(page, request); await login(page, email); await createWallet(page, "0");
  await expect(page.getByRole("heading", { name: "Chưa có thu chi trong tháng" })).toBeVisible();
  const add = page.getByRole("button", { name: "Thêm giao dịch" });
  await add.click(); await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Escape"); await expect(page.getByRole("dialog")).not.toBeVisible(); await expect(add).toBeFocused();
  await add.click(); await page.getByRole("button", { name: "Khoản thu", exact: true }).click();
  await page.getByLabel("Số tiền (VND)").fill("8000000");
  await page.getByLabel("Danh mục", { exact: true }).selectOption({ label: "Lương" });
  await page.getByLabel(/Ghi chú/).fill("Nhận lương");
  await page.getByRole("button", { name: "Lưu giao dịch", exact: true }).click();
  await expect(page.locator("#activity")).toContainText("Nhận lương");
  await expect(page.getByRole("article").filter({ hasText: "Tổng số dư hiện tại" })).toContainText("8.000.000");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: "test-results/dashboard-mobile.png", fullPage: true });
  await page.getByLabel("Tháng báo cáo", { exact: true }).fill("2025-01");
  await expect(page.getByRole("heading", { name: "Chưa có thu chi trong tháng" })).toBeVisible();
  await expect(page.locator("#activity")).not.toContainText("Nhận lương");
  await expect(page.locator("#activity")).toContainText("Cuốn sổ của bạn đang chờ");
});

test("recovery: reset through local email, reject old password and preserve login form", async ({ page, request }) => {
  const email = await registerAndVerify(page, request);
  await page.getByRole("link", { name: "Quên mật khẩu?" }).click();
  await expect(page.getByRole("heading", { name: "Lấy lại quyền truy cập" })).toBeVisible();
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByRole("button", { name: "Gửi hướng dẫn", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Hãy kiểm tra hộp thư" })).toBeVisible();
  const token = await mailToken(request, email, "RESET");
  await page.getByRole("link", { name: "Nhập mã đặt lại mật khẩu" }).click();
  const replacement = "New-Browser-Password-456!";
  await page.getByLabel("Mã trong email").fill(token);
  await page.getByLabel("Mật khẩu mới", { exact: true }).fill(replacement);
  await page.getByLabel("Nhập lại mật khẩu").fill(replacement);
  await page.getByRole("button", { name: "Đặt lại mật khẩu", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Mật khẩu đã được cập nhật" })).toBeVisible();
  await page.getByRole("link", { name: "Đến trang đăng nhập" }).click();
  await page.getByLabel("Email", { exact: true }).fill(email); await page.getByLabel("Mật khẩu", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: "Email hoặc mật khẩu" })).toContainText("không hợp lệ");
  await expect(page.getByLabel("Email", { exact: true })).toHaveValue(email);
  await login(page, email, replacement);
});
