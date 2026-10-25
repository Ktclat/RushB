// Runs in an isolated extension world. No page messages, cookies or credentials are read.
(() => {
  if (window.top !== window) return;
  const port = browser.runtime.connectNative("rushb_reader");
  let jsonStarted = false;
  port.onMessage.addListener(async request => {
    if (request.type !== "capture") return;
    const reply = {requestId: request.requestId, url: location.href, title: document.title};
    if (document.readyState === "loading") {
      port.postMessage({...reply, state: "loading"});
      return;
    }
    if (/attention required/i.test(document.title) && /sorry, you have been blocked|you are unable to access/i.test(document.body?.innerText || "")) {
      port.postMessage({...reply, state: "blocked"});
      return;
    }
    if (/just a moment|attention required|请稍候/i.test(document.title) ||
        document.querySelector("#challenge-running, #cf-challenge-running, #challenge-stage")) {
      port.postMessage({...reply, state: "challenge"});
      return;
    }
    if (request.inspectOnly === true) {
      port.postMessage({...reply, state: "ready"});
      return;
    }
    if (typeof request.jsonUrl === "string") {
      if (jsonStarted) return;
      let target;
      try { target = new URL(request.jsonUrl); } catch (_) { return; }
      const expected = {MatchesV4: null, MatchScreen: "matchId", EventsData2: null, EventDetails2: "eventId", TeamScreen: "teamId", PlayerScreen: "playerId", TeamNews: "teamId", PlayerNews: "playerId"};
      const route = target.pathname.substring("/mobile/".length);
      const parameter = expected[route];
      if (target.origin !== location.origin || target.username || target.password || target.hash ||
          !target.pathname.startsWith("/mobile/") || !Object.hasOwn(expected, route) ||
          (parameter === null ? target.search !== "" : [...target.searchParams].length !== 1 || !/^[1-9][0-9]{0,9}$/.test(target.searchParams.get(parameter) || ""))) return;
      jsonStarted = true;
      const abort = new AbortController();
      const timeout = setTimeout(() => abort.abort(), 25000);
      try {
        const response = await fetch(target.href, {headers: {Accept: "application/json"}, credentials: "same-origin", cache: "no-store", redirect: "error", signal: abort.signal});
        const headers = {};
        for (const key of ["content-type", "cf-mitigated", "x-app-integrity-error", "retry-after"]) {
          const value = response.headers.get(key); if (value !== null) headers[key] = value;
        }
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let bytes = 0, body = "";
        while (true) {
          const chunk = await reader.read();
          if (chunk.done) break;
          bytes += chunk.value.byteLength;
          if (bytes > 4 * 1024 * 1024) { await reader.cancel(); port.postMessage({...reply, state: "oversize"}); return; }
          body += decoder.decode(chunk.value, {stream: true});
        }
        body += decoder.decode();
        port.postMessage({...reply, state: "json-response", jsonUrl: target.href, responseUrl: response.url, status: response.status, headers, body});
      } catch (_) { port.postMessage({...reply, state: "json-network-error"}); }
      finally { clearTimeout(timeout); }
      return;
    }
    if (request.rssUrl === "https://www.hltv.org/rss/news") {
      if (jsonStarted || location.origin !== "https://www.hltv.org" || new URL(location.href).pathname !== "/rss/news") return;
      jsonStarted = true;
      // Firefox may render RSS as an XML document rather than a preformatted body.
      // Read the document the user already opened instead of making a second request.
      const xmlRoot = document.documentElement?.localName === "rss" ? document.documentElement :
        document.querySelector("#webkit-xml-viewer-source-xml > rss, #original-source > rss");
      const displayedXml = xmlRoot ? new XMLSerializer().serializeToString(xmlRoot) : document.querySelector("body > pre")?.textContent;
      if (displayedXml && /^\s*(<\?xml[^>]*>\s*)?<rss\b/.test(displayedXml)) {
        port.postMessage({...reply, state: displayedXml.length > 4 * 1024 * 1024 ? "oversize" : "document", html: displayedXml});
        return;
      }
      const abort = new AbortController();
      const timeout = setTimeout(() => abort.abort(), 25000);
      try {
        const response = await fetch(request.rssUrl, {credentials: "same-origin", redirect: "error", cache: "no-store", signal: abort.signal});
        if (response.status === 403 || response.status === 429) { port.postMessage({...reply, state: "blocked"}); return; }
        if (!response.ok) { port.postMessage({...reply, state: "json-network-error"}); return; }
        const reader = response.body.getReader(), decoder = new TextDecoder();
        let bytes = 0, html = "";
        while (true) {
          const chunk = await reader.read();
          if (chunk.done) break;
          bytes += chunk.value.byteLength;
          if (bytes > 4 * 1024 * 1024) { await reader.cancel(); port.postMessage({...reply, state: "oversize"}); return; }
          html += decoder.decode(chunk.value, {stream: true});
        }
        html += decoder.decode();
        port.postMessage({...reply, state: "document", html});
      } catch (_) { port.postMessage({...reply, state: "json-network-error"}); }
      finally { clearTimeout(timeout); }
      return;
    }
    const root = document.documentElement.cloneNode(true);
    root.querySelectorAll("script,style,iframe,form,input,textarea,noscript").forEach(n => n.remove());
    const html = root.outerHTML;
    if (html.length > 8 * 1024 * 1024) port.postMessage({...reply, state: "oversize"});
    else port.postMessage({...reply, state: "document", html});
  });
})();
