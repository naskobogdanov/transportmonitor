package com.tram.monitor

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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

    private val client = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                android.util.Log.d("TRAM", "Saving ${cookies.size} cookies for ${url.host}")
                cookies.forEach {
                    android.util.Log.d("TRAM", "  Cookie: ${it.name}=${it.value.take(30)}")
                }
                // Merge, don't replace — keep existing cookies not in new response
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

    // Stop IDs — adjust to your actual stops
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

    private fun initSession(retryDelay: Long = 8000L) {
        android.util.Log.d("TRAM", "initSession called")
        val req = Request.Builder()
            .url("https://www.sofiatraffic.bg/bg/")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Redmi) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "bg,en;q=0.9")
            .get()
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("TRAM", "initSession FAILED: ${e.message}")
                val next = minOf(retryDelay * 2, 300_000L)
                handler.postDelayed({ initSession(next) }, retryDelay)
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                android.util.Log.d("TRAM", "initSession HTTP $code")
                response.close()

                if (code == 429) {
                    val next = minOf(retryDelay * 2, 300_000L)
                    android.util.Log.w("TRAM", "Rate limited! Retrying in ${next / 1000}s")
                    handler.postDelayed({ initSession(next) }, next)
                    return
                }

                val xsrf = getXsrf()
                android.util.Log.d("TRAM", "XSRF after init: '${xsrf.take(20)}'")

                if (xsrf.isEmpty()) {
                    android.util.Log.w("TRAM", "No XSRF yet, retrying in ${retryDelay / 1000}s")
                    handler.postDelayed({ initSession(retryDelay) }, retryDelay)
                } else {
                    scheduleRefresh()
                }
            }
        })
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

        val json = """{"stop_id":"$stopId","type":"1"}"""
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())

        val req = Request.Builder()
            .url("https://www.sofiatraffic.bg/bg/trip/getVirtualTable")
            .post(body)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Redmi) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Accept-Language", "bg,en;q=0.9")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("X-XSRF-TOKEN", xsrf)
            .header("Referer", "https://www.sofiatraffic.bg/bg/")
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
