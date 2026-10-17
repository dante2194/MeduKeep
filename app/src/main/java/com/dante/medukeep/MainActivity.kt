package com.dante.medukeep

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject
import rikka.shizuku.Shizuku

// ---------------- Tunables ----------------
private const val PULL_TO_REFRESH = true      // false = disable swipe-down reload completely
private const val PULL_TRIGGER_DP = 180       // finger travels about 2x this. Android default is 64
private const val DESKTOP_VIEWPORT = "1280"   // layout width (px) used in desktop mode
private const val DUP_WINDOW_MS = 4000L       // ignore repeated identical downloads inside this window

// Injected into every medu.ir page:
//  - window.print() opens Android's print dialog
//  - remembers <a download="..."> names for blob downloads
//  - reports whether the touched spot (page AND inner scroll containers) is at the very top,
//    so pull-to-refresh never hijacks normal scrolling
private const val SHIM = "(function(){if(window.__mk)return;window.__mk=1;" +
    "window.print=function(){AndroidBridge.print();};" +
    "document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a[download]'):null;" +
    "window.__lastDl=(a&&a.download)||'';},true);" +
    "document.addEventListener('touchstart',function(e){var n=e.target,top=true;" +
    "while(n&&n!==document.documentElement){if(n.scrollTop>0){top=false;break;}n=n.parentElement;}" +
    "if((window.pageYOffset||0)>0)top=false;AndroidBridge.atTop(top);},{capture:true,passive:true});})();"

// Forces a wide layout so responsive sites really switch to their desktop version.
private const val VIEWPORT_JS = "(function(){var h=document.head||document.documentElement;" +
    "var m=document.querySelector('meta[name=viewport]');" +
    "if(!m){m=document.createElement('meta');m.name='viewport';h.appendChild(m);}" +
    "m.setAttribute('content','width=" + DESKTOP_VIEWPORT + "');})();"

// Builds a self-contained copy of the page (rendered DOM, CSS and images inlined, scripts removed)
// and hands it to AndroidBridge.savePage. Runs inside the page; "tok" is a one-time token.
private const val SAVE_JS_BODY = """
var MAXTOTAL=15*1024*1024,MAXIMG=4*1024*1024,total=0;
function abs(u,b){try{return new URL(u,b||document.baseURI).href}catch(e){return u}}
function withTimeout(p,ms){return Promise.race([p,new Promise(function(_,rej){setTimeout(function(){rej(new Error('timeout'))},ms)})])}
function getBlob(u){return withTimeout(fetch(u,{credentials:'include'}).then(function(r){if(!r.ok)throw new Error(r.status);return r.blob()}),20000)}
function toData(b){return new Promise(function(res,rej){var r=new FileReader();r.onload=function(){res(r.result)};r.onerror=rej;r.readAsDataURL(b)})}
function fixCss(css,base){return css.replace(/url\(\s*(['"]?)(?!data:|#)([^'")]+)\1\s*\)/gi,function(m,q,u){return 'url("'+abs(u.trim(),base)+'")'})}
async function pool(items,fn,n){var i=0;async function w(){while(i<items.length){var k=i++;try{await fn(items[k],k)}catch(e){}}}var ws=[];for(var j=0;j<n;j++)ws.push(w());await Promise.all(ws)}
async function run(){
var liveImgs=[].slice.call(document.querySelectorAll('img'));
var srcs=liveImgs.map(function(i){return i.currentSrc||i.src||''});
var liveIn=[].slice.call(document.querySelectorAll('input,textarea,select'));
var c=document.documentElement.cloneNode(true);
var cImgs=[].slice.call(c.querySelectorAll('img'));
var cIn=[].slice.call(c.querySelectorAll('input,textarea,select'));
if(cImgs.length!==srcs.length){srcs=cImgs.map(function(i){return abs(i.getAttribute('src')||'')})}
if(liveIn.length===cIn.length){liveIn.forEach(function(e,i){var d=cIn[i],t=(e.type||'').toLowerCase();
if(e.tagName==='TEXTAREA'){d.textContent=e.value}
else if(e.tagName==='SELECT'){[].forEach.call(d.options,function(o,k){if(e.options[k]&&e.options[k].selected)o.setAttribute('selected','');else o.removeAttribute('selected')})}
else if(t==='checkbox'||t==='radio'){if(e.checked)d.setAttribute('checked','');else d.removeAttribute('checked')}
else if(t!=='password'&&t!=='file'){d.setAttribute('value',e.value)}})}
c.querySelectorAll('script,noscript,base,link[rel=preload],link[rel=modulepreload],link[rel=prefetch],link[rel=dns-prefetch],link[rel=preconnect]').forEach(function(n){n.remove()});
c.querySelectorAll('meta[charset],meta[http-equiv]').forEach(function(n){if(n.hasAttribute('charset')||/content-type/i.test(n.getAttribute('http-equiv')||''))n.remove()});
var links=[].slice.call(c.querySelectorAll('link[rel~=stylesheet][href]'));
await pool(links,async function(l){var u=abs(l.getAttribute('href'));
try{var b=await getBlob(u);var t=await b.text();var s=document.createElement('style');var m=l.getAttribute('media');if(m)s.setAttribute('media',m);s.textContent=fixCss(t,u);l.replaceWith(s)}
catch(e){l.setAttribute('href',u)}},5);
await pool(cImgs,async function(im,i){var u=srcs[i];if(!u||u.indexOf('data:')===0)return;
im.removeAttribute('srcset');im.removeAttribute('sizes');im.removeAttribute('loading');
try{if(total>MAXTOTAL)throw 0;var b=await getBlob(u);if(b.size>MAXIMG||total+b.size>MAXTOTAL)throw 0;total+=b.size;im.setAttribute('src',await toData(b))}
catch(e){im.setAttribute('src',u)}},6);
c.querySelectorAll('picture source').forEach(function(s){s.remove()});
var extra='';[].forEach.call(document.querySelectorAll('style'),function(s){if(!s.textContent.trim()&&s.sheet){try{extra+=[].map.call(s.sheet.cssRules,function(r){return r.cssText}).join('\n')}catch(e){}}});
var head=c.querySelector('head');if(!head){head=document.createElement('head');c.insertBefore(head,c.firstChild)}
if(extra){var es=document.createElement('style');es.textContent=extra;head.appendChild(es)}
var base=document.createElement('base');base.href=document.baseURI;head.insertBefore(base,head.firstChild);
var mc=document.createElement('meta');mc.setAttribute('charset','utf-8');head.insertBefore(mc,head.firstChild);
var dt=document.doctype?new XMLSerializer().serializeToString(document.doctype):'<!DOCTYPE html>';
var html=dt+'\n<!-- Saved from '+location.href.replace(/--/g,'%2D%2D')+' on '+new Date().toISOString()+' -->\n'+c.outerHTML;
AndroidBridge.savePage(tok,document.title||'',html)}
run().catch(function(e){AndroidBridge.savePageFail(tok,String(e))});
"""

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var desktopBtn: TextView

    private var defaultUA = ""
    private var desktop = false
    private var lastBoost = 0L
    private val recentDl = HashMap<String, Long>()
    private var lastContentKey = ""
    private var lastContentAt = 0L
    @Volatile private var saveToken = ""
    private var saveStartedAt = 0L

    @Volatile private var trusted = false
    @Volatile private var pageAtTop = false

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) {
            Boost.apply(this) { if (oncePerBoot("shz_ok")) toast(it) }
        } else if (oncePerBoot("shz_err")) {
            toast("Shizuku permission denied")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
        }

        Shizuku.addRequestPermissionResultListener(permListener)
        val restoreInfo = Session.restoreOnce(this)

        WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(Bridge(), "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    Session.noteRequest(this@MainActivity, request.method, request.url, request.requestHeaders)
                    return null
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    Session.pageStarted(view)
                    Session.restoreStorage(this@MainActivity, view, url)
                    trusted = isTrusted(url)
                    if (trusted) view.evaluateJavascript(SHIM, null)
                    if (desktop) view.evaluateJavascript(VIEWPORT_JS, null)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    swipe.isRefreshing = false
                    if (trusted) view.evaluateJavascript(SHIM, null)
                    if (desktop) view.evaluateJavascript(VIEWPORT_JS, null)
                    Session.check(this@MainActivity, view, url, 2500)
                }

                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    swipe.isRefreshing = false
                    if (request.isForMainFrame) {
                        Session.markFailed(view)
                        Dbg.log(this@MainActivity, "load error ${error.errorCode} ${error.description}")
                    }
                }
            }
            setDownloadListener { url, ua, cd, mime, _ -> handleDownload(url, ua, cd, mime) }
        }
        defaultUA = web.settings.userAgentString
        desktop = Store.prefs(this).getBoolean("desktop", false)

        swipe = SwipeRefreshLayout(this).apply {
            isEnabled = PULL_TO_REFRESH
            setColorSchemeColors(Color.parseColor("#1AA6B7"))
            setDistanceToTriggerSync(dp(PULL_TRIGGER_DP))
            setOnRefreshListener { web.reload() }
            // Refresh only if BOTH the page and the touched inner scroller are at the top.
            setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 || !pageAtTop }
            addView(
                web,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        // Tiny, backgroundless emoji buttons (bottom-left).
        //  🖥 tap = desktop/mobile mode, long-press = reload      💾 tap = save page as .html
        fun tiny(label: String) = TextView(this).apply {
            text = label
            textSize = 15f
            alpha = 0.45f
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        desktopBtn = tiny("🖥").apply {
            setOnClickListener { toggleDesktop() }
            setOnLongClickListener { web.reload(); true }
        }
        val saveBtn = tiny("💾").apply {
            setOnClickListener { savePage() }
            setOnLongClickListener { exportLog(); true }
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(desktopBtn)
            addView(saveBtn)
        }

        val root = FrameLayout(this).apply {
            addView(
                swipe,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                btnRow,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.LEFT
                ).apply { setMargins(dp(2), 0, 0, dp(2)) }
            )
        }
        setContentView(root)

        applyDesktop(reload = false)
        toast(restoreInfo)
        ContextCompat.startForegroundService(this, Intent(this, KeepAliveService::class.java))
        web.loadUrl(Store.BASE)
    }

    override fun onResume() {
        super.onResume()
        autoBoost()
    }

    // A new finger-down resets the "at top" flag; the page's JS sets it back to true if appropriate.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) pageAtTop = false
        return super.dispatchTouchEvent(ev)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun isTrusted(url: String?): Boolean {
        val host = try { Uri.parse(url).host } catch (_: Throwable) { null } ?: return false
        return host == "medu.ir" || host.endsWith(".medu.ir")
    }

    // ---------- Desktop mode ----------
    private fun desktopUA(): String =
        defaultUA.replace(Regex("\\(Linux;[^)]*\\)"), "(X11; Linux x86_64)")
            .replace(" Mobile", "")
            .replace("Version/4.0 ", "")

    private fun applyDesktop(reload: Boolean) {
        web.settings.userAgentString = if (desktop) desktopUA() else defaultUA
        web.settings.useWideViewPort = desktop
        web.settings.loadWithOverviewMode = desktop
        desktopBtn.text = if (desktop) "📱" else "🖥"
        if (reload) web.reload()
    }

    private fun toggleDesktop() {
        desktop = !desktop
        Store.prefs(this).edit().putBoolean("desktop", desktop).apply()
        applyDesktop(reload = true)
        Toast.makeText(this, if (desktop) "Desktop mode" else "Mobile mode", Toast.LENGTH_SHORT).show()
    }

    // ---------- Shizuku boost: runs on every app open; messages only once per reboot ----------
    private fun bootCount(): Int = try {
        Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT)
    } catch (_: Throwable) { -1 }

    /** True the first time it's called for [key] since the last reboot. */
    private fun oncePerBoot(key: String): Boolean {
        val boot = bootCount()
        val p = Store.prefs(this)
        if (p.getInt(key, -2) == boot) return false
        p.edit().putInt(key, boot).apply()
        return true
    }

    private fun autoBoost() {
        try {
            if (!Shizuku.pingBinder()) {
                if (oncePerBoot("shz_err")) toast("Shizuku isn't running, boost skipped")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBoost < 30_000) return
                lastBoost = now
                Boost.apply(this) { if (oncePerBoot("shz_ok")) toast(it) }
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                if (oncePerBoot("shz_err")) toast("Shizuku permission was denied. Allow it in the Shizuku app.")
            } else {
                Shizuku.requestPermission(100)
            }
        } catch (e: Throwable) {
            if (oncePerBoot("shz_err")) toast("Shizuku: ${e.message}")
        }
    }

    // ---------- Save page as .html ----------
    private fun savePage() {
        val now = SystemClock.elapsedRealtime()
        if (saveToken.isNotEmpty() && now - saveStartedAt < 60_000) {
            Toast.makeText(this, "Still saving…", Toast.LENGTH_SHORT).show()
            return
        }
        saveToken = java.util.UUID.randomUUID().toString()
        saveStartedAt = now
        Toast.makeText(this, "Saving page…", Toast.LENGTH_SHORT).show()
        web.evaluateJavascript(
            "(function(tok){" + SAVE_JS_BODY + "})(" + JSONObject.quote(saveToken) + ");", null
        )
    }

    // ---------- Printing ----------
    private fun doPrint() {
        val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val title = (web.title ?: "").ifBlank { "medu" }
        pm.print(title, web.createPrintDocumentAdapter(title), PrintAttributes.Builder().build())
    }

    // ---------- Downloads ----------
    /** True if any of [keys] was seen within the last DUP_WINDOW_MS. Main thread only. */
    private fun isDuplicate(vararg keys: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        recentDl.entries.removeAll { now - it.value > DUP_WINDOW_MS }
        val dup = keys.any { recentDl.containsKey(it) }
        keys.forEach { recentDl[it] = now }
        return dup
    }

    @Synchronized
    private fun isDupContent(bytes: ByteArray): Boolean {
        val key = bytes.size.toString() + ":" + bytes.contentHashCode()
        val now = SystemClock.elapsedRealtime()
        val dup = key == lastContentKey && now - lastContentAt < 15_000
        lastContentKey = key
        lastContentAt = now
        return dup
    }

    private fun handleDownload(url: String, ua: String?, cd: String?, mime: String?) {
        when {
            url.startsWith("blob:") || url.startsWith("data:") -> {
                if (isDuplicate("blob-any")) return
                pullViaJs(url)
            }
            url.startsWith("http") -> {
                val name = URLUtil.guessFileName(url, cd, mime)
                if (isDuplicate(url, "name:$name")) return
                try {
                    val req = DownloadManager.Request(Uri.parse(url))
                        .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                        .addRequestHeader("User-Agent", ua ?: "")
                        .addRequestHeader("Referer", web.url ?: Store.BASE)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    if (!mime.isNullOrBlank()) req.setMimeType(mime)
                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                    toast("Downloading $name")
                } catch (e: Throwable) {
                    toast("Download failed: ${e.message}")
                }
            }
            else -> toast("Unsupported download link")
        }
    }

    // blob:/data: URLs only exist inside the page, so let the page hand the bytes to us.
    private fun pullViaJs(url: String) {
        val js = "(function(u){fetch(u).then(function(r){return r.blob();}).then(function(b){" +
            "var fr=new FileReader();fr.onload=function(){var s=String(fr.result);" +
            "AndroidBridge.saveBase64(window.__lastDl||'',b.type||'',s.substring(s.indexOf(',')+1));};" +
            "fr.readAsDataURL(b);}).catch(function(e){AndroidBridge.fail(String(e));});})(" +
            JSONObject.quote(url) + ");"
        web.evaluateJavascript(js, null)
    }

    inner class Bridge {
        @JavascriptInterface
        fun print() {
            if (!trusted) return
            runOnUiThread { doPrint() }
        }

        @JavascriptInterface
        fun atTop(v: Boolean) {
            if (trusted) pageAtTop = v
        }

        @JavascriptInterface
        fun saveBase64(name: String, mime: String, b64: String) {
            if (!trusted) return
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                if (isDupContent(bytes)) return
                val type = if (mime.isNotBlank()) mime else "application/octet-stream"
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
                val fn = if (name.isNotBlank()) name
                else "medu_" + System.currentTimeMillis() + (if (ext != null) ".$ext" else "")
                val ok = Files.save(this@MainActivity, fn, type, bytes)
                runOnUiThread { toast(if (ok) "Saved to Downloads: $fn" else "Couldn't save the file") }
            } catch (e: Throwable) {
                runOnUiThread { toast("Save failed: ${e.message}") }
            }
        }

        // Token-checked (not "trusted"-checked) so it works on any page you tap 💾 on,
        // but a page can't call it on its own.
        @JavascriptInterface
        fun savePage(tok: String, title: String, html: String) {
            if (tok.isEmpty() || tok != saveToken) return
            saveToken = ""
            try {
                val base = title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
                    .trim('_').take(60).ifBlank { "page" }
                val fn = "$base.html"
                val ok = Files.save(this@MainActivity, fn, "text/html", html.toByteArray(Charsets.UTF_8))
                runOnUiThread { toast(if (ok) "Saved to Downloads: $fn" else "Couldn't save the page") }
            } catch (e: Throwable) {
                runOnUiThread { toast("Save failed: ${e.message}") }
            }
        }

        @JavascriptInterface
        fun savePageFail(tok: String, msg: String) {
            if (tok.isEmpty() || tok != saveToken) return
            saveToken = ""
            runOnUiThread { toast("Save failed: $msg") }
        }

        @JavascriptInterface
        fun fail(msg: String) {
            runOnUiThread { toast("Download failed: $msg") }
        }
    }

    // ---------- Debug-log export (long-press 💾) ----------
    private fun exportLog() {
        val ok = Files.save(this, "medukeep-log.txt", "text/plain", Dbg.read(this).toByteArray(Charsets.UTF_8))
        toast(if (ok) "Log saved: Downloads/medukeep-log.txt" else "Could not save the log")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onPause() {
        Session.check(this, web, web.url, 0)
        CookieManager.getInstance().flush()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        web.destroy()
        super.onDestroy()
    }
}

object Files {
    fun save(ctx: Context, name: String, mime: String, bytes: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: return false
            val out = ctx.contentResolver.openOutputStream(uri) ?: return false
            out.use { it.write(bytes) }
            return true
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        java.io.File(dir, name).writeBytes(bytes)
        return true
    }
}
