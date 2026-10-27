import { cp, mkdir } from "node:fs/promises";
// Next traces server dependencies but deliberately omits static/public assets.
await mkdir(".next/standalone/.next", { recursive: true });
await cp(".next/static", ".next/standalone/.next/static", { recursive: true });
await cp("public", ".next/standalone/public", { recursive: true });
