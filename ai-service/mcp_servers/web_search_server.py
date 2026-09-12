"""web-search MCP server（stdio）：给 Agent 提供联网搜索工具。

接入方式（ai-service 的 AI_MCP_CONFIG，已随本文件提供默认启用配置）：
  {"web-search": {"command": "<python>", "args": ["-m", "mcp_servers.web_search"], "cwd": "<ai-service根目录>"}}

工具：web_search(query, count=5)
搜索源策略：
  1) 配置了 TAVILY_API_KEY 时走 Tavily API（质量更好，需自行注册免费 key）；
  2) 未配置时自动降级为 Bing 中文版 HTML 结果解析（免 key、零第三方依赖，演示够用）。

输出格式（纯文本，含 [n] 编号，供 LLM 引用与标源）：
  [1] 标题
  摘要
  链接
"""
import html
import json
import os
import re
import sys
import urllib.parse
import urllib.request

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/126.0 Safari/537.36")
TIMEOUT = 20

strip_tags = lambda s: re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", s or "")).strip()


def _http_get(url: str, headers: dict = None) -> str:
    req = urllib.request.Request(url)
    req.add_header("User-Agent", UA)
    if headers:
        for k, v in headers.items():
            req.add_header(k, v)
    with urllib.request.urlopen(req, timeout=25) as resp:
        return resp.read().decode("utf-8", "ignore")


def _http_post_json(url: str, payload: dict) -> dict:
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "User-Agent": UA},
        method="POST")
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        return json.loads(resp.read().decode("utf-8"))


def search_tavily(query: str, count: int, api_key: str) -> list[dict]:
    """Tavily 搜索 API（有 key 时优先）。返回 [{title, url, content}]。"""
    data = _http_post_json(
        "https://api.tavily.com/search",
        {"api_key": api_key, "query": query, "max_results": count,
         "search_depth": "basic", "include_answer": False})
    return [{"title": r.get("title", "")[:200],
             "url": r.get("url", ""),
             "content": (r.get("content", "") or "")[:300]}
            for r in (data.get("results") or [])[:count]]


def search_bing(query: str, count: int) -> list[dict]:
    """Bing 中文版 HTML 结果解析（免 key）。返回 [{title, url, content}]。"""
    q = urllib.parse.quote(query)
    html_text = _http_get(f"https://cn.bing.com/search?q={q}&count={max(count, 10)}&mkt=zh-CN")
    results = []
    for block in re.split(r'<li class="b_algo"', html_text)[1:]:
        if len(results) >= count:
            break
        clean = lambda s: re.sub(r"[\s\u200b\u200e]+", " ", (strip_tags(html.unescape(s or "")))).strip()
        h2 = re.search(r"<h2[^>]*>(.*?)</h2>", block, re.S)
        if not h2:
            continue
        a = re.search(r'<a[^>]+href="(http[^"]+)"[^>]*>(.*?)</a>', h2.group(1), re.S)
        if not a:
            continue
        title = clean(a.group(2))
        if not title:
            continue
        p = re.search(r"<p[^>]*>(.*?)</p>", block, re.S)
        results.append({"title": title[:200],
                        "url": clean(a.group(1)),
                        "content": (clean(p.group(1)) if p else "")[:300]})
    return results


def search(query: str, count: int = 5) -> str:
    """搜索主体：Tavily 优先，Bing 兜底；返回 [n] 编号格式化文本。"""
    query = (query or "").strip()
    if not query:
        return "查询词为空，请提供要搜索的问题。"
    count = max(1, min(int(count or 5), 10))

    tavily_key = os.getenv("TAVILY_API_KEY", "").strip()
    items = []
    source = "bing"
    if tavily_key:
        try:
            items = search_tavily(query, count, tavily_key)
            source = "tavily"
        except Exception as e:  # noqa: BLE001  Tavily 失败回落 Bing
            items = []
            print(f"[web-search] Tavily failed, fallback to bing: {e}", file=sys.stderr)
    if not items:
        try:
            items = search_bing(query, count)
        except Exception as e:  # noqa: BLE001
            return f"联网搜索失败：{type(e).__name__}: {e}"

    if not items:
        return f"没有搜索到与「{query}」相关的结果，请换更具体的关键词。"

    lines = [f"（来源：{source}，查询：{query}）"]
    for i, it in enumerate(items, start=1):
        lines.append(f"[{i}] {it['title']}")
        if it.get("content"):
            lines.append(f"    内容：{it['content']}")
        if it.get("url"):
            lines.append(f"    链接：{it['url']}")
    return "\n".join(lines)


def build_server():
    from mcp.server.fastmcp import FastMCP

    mcp = FastMCP("web-search")

    @mcp.tool()
    def web_search(query: str, count: int = 5) -> str:
        """联网搜索最新信息。query 为要搜索的问题或关键词（支持中文），count 为结果条数(1-10)。
        返回带 [n] 编号的标题、内容摘要与链接；回答时应注明信息来源编号。"""
        return search(query, count)

    return mcp


if __name__ == "__main__":
    build_server().run()
