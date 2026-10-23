import { expect, type Page, type APIRequestContext } from "@playwright/test";
import { randomUUID } from "node:crypto";
export const password = "Finstep-Browser-Test-123!";
export async function mailToken(request: APIRequestContext, email: string, purpose: "VERIFY" | "RESET") {
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
export async function registerAndVerify(page: Page, request: APIRequestContext) {
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
export async function login(page: Page, email: string, value = password) {
  await page.getByLabel("Email", { exact: true }).fill(email);
  await page.getByLabel("Mật khẩu", { exact: true }).fill(value);
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Bắt đầu với ví đầu tiên." })).toBeVisible();
}
export async function createWallet(page: Page, balance = "3000000") {
  await page.getByRole("link", { name: "Tạo ví đầu tiên" }).click();
  await page.getByLabel("Tên ví").fill("Ví sinh hoạt");
  await page.getByLabel("Số dư ban đầu (VND)").fill(balance);
  await page.getByRole("button", { name: "Tạo ví và bắt đầu" }).click();
  await expect(page.getByRole("heading", { name: "Tổng quan tài chính." })).toBeVisible();
}
