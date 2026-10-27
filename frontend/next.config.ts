import type { NextConfig } from "next";

const origin = new URL(process.env.API_ORIGIN ?? "http://127.0.0.1:8080");
if (!["http:", "https:"].includes(origin.protocol) || origin.username || origin.password || origin.pathname !== "/") {
  throw new Error("API_ORIGIN must be an HTTP(S) origin without credentials or path");
}
const config: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  async rewrites() { return [{ source: "/api/:path*", destination: `${origin.origin}/api/:path*` }]; },
  async headers() { return [{ source: "/:path*", headers: [
    { key: "X-Content-Type-Options", value: "nosniff" },
    { key: "X-Frame-Options", value: "DENY" },
    { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  ] }]; },
};
export default config;
