import { test, expect, type Page } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { password, registerAndVerify, login, createWallet, mailToken } from "./helpers";
async function mutate(page: Page, method: string, path: string, data: unknown, key?: string) {
  const csrf = await (await page.request.get("/api/v1/auth/csrf")).json();
  const response = await page.request.fetch(`/api/v1${path}`, { method, data, headers: { [csrf.headerName]: csrf.token, ...(key ? { "Idempotency-Key": key } : {}) } });
  expect(response.ok()).toBe(true); return response.status() === 204 || response.status() === 202 ? undefined : response.json();
}
test("privacy: complete export, incomplete download rejection, reauthentication and multi-session deletion", async ({ page, request, browser }) => {
  const email = await registerAndVerify(page, request); await login(page, email); await createWallet(page);
  const wallet = (await (await page.request.get("/api/v1/wallets")).json())[0];
  const bank = await mutate(page, "POST", "/wallets", { name: "Ví lưu trữ", type: "BANK", openingBalance: "1000", effectiveAt: new Date().toISOString() });
  const categories = await (await page.request.get("/api/v1/categories")).json();
  const expense = categories.find((category: { type: string }) => category.type === "EXPENSE");
  const tx = await mutate(page, "POST", "/transactions", { walletId: wallet.id, categoryId: expense.id, type: "EXPENSE", amount: "45000", note: "Ghi chú đã xóa", occurredAt: new Date().toISOString() }, crypto.randomUUID());
  await mutate(page, "DELETE", `/transactions/${tx.id}`, undefined);
  await mutate(page, "POST", "/transfers", { fromWalletId: wallet.id, toWalletId: bank.id, amount: "500", note: "Chuyển ví riêng tư", occurredAt: new Date().toISOString() }, crypto.randomUUID());
  await mutate(page, "PUT", "/budgets/2026-01", { items: [{ categoryId: expense.id, limitAmount: "50000" }] });
  await mutate(page, "PATCH", `/wallets/${bank.id}`, { archived: true });
  await mutate(page, "PATCH", `/categories/${expense.id}`, { archived: true });
  await mutate(page, "POST", "/auth/forgot-password", { email });
  const reset = await mailToken(request, email, "RESET");
  const second = await browser.newContext({ baseURL: new URL(page.url()).origin });
  try {
    const other = await second.newPage(); await other.goto("/auth/login");
    await other.getByLabel("Email", { exact: true }).fill(email); await other.getByLabel("Mật khẩu", { exact: true }).fill(password);
    await other.getByRole("button", { name: "Đăng nhập", exact: true }).click(); await expect(other.getByRole("heading", { name: "Tổng quan tài chính." })).toBeVisible();
    await page.goto("/app/profile"); await page.getByRole("link", { name: "Đọc thông tin quyền riêng tư" }).click();
    await expect(page.getByRole("heading", { name: "Dữ liệu của bạn trong Finstep" })).toBeVisible();
    await page.getByRole("link", { name: "Đến hồ sơ", exact: true }).click();
    await page.route("**/api/v1/me/export", route => route.fulfill({ status: 200, contentType: "application/json", body: '{"schemaVersion":1,"account":' }));
    await page.getByRole("button", { name: "Xuất toàn bộ dữ liệu", exact: true }).click();
    await expect(page.getByRole("region", { name: "Dữ liệu và quyền riêng tư" }).getByRole("alert")).toContainText("tệp dữ liệu đầy đủ");
    await page.unroute("**/api/v1/me/export");
    const downloading = page.waitForEvent("download"); await page.getByRole("button", { name: "Xuất toàn bộ dữ liệu", exact: true }).click();
    const download = await downloading; expect(download.suggestedFilename()).toBe("finstep-personal-data.json");
    const text = await readFile((await download.path())!, "utf8"), data = JSON.parse(text);
    expect(data.account.email).toBe(email); expect(data.schemaVersion).toBe(1); expect(data.wallets).toHaveLength(2);
    expect(data.wallets.find((item: { id: string }) => item.id === bank.id).archived).toBe(true);
    expect(data.transactions).toHaveLength(1); expect(data.transactions[0].deletedAt).toBeTruthy(); expect(data.transactions[0].amount).toBe("45000");
    expect(data.transfers).toHaveLength(1); expect(data.budgets).toHaveLength(1); expect(data.budgetItems[0].limitAmount).toBe("50000");
    expect(text).not.toMatch(/password|token|authVersion|idempotency|spring_session/);
    await page.getByRole("button", { name: "Xóa tài khoản", exact: true }).click(); const dialog = page.getByRole("dialog");
    await dialog.getByRole("button", { name: "Hủy", exact: true }).click(); await expect(dialog).not.toBeVisible();
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.getByRole("region", { name: "Dữ liệu và quyền riêng tư" }).screenshot({ path: "test-results/privacy-mobile.png" });
    await page.getByRole("button", { name: "Xóa tài khoản", exact: true }).click();
    await dialog.getByLabel("Mật khẩu hiện tại", { exact: true }).fill("Wrong-Password-123!"); await dialog.getByLabel("Nhập XÓA TÀI KHOẢN", { exact: true }).fill("XÓA TÀI KHOẢN");
    await dialog.getByRole("button", { name: "Xóa vĩnh viễn", exact: true }).click(); await expect(dialog.getByRole("alert")).toContainText("Mật khẩu hiện tại không đúng");
    expect((await page.request.get("/api/v1/wallets")).status()).toBe(200);
    await dialog.getByLabel("Mật khẩu hiện tại", { exact: true }).fill(password); await dialog.getByRole("button", { name: "Xóa vĩnh viễn", exact: true }).click();
    await expect(page.getByRole("heading", { name: "Tài khoản đã được xóa", exact: true })).toBeVisible();
    expect((await page.request.get("/api/v1/me/export")).status()).toBe(401);
    await other.goto("/app"); await expect(other).toHaveURL(/\/auth\/login$/);
    const csrf = await (await page.request.get("/api/v1/auth/csrf")).json();
    expect((await page.request.post("/api/v1/auth/reset-password", { data: { token: reset, password }, headers: { [csrf.headerName]: csrf.token } })).status()).toBe(400);
    await page.goto("/auth/login"); await page.getByLabel("Email", { exact: true }).fill(email); await page.getByLabel("Mật khẩu", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Đăng nhập", exact: true }).click(); await expect(page.getByRole("alert").filter({ hasText: "Email hoặc mật khẩu" })).toContainText("Email hoặc mật khẩu không hợp lệ");
  } finally { await second.close(); }
});

test("privacy: lost deletion response reports uncertainty without claiming failure", async ({ page, request }) => {
  const email = await registerAndVerify(page, request); await login(page, email);
  await page.goto("/app/profile");
  let commits = 0;
  await page.route("**/api/v1/me", async route => {
    if (route.request().method() !== "DELETE") return route.continue();
    commits++; expect((await route.fetch()).status()).toBe(204);
    await route.fulfill({ status: 503, contentType: "application/problem+json", body: JSON.stringify({ detail: "Mất phản hồi sau khi xóa." }) });
  });
  await page.getByRole("button", { name: "Xóa tài khoản", exact: true }).click();
  await page.getByRole("dialog").getByLabel("Mật khẩu hiện tại", { exact: true }).fill(password); await page.getByLabel("Nhập XÓA TÀI KHOẢN", { exact: true }).fill("XÓA TÀI KHOẢN");
  await page.getByRole("button", { name: "Xóa vĩnh viễn", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Chưa xác nhận được kết quả xóa", exact: true })).toBeVisible(); expect(commits).toBe(1);
  expect((await page.request.get("/api/v1/me")).status()).toBe(401);
  await page.getByRole("link", { name: "Kiểm tra đăng nhập", exact: true }).click();
  await page.getByLabel("Email", { exact: true }).fill(email); await page.getByLabel("Mật khẩu", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click(); await expect(page.getByRole("alert").filter({ hasText: "Email hoặc mật khẩu" })).toContainText("Email hoặc mật khẩu không hợp lệ");
});
