import http from "node:http";
import { URL } from "node:url";

const ALLOWED_HOSTS = new Set([
  "www.merriam-webster.com",
  "www.ldoceonline.com",
  "www.oxfordlearnersdictionaries.com",
]);

const HOP_BY_HOP_HEADERS = new Set([
  "connection",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailers",
  "transfer-encoding",
  "upgrade",
]);

function sendJson(res, statusCode, body) {
  res.statusCode = statusCode;
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.end(JSON.stringify(body));
}

async function handleProxyRequest(req, res) {
  if (req.method !== "GET") {
    sendJson(res, 405, { error: "Method not allowed" });
    return;
  }

  const requestUrl = new URL(req.url, `http://${req.headers.host}`);
  if (requestUrl.pathname !== "/proxy") {
    sendJson(res, 404, { error: "Not found" });
    return;
  }

  const targetUrlParam = requestUrl.searchParams.get("url");
  if (!targetUrlParam) {
    sendJson(res, 400, { error: "Missing url parameter" });
    return;
  }

  let targetUrl;
  try {
    targetUrl = new URL(targetUrlParam);
  } catch (error) {
    sendJson(res, 400, { error: "Invalid url parameter" });
    return;
  }

  if (!ALLOWED_HOSTS.has(targetUrl.host)) {
    sendJson(res, 400, { error: "URL host is not permitted" });
    return;
  }

  let upstreamResponse;
  try {
    upstreamResponse = await fetch(targetUrl, {
      headers: {
        Accept:
          "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "User-Agent":
          "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
      },
    });
  } catch (error) {
    sendJson(res, 502, { error: "Upstream fetch failed" });
    return;
  }

  res.statusCode = upstreamResponse.status;
  for (const [header, value] of upstreamResponse.headers.entries()) {
    if (HOP_BY_HOP_HEADERS.has(header.toLowerCase())) {
      continue;
    }

    try {
      res.setHeader(header, value);
    } catch (error) {
      // Ignore invalid header attempts.
    }
  }

  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Cache-Control", "public, max-age=300");

  const body = await upstreamResponse.text();
  res.end(body);
}

const server = http.createServer((req, res) => {
  handleProxyRequest(req, res).catch((error) => {
    console.error("Unexpected proxy error", error);
    if (!res.headersSent) {
      sendJson(res, 500, { error: "Internal server error" });
    } else {
      res.end();
    }
  });
});

const port = process.env.PORT ? Number.parseInt(process.env.PORT, 10) : 3000;
server.listen(port, () => {
  console.log(`Dictionary proxy listening on http://localhost:${port}`);
});
