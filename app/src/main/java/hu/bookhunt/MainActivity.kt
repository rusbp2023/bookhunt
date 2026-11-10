package hu.bookhunt

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.Typeface
import android.widget.*
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var root: LinearLayout

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val pad = (16 * resources.displayMetrics.density).toInt()
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        setContentView(ScrollView(this).apply { addView(root) })
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("check", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CheckWorker>(60, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        render()
    }

    private fun tv(t: String, size: Float, bold: Boolean = false, onClick: (() -> Unit)? = null) = TextView(this).apply {
        text = t; textSize = size; setPadding(0, 12, 0, 12)
        if (bold) setTypeface(null, Typeface.BOLD)
        if (onClick != null) setOnClickListener { onClick() }
    }

    private fun render() {
        root.removeAllViews()
        root.addView(tv("Könyvfigyelő", 24f, true))
        root.addView(tv(Store.status(this), 12f))
        root.addView(Button(this).apply {
            text = "Frissítés most"
            setOnClickListener {
                isEnabled = false; text = "Ellenőrzés..."
                Thread { runCheck(this@MainActivity).forEach { notifyHit(this@MainActivity, it) }
                    runOnUiThread { render() } }.start()
            }
        })
        val hits = Store.hits(this)
        for (b in BOOKS) {
            root.addView(tv(b.name, 16f, true))
            val mine = hits.filter { it.book == b.name }.sortedBy { if (it.price < 0) Int.MAX_VALUE else it.price }
            if (mine.isEmpty()) root.addView(tv("Jófogás — nincs", 15f))
            mine.forEach { h ->
                root.addView(tv("${priceText(h.price)} — Jófogás — ${h.title}" + if (h.verdict == "maybe") " (?)" else "", 15f) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(h.url)))
                })
            }
        }
    }
}
