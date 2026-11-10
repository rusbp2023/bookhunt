package hu.bookhunt

import android.app.*
import android.content.*
import android.net.Uri
import org.json.*
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.*

// ---- Itt add meg a figyelt könyveket ----
data class Book(val name: String, val query: String, val year: String, val isbns: List<String>)
val BOOKS = listOf(
    Book("Szolzsenyicin: A GULAG szigetvilág (Európa, 1993)", "gulag szigetvilág", "1993",
        listOf("9630754320", "9789630754323"))
)
// -----------------------------------------

data class Hit(val id: String, val book: String, val title: String, val price: Int, val url: String, val verdict: String)

const val UA = "BookHunt/0.1 (personal, non-commercial)"
private val idRe = Regex("_(\\d{6,})\\.htm$")

fun norm(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

fun priceText(p: Int) = if (p < 0) "ár nincs megadva" else "%,d Ft".format(Locale.US, p).replace(',', ' ')

object Store {
    private fun p(c: Context) = c.getSharedPreferences("bookhunt", Context.MODE_PRIVATE)
    fun seen(c: Context): MutableSet<String> = p(c).getStringSet("seen", emptySet())!!.toMutableSet()
    fun status(c: Context): String = p(c).getString("status", "Még nem volt ellenőrzés") ?: ""
    fun setStatus(c: Context, s: String) = p(c).edit().putString("status", s).apply()
    fun hits(c: Context): MutableList<Hit> {
        val a = JSONArray(p(c).getString("hits", "[]"))
        return (0 until a.length()).map { val o = a.getJSONObject(it)
            Hit(o.getString("id"), o.getString("book"), o.getString("title"), o.getInt("price"), o.getString("url"), o.getString("verdict")) }.toMutableList()
    }
    fun save(c: Context, seen: Set<String>, hits: List<Hit>) {
        val a = JSONArray(); hits.forEach { a.put(JSONObject().put("id", it.id).put("book", it.book).put("title", it.title)
            .put("price", it.price).put("url", it.url).put("verdict", it.verdict)) }
        p(c).edit().putStringSet("seen", seen).putString("hits", a.toString()).apply()
    }
}

fun classify(title: String, desc: String, b: Book): String {
    val all = norm("$title $desc"); val d = norm(desc)
    val digits = all.replace(Regex("[^0-9]"), "")
    if (b.isbns.any { digits.contains(it) } || Regex("\\b${b.year}\\b").containsMatchIn(all)) return "match"
    if (Regex("\\b(19[7-9]\\d|20[0-3]\\d)\\b").containsMatchIn(d)) return "other"
    return "maybe"
}

fun runCheck(c: Context): List<Hit> {
    val seen = Store.seen(c); val hits = Store.hits(c); val fresh = mutableListOf<Hit>()
    var error: String? = null
    for (b in BOOKS) {
        try {
            val q = URLEncoder.encode(b.query, "UTF-8")
            val doc = Jsoup.connect("https://www.jofogas.hu/magyarorszag?q=$q").userAgent(UA).timeout(20000).get()
            val urls = doc.select("a[href]").map { it.absUrl("href") }
                .filter { it.startsWith("https://www.jofogas.hu/") && idRe.containsMatchIn(it.substringBefore('?')) }
                .distinctBy { idRe.find(it.substringBefore('?'))!!.groupValues[1] }.take(30)
            val words = norm(b.query).split(" ")
            for (u in urls) {
                val id = idRe.find(u.substringBefore('?'))!!.groupValues[1]
                if (id in seen) continue
                try {
                    Thread.sleep(2000)
                    val d = Jsoup.connect(u).userAgent(UA).timeout(20000).get()
                    val title = d.selectFirst("h1")?.text() ?: ""
                    val text = d.body().text()
                    val end = text.indexOf("Hirdetés azonosító").let { if (it < 0) text.length else it }
                    val st = text.indexOf("Tulajdonságok").let { if (it < 0 || it > end) maxOf(0, end - 1500) else it }
                    val desc = text.substring(st, end)
                    seen.add(id)
                    if (!words.all { norm("$title $desc").contains(it) }) continue
                    val v = classify(title, desc, b)
                    if (v == "other") continue
                    val price = d.select("h2").map { it.text() }.firstOrNull { it.contains("Ft") }
                        ?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: -1
                    val h = Hit(id, b.name, title, price, u, v); hits.add(h); fresh.add(h)
                } catch (e: Exception) { error = "hirdetés: ${e.message}" }
            }
        } catch (e: Exception) { error = "keresés: ${e.message}" }
    }
    Store.save(c, seen, hits)
    val now = SimpleDateFormat("MM.dd. HH:mm", Locale.US).format(Date())
    Store.setStatus(c, if (error == null) "Utolsó sikeres ellenőrzés: $now" else "Hiba ($now): $error")
    return fresh
}

fun notifyHit(c: Context, h: Hit) {
    val nm = c.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("hits", "Új találatok", NotificationManager.IMPORTANCE_HIGH))
    val pi = PendingIntent.getActivity(c, h.id.hashCode(), Intent(Intent.ACTION_VIEW, Uri.parse(h.url)), PendingIntent.FLAG_IMMUTABLE)
    val n = Notification.Builder(c, "hits").setSmallIcon(android.R.drawable.ic_menu_search)
        .setContentTitle("Új találat: ${h.title}")
        .setContentText(priceText(h.price) + if (h.verdict == "maybe") " (a kiadás nem derül ki)" else "")
        .setContentIntent(pi).setAutoCancel(true).build()
    nm.notify(h.id.hashCode(), n)
}
