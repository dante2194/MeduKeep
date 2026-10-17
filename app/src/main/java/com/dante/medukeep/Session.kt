package com.dante.medukeep

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap

object Store {
    const val BASE = "https://my.medu.ir/"
    private const val PREFS = "medukeep"

    fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun keep(c: Context) = prefs(c).getBoolean("keep", true)
    fun setKeep(c: Context, on: Boolean) = prefs(c).edit().putBoolean("keep", on).apply()
}

/** Small rolling log in app-private storage. Export it: long-press the cookie button. */
object Dbg {
    private const val MAX = 300000L
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private fun file(c: Context) = File(c.applicationContext.filesDir, "medukeep.log")

    @Synchronized
    fun log(c: Context, msg: String) {
        try {
            val f = file(c)
            if (f.length() > MAX) {
                val t = f.readText()
                f.writeText(t.substring(t.length / 2))
            }
            f.appendText(fmt.format(Date()) + "  " + msg + "\n")
        } catch (_: Throwable) { }
    }

    @Synchronized
    fun read(c: Context): String = try {
        val f = file(c)
        if (f.exists()) f.readText() else "(empty log)"
    } catch (t: Throwable) {
        "(cannot read log: $t)"
    }

    /** name:valueLength:shortHash for every cookie, DUP marks a name that appears twice. */
    fun describe(raw: String?): String {
        if (raw.isNullOrBlank()) return "none"
        val seen = HashSet<String>()
        return raw.split(";").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ") { p ->
            val n = p.substringBefore("=")
            val v = p.substringAfter("=", "")
            val dup = if (seen.add(n)) "" else "(DUP)"
            n + dup + ":" + v.length + ":" + Integer.toHexString(v.hashCode()).takeLast(4)
        }
    }
}

/**
 * Keeps the my.medu.ir login across reboots.
 *
 *  - WebView drops "session cookies" (no expiry) when the process dies, so we keep a snapshot
 *    of the cookies and put them back (with a 30-day Max-Age) once per app start.
 *  - The snapshot is only updated while the page really looks logged in (no password field,
 *    not a login URL, page not blank, load not failed). A logged-out state can never overwrite it.
 *  - Everything that happens is written to a small log (see Dbg).
 */
object Session {
    private const val KEY_GOOD = "cookies_good"
    private const val KEY_GOOD_AT = "cookies_good_at"
    private const val MAX_AGE = 2592000

    private const val PROBE = "(function(){try{" +
        "if(document.querySelector('input[type=password]'))return 'LOGIN';" +
        "var t=(document.body&&document.body.innerText)||'';" +
        "return t.trim().length>20?'OK':'EMPTY';}catch(e){return 'ERR';}})()"

    private val LOGIN_URL = Regex("(?i)/(log-?in|sign-?in|logout|sso)\\b")

    @Volatile private var restored = false
    private val ui = Handler(Looper.getMainLooper())
    private val failed: MutableSet<WebView> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<WebView, Boolean>()))

    private fun isMedu(host: String?) = host != null && (host == "medu.ir" || host.endsWith(".medu.ir"))

    fun markFailed(v: WebView) { failed.add(v) }
    fun pageStarted(v: WebView) { failed.remove(v) }

    /** First call in a process puts the last logged-in cookies back, overwriting stale values. */
    @Synchronized
    fun restoreOnce(ctx: Context): String {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        if (restored) return "Session: already restored in this run"
        restored = true
        val p = Store.prefs(ctx)
        val o = JSONObject(p.getString(KEY_GOOD, "{}") ?: "{}")
        val at = p.getLong(KEY_GOOD_AT, 0L)
        val ageMin = if (at == 0L) -1L else (System.currentTimeMillis() - at) / 60000L
        var n = 0
        val hosts = o.keys()
        while (hosts.hasNext()) {
            val host = hosts.next()
            val url = "https://$host/"
            Dbg.log(ctx, "restore $host  jar before: " + Dbg.describe(cm.getCookie(url)))
            o.getString(host).split(";").forEach { pair ->
                val t = pair.trim()
                if (t.isEmpty() || !t.contains("=")) return@forEach
                cm.setCookie(url, "$t; Max-Age=$MAX_AGE; Path=/; Secure")
                n++
            }
        }
        cm.flush()
        Dbg.log(ctx, "restore done: $n cookies put back, snapshot age $ageMin min")
        return if (n == 0) "Session: no saved login yet" else "Session: restored $n cookies"
    }

    /** Looks at the page (after delayMs, so scripts can finish) and saves cookies only if logged in. */
    fun check(ctx: Context, view: WebView, url: String?, delayMs: Long) {
        val u = url ?: return
        val host = try { Uri.parse(u).host } catch (_: Throwable) { null }
        if (!isMedu(host)) return
        val h = host ?: return
        val app = ctx.applicationContext
        if (delayMs <= 0L) probe(app, view, h, u) else ui.postDelayed({ probe(app, view, h, u) }, delayMs)
    }

    private fun probe(app: Context, view: WebView, host: String, url: String) {
        try {
            if (failed.contains(view)) {
                Dbg.log(app, "FAILED  $url  not saved")
                return
            }
            view.evaluateJavascript(PROBE) { r -> onProbe(app, view, host, url, (r ?: "").trim('"')) }
        } catch (t: Throwable) {
            Dbg.log(app, "probe error: $t")
        }
    }

    private fun onProbe(app: Context, view: WebView, host: String, url: String, state: String) {
        val cm = CookieManager.getInstance()
        val raw = cm.getCookie("https://$host/")
        if (state == "OK" && !LOGIN_URL.containsMatchIn(url) && !raw.isNullOrBlank()) {
            val p = Store.prefs(app)
            val o = JSONObject(p.getString(KEY_GOOD, "{}") ?: "{}")
            o.put(host, raw)
            p.edit().putString(KEY_GOOD, o.toString()).putLong(KEY_GOOD_AT, System.currentTimeMillis()).apply()
            cm.flush()
            Dbg.log(app, "LOGGED-IN  $url  saved: " + Dbg.describe(raw))
            saveStorage(app, view)
        } else {
            Dbg.log(app, "$state  $url  NOT saved; jar: " + Dbg.describe(raw))
        }
    }

    // ---------------- web storage snapshot / restore ----------------

    private const val KEY_STORE = "storage_good"
    private const val STORE_JS = "(function(){try{var o={l:{},s:{}};" +
        "for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);o.l[k]=localStorage.getItem(k);}" +
        "for(var j=0;j<sessionStorage.length;j++){var m=sessionStorage.key(j);o.s[m]=sessionStorage.getItem(m);}" +
        "o.origin=location.origin;return JSON.stringify(o);}catch(e){return '';}})()"

    @Volatile private var storageRestores = 0
    private val seenReq = HashSet<String>()

    /** Only called while logged in. Keeps the last non-empty localStorage + sessionStorage of the site. */
    private fun saveStorage(app: Context, view: WebView) {
        try {
            view.evaluateJavascript(STORE_JS) { r ->
                try {
                    if (r.isNullOrEmpty() || r == "null") return@evaluateJavascript
                    val s = JSONTokener(r).nextValue() as? String ?: return@evaluateJavascript
                    val o = JSONObject(s)
                    val nl = o.getJSONObject("l").length()
                    val ns = o.getJSONObject("s").length()
                    if (nl + ns > 0 && s.length < 600000) {
                        Store.prefs(app).edit().putString(KEY_STORE, s).apply()
                        Dbg.log(app, "storage saved: local=$nl session=$ns origin=" + o.optString("origin"))
                    }
                } catch (t: Throwable) {
                    Dbg.log(app, "storage save error: $t")
                }
            }
        } catch (_: Throwable) { }
    }

    /** At page start: if the WebView lost its storage, put the snapshot back (and reload once). */
    fun restoreStorage(ctx: Context, view: WebView, url: String?) {
        if (storageRestores >= 2) return
        val u = url ?: return
        val uri = Uri.parse(u)
        if (!isMedu(uri.host)) return
        val s = Store.prefs(ctx).getString(KEY_STORE, null) ?: return
        val origin = try { JSONObject(s).optString("origin") } catch (_: Throwable) { "" }
        if (origin.isEmpty() || origin != (uri.scheme ?: "") + "://" + (uri.authority ?: "")) return
        val js = "(function(){try{var d=JSON.parse(" + JSONObject.quote(s) + ");var n=0;" +
            "if(localStorage.length===0){for(var k in d.l){localStorage.setItem(k,d.l[k]);n++;}}" +
            "if(sessionStorage.length===0){for(var m in d.s){sessionStorage.setItem(m,d.s[m]);n++;}}" +
            "return n;}catch(e){return -1;}})()"
        val app = ctx.applicationContext
        view.evaluateJavascript(js) { r ->
            val n = (r ?: "0").trim().toIntOrNull() ?: 0
            if (n > 0) {
                storageRestores++
                Dbg.log(app, "storage restored: $n keys, reloading once")
                ui.post { view.reload() }
            }
        }
    }

    /** Logs method + path + header NAMES (never values) of the first requests the site makes. */
    fun noteRequest(ctx: Context, method: String?, url: Uri?, headers: Map<String, String>?) {
        val u = url ?: return
        val host = u.host ?: return
        if (!isMedu(host)) return
        val key = (method ?: "?") + " " + host + (u.path ?: "")
        synchronized(seenReq) {
            if (seenReq.size >= 60 || !seenReq.add(key)) return
        }
        Dbg.log(ctx, "REQ $key headers: " + (headers?.keys?.sorted()?.joinToString(",") ?: ""))
    }
}
