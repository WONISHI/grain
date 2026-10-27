export type SandboxLevel = 'log' | 'info' | 'warn' | 'error'

export interface SandboxEvent {
  runId: number
  kind: 'log' | 'error' | 'rejection' | 'dom' | 'done'
  level?: SandboxLevel
  text?: string
  empty?: boolean
}

const harness = `<script>
(function () {
  var runId = __RUN_ID__;
  function send(payload) {
    parent.postMessage(Object.assign({ source: 'grain-sandbox', runId: runId }, payload), '*');
  }
  function formatOne(value) {
    if (value instanceof Error) return value.stack || value.message;
    if (typeof value === 'string') return value;
    if (typeof value === 'undefined') return 'undefined';
    try { return JSON.stringify(value); }
    catch (error) { return String(value); }
  }
  function format(args) {
    return Array.prototype.map.call(args, formatOne).join(' ');
  }
  ['log', 'info', 'warn', 'error'].forEach(function (level) {
    var original = console[level].bind(console);
    console[level] = function () {
      original.apply(console, arguments);
      send({ kind: 'log', level: level, text: format(arguments) });
    };
  });
  window.addEventListener('error', function (event) {
    var err = event.error;
    var text = err instanceof Error ? (err.stack || err.message) : String(event.message || '未知错误');
    send({ kind: 'error', text: text });
  });
  window.addEventListener('unhandledrejection', function (event) {
    var reason = event.reason;
    var text = reason instanceof Error ? (reason.stack || reason.message) : formatOne(reason);
    send({ kind: 'rejection', text: text });
  });
  function isEmpty() {
    var nodes = document.body.childNodes;
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      if (node.nodeType === Node.TEXT_NODE && node.textContent && node.textContent.trim()) return false;
      if (node.nodeType === Node.ELEMENT_NODE && node.tagName !== 'SCRIPT') return false;
    }
    return true;
  }
  var domTimer = 0;
  function reportDom() {
    clearTimeout(domTimer);
    domTimer = setTimeout(function () {
      send({ kind: 'dom', empty: isEmpty() });
    }, 30);
  }
  new MutationObserver(reportDom).observe(document.body, { childList: true, subtree: true, characterData: true });
  window.addEventListener('load', function () {
    reportDom();
    send({ kind: 'done' });
  });
})();
</script>`

export function buildSrcdoc(code: string, runId: number): string {
  const escaped = code.replace(/<\/script/gi, '<\\/script')
  const bridge = harness.replace('__RUN_ID__', String(runId))
  return `<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8" />
<style>
  html, body { margin: 0; min-height: 100%; background: #0b0d10; color: #f4f1ec; }
</style>
</head>
<body>
${bridge}
<script type="module">
${escaped}
</script>
</body>
</html>`
}

export function readSandboxEvent(data: unknown): SandboxEvent | null {
  if (!data || typeof data !== 'object') return null
  const record = data as Record<string, unknown>
  if (record.source !== 'grain-sandbox') return null
  if (typeof record.runId !== 'number') return null
  if (
    record.kind !== 'log' &&
    record.kind !== 'error' &&
    record.kind !== 'rejection' &&
    record.kind !== 'dom' &&
    record.kind !== 'done'
  ) {
    return null
  }
  const level = record.level
  return {
    runId: record.runId,
    kind: record.kind,
    level: level === 'log' || level === 'info' || level === 'warn' || level === 'error' ? level : undefined,
    text: typeof record.text === 'string' ? record.text : undefined,
    empty: typeof record.empty === 'boolean' ? record.empty : undefined,
  }
}
