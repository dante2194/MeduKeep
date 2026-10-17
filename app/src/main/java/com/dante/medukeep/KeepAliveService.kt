package com.dante.medukeep

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.graphics.Bitmap
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var wake: PowerManager.WakeLock? = null

    private val tick = object : Runnable {
        override fun run() {
            ping()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Session keep-alive", NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )

        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "medukeep:alive")
            .apply { acquire() }

        Dbg.log(applicationContext, "service created")
        Session.restoreOnce(applicationContext)

        web = WebView(applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    Session.pageStarted(view)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    Session.check(applicationContext, view, url, 2500)
                }

                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        Session.markFailed(view)
                        Dbg.log(applicationContext, "ping load error ${error.errorCode} ${error.description}")
                    }
                }
            }
        }
        handler.post(tick)
    }

    private var tickCount = 0

    // Looks like a person touching the page: input events, a 1px scroll, and a same-origin
    // request with the session cookies. Resets JS idle timers and the server's idle clock.
    private val engageJs = "(function(){try{" +
        "['mousemove','mousedown','mouseup','keydown','keyup','touchstart','touchend','scroll'].forEach(function(t){" +
        "try{document.dispatchEvent(new Event(t,{bubbles:true}));window.dispatchEvent(new Event(t));}catch(e){}});" +
        "window.scrollBy(0,1);window.scrollBy(0,-1);" +
        "fetch(location.href,{credentials:'include',cache:'no-store'}).catch(function(){});" +
        "return 'ENGAGED';}catch(e){return 'ERR';}})()"

    private fun ping() {
        tickCount++
        val w = web
        val cur = try { w?.url } catch (_: Throwable) { null }
        val host = try { Uri.parse(cur ?: "").host } catch (_: Throwable) { null }
        val onSite = host != null && (host == "medu.ir" || host.endsWith(".medu.ir"))
        val loginish = cur != null && Regex("(?i)/(log-?in|sign-?in|logout|sso)\\b").containsMatchIn(cur)
        if (w != null && cur != null && onSite && !loginish && tickCount % 3 != 0) {
            Dbg.log(this, "ping: engage $cur")
            w.evaluateJavascript(engageJs, null)
            Session.check(applicationContext, w, cur, 3000)
        } else {
            Dbg.log(this, "ping: reload")
            w?.loadUrl(Store.BASE)
        }
        val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification("Last ping $t"))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("MeduKeep is holding your session")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        web?.destroy()
        web = null
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "keepalive"
        private const val NOTIF_ID = 1
        private const val INTERVAL_MS = 4 * 60 * 1000L
    }
}
