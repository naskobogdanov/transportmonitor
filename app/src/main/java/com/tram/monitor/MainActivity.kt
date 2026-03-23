package com.tram.monitor

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import java.text.SimpleDateFormat
import java.util.*

data class TramLine(val name: String, val times: List<Int>)

class TramAdapter : RecyclerView.Adapter<TramAdapter.VH>() {
    private var lines: List<TramLine> = emptyList()

    class VH(val root: View) : RecyclerView.ViewHolder(root) {
        val tv: TextView = root.findViewById(R.id.tvLine)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tram, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val line = lines[position]
        val times = if (line.times.isEmpty()) "No data"
                    else line.times.joinToString("  ·  ") { "${it} min" }
        holder.tv.text = "Tram ${line.name}:  $times"
    }

    override fun getItemCount() = lines.size

    fun update(newLines: List<TramLine>) {
        lines = newLines
        notifyDataSetChanged()
    }
}

class MainActivity : AppCompatActivity() {

    private val cookieStore = mutableListOf<Cookie>()
    private val client = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore.removeAll { c -> cookies.any { it.name == c.name } }
                cookieStore.addAll(cookies)
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieStore.toList()
        })
        .build()

    private var xsrf = ""
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var adapter1711: TramAdapter
    private lateinit var adapter1703: TramAdapter
    private var refreshRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        adapter1711 = TramAdapter()
        adapter1703 = TramAdapter()

        findViewById<RecyclerView>(R.id.rv1711).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = adapter1711
        }
        findViewById<RecyclerView>(R.id.rv1703).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = adapter1703
        }

        startClock()
        initSession()
    }

    private fun initSession() {
        android.util.Log.d("TRAM", "initSession called")
        val req = Request.Builder().url("https://www.sofiatraffic.bg/bg/").get().build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("TRAM", "initSession FAILED: ${e.message}")
                handler.postDelayed({ initSession() }, 5000)
            }
            override fun onResponse(call: Call, response: Response) {
                android.util.Log.d("TRAM", "initSession HTTP ${response.code}")
                android.util.Log.d("TRAM", "Cookies: ${response.headers("Set-Cookie")}")
                response.headers("Set-Cookie").forEach { header ->
                    if (header.contains("XSRF-TOKEN")) {
                        val raw = header.substringAfter("XSRF-TOKEN=").substringBefore(";")
                        xsrf = URLDecoder.decode(raw, "UTF-8")
                    }
                }
                android.util.Log.d("TRAM", "XSRF result: '$xsrf'")
                response.close()
                scheduleRefresh()
            }
        })
    }

    private fun fetchStop(stopId: String, adapter: TramAdapter) {
        if (xsrf.isEmpty()) { android.util.Log.e("TRAM", "fetchStop $stopId skipped - no xsrf"); return }
        android.util.Log.d("TRAM", "fetchStop $stopId xsrf=${xsrf.take(15)}")
        val json = """{"stop":"$stopId","type":2}"""
        val body = json.toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://www.sofiatraffic.bg/bg/trip/getVirtualTable")
            .post(body)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("X-XSRF-TOKEN", xsrf)
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("TRAM", "fetchStop $stopId FAILED: ${e.message}")
            }
            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val respBody = response.body?.string() ?: run { response.close(); return }
                android.util.Log.d("TRAM", "fetchStop $stopId HTTP=$code body=${respBody.take(300)}")
                response.close()
                if (code == 419 || code == 403) { xsrf = ""; handler.post { initSession() }; return }
                try {
                    val arr = JSONArray(respBody)
                    val lines = (0 until arr.length()).map { i ->
                        val obj = arr.getJSONObject(i)
                        val name = obj.getString("name")
                        val details = obj.getJSONArray("details")
                        val times = (0 until details.length()).map { j -> details.getJSONObject(j).getInt("t") }
                        TramLine(name, times)
                    }
                    android.util.Log.d("TRAM", "fetchStop $stopId parsed ${lines.size} lines")
                    handler.post { adapter.update(lines) }
                } catch (e: Exception) {
                    android.util.Log.e("TRAM", "fetchStop $stopId parse error: ${e.message}")
                    xsrf = ""
                    handler.post { initSession() }
                }
            }
        })
    }
    
    private fun scheduleRefresh() {
        refreshRunnable?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                fetchStop("1711", adapter1711)
                fetchStop("1703", adapter1703)
                handler.postDelayed(this, 30_000)
            }
        }
        refreshRunnable = r
        handler.post(r)
    }

    private fun startClock() {
        val tv = findViewById<TextView>(R.id.tvClock)
        val fmt = SimpleDateFormat("HH:mm:ss  EEEE, d MMMM", Locale.getDefault())
        val tick = object : Runnable {
            override fun run() {
                tv.text = "Sofia Trams  —  ${fmt.format(Date())}"
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
