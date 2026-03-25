package com.tram.monitor

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

data class TramLine(val name: String, val times: List<Int>)

class TramAdapter(private var items: List<TramLine>) :
    RecyclerView.Adapter<TramAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvLineName)
        val times: TextView = v.findViewById(R.id.tvTimes)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tram, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.times.text = item.times.joinToString("  ") { "${it}min" }
    }

    fun update(newItems: List<TramLine>) {
        items = newItems
        notifyDataSetChanged()
    }
}

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 13; Redmi Note 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private const val REFRESH_MS = 30_000L
        private const val STOP_A = "1711"
        private const val STOP_B = "1703"
    }

    private lateinit var webView: WebView
    private lateinit var adapterA: TramAdapter
    private lateinit var adapterB: TramAdapter
    private var isFetching = false

    inner class TramBridge {
        @JavascriptInterface
        fun onStopData(stopId: String, json: String) {
            android.util.Log.d("TRAM", "onStopData stop=$stopId json=${json.take(200)}")
            handler.post {
                isFetching = false
                try {
                    val obj = JSONObject(json)
                    val lines = mutableListOf<TramLine>()
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val entry = obj.getJSONObject(key)
                        val lineName = entry.getString("name")
                    
                        // For stop B, show only line 42
                        if (stopId == STOP_B && lineName != "42") continue
                    
                        val details = entry.getJSONArray("details")
                        val times = (0 until details.length()).map { j ->
                            details.getJSONObject(j).getInt("t")
                        }
                    
                        // Merge with existing entry if same line name
                        val existing = lines.indexOfFirst { it.name == lineName }
                        if (existing >= 0) {
                            val merged = (lines[existing].times + times).sorted().distinct()
                            lines[existing] = TramLine(lineName, merged)
                        } else {
                            lines.add(TramLine(lineName, times))
                        }
                    }
                    
                    android.util.Log.d("TRAM", "stop=$stopId parsed ${lines.size} lines")

                    val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date())

                    if (stopId == STOP_A) {
                        adapterA.update(lines)
                        findViewById<TextView>(R.id.tvStopAUpdated).text = "обновено $timeStr"
                    } else {
                        adapterB.update(lines)
                        findViewById<TextView>(R.id.tvStopBUpdated).text = "обновено $timeStr"
                    }
                } catch (e: Exception) {
                    android.util.Log.e("TRAM", "parse error stop=$stopId: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun onError(stopId: String, status: Int, body: String) {
            android.util.Log.w("TRAM", "onError stop=$stopId status=$status body=${body.take(200)}")
            handler.post {
                isFetching = false
                handler.postDelayed({
                    android.util.Log.d("TRAM", "Reloading WebView after error")
                    webView.loadUrl("https://www.sofiatraffic.bg/bg/")
                }, 15_000L)
            }
        }

        @JavascriptInterface
        fun onPageReady() {
            android.util.Log.d("TRAM", "onPageReady — scheduling first fetch")
            handler.post {
                handler.postDelayed({ doFetch() }, 1000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Full screen — AFTER setContentView
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.post {
            window.insetsController?.apply {
                hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        adapterA = TramAdapter(emptyList())
        adapterB = TramAdapter(emptyList())

        findViewById<RecyclerView>(R.id.rvStopA).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = adapterA
        }
        findViewById<RecyclerView>(R.id.rvStopB).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = adapterB
        }

        setupWebView()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        webView.destroy()
    }

    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)

        webView = WebView(this)
        webView.visibility = View.GONE
        webView.settings.javaScriptEnabled = true
        webView.settings.userAgentString = UA
        webView.settings.domStorageEnabled = true

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(TramBridge(), "TramBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                android.util.Log.d("TRAM", "WebView page finished: $url")

                val js = """
                    (function() {
                        function getXsrf() {
                            var match = document.cookie.match(/XSRF-TOKEN=([^;]+)/);
                            if (!match) return '';
                            try { return decodeURIComponent(match[1]); } catch(e) { return match[1]; }
                        }

                        function fetchStop(stopId) {
                            var xsrf = getXsrf();
                            if (!xsrf) {
                                TramBridge.onError(stopId, 0, 'no xsrf');
                                return;
                            }
                            fetch('https://www.sofiatraffic.bg/bg/trip/getVirtualTable', {
                                method: 'POST',
                                headers: {
                                    'Content-Type': 'application/json',
                                    'X-Requested-With': 'XMLHttpRequest',
                                    'X-XSRF-TOKEN': xsrf,
                                    'Accept': 'application/json, text/javascript, */*; q=0.01'
                                },
                                body: JSON.stringify({stop: stopId, type: 2})
                            })
                            .then(function(r) {
                                if (!r.ok) {
                                    r.text().then(function(t) {
                                        TramBridge.onError(stopId, r.status, t);
                                    });
                                    return;
                                }
                                return r.json();
                            })
                            .then(function(data) {
                                if (data) TramBridge.onStopData(stopId, JSON.stringify(data));
                            })
                            .catch(function(e) {
                                TramBridge.onError(stopId, -1, e.toString());
                            });
                        }

                        window._fetchStop = fetchStop;
                        TramBridge.onPageReady();
                    })();
                """.trimIndent()

                view?.evaluateJavascript(js, null)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    android.util.Log.e("TRAM", "WebView error: ${error?.description}")
                    handler.postDelayed({
                        webView.loadUrl("https://www.sofiatraffic.bg/bg/")
                    }, 30_000L)
                }
            }
        }

        webView.loadUrl("https://www.sofiatraffic.bg/bg/")
    }

    private fun doFetch() {
        if (isFetching) return
        isFetching = true
        android.util.Log.d("TRAM", "doFetch — calling JS fetchStop for both stops")

        webView.evaluateJavascript("window._fetchStop('$STOP_A')", null)
        handler.postDelayed({
            webView.evaluateJavascript("window._fetchStop('$STOP_B')", null)
        }, 2000L)

        handler.postDelayed({ doFetch() }, REFRESH_MS)
    }
}
