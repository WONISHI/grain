package app.grain.dev;

import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebViewClient;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginHandle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class GrainWebViewClient extends BridgeWebViewClient {
    private static final String TAG = "GrainWebView";
    private static final Pattern HEAD_CLOSE = Pattern.compile("(?i)</head>");
    private static final Pattern BODY_CLOSE = Pattern.compile("(?i)</body>");
    private static final String INSPECT_SCRIPT = "(function(){if(window.__grainDevtools)return;window.__grainDevtools=1;"
        + "function attrs(el){var out=[],list=el.attributes||[];for(var i=0;i<Math.min(list.length,24);i++){var a=list[i];"
        + "if(!a||!a.name)continue;out.push({name:a.name,value:String(a.value||'').slice(0,160)})}return out}"
        + "function badges(el){var b=[];try{var s=window.getComputedStyle(el);"
        + "if(s&&(s.display==='flex'||s.display==='inline-flex'))b.push('flex');"
        + "if(s&&(s.display==='grid'||s.display==='inline-grid'))b.push('grid');"
        + "if(el.scrollHeight>el.clientHeight+1)b.push('scroll')}catch(e){}return b}"
        + "function walk(el,d){if(!el||d>14)return null;"
        + "if(el.nodeType===3){var t=String(el.textContent||'').replace(/\\s+/g,' ').trim();if(!t)return null;return{tag:'#text',text:t.slice(0,120)}}"
        + "if(el.nodeType===8){return{tag:'#comment',text:String(el.textContent||'').replace(/\\s+/g,' ').trim().slice(0,80)}}"
        + "if(el.nodeType!==1)return null;var o={tag:el.tagName.toLowerCase(),attrs:attrs(el),badges:badges(el)};var kids=[];"
        + "var nodes=el.childNodes;for(var i=0;i<nodes.length&&kids.length<48;i++){var c=walk(nodes[i],d+1);if(c)kids.push(c)}"
        + "if(kids.length)o.children=kids;else{var tx=String(el.textContent||'').replace(/\\s+/g,' ').trim();"
        + "if(tx&&!el.childElementCount)o.text=tx.slice(0,120)}return o}"
        + "function walked(node){if(!node)return false;if(node.nodeType===3){return!!String(node.textContent||'').replace(/\\s+/g,' ').trim()}"
        + "return node.nodeType===1||node.nodeType===8}"
        + "function pathOf(el){var path=[];var cur=el;while(cur&&cur!==document.documentElement&&cur.parentNode){"
        + "var parent=cur.parentNode;var idx=-1,n=0;for(var i=0;i<parent.childNodes.length&&n<48;i++){"
        + "var c=parent.childNodes[i];if(!walked(c))continue;if(c===cur){idx=n;break}n++}if(idx<0)break;"
        + "path.unshift(idx);cur=parent.nodeType===1?parent:null}return path}"
        + "function emit(kind,payload){var msg='grain-'+kind+':'+JSON.stringify(payload);try{console.log(msg)}catch(e){}"
        + "try{if(window.parent&&window.parent!==window)window.parent.postMessage({type:'grain-devtools',kind:kind,payload:payload},'*')}catch(e){}}"
        + "window.__grainDumpDom=function(){try{emit('elements',walk(document.documentElement,0))}"
        + "catch(e){emit('elements',{tag:'error',error:String(e)})}};"
        + "var picking=false,overlay=null,lastEl=null,marked=null;"
        + "function ensureOverlay(){if(overlay&&overlay.isConnected)return overlay;overlay=document.createElement('div');"
        + "overlay.id='__grain_pick_overlay';overlay.setAttribute('data-grain-ui','1');"
        + "overlay.style.cssText='position:fixed;left:0;top:0;width:0;height:0;pointer-events:none;z-index:2147483646;"
        + "border:2px solid #4c8bf5;background:rgba(76,139,245,.22);display:none;box-sizing:border-box;margin:0;padding:0';"
        + "(document.documentElement||document.body).appendChild(overlay);return overlay}"
        + "function clearMark(){if(marked){try{marked.style.outline='';marked.style.outlineOffset=''}catch(e){}marked=null}}"
        + "function highlight(el){clearMark();if(!el||el.nodeType!==1||el.id==='__grain_pick_overlay'){if(overlay)overlay.style.display='none';return}"
        + "lastEl=el;marked=el;try{el.style.outline='2px solid #4c8bf5';el.style.outlineOffset='1px'}catch(e){}"
        + "var r=el.getBoundingClientRect();var o=ensureOverlay();o.style.display='block';"
        + "o.style.left=Math.max(0,r.left)+'px';o.style.top=Math.max(0,r.top)+'px';"
        + "o.style.width=Math.max(0,r.width)+'px';o.style.height=Math.max(0,r.height)+'px'}"
        + "function hit(x,y){var o=overlay;if(o)o.style.visibility='hidden';var el=document.elementFromPoint(x,y);"
        + "if(o)o.style.visibility='visible';while(el&&el.nodeType!==1)el=el.parentElement;return el}"
        + "function targetFrom(e){var x=e.clientX,y=e.clientY;if(typeof x!=='number'&&e.touches&&e.touches[0]){"
        + "x=e.touches[0].clientX;y=e.touches[0].clientY}return hit(x,y)}"
        + "function onMove(e){if(!picking)return;var el=targetFrom(e);if(!el)return;highlight(el)}"
        + "function stopEvent(e){e.preventDefault();e.stopPropagation();if(e.stopImmediatePropagation)e.stopImmediatePropagation()}"
        + "function onDown(e){if(!picking)return;stopEvent(e);var el=targetFrom(e);if(el)highlight(el)}"
        + "function finishPick(el){if(!el||el===document.documentElement)el=document.body;if(!el)return;"
        + "emit('pick',{path:pathOf(el),tag:el.tagName.toLowerCase(),attrs:attrs(el),badges:badges(el)});setPick(false)}"
        + "function onClick(e){if(!picking)return;stopEvent(e);finishPick(lastEl||targetFrom(e))}"
        + "function onKey(e){if(!picking)return;if(e.key==='Escape'||e.keyCode===27){stopEvent(e);setPick(false);emit('pick-cancel',{})}}"
        + "function setPick(on){picking=!!on;try{document.documentElement.style.cursor=picking?'crosshair':''}catch(e){}"
        + "if(picking){ensureOverlay();document.addEventListener('pointermove',onMove,true);"
        + "document.addEventListener('mousemove',onMove,true);document.addEventListener('touchmove',onMove,{capture:true,passive:false});"
        + "document.addEventListener('pointerdown',onDown,true);document.addEventListener('touchstart',onDown,{capture:true,passive:false});"
        + "document.addEventListener('click',onClick,true);document.addEventListener('keydown',onKey,true)}else{"
        + "document.removeEventListener('pointermove',onMove,true);document.removeEventListener('mousemove',onMove,true);"
        + "document.removeEventListener('touchmove',onMove,true);document.removeEventListener('pointerdown',onDown,true);"
        + "document.removeEventListener('touchstart',onDown,true);document.removeEventListener('click',onClick,true);"
        + "document.removeEventListener('keydown',onKey,true);clearMark();if(overlay)overlay.style.display='none';lastEl=null}}"
        + "window.__grainSetPick=setPick;window.__grainHighlight=highlight;window.__grainPathOf=pathOf;"
        + "window.addEventListener('message',function(ev){var d=ev.data;if(!d||d.type!=='grain-devtools')return;"
        + "if(d.kind==='pick')setPick(!!d.on)});"
        + "function hdrs(h){var o={};if(!h)return o;try{if(typeof h.forEach==='function')h.forEach(function(v,k){o[String(k)]=String(v).slice(0,500)});"
        + "else if(typeof h==='object'){for(var k in h)if(Object.prototype.hasOwnProperty.call(h,k))o[k]=String(h[k]).slice(0,500)}}catch(e){}return o}"
        + "function clip(s,n){s=String(s==null?'':s);return s.length>n?s.slice(0,n)+'\\n…':s}"
        + "function textish(ct){return!ct||/^(text\\/|application\\/(json|javascript|xml|x-www-form-urlencoded|.*\\+json)|image\\/svg)/i.test(ct)}"
        + "var rid=0;function net(e){emit('network',e)}"
        + "var F=window.fetch;if(F){window.fetch=function(){var a=arguments,input=a[0],init=a[1]||{},"
        + "u=typeof input==='string'?input:(input&&input.url)||'',m=(init.method||(input&&input.method)||'GET'),t=Date.now(),id='f'+(++rid),"
        + "reqH=hdrs(init.headers||(input&&input.headers)),reqB=init.body!=null?clip(init.body,4000):undefined;"
        + "net({requestId:id,type:'fetch',method:m,url:String(u),requestHeaders:reqH,requestBody:reqB});"
        + "return F.apply(this,a).then(function(r){var ct=(r.headers&&r.headers.get&&r.headers.get('content-type'))||'';var rh=hdrs(r.headers);"
        + "var finish=function(body,sz){net({requestId:id,type:'fetch',method:m,url:String(u),status:r.status,statusText:r.statusText,"
        + "duration:Date.now()-t,mimeType:ct,responseHeaders:rh,responseBody:body,size:sz,requestHeaders:reqH,requestBody:reqB})};"
        + "if(textish(ct)){return r.clone().text().then(function(txt){finish(clip(txt,12000),txt.length);return r},function(){finish(undefined,undefined);return r})}"
        + "finish(undefined,undefined);return r},"
        + "function(err){net({requestId:id,type:'fetch',method:m,url:String(u),status:0,error:String(err),duration:Date.now()-t,"
        + "requestHeaders:reqH,requestBody:reqB});throw err})}};"
        + "var X=window.XMLHttpRequest;if(X){var o=X.prototype.open,s=X.prototype.send,sh=X.prototype.setRequestHeader;"
        + "X.prototype.open=function(m,u){this.__g={method:m,url:String(u),t:0,id:'x'+(++rid),headers:{}};return o.apply(this,arguments)};"
        + "X.prototype.setRequestHeader=function(k,v){if(this.__g)this.__g.headers[k]=String(v);return sh.apply(this,arguments)};"
        + "X.prototype.send=function(body){var x=this;if(x.__g){x.__g.t=Date.now();if(body!=null)x.__g.body=clip(body,4000);"
        + "net({requestId:x.__g.id,type:'xhr',method:x.__g.method,url:x.__g.url,requestHeaders:x.__g.headers,requestBody:x.__g.body});"
        + "x.addEventListener('loadend',function(){if(!x.__g)return;var rh={},raw='';try{raw=x.getAllResponseHeaders()||''}catch(e){}"
        + "String(raw).split(/\\r?\\n/).forEach(function(line){var i=line.indexOf(':');if(i>0)rh[line.slice(0,i).trim()]=line.slice(i+1).trim().slice(0,500)});"
        + "var ct='';try{ct=x.getResponseHeader('content-type')||''}catch(e){}var body;"
        + "try{if(typeof x.responseText==='string'&&textish(ct))body=clip(x.responseText,12000)}catch(e){}"
        + "net({requestId:x.__g.id,type:'xhr',method:x.__g.method,url:x.__g.url,status:x.status,statusText:x.statusText,"
        + "duration:Date.now()-x.__g.t,mimeType:ct,responseHeaders:rh,responseBody:body,size:body?body.length:undefined,"
        + "requestHeaders:x.__g.headers,requestBody:x.__g.body})})}return s.apply(this,arguments)}}"
        + "function boot(){setTimeout(window.__grainDumpDom,500);setTimeout(window.__grainDumpDom,1600)}"
        + "if(document.readyState==='complete'||document.readyState==='interactive')boot();"
        + "else document.addEventListener('DOMContentLoaded',boot);"
        + "})();";

    private final Bridge bridge;

    public GrainWebViewClient(Bridge bridge) {
        super(bridge);
        this.bridge = bridge;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri uri = request.getUrl();
        if (isPreviewUri(uri)) {
            emitNetwork(request.getMethod(), uri.toString(), -1, 0, request.isForMainFrame());
            if (shouldInjectHtml(request)) {
                WebResourceResponse injected = fetchAndInject(uri);
                if (injected != null) return injected;
            }
        }
        return super.shouldInterceptRequest(view, request);
    }

    private static boolean shouldInjectHtml(WebResourceRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) return false;
        if (request.isForMainFrame()) return true;
        Map<String, String> headers = request.getRequestHeaders();
        if (headers != null) {
            String dest = headers.get("Sec-Fetch-Dest");
            if (dest != null) {
                String value = dest.toLowerCase();
                return "document".equals(value) || "iframe".equals(value) || "frame".equals(value);
            }
            String accept = headers.get("Accept");
            if (accept != null && accept.toLowerCase().contains("text/html")) return true;
        }
        String path = request.getUrl().getPath();
        return path == null || path.isEmpty() || path.endsWith("/") || path.endsWith(".html") || path.endsWith(".htm");
    }

    private WebResourceResponse fetchAndInject(Uri uri) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) stream = new ByteArrayInputStream(new byte[0]);
            String contentType = connection.getContentType();
            String mime = mimeFrom(contentType, "application/octet-stream");
            String charsetName = charsetFrom(contentType, "UTF-8");
            Charset charset = charsetFor(charsetName);
            byte[] bytes = readAll(stream);
            emitNetwork("GET", uri.toString(), status, bytes.length, true);
            if (status >= 400 || contentType == null || !contentType.toLowerCase().contains("html")) {
                return new WebResourceResponse(mime, charsetName, status, reason(status), headers(connection), new ByteArrayInputStream(bytes));
            }
            String html = new String(bytes, charset);
            html = inject(html);
            byte[] out = html.getBytes(charset);
            Log.i(TAG, "injected inspect script into " + uri);
            return new WebResourceResponse("text/html", charsetName, status, reason(status), headers(connection), new ByteArrayInputStream(out));
        } catch (Exception e) {
            Log.e(TAG, "fetchAndInject failed: " + uri, e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String inject(String html) {
        String tag = "<script>" + INSPECT_SCRIPT + "</script>";
        if (HEAD_CLOSE.matcher(html).find()) return HEAD_CLOSE.matcher(html).replaceFirst(tag + "</head>");
        if (BODY_CLOSE.matcher(html).find()) return BODY_CLOSE.matcher(html).replaceFirst(tag + "</body>");
        return tag + html;
    }

    private void emitNetwork(String method, String url, int status, int size, boolean main) {
        JSObject payload = new JSObject();
        payload.put("method", method == null ? "GET" : method);
        payload.put("url", url);
        if (status >= 0) payload.put("status", status);
        if (size > 0) payload.put("size", size);
        payload.put("main", main);
        PluginHandle handle = bridge.getPlugin("GrainWorkspace");
        if (handle != null && handle.getInstance() instanceof GrainWorkspacePlugin) {
            ((GrainWorkspacePlugin) handle.getInstance()).emitWebNetwork(payload);
        }
    }

    static boolean isPreviewUri(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("http")) return false;
        String host = uri.getHost();
        if (host == null) return false;
        if (!host.equals("127.0.0.1") && !host.equalsIgnoreCase("localhost")) return false;
        return uri.getPort() > 0;
    }

    private static String mimeFrom(String contentType, String fallback) {
        if (contentType == null || contentType.isEmpty()) return fallback;
        int cut = contentType.indexOf(';');
        return (cut >= 0 ? contentType.substring(0, cut) : contentType).trim();
    }

    private static String charsetFrom(String contentType, String fallback) {
        if (contentType == null) return fallback;
        String lower = contentType.toLowerCase();
        int at = lower.indexOf("charset=");
        if (at < 0) return fallback;
        String value = contentType.substring(at + 8).trim();
        int end = value.indexOf(';');
        if (end >= 0) value = value.substring(0, end).trim();
        return value.replace("\"", "");
    }

    private static Charset charsetFor(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static byte[] readAll(InputStream stream) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) >= 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private static Map<String, String> headers(HttpURLConnection connection) {
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        Map<String, List<String>> raw = connection.getHeaderFields();
        if (raw == null) return map;
        for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) continue;
            map.put(entry.getKey(), entry.getValue().get(0));
        }
        return map;
    }

    private static String reason(int status) {
        if (status == 200) return "OK";
        if (status == 404) return "Not Found";
        return "OK";
    }
}
