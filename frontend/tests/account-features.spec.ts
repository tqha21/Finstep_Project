import { test, expect } from "./fixtures";
import { readFile } from "node:fs/promises";
import { password, registerAndVerify, login, createWallet, mailToken } from "./helpers";
const replacement="Browser-Changed-Password-456!";
test("password: form validation, current password, all sessions and prior reset tokens", async ({ page, request, browser }) => {
  const email=await registerAndVerify(page,request); await login(page,email); await createWallet(page);
  const csrf=await (await page.request.get("/api/v1/auth/csrf")).json();
  expect((await page.request.post("/api/v1/auth/forgot-password",{data:{email},headers:{[csrf.headerName]:csrf.token}})).status()).toBe(202);
  const reset=await mailToken(request,email,"RESET");
  const second=await browser.newContext({baseURL:new URL(page.url()).origin});
  try {
    const other=await second.newPage();await other.goto("/auth/login");await other.getByLabel("Email",{exact:true}).fill(email);await other.getByLabel("Mật khẩu",{exact:true}).fill(password);
    await other.getByRole("button",{name:"Đăng nhập",exact:true}).click();await expect(other.getByRole("heading",{name:"Tổng quan tài chính."})).toBeVisible();
    await page.goto("/app/profile");const form=page.getByRole("region",{name:"Đổi mật khẩu",exact:true});
    await form.getByLabel("Mật khẩu hiện tại",{exact:true}).fill(password);await form.getByLabel("Mật khẩu mới",{exact:true}).fill(replacement);await form.getByLabel("Nhập lại mật khẩu mới",{exact:true}).fill("Mismatched-Password-789!");
    await form.getByRole("button",{name:"Đổi mật khẩu",exact:true}).click();await expect(form.getByRole("alert")).toContainText("chưa trùng khớp");
    await form.getByLabel("Mật khẩu mới",{exact:true}).fill("đ".repeat(40));await form.getByLabel("Nhập lại mật khẩu mới",{exact:true}).fill("đ".repeat(40));
    await form.getByRole("button",{name:"Đổi mật khẩu",exact:true}).click();await expect(form.getByRole("alert")).toContainText("72 byte");
    await form.getByLabel("Mật khẩu hiện tại",{exact:true}).fill("Wrong-Password-123!");await form.getByLabel("Mật khẩu mới",{exact:true}).fill(replacement);await form.getByLabel("Nhập lại mật khẩu mới",{exact:true}).fill(replacement);
    await form.getByRole("button",{name:"Đổi mật khẩu",exact:true}).click();await expect(form.getByRole("alert")).toContainText("Mật khẩu hiện tại không đúng");
    await expect(form.getByLabel("Mật khẩu mới",{exact:true})).toHaveValue(replacement);
    await form.getByLabel("Mật khẩu hiện tại",{exact:true}).fill(password);await form.getByRole("button",{name:"Đổi mật khẩu",exact:true}).click();
    await expect(page.getByRole("heading",{name:"Mật khẩu đã được đổi",exact:true})).toBeVisible();
    expect((await page.request.get("/api/v1/me")).status()).toBe(401);await other.goto("/app");await expect(other).toHaveURL(/\/auth\/login$/);
    const token=await (await page.request.get("/api/v1/auth/csrf")).json();expect((await page.request.post("/api/v1/auth/reset-password",{data:{token:reset,password},headers:{[token.headerName]:token.token}})).status()).toBe(400);
    await page.getByRole("link",{name:"Đăng nhập bằng mật khẩu mới",exact:true}).click();
    await page.getByLabel("Email",{exact:true}).fill(email);await page.getByLabel("Mật khẩu",{exact:true}).fill(password);await page.getByRole("button",{name:"Đăng nhập",exact:true}).click();await expect(page.getByRole("alert").filter({hasText:"Email hoặc mật khẩu"})).toBeVisible();
    await page.getByLabel("Mật khẩu",{exact:true}).fill(replacement);await page.getByRole("button",{name:"Đăng nhập",exact:true}).click();await expect(page.getByRole("heading",{name:"Tổng quan tài chính."})).toBeVisible();
    expect((await (await page.request.get("/api/v1/wallets")).json())).toHaveLength(1);
  } finally {await second.close();}
});
test("feedback: mobile, lost response retry, escaped content and personal export", async ({ page, request }) => {
  await page.setViewportSize({width:390,height:844});const email=await registerAndVerify(page,request);await login(page,email);
  await page.getByRole("link",{name:"Gửi phản hồi",exact:true}).filter({visible:true}).click();await expect(page.getByRole("heading",{name:"Gửi phản hồi",exact:true})).toBeVisible();
  const content="Lịch sử trên điện thoại cần dễ tìm hơn.\n<script>window.feedbackExecuted=true</script>";
  const keys:string[]=[];let dropped=false;
  await page.route("**/api/v1/feedback",async route=>{
    if(route.request().method()!=="POST") return route.continue(); keys.push(route.request().headers()["idempotency-key"]);
    if(dropped) return route.continue();dropped=true;expect((await route.fetch()).status()).toBe(201);
    await route.fulfill({status:503,contentType:"application/problem+json",body:JSON.stringify({detail:"Mất phản hồi thử nghiệm. Hãy gửi lại."})});
  });
  await page.getByLabel("Loại phản hồi",{exact:true}).selectOption("IDEA");await page.getByLabel("Nội dung phản hồi",{exact:true}).fill(content);
  await page.getByRole("button",{name:"Gửi phản hồi",exact:true}).click();await expect(page.getByRole("region",{name:"Form phản hồi"}).getByRole("alert")).toContainText("Mất phản hồi thử nghiệm");
  await expect(page.getByLabel("Nội dung phản hồi",{exact:true})).toHaveValue(content);await expect(page.getByLabel("Loại phản hồi",{exact:true})).toHaveValue("IDEA");
  await page.getByRole("button",{name:"Gửi phản hồi",exact:true}).click();const history=page.getByRole("region",{name:"Phản hồi của bạn",exact:true});await expect(history.locator("li")).toHaveCount(1);await expect(history).toContainText(content);
  expect(keys).toHaveLength(2);expect(keys[0]).toBeTruthy();expect(keys[0]).toBe(keys[1]);expect(await page.evaluate(()=>"feedbackExecuted" in window)).toBe(false);
  expect((await (await page.request.get("/api/v1/feedback")).json())).toHaveLength(1);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.screenshot({path:"test-results/feedback-mobile.png",fullPage:true});
  await page.goto("/app/profile");const downloading=page.waitForEvent("download");await page.getByRole("button",{name:"Xuất toàn bộ dữ liệu",exact:true}).click();const download=await downloading;
  const data=JSON.parse(await readFile((await download.path())!,"utf8"));expect(data.feedback).toHaveLength(1);expect(data.feedback[0].message).toBe(content);expect(data.feedback[0].type).toBe("IDEA");expect(data.feedback[0].requestKey).toBeUndefined();
});
test("password: lost committed response offers verification and accepts new credentials", async ({ page, request }) => {
  const email=await registerAndVerify(page,request);await login(page,email);await page.goto("/app/profile");let commits=0;
  await page.route("**/api/v1/me/password",async route=>{commits++;expect((await route.fetch()).status()).toBe(204);await route.fulfill({status:503,contentType:"application/problem+json",body:JSON.stringify({detail:"Mất phản hồi sau đổi mật khẩu."})});});
  const form=page.getByRole("region",{name:"Đổi mật khẩu",exact:true});await form.getByLabel("Mật khẩu hiện tại",{exact:true}).fill(password);await form.getByLabel("Mật khẩu mới",{exact:true}).fill(replacement);await form.getByLabel("Nhập lại mật khẩu mới",{exact:true}).fill(replacement);await form.getByRole("button",{name:"Đổi mật khẩu",exact:true}).click();
  await expect(page.getByRole("heading",{name:"Chưa xác nhận được kết quả đổi mật khẩu",exact:true})).toBeVisible();expect(commits).toBe(1);
  await page.getByRole("link",{name:"Kiểm tra đăng nhập",exact:true}).click();await login(page,email,replacement);
});
