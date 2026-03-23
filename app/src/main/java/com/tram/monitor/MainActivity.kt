package com.tram.monitor

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.io.IOException
import java.net.URLDecoder

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
    private val cookieStore = HashMap<String, List<Cookie>>()

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 13; Redmi Note 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                android.util.Log.d("TRAM", "Saving ${cookies.size} cookies for ${url.host}")
                val existing = cookieStore[url.host]?.associateBy { it.name }?.toMutableMap()
                    ?: mutableMapOf()
                cookies.forEach { existing[it.name] = it }
                cookieStore[url.host] = existing.values.toList()
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val cookies = cookieStore[url.host] ?: emptyList()
                android.util.Log.d("TRAM", "Loading ${cookies.size} cookies for ${url.host}")
                return cookies
            }
        })
        .build()

    private val STOP_A = "1711"
    private val STOP_B = "1703"

    private lateinit var adapterA: TramAdapter
    private lateinit var adapterB: TramAdapter

    private val REFRESH_MS = 30_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

        initSession()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun initSession(retryDelay: Long = 16_000L) {
        android.util.Log.d("TRAM", "initSession called via WebView")

        handler.post {
            // Enable cookies before creating WebView
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
            }

            val webView = WebView(this)
            webView.settings.javaScriptEnabled = true
            webView.settings.userAgentString = UA
            webView.visibility = View.GONE

            // Enable third-party cookies on this specific WebView instance
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            webView.webViewClient = object : WebViewClient() {

                override fun onPageFinished(view: WebView?, url: String?) {
                    android.util.Log.d("TRAM", "WebView loaded: $url")

                    // Flush cookies to disk first
                    CookieManager.getInstance().flush()

                    val wvCookies = CookieManager.getInstance()
                        .getCookie("https://www.sofiatraffic.bg") ?: ""

                    android.util.Log.d("TRAM", "WebView cookies: ${wvCookies.take(300)}")

                    // Inject cookies into OkHttp cookie store
                    wvCookies.split(";").forEach { pair ->
                        val parts = pair.trim().split("=", limit = 2)
                        if (parts.size == 2) {
                            val name = parts[0].trim()
                            val value = parts[1].trim()
                            val cookie = Cookie.Builder()
                                .domain("www.sofiatraffic.bg")
                                .path("/")
                                .name(name)
                                .value(value)
                                .build()
                            val existing = cookieStore["www.sofiatraffic.bg"]
                                ?.associateBy { it.name }?.toMutableMap() ?: mutableMapOf()
                            existing[name] = cookie
                            cookieStore["www.sofiatraffic.bg"] = existing.values.toList()
                        }
                    }

                    val xsrf = getXsrf()
                    android.util.Log.d("TRAM", "XSRF from WebView: '${xsrf.take(20)}'")

                    view?.destroy()

                    if (xsrf.isEmpty()) {
                        android.util.Log.w("TRAM", "No XSRF from WebView, retrying in ${retryDelay / 1000}s")
                        handler.postDelayed({ initSession(retryDelay) }, retryDelay)
                    } else {
                        scheduleRefresh()
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        android.util.Log.e("TRAM", "WebView error: ${error?.description}")
                        view?.destroy()
                        val next = minOf(retryDelay * 2, 300_000L)
                        handler.postDelayed({ initSession(next) }, retryDelay)
                    }
                }
            }

            webView.loadUrl("https://www.sofiatraffic.bg/bg/")
        }
    }

    private fun getXsrf(): String {
        val cookies = cookieStore["www.sofiatraffic.bg"] ?: return ""
        val raw = cookies.find { it.name == "XSRF-TOKEN" }?.value ?: return ""
        return try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (e: Exception) {
            raw
        }
    }

    private fun scheduleRefresh() {
        fetchStop(STOP_A, adapterA)
        fetchStop(STOP_B, adapterB)
        handler.postDelayed({ scheduleRefresh() }, REFRESH_MS)
    }

    private fun fetchStop(stopId: String, adapter: TramAdapter) {
        val xsrf = getXsrf()
        if (xsrf.isEmpty()) {
            android.util.Log.e("TRAM", "fetchStop $stopId skipped - no xsrf, re-initing")
            initSession()
            return
        }
        android.util.Log.d("TRAM", "fetchStop $stopId xsrf=${xsrf.take(20)}")

        val json = """{"stop":"$stopId","type":2}"""
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())

        val req = Request.Builder()
            .url("https://www.sofiatraffic.bg/bg/trip/getVirtualTable")
            .post(body)
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Accept-Language", "bg,en-US;q=0.7,en;q=0.3")
            .header("Content-Type", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("X-XSRF-TOKEN", xsrf)
            .header("Origin", "https://www.sofiatraffic.bg")
            .header("Referer", "https://www.sofiatraffic.bg/bg/transport/tramway")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("TRAM", "fetchStop $stopId FAILED: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val respBody = response.body?.string() ?: run { response.close(); return }
                android.util.Log.d("TRAM", "fetchStop $stopId HTTP=$code body=${respBody.take(400)}")
                response.close()

                when (code) {
                    419, 403, 429 -> {
                        android.util.Log.w("TRAM", "fetchStop $stopId auth error $code, re-init")
                        cookieStore.clear()
                        handler.post { initSession() }
                        return
                    }
                }

                try {
                    val arr = JSONArray(respBody)
                    val lines = (0 until arr.length()).map { i ->
                        val obj = arr.getJSONObject(i)
                        val lineName = obj.getString("name")
                        val details = obj.getJSONArray("details")
                        val times = (0 until details.length()).map { j ->
                            details.getJSONObject(j).getInt("t")
                        }
                        TramLine(lineName, times)
                    }
                    android.util.Log.d("TRAM", "fetchStop $stopId OK: ${lines.size} lines")
                    handler.post { adapter.update(lines) }
                } catch (e: Exception) {
                    android.util.Log.e("TRAM", "fetchStop $stopId parse error: ${e.message} | body: ${respBody.take(200)}")
                    cookieStore.clear()
                    handler.post { initSession() }
                }
            }
        })
    }
}
