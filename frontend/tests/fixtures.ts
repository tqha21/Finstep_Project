import { test as base, expect } from "@playwright/test";
import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import { promisify } from "node:util";
const execute=promisify(execFile);
export const test=base.extend<{ resetNetworkLimit: void }>({
  resetNetworkLimit: [async ({ baseURL },use)=>{
    const container=process.env.FINSTEP_E2E_DB_CONTAINER;
    if(!baseURL || new URL(baseURL).hostname!=="127.0.0.1" || !container || !/^finstep-web-db-[0-9]+$/.test(container))
      throw new Error("Run browser tests through scripts/test-web.sh with an isolated fixture database");
    const label=await execute("docker",["inspect","-f",'{{index .Config.Labels "com.finstep.e2e"}}',container]);
    if(label.stdout.trim()!=="true") throw new Error("Refusing to change a database without the E2E ownership label");
    // All browser requests share the local Next proxy address. Isolate this network bucket per
    // scenario; account limits and all in-scenario throttling remain intact.
    const bucket=createHash("sha256").update("ip:127.0.0.1").digest("hex");
    await execute("docker",["exec",container,"psql","-X","-U","postgres","-d","postgres","-v","ON_ERROR_STOP=1","-c",`DELETE FROM auth_rate_limits WHERE bucket_hash='${bucket}'`]);
    await use();
  },{auto:true}],
});
export { expect };
