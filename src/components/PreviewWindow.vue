<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { PluginListenerHandle } from '@capacitor/core'
import VscodeIcon from './VscodeIcon.vue'
import PreviewDomTree, { type DomNode } from './PreviewDomTree.vue'
import { isAndroidWorkspace, Workspace } from '../plugins/workspace'

export type PreviewLogLevel = 'log' | 'info' | 'warn' | 'error' | 'debug'
export type DevtoolsTab = 'console' | 'elements' | 'network'

export interface PreviewLogLine {
  id: number
  level: PreviewLogLevel
  text: string
  source?: string
}

export interface PreviewNetworkLine {
  id: number
  requestId?: string
  method: string
  url: string
  status?: number
  statusText?: string
  duration?: number
  size?: number
  type?: string
  error?: string
  mimeType?: string
  requestHeaders?: Record<string, string>
  responseHeaders?: Record<string, string>
  requestBody?: string
  responseBody?: string
}

type NetworkDetailTab = 'headers' | 'payload' | 'response' | 'timing'

const props = defineProps<{
  open: boolean
  url?: string
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
}>()

const address = ref('http://127.0.0.1:8080')
const frameUrl = ref('')
const frameSrcdoc = ref('')
const error = ref('')
const revision = ref(0)
const consoleOpen = ref(false)
const activeTab = ref<DevtoolsTab>('elements')
const logs = ref<PreviewLogLine[]>([])
const network = ref<PreviewNetworkLine[]>([])
const selectedNetworkId = ref<number | null>(null)
const networkDetailTab = ref<NetworkDetailTab>('headers')
const networkResponseLoading = ref(false)
const elementTree = ref<DomNode | null>(null)
const selectedNodeId = ref('')
const picking = ref(false)
const frameEl = ref<HTMLIFrameElement | null>(null)

const errorCount = computed(() => logs.value.filter((line) => line.level === 'error').length)
const warnCount = computed(() => logs.value.filter((line) => line.level === 'warn').length)
const infoCount = computed(() => logs.value.filter((line) => line.level === 'info' || line.level === 'log').length)

const selectedNetwork = computed(() => {
  if (selectedNetworkId.value == null) return null
  return network.value.find((item) => item.id === selectedNetworkId.value) ?? null
})

const networkRequestHeaderEntries = computed(() => entriesOf(selectedNetwork.value?.requestHeaders))
const networkResponseHeaderEntries = computed(() => entriesOf(selectedNetwork.value?.responseHeaders))
const networkResponsePretty = computed(() => formatBody(selectedNetwork.value?.responseBody, selectedNetwork.value?.mimeType))
const networkPayloadPretty = computed(() => formatBody(selectedNetwork.value?.requestBody, selectedNetwork.value?.requestHeaders?.['content-type']))

const INSPECT_SCRIPT = `(function(){if(window.__grainDevtools)return;window.__grainDevtools=1;
function attrs(el){var out=[],list=el.attributes||[];for(var i=0;i<Math.min(list.length,24);i++){var a=list[i];if(!a||!a.name)continue;out.push({name:a.name,value:String(a.value||'').slice(0,160)})}return out}
function badges(el){var b=[];try{var s=window.getComputedStyle(el);if(s&&(s.display==='flex'||s.display==='inline-flex'))b.push('flex');if(s&&(s.display==='grid'||s.display==='inline-grid'))b.push('grid');if(el.scrollHeight>el.clientHeight+1)b.push('scroll')}catch(e){}return b}
function walk(el,d){if(!el||d>14)return null;if(el.nodeType===3){var t=String(el.textContent||'').replace(/\\s+/g,' ').trim();if(!t)return null;return{tag:'#text',text:t.slice(0,120)}}if(el.nodeType===8){return{tag:'#comment',text:String(el.textContent||'').replace(/\\s+/g,' ').trim().slice(0,80)}}if(el.nodeType!==1)return null;var o={tag:el.tagName.toLowerCase(),attrs:attrs(el),badges:badges(el)};var kids=[];var nodes=el.childNodes;for(var i=0;i<nodes.length&&kids.length<48;i++){var c=walk(nodes[i],d+1);if(c)kids.push(c)}if(kids.length)o.children=kids;else{var tx=String(el.textContent||'').replace(/\\s+/g,' ').trim();if(tx&&!el.childElementCount)o.text=tx.slice(0,120)}return o}
function walked(node){if(!node)return false;if(node.nodeType===3){return!!String(node.textContent||'').replace(/\\s+/g,' ').trim()}return node.nodeType===1||node.nodeType===8}
function pathOf(el){var path=[];var cur=el;while(cur&&cur!==document.documentElement&&cur.parentNode){var parent=cur.parentNode;var idx=-1,n=0;for(var i=0;i<parent.childNodes.length&&n<48;i++){var c=parent.childNodes[i];if(!walked(c))continue;if(c===cur){idx=n;break}n++}if(idx<0)break;path.unshift(idx);cur=parent.nodeType===1?parent:null}return path}
function emit(kind,payload){var msg='grain-'+kind+':'+JSON.stringify(payload);try{console.log(msg)}catch(e){}try{if(window.parent&&window.parent!==window)window.parent.postMessage({type:'grain-devtools',kind:kind,payload:payload},'*')}catch(e){}}
window.__grainDumpDom=function(){try{emit('elements',walk(document.documentElement,0))}catch(e){emit('elements',{tag:'error',error:String(e)})}};
var picking=false,overlay=null,lastEl=null,marked=null;
function ensureOverlay(){if(overlay&&overlay.isConnected)return overlay;overlay=document.createElement('div');overlay.id='__grain_pick_overlay';overlay.setAttribute('data-grain-ui','1');overlay.style.cssText='position:fixed;left:0;top:0;width:0;height:0;pointer-events:none;z-index:2147483646;border:2px solid #4c8bf5;background:rgba(76,139,245,.22);display:none;box-sizing:border-box;margin:0;padding:0';(document.documentElement||document.body).appendChild(overlay);return overlay}
function clearMark(){if(marked){try{marked.style.outline='';marked.style.outlineOffset=''}catch(e){}marked=null}}
function highlight(el){clearMark();if(!el||el.nodeType!==1||el.id==='__grain_pick_overlay'){if(overlay)overlay.style.display='none';return}lastEl=el;marked=el;try{el.style.outline='2px solid #4c8bf5';el.style.outlineOffset='1px'}catch(e){}var r=el.getBoundingClientRect();var o=ensureOverlay();o.style.display='block';o.style.left=Math.max(0,r.left)+'px';o.style.top=Math.max(0,r.top)+'px';o.style.width=Math.max(0,r.width)+'px';o.style.height=Math.max(0,r.height)+'px'}
function hit(x,y){var o=overlay;if(o)o.style.visibility='hidden';var el=document.elementFromPoint(x,y);if(o)o.style.visibility='visible';while(el&&(el.id==='__grain_pick_overlay'||el.getAttribute&&el.getAttribute('data-grain-ui'))){if(o)o.style.visibility='hidden';el=document.elementFromPoint(x,y);if(o)o.style.visibility='visible';break}while(el&&el.nodeType!==1)el=el.parentElement;return el}
function targetFrom(e){var x=e.clientX,y=e.clientY;if(typeof x!=='number'&&e.touches&&e.touches[0]){x=e.touches[0].clientX;y=e.touches[0].clientY}return hit(x,y)}
function onMove(e){if(!picking)return;var el=targetFrom(e);if(!el)return;highlight(el)}
function stopEvent(e){e.preventDefault();e.stopPropagation();if(e.stopImmediatePropagation)e.stopImmediatePropagation()}
function onDown(e){if(!picking)return;stopEvent(e);var el=targetFrom(e);if(el)highlight(el)}
function finishPick(el){if(!el||el===document.documentElement)el=document.body;if(!el)return;emit('pick',{path:pathOf(el),tag:el.tagName.toLowerCase(),attrs:attrs(el),badges:badges(el)});setPick(false)}
function onClick(e){if(!picking)return;stopEvent(e);finishPick(lastEl||targetFrom(e))}
function onKey(e){if(!picking)return;if(e.key==='Escape'||e.keyCode===27){stopEvent(e);setPick(false);emit('pick-cancel',{})}}
function setPick(on){picking=!!on;try{document.documentElement.style.cursor=picking?'crosshair':''}catch(e){}if(picking){ensureOverlay();document.addEventListener('pointermove',onMove,true);document.addEventListener('mousemove',onMove,true);document.addEventListener('touchmove',onMove,{capture:true,passive:false});document.addEventListener('pointerdown',onDown,true);document.addEventListener('touchstart',onDown,{capture:true,passive:false});document.addEventListener('click',onClick,true);document.addEventListener('keydown',onKey,true)}else{document.removeEventListener('pointermove',onMove,true);document.removeEventListener('mousemove',onMove,true);document.removeEventListener('touchmove',onMove,true);document.removeEventListener('pointerdown',onDown,true);document.removeEventListener('touchstart',onDown,true);document.removeEventListener('click',onClick,true);document.removeEventListener('keydown',onKey,true);clearMark();if(overlay)overlay.style.display='none';lastEl=null}}
window.__grainSetPick=setPick;window.__grainHighlight=highlight;window.__grainPathOf=pathOf;
window.addEventListener('message',function(ev){var d=ev.data;if(!d||d.type!=='grain-devtools')return;if(d.kind==='pick')setPick(!!d.on)});
function hdrs(h){var o={};if(!h)return o;try{if(typeof h.forEach==='function')h.forEach(function(v,k){o[String(k)]=String(v).slice(0,500)});else if(typeof h==='object'){for(var k in h)if(Object.prototype.hasOwnProperty.call(h,k))o[k]=String(h[k]).slice(0,500)}}catch(e){}return o}
function clip(s,n){s=String(s==null?'':s);return s.length>n?s.slice(0,n)+'\\n…':s}
function textish(ct){return!ct||/^(text\\/|application\\/(json|javascript|xml|x-www-form-urlencoded|.*\\+json)|image\\/svg)/i.test(ct)}
var rid=0;function net(e){emit('network',e)}
var F=window.fetch;if(F){window.fetch=function(){var a=arguments,input=a[0],init=a[1]||{},u=typeof input==='string'?input:(input&&input.url)||'',m=(init.method||(input&&input.method)||'GET'),t=Date.now(),id='f'+(++rid),reqH=hdrs(init.headers||(input&&input.headers)),reqB=init.body!=null?clip(init.body,4000):undefined;net({requestId:id,type:'fetch',method:m,url:String(u),requestHeaders:reqH,requestBody:reqB});return F.apply(this,a).then(function(r){var ct=(r.headers&&r.headers.get&&r.headers.get('content-type'))||'';var rh=hdrs(r.headers);var finish=function(body,sz){net({requestId:id,type:'fetch',method:m,url:String(u),status:r.status,statusText:r.statusText,duration:Date.now()-t,mimeType:ct,responseHeaders:rh,responseBody:body,size:sz,requestHeaders:reqH,requestBody:reqB})};if(textish(ct)){return r.clone().text().then(function(txt){finish(clip(txt,12000),txt.length);return r},function(){finish(undefined,undefined);return r})}finish(undefined,undefined);return r},function(err){net({requestId:id,type:'fetch',method:m,url:String(u),status:0,error:String(err),duration:Date.now()-t,requestHeaders:reqH,requestBody:reqB});throw err})}};
var X=window.XMLHttpRequest;if(X){var o=X.prototype.open,s=X.prototype.send,sh=X.prototype.setRequestHeader;X.prototype.open=function(m,u){this.__g={method:m,url:String(u),t:0,id:'x'+(++rid),headers:{}};return o.apply(this,arguments)};X.prototype.setRequestHeader=function(k,v){if(this.__g)this.__g.headers[k]=String(v);return sh.apply(this,arguments)};X.prototype.send=function(body){var x=this;if(x.__g){x.__g.t=Date.now();if(body!=null)x.__g.body=clip(body,4000);net({requestId:x.__g.id,type:'xhr',method:x.__g.method,url:x.__g.url,requestHeaders:x.__g.headers,requestBody:x.__g.body});x.addEventListener('loadend',function(){if(!x.__g)return;var rh={},raw='';try{raw=x.getAllResponseHeaders()||''}catch(e){}String(raw).split(/\\r?\\n/).forEach(function(line){var i=line.indexOf(':');if(i>0)rh[line.slice(0,i).trim()]=line.slice(i+1).trim().slice(0,500)});var ct='';try{ct=x.getResponseHeader('content-type')||''}catch(e){}var body;try{if(typeof x.responseText==='string'&&textish(ct))body=clip(x.responseText,12000)}catch(e){}net({requestId:x.__g.id,type:'xhr',method:x.__g.method,url:x.__g.url,status:x.status,statusText:x.statusText,duration:Date.now()-x.__g.t,mimeType:ct,responseHeaders:rh,responseBody:body,size:body?body.length:undefined,requestHeaders:x.__g.headers,requestBody:x.__g.body})})}return s.apply(this,arguments)}}
function boot(){setTimeout(window.__grainDumpDom,500);setTimeout(window.__grainDumpDom,1600)}
if(document.readyState==='complete'||document.readyState==='interactive')boot();else document.addEventListener('DOMContentLoaded',boot);
})();`.replace(/\n/g, '')

function withInspect(html: string, baseHref: string): string {
  const base = `<base href="${baseHref.replace(/"/g, '&quot;')}">`
  const script = `<script>${INSPECT_SCRIPT}</` + `script>`
  if (/<head[^>]*>/i.test(html)) return html.replace(/<head[^>]*>/i, (m) => `${m}${base}${script}`)
  if (/<html[^>]*>/i.test(html)) return html.replace(/<html[^>]*>/i, (m) => `${m}<head>${base}${script}</head>`)
  return `${base}${script}${html}`
}

function baseHrefFor(url: string): string {
  const parsed = new URL(url)
  if (!parsed.pathname.endsWith('/')) {
    parsed.pathname = parsed.pathname.replace(/[^/]*$/, '')
  }
  if (!parsed.pathname.endsWith('/')) parsed.pathname += '/'
  return parsed.href
}
const consoleHeight = ref(readNumber('grain.previewConsoleHeight', 260, 120, 560))
let logSeq = 0
let networkSeq = 0

const left = ref(readNumber('grain.previewLeft', 48, 0, 4000))
const top = ref(readNumber('grain.previewTop', 72, 0, 4000))
const width = ref(readNumber('grain.previewFloatWidth', 360, 240, 4000))
const height = ref(readNumber('grain.previewFloatHeight', 480, 220, 4000))

const root = ref<HTMLElement | null>(null)
let consoleListener: PluginListenerHandle | undefined
let networkListener: PluginListenerHandle | undefined
let drag: { kind: 'move' | 'resize' | 'console'; startX: number; startY: number; originLeft: number; originTop: number; originWidth: number; originHeight: number; originConsole: number } | null = null

const style = computed(() => ({
  left: `${left.value}px`,
  top: `${top.value}px`,
  width: `${width.value}px`,
  height: `${height.value}px`,
  '--preview-console-height': consoleOpen.value ? `${consoleHeight.value}px` : '0px',
}))

function readNumber(key: string, fallback: number, min: number, max: number): number {
  try {
    const stored = Number(localStorage.getItem(key))
    if (Number.isFinite(stored)) return Math.min(max, Math.max(min, stored))
  } catch {
    /* ignore */
  }
  return fallback
}

function remember(key: string, value: number) {
  try {
    localStorage.setItem(key, String(Math.round(value)))
  } catch {
    /* ignore */
  }
}

function normalizeLocalUrl(raw: string): string {
  const url = new URL(raw.trim())
  if (url.protocol !== 'http:') throw new Error('请输入 http://127.0.0.1:端口 形式的本机服务地址')
  if (url.hostname === 'localhost') url.hostname = '127.0.0.1'
  if (url.hostname !== '127.0.0.1' || !url.port || url.username || url.password) {
    throw new Error('请输入 http://127.0.0.1:端口 形式的本机服务地址')
  }
  url.hash = ''
  return url.href
}

async function loadPreviewHtml(href: string): Promise<{ html: string; status: number }> {
  if (isAndroidWorkspace) {
    const result = await Workspace.fetchUrl({ url: href })
    return { html: result.text || '', status: result.status || 0 }
  }
  const response = await fetch(href, { cache: 'no-store' })
  return { html: await response.text(), status: response.status }
}

async function openAddress(raw = address.value) {
  try {
    const href = normalizeLocalUrl(raw)
    address.value = href
    error.value = ''
    elementTree.value = null
    network.value = []
    selectedNodeId.value = ''
    if (picking.value) setPicking(false)
    try {
      const { html, status } = await loadPreviewHtml(href)
      if (!html && status >= 400) throw new Error(`请求失败 (${status})`)
      frameSrcdoc.value = withInspect(html, baseHrefFor(href))
      frameUrl.value = ''
      pushNetwork({ method: 'GET', url: href, status, type: 'document' })
    } catch (fetchError) {
      // 回退到 iframe src，由 WebView 注入脚本；不再把 Failed to fetch 吓到用户脸上
      frameSrcdoc.value = ''
      frameUrl.value = href
      const message = fetchError instanceof Error ? fetchError.message : String(fetchError)
      if (!/failed to fetch/i.test(message)) error.value = message
    }
    revision.value += 1
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

function refresh() {
  void openAddress(address.value || frameUrl.value)
}

function close() {
  if (picking.value) setPicking(false)
  emit('update:open', false)
}

function toggleConsole() {
  consoleOpen.value = !consoleOpen.value
  if (consoleOpen.value) {
    if (consoleHeight.value < 180) consoleHeight.value = 260
    if (!activeTab.value) activeTab.value = 'elements'
  } else if (picking.value) {
    setPicking(false)
  }
}

type FramePickApi = Window & {
  __grainSetPick?: (on: boolean) => void
  __grainHighlight?: (el: Element | null) => void
  __grainPathOf?: (el: Element) => number[]
}

let pickRetryTimer = 0
let lastPickEl: Element | null = null

function frameWindow(): FramePickApi | null {
  try {
    return (frameEl.value?.contentWindow as FramePickApi | null) ?? null
  } catch {
    return null
  }
}

function frameDocument(): Document | null {
  try {
    return frameEl.value?.contentDocument ?? null
  } catch {
    return null
  }
}

/** srcdoc 同源可用蒙层；跨域 frameUrl 必须让事件进 iframe */
const pickUsesMask = computed(() => picking.value && Boolean(frameSrcdoc.value))

function postToFrame(message: Record<string, unknown>) {
  try {
    frameEl.value?.contentWindow?.postMessage({ type: 'grain-devtools', ...message }, '*')
  } catch {
    /* ignore */
  }
}

function enablePickInFrame(on: boolean): boolean {
  const win = frameWindow()
  if (win) {
    try {
      if (typeof win.__grainSetPick === 'function') {
        win.__grainSetPick(on)
        return true
      }
    } catch {
      /* 跨域读属性会抛错，改走 postMessage */
    }
  }
  postToFrame({ kind: 'pick', on })
  return false
}

function setPicking(on: boolean) {
  picking.value = on
  lastPickEl = null
  if (pickRetryTimer) {
    window.clearInterval(pickRetryTimer)
    pickRetryTimer = 0
  }
  const ok = enablePickInFrame(on)
  if (on && !ok) {
    let attempts = 0
    pickRetryTimer = window.setInterval(() => {
      attempts += 1
      if (enablePickInFrame(true) || attempts >= 30 || !picking.value) {
        window.clearInterval(pickRetryTimer)
        pickRetryTimer = 0
      }
    }, 80)
  }
  if (on) {
    consoleOpen.value = true
    activeTab.value = 'elements'
    if (consoleHeight.value < 180) consoleHeight.value = 260
  }
}

function togglePick() {
  setPicking(!picking.value)
}

function onFrameLoad() {
  if (picking.value) enablePickInFrame(true)
}

function pickCoords(event: PointerEvent | TouchEvent | MouseEvent): { x: number; y: number } | null {
  const frame = frameEl.value
  if (!frame) return null
  const rect = frame.getBoundingClientRect()
  let clientX = 0
  let clientY = 0
  if ('clientX' in event && typeof event.clientX === 'number') {
    clientX = event.clientX
    clientY = event.clientY
  } else if ('touches' in event && event.touches[0]) {
    clientX = event.touches[0].clientX
    clientY = event.touches[0].clientY
  } else {
    return null
  }
  const x = clientX - rect.left
  const y = clientY - rect.top
  if (x < 0 || y < 0 || x > rect.width || y > rect.height) return null
  return { x, y }
}

function elementAtPickPoint(x: number, y: number): Element | null {
  const doc = frameDocument()
  if (!doc) return null
  const overlay = doc.getElementById('__grain_pick_overlay')
  if (overlay) overlay.style.visibility = 'hidden'
  let el = doc.elementFromPoint(x, y)
  if (overlay) overlay.style.visibility = 'visible'
  while (el && (el.id === '__grain_pick_overlay' || el.getAttribute?.('data-grain-ui'))) {
    if (overlay) overlay.style.visibility = 'hidden'
    el = doc.elementFromPoint(x, y)
    if (overlay) overlay.style.visibility = 'visible'
    break
  }
  while (el && el.nodeType !== 1) el = el.parentElement
  return el
}

function onPickMaskMove(event: PointerEvent) {
  if (!pickUsesMask.value) return
  const point = pickCoords(event)
  if (!point) return
  const el = elementAtPickPoint(point.x, point.y)
  if (!el) return
  lastPickEl = el
  try {
    frameWindow()?.__grainHighlight?.(el)
  } catch {
    enablePickInFrame(true)
  }
}

function onPickMaskClick(event: PointerEvent) {
  if (!pickUsesMask.value) return
  event.preventDefault()
  event.stopPropagation()
  const point = pickCoords(event)
  const el = (point && elementAtPickPoint(point.x, point.y)) || lastPickEl
  if (!el) return
  let path: number[] = []
  try {
    path = frameWindow()?.__grainPathOf?.(el) ?? []
  } catch {
    path = []
  }
  applyPick({ path })
  try {
    frameWindow()?.__grainSetPick?.(false)
  } catch {
    postToFrame({ kind: 'pick', on: false })
  }
}

function applyPick(payload: { path?: number[] }) {
  const path = Array.isArray(payload?.path) ? payload.path.filter((n) => Number.isInteger(n) && n >= 0) : []
  selectedNodeId.value = path.length ? `root.${path.join('.')}` : 'root'
  picking.value = false
  consoleOpen.value = true
  activeTab.value = 'elements'
  void nextTick(() => {
    const node = root.value?.querySelector('.dt-node.on .dt-line') as HTMLElement | null
    node?.scrollIntoView({ block: 'nearest' })
  })
}

function clearActivePanel() {
  if (activeTab.value === 'console') logs.value = []
  else if (activeTab.value === 'network') {
    network.value = []
    selectedNetworkId.value = null
  } else {
    elementTree.value = null
    selectedNodeId.value = ''
  }
}

function entriesOf(map?: Record<string, string>): Array<{ name: string; value: string }> {
  if (!map) return []
  return Object.keys(map).sort().map((name) => ({ name, value: map[name] }))
}

function formatBody(raw?: string, mime?: string): string {
  if (raw == null || raw === '') return ''
  const looksJson = /json/i.test(mime || '') || /^\s*[\[{]/.test(raw)
  if (looksJson) {
    try {
      return JSON.stringify(JSON.parse(raw), null, 2)
    } catch {
      /* keep raw */
    }
  }
  return raw
}

function mergeNetworkFields(target: PreviewNetworkLine, entry: Omit<PreviewNetworkLine, 'id'>) {
  if (entry.method) target.method = entry.method
  if (entry.url) target.url = entry.url
  if (entry.status != null) target.status = entry.status
  if (entry.statusText) target.statusText = entry.statusText
  if (entry.duration != null) target.duration = entry.duration
  if (entry.size != null) target.size = entry.size
  if (entry.type) target.type = entry.type
  if (entry.error) target.error = entry.error
  if (entry.mimeType) target.mimeType = entry.mimeType
  if (entry.requestHeaders) target.requestHeaders = { ...target.requestHeaders, ...entry.requestHeaders }
  if (entry.responseHeaders) target.responseHeaders = { ...target.responseHeaders, ...entry.responseHeaders }
  if (entry.requestBody != null) target.requestBody = entry.requestBody
  if (entry.responseBody != null) target.responseBody = entry.responseBody
  if (entry.requestId) target.requestId = entry.requestId
}

function pushLog(level: PreviewLogLevel, text: string, source?: string) {
  logs.value.push({ id: ++logSeq, level, text, source })
  if (logs.value.length > 500) logs.value.splice(0, logs.value.length - 500)
}

function pushNetwork(entry: Omit<PreviewNetworkLine, 'id'>) {
  if (entry.requestId) {
    const byId = network.value.find((item) => item.requestId === entry.requestId)
    if (byId) {
      mergeNetworkFields(byId, entry)
      return
    }
  }
  const key = `${entry.method}|${entry.url}`
  const existing = [...network.value].reverse().find((item) => `${item.method}|${item.url}` === key && item.status == null)
  if (existing && (entry.status != null || entry.responseBody != null || entry.error)) {
    mergeNetworkFields(existing, entry)
    return
  }
  network.value.push({ id: ++networkSeq, ...entry })
  if (network.value.length > 300) network.value.splice(0, network.value.length - 300)
}

function selectNetwork(item: PreviewNetworkLine) {
  selectedNetworkId.value = item.id
  networkDetailTab.value = 'headers'
  if (!item.responseBody && item.method === 'GET' && item.url && !item.error) {
    void loadNetworkResponse(item)
  }
}

function closeNetworkDetail() {
  selectedNetworkId.value = null
}

async function loadNetworkResponse(item: PreviewNetworkLine) {
  if (networkResponseLoading.value) return
  networkResponseLoading.value = true
  try {
    const result = await Workspace.fetchUrl({ url: item.url })
    const found = network.value.find((row) => row.id === item.id)
    if (!found) return
    if (found.status == null && result.status != null) found.status = result.status
    if (result.contentType) found.mimeType = result.contentType
    if (result.text != null) {
      found.responseBody = result.text.length > 12000 ? `${result.text.slice(0, 12000)}\n…` : result.text
      found.size = result.text.length
    }
  } catch {
    /* 无法补拉响应时忽略 */
  } finally {
    networkResponseLoading.value = false
  }
}

function isPreviewSource(source?: string): boolean {
  if (!source) return false
  return /https?:\/\/(127\.0\.0\.1|localhost):\d+/i.test(source)
    || /about:srcdoc/i.test(source)
    || source === 'about:blank'
}

function applyElements(payload: unknown) {
  if (payload && typeof payload === 'object') {
    elementTree.value = payload as DomNode
    if (!selectedNodeId.value) selectedNodeId.value = 'root'
  }
}

function applyNetworkPayload(payload: Partial<PreviewNetworkLine>) {
  pushNetwork({
    requestId: payload.requestId,
    method: payload.method || 'GET',
    url: payload.url || '',
    status: payload.status,
    statusText: payload.statusText,
    duration: payload.duration,
    size: payload.size,
    type: payload.type,
    error: payload.error,
    mimeType: payload.mimeType,
    requestHeaders: payload.requestHeaders,
    responseHeaders: payload.responseHeaders,
    requestBody: payload.requestBody,
    responseBody: payload.responseBody,
  })
}

function handleConsoleText(text: string, level: PreviewLogLevel, source?: string) {
  if (text.startsWith('grain-elements:')) {
    try {
      applyElements(JSON.parse(text.slice('grain-elements:'.length)))
    } catch {
      elementTree.value = { tag: 'error', error: text.slice('grain-elements:'.length) }
    }
    return
  }
  if (text.startsWith('grain-network:')) {
    try {
      applyNetworkPayload(JSON.parse(text.slice('grain-network:'.length)) as PreviewNetworkLine)
    } catch {
      /* ignore */
    }
    return
  }
  if (text.startsWith('grain-pick:')) {
    try {
      applyPick(JSON.parse(text.slice('grain-pick:'.length)) as { path?: number[] })
    } catch {
      /* ignore */
    }
    return
  }
  if (text.startsWith('grain-pick-cancel:')) {
    picking.value = false
    return
  }
  pushLog(level, text, source)
}

function onDevtoolsMessage(event: MessageEvent) {
  if (!props.open) return
  const data = event.data
  if (!data || data.type !== 'grain-devtools') return
  if (data.kind === 'elements') applyElements(data.payload)
  else if (data.kind === 'network' && data.payload) applyNetworkPayload(data.payload as PreviewNetworkLine)
  else if (data.kind === 'pick' && data.payload) applyPick(data.payload as { path?: number[] })
  else if (data.kind === 'pick-cancel') picking.value = false
}

function clampGeometry() {
  const maxW = Math.max(240, window.innerWidth - 16)
  const maxH = Math.max(220, window.innerHeight - 16)
  width.value = Math.min(maxW, Math.max(240, width.value))
  height.value = Math.min(maxH, Math.max(220, height.value))
  left.value = Math.min(window.innerWidth - 48, Math.max(8 - width.value + 48, left.value))
  top.value = Math.min(window.innerHeight - 48, Math.max(8, top.value))
}

function stopDragListeners() {
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('pointercancel', onPointerUp)
}

function beginDrag(kind: 'move' | 'resize' | 'console', event: PointerEvent) {
  drag = {
    kind,
    startX: event.clientX,
    startY: event.clientY,
    originLeft: left.value,
    originTop: top.value,
    originWidth: width.value,
    originHeight: height.value,
    originConsole: consoleHeight.value,
  }
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('pointercancel', onPointerUp)
}

function onMoveDown(event: PointerEvent) {
  if (event.button !== 0) return
  const target = event.target as HTMLElement | null
  if (target?.closest('input, button:not(.preview-float-drag), a, form')) return
  event.preventDefault()
  beginDrag('move', event)
}

function onDragHandleDown(event: PointerEvent) {
  if (event.button !== 0) return
  event.preventDefault()
  event.stopPropagation()
  try {
    ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  } catch {
    /* 部分 WebView 可能拒绝捕获 */
  }
  beginDrag('move', event)
}

function onResizeDown(event: PointerEvent) {
  if (event.button !== 0) return
  event.preventDefault()
  event.stopPropagation()
  beginDrag('resize', event)
}

function onConsoleResizeDown(event: PointerEvent) {
  if (event.button !== 0) return
  event.preventDefault()
  event.stopPropagation()
  beginDrag('console', event)
}

function onPointerMove(event: PointerEvent) {
  if (!drag) return
  const dx = event.clientX - drag.startX
  const dy = event.clientY - drag.startY
  if (drag.kind === 'move') {
    left.value = drag.originLeft + dx
    top.value = drag.originTop + dy
    clampGeometry()
  } else if (drag.kind === 'resize') {
    width.value = drag.originWidth + dx
    height.value = drag.originHeight + dy
    clampGeometry()
  } else {
    consoleHeight.value = Math.min(480, Math.max(100, drag.originConsole - dy))
  }
}

function onPointerUp() {
  if (!drag) return
  const kind = drag.kind
  drag = null
  stopDragListeners()
  remember('grain.previewLeft', left.value)
  remember('grain.previewTop', top.value)
  remember('grain.previewFloatWidth', width.value)
  remember('grain.previewFloatHeight', height.value)
  if (kind === 'console') remember('grain.previewConsoleHeight', consoleHeight.value)
}

watch(
  () => props.url,
  (value) => {
    if (!value) return
    void openAddress(value)
  },
)

watch(
  () => props.open,
  (value) => {
    if (!value) return
    clampGeometry()
    if (props.url) void openAddress(props.url)
    void nextTick(() => root.value?.focus())
  },
)

watch(activeTab, (tab) => {
  if (!props.open || !consoleOpen.value) return
  if ((tab === 'elements' && !elementTree.value) || (tab === 'network' && network.value.length === 0)) {
    if (address.value || frameUrl.value) void openAddress(address.value || frameUrl.value)
  }
})

onMounted(async () => {
  clampGeometry()
  window.addEventListener('message', onDevtoolsMessage)
  if (props.url) void openAddress(props.url)
  if (!isAndroidWorkspace) return
  try {
    consoleListener = await Workspace.addListener('webConsole', (event) => {
      if (!props.open) return
      const text = event.text || ''
      const special = text.startsWith('grain-elements:')
        || text.startsWith('grain-network:')
        || text.startsWith('grain-pick:')
        || text.startsWith('grain-pick-cancel:')
      if (!special && !isPreviewSource(event.source)) return
      const raw = (event.level || 'LOG').toUpperCase()
      const level: PreviewLogLevel =
        raw === 'ERROR' ? 'error'
          : raw === 'WARNING' ? 'warn'
            : raw === 'TIP' || raw === 'DEBUG' ? 'debug'
              : raw === 'LOG' ? 'log'
                : 'info'
      handleConsoleText(text, level, event.source)
    })
    networkListener = await Workspace.addListener('webNetwork', (event) => {
      if (!props.open) return
      pushNetwork({
        method: event.method || 'GET',
        url: event.url || '',
        status: event.status,
        size: event.size,
        duration: event.duration,
        type: event.type || 'resource',
        error: event.error,
      })
    })
  } catch {
    /* 旧包没有事件时忽略 */
  }
})

onBeforeUnmount(() => {
  if (pickRetryTimer) window.clearInterval(pickRetryTimer)
  if (picking.value) setPicking(false)
  stopDragListeners()
  window.removeEventListener('message', onDevtoolsMessage)
  void consoleListener?.remove()
  void networkListener?.remove()
})

defineExpose({ openAddress, refresh })
</script>

<template>
  <div
    v-if="open"
    ref="root"
    class="preview-float"
    :style="style"
    tabindex="-1"
    role="dialog"
    aria-label="页面预览"
  >
    <header class="preview-float-bar" @pointerdown="onMoveDown">
      <button
        type="button"
        class="preview-float-btn preview-float-drag"
        title="按住拖动窗口"
        aria-label="按住拖动窗口"
        @pointerdown="onDragHandleDown"
      >
        <VscodeIcon name="gripper" :size="16" />
      </button>
      <form class="preview-float-address" @submit.prevent="openAddress()">
        <input v-model="address" type="url" aria-label="本机服务地址" autocapitalize="off" spellcheck="false" />
      </form>
      <button type="button" class="preview-float-btn" title="访问" aria-label="访问" @click="openAddress()">
        <VscodeIcon name="link-external" :size="14" />
      </button>
      <button type="button" class="preview-float-btn" title="刷新" aria-label="刷新" @click="refresh">
        <VscodeIcon name="refresh" :size="14" />
      </button>
      <button type="button" class="preview-float-btn" :class="{ on: consoleOpen }" title="开发者工具" aria-label="开发者工具" @click="toggleConsole">
        <VscodeIcon name="debug-console" :size="14" />
      </button>
      <button type="button" class="preview-float-btn" title="关闭" aria-label="关闭" @click="close">
        <VscodeIcon name="close" :size="14" />
      </button>
    </header>
    <p v-if="error" class="preview-float-error" role="alert">{{ error }}</p>
    <div class="preview-float-body">
      <div class="preview-stage preview-float-stage" :class="{ 'pick-masked': pickUsesMask }">
        <iframe
          v-if="frameSrcdoc"
          ref="frameEl"
          :key="`doc-${revision}`"
          class="preview-frame"
          :class="{ picking: picking }"
          :srcdoc="frameSrcdoc"
          sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-modals"
          title="本机服务预览"
          @load="onFrameLoad"
        ></iframe>
        <iframe
          v-else-if="frameUrl"
          ref="frameEl"
          :key="`url-${revision}`"
          class="preview-frame"
          :class="{ picking: picking }"
          :src="frameUrl"
          sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-modals"
          title="本机服务预览"
          @load="onFrameLoad"
        ></iframe>
        <div v-else class="preview-empty">
          <p>先在终端启动项目服务</p>
          <p class="preview-hint">输入地址后点访问，或点击终端里的链接</p>
        </div>
        <div
          v-if="pickUsesMask"
          class="preview-pick-mask"
          aria-hidden="true"
          @pointermove="onPickMaskMove"
          @pointerdown.prevent="onPickMaskMove"
          @click="onPickMaskClick"
        ></div>
      </div>
      <div v-if="consoleOpen" class="preview-console dt-panel">
        <button type="button" class="preview-console-sash" aria-label="调整开发者工具高度" @pointerdown="onConsoleResizeDown"></button>
        <header class="dt-toolbar">
          <button
            type="button"
            class="preview-float-btn dt-inspect"
            :class="{ on: picking }"
            title="选择页面中的元素进行检查"
            aria-label="选择页面中的元素进行检查"
            :aria-pressed="picking"
            @click="togglePick"
          >
            <VscodeIcon name="inspect" :size="14" />
          </button>
          <div class="dt-tabs" role="tablist" aria-label="开发者工具">
            <button type="button" role="tab" :aria-selected="activeTab === 'elements'" :class="{ on: activeTab === 'elements' }" @click="activeTab = 'elements'">元素</button>
            <button type="button" role="tab" :aria-selected="activeTab === 'console'" :class="{ on: activeTab === 'console' }" @click="activeTab = 'console'">控制台</button>
            <button type="button" role="tab" :aria-selected="activeTab === 'network'" :class="{ on: activeTab === 'network' }" @click="activeTab = 'network'">网络</button>
          </div>
          <div class="dt-counts" aria-hidden="true">
            <span v-if="errorCount" class="dt-count error">{{ errorCount }}</span>
            <span v-if="warnCount" class="dt-count warn">{{ warnCount }}</span>
            <span v-if="infoCount" class="dt-count info">{{ infoCount }}</span>
          </div>
          <button type="button" class="preview-float-btn" title="清空" aria-label="清空" @click="clearActivePanel">
            <VscodeIcon name="clear-all" :size="14" />
          </button>
        </header>

        <div v-show="activeTab === 'elements'" class="dt-body preview-elements">
          <p v-if="picking" class="preview-console-empty dt-pick-hint">在上方预览中点击要检查的元素，Esc 取消。</p>
          <p v-else-if="!elementTree" class="preview-console-empty">正在读取 DOM… 若一直空白请点上方刷新。</p>
          <p v-else-if="elementTree.error" class="preview-console-empty">{{ elementTree.error }}</p>
          <PreviewDomTree
            v-if="elementTree && !elementTree.error"
            :node="elementTree"
            node-id="root"
            :selected-id="selectedNodeId"
            @select="selectedNodeId = $event"
          />
        </div>

        <div v-show="activeTab === 'console'" class="dt-body preview-console-log" role="log" aria-live="polite">
          <p v-if="!logs.length" class="preview-console-empty">暂无输出。页面里的 console 会显示在这里。</p>
          <div v-for="line in logs" :key="line.id" class="preview-console-line" :data-level="line.level">
            <span class="preview-console-level">{{ line.level }}</span>
            <span class="preview-console-text">{{ line.text }}</span>
          </div>
        </div>

        <div v-show="activeTab === 'network'" class="dt-body preview-network" :class="{ 'has-detail': !!selectedNetwork }">
          <div class="preview-network-list">
            <div class="dt-network-head">
              <span>状态</span><span>方法</span><span>名称</span><span>耗时</span>
            </div>
            <p v-if="!network.length" class="preview-console-empty">暂无请求。刷新预览或操作页面后会出现。</p>
            <button
              v-for="item in network"
              :key="item.id"
              type="button"
              class="preview-network-row"
              :class="{ on: selectedNetworkId === item.id }"
              @click="selectNetwork(item)"
            >
              <span class="preview-network-status" :data-ok="item.status != null && item.status > 0 && item.status < 400">{{ item.status ?? '…' }}</span>
              <span class="preview-network-method">{{ item.method }}</span>
              <span class="preview-network-url" :title="item.url">{{ item.url }}</span>
              <span class="preview-network-meta">{{ item.duration != null ? `${item.duration}ms` : '' }}</span>
            </button>
          </div>

          <aside v-if="selectedNetwork" class="preview-network-detail">
            <header class="preview-network-detail-tabs">
              <button type="button" class="preview-network-detail-close" aria-label="关闭详情" @click="closeNetworkDetail">×</button>
              <button type="button" :class="{ on: networkDetailTab === 'headers' }" @click="networkDetailTab = 'headers'">标头</button>
              <button type="button" :class="{ on: networkDetailTab === 'payload' }" @click="networkDetailTab = 'payload'">载荷</button>
              <button type="button" :class="{ on: networkDetailTab === 'response' }" @click="networkDetailTab = 'response'">响应</button>
              <button type="button" :class="{ on: networkDetailTab === 'timing' }" @click="networkDetailTab = 'timing'">时间</button>
            </header>

            <div v-show="networkDetailTab === 'headers'" class="preview-network-detail-body">
              <section class="preview-network-section">
                <h4>常规</h4>
                <div class="preview-network-kv"><span>请求网址</span><code>{{ selectedNetwork.url }}</code></div>
                <div class="preview-network-kv"><span>请求方法</span><code>{{ selectedNetwork.method }}</code></div>
                <div class="preview-network-kv">
                  <span>状态代码</span>
                  <code>
                    <span
                      class="preview-network-dot"
                      :data-ok="selectedNetwork.status != null && selectedNetwork.status > 0 && selectedNetwork.status < 400"
                    />
                    {{ selectedNetwork.status != null ? selectedNetwork.status : '…' }}{{ selectedNetwork.statusText ? ` ${selectedNetwork.statusText}` : '' }}
                  </code>
                </div>
                <div v-if="selectedNetwork.type" class="preview-network-kv"><span>资源类型</span><code>{{ selectedNetwork.type }}</code></div>
                <div v-if="selectedNetwork.mimeType" class="preview-network-kv"><span>内容类型</span><code>{{ selectedNetwork.mimeType }}</code></div>
                <div v-if="selectedNetwork.size != null" class="preview-network-kv"><span>大小</span><code>{{ selectedNetwork.size }} B</code></div>
                <div v-if="selectedNetwork.error" class="preview-network-kv"><span>错误</span><code class="is-error">{{ selectedNetwork.error }}</code></div>
              </section>
              <section v-if="networkResponseHeaderEntries.length" class="preview-network-section">
                <h4>响应标头</h4>
                <div v-for="row in networkResponseHeaderEntries" :key="'rh-' + row.name" class="preview-network-kv">
                  <span>{{ row.name }}</span><code>{{ row.value }}</code>
                </div>
              </section>
              <section v-if="networkRequestHeaderEntries.length" class="preview-network-section">
                <h4>请求标头</h4>
                <div v-for="row in networkRequestHeaderEntries" :key="'qh-' + row.name" class="preview-network-kv">
                  <span>{{ row.name }}</span><code>{{ row.value }}</code>
                </div>
              </section>
              <p v-if="!networkResponseHeaderEntries.length && !networkRequestHeaderEntries.length" class="preview-console-empty">暂无标头详情。页面内的 fetch / XHR 会附带更多信息。</p>
            </div>

            <div v-show="networkDetailTab === 'payload'" class="preview-network-detail-body">
              <pre v-if="networkPayloadPretty" class="preview-network-pre">{{ networkPayloadPretty }}</pre>
              <p v-else class="preview-console-empty">此请求没有载荷。</p>
            </div>

            <div v-show="networkDetailTab === 'response'" class="preview-network-detail-body">
              <p v-if="networkResponseLoading" class="preview-console-empty">正在加载响应…</p>
              <pre v-else-if="networkResponsePretty" class="preview-network-pre">{{ networkResponsePretty }}</pre>
              <p v-else class="preview-console-empty">暂无响应正文（二进制资源不会展示）。</p>
            </div>

            <div v-show="networkDetailTab === 'timing'" class="preview-network-detail-body">
              <section class="preview-network-section">
                <div class="preview-network-kv"><span>耗时</span><code>{{ selectedNetwork.duration != null ? `${selectedNetwork.duration} ms` : '—' }}</code></div>
              </section>
            </div>
          </aside>
        </div>
      </div>
    </div>
    <button type="button" class="preview-float-resize" aria-label="调整预览大小" @pointerdown="onResizeDown"></button>
  </div>
</template>
