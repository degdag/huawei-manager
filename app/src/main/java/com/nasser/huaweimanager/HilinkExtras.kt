package com.nasser.huaweimanager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

data class Rating(val pct: Int, val label: String, val color: Int)
data class HostInfo(val name: String, val ip: String, val mac: String, val seconds: Long)

/** Same thresholds/colors as the original main.js (colorrssi/colorrsrp/colorrsrq/colorsinr). */
fun rate(cur: Int, l0: Int, l1: Int, l2: Int, l3: Int, l4: Int, l5: Int): Rating {
    val c = cur.coerceIn(l0, l5)
    val pct = minOf((c - l0) * 100 / (l5 - l0) + 1, 100)
    val (label, color) = when {
        c >= l5 -> "Excellent" to 0xFF008013.toInt()
        c >= l4 -> "V.Good" to 0xFF00BB1C.toInt()
        c >= l3 -> "Good" to 0xFFE28F00.toInt()
        c >= l2 -> "Middle" to 0xFFE0AC00.toInt()
        c >= l1 -> "Weak" to 0xFFFF7300.toInt()
        else -> "Bad" to 0xFFAA0000.toInt()
    }
    return Rating(pct, label, color)
}

private fun Element?.txt(name: String): String =
    this?.getElementsByTagName(name)?.item(0)?.textContent?.trim() ?: ""

fun fmtDur(sec: Long): String {
    val d = sec / 86400; val h = sec % 86400 / 3600; val m = sec % 3600 / 60; val s = sec % 60
    return "$d Day %d:%02d:%02d".format(h, m, s)
}

private fun fmtBytes(b: Long): String {
    val d = b.toDouble()
    return when {
        d >= 1099511627776.0 -> String.format(Locale.US, "%.2fTB", d / 1099511627776.0)
        d >= 1073741824.0 -> String.format(Locale.US, "%.2fGB", d / 1073741824.0)
        d >= 1048576.0 -> String.format(Locale.US, "%.2fMB", d / 1048576.0)
        else -> String.format(Locale.US, "%.2fKB", d / 1024.0)
    }
}

suspend fun HilinkClient.getDoc(path: String): Element? = withContext(Dispatchers.IO) {
    try {
        val req = Request.Builder().url("$gatewayIp$path")
            .header("__RequestVerificationToken", sessionToken ?: "").build()
        client.newCall(req).execute().use { res ->
            val s = res.body?.string() ?: return@use null
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(ByteArrayInputStream(s.toByteArray(Charsets.UTF_8))).documentElement
            if (doc.tagName == "error") null else doc
        }
    } catch (e: Exception) { null }
}

suspend fun HilinkClient.post(path: String, xml: String): Boolean = withContext(Dispatchers.IO) {
    try {
        fetchSessionInfo()
        val body = """<?xml version="1.0" encoding="UTF-8"?>$xml"""
            .toRequestBody("application/xml; charset=UTF-8".toMediaTypeOrNull())
        val req = Request.Builder().url("$gatewayIp$path")
            .header("__RequestVerificationToken", sessionToken ?: "")
            .post(body).build()
        client.newCall(req).execute().use { it.body?.string()?.contains("<response>OK</response>") == true }
    } catch (e: Exception) { false }
}

suspend fun HilinkClient.restartRouter() = post("/api/device/control", "<request><Control>1</Control></request>")
suspend fun HilinkClient.powerOffRouter() = post("/api/device/control", "<request><Control>4</Control></request>")
suspend fun HilinkClient.clearTraffic() = post("/api/monitoring/clear-traffic", "<request><ClearTraffic>1</ClearTraffic></request>")
suspend fun HilinkClient.setDataSwitch(on: Boolean) =
    post("/api/dialup/mobile-dataswitch", "<request><dataswitch>${if (on) 1 else 0}</dataswitch></request>")

suspend fun HilinkClient.refreshIp(): Boolean {
    val off = setDataSwitch(false)
    delay(3000)
    return setDataSwitch(true) && off
}

/** Original 3G lock: NetworkMode 02 + per-band NetworkBand mask (B1=400000, B8=2000000000000). */
suspend fun HilinkClient.lock3G(b1: Boolean, b8: Boolean): Pair<Boolean, String> {
    var mask = 0L
    if (b1) mask = mask or 0x400000L
    if (b8) mask = mask or 0x2000000000000L
    val hex = java.lang.Long.toHexString(mask).uppercase()
    val ok = post("/api/net/net-mode",
        "<request><NetworkMode>02</NetworkMode><NetworkBand>$hex</NetworkBand><LTEBand>7FFFFFFFFFFFFFFF</LTEBand><networkOption></networkOption></request>")
    return Pair(ok, if (ok) "3G locked: $hex" else "3G lock failed")
}

suspend fun HilinkClient.antennaLabel(): String {
    val d = getDoc("/api/device/antenna_type") ?: return "Unknown"
    val a = d.txt("antenna1type"); val b = d.txt("antenna2type")
    return when {
        a == "0" && b == "0" -> "Internal"
        a == "1" && b == "1" -> "External"
        a.isNotEmpty() && b.isNotEmpty() -> "Mixed"
        else -> "Unknown"
    }
}

/** Newer firmware: antenna_set_type (0 Auto,1 External,2 Internal). Older: antenna_settings (3 Auto,1 External,0 Internal). */
suspend fun HilinkClient.setAntenna(mode: String): Boolean {
    val st = getDoc("/api/device/antenna_set_type").txt("antennasettype")
    return if (st.isNotEmpty()) {
        val v = when (mode) { "Auto" -> 0; "External" -> 1; else -> 2 }
        post("/api/device/antenna_set_type", "<request><antennasettype>$v</antennasettype></request>")
    } else {
        val v = when (mode) { "Auto" -> 3; "External" -> 1; else -> 0 }
        post("/api/device/antenna_settings", "<request><antenna_type>$v</antenna_type></request>")
    }
}

suspend fun HilinkClient.hosts(): List<HostInfo> {
    val doc = getDoc("/api/wlan/host-list") ?: return emptyList()
    val n = doc.getElementsByTagName("Host")
    return (0 until n.length).map { i ->
        val h = n.item(i) as Element
        HostInfo(h.txt("HostName"), h.txt("IpAddress").substringBefore(';'), h.txt("MacAddress"),
            h.txt("AssociatedTime").toLongOrNull() ?: 0L)
    }
}

suspend fun HilinkClient.routerStats(): List<String> {
    val tr = getDoc("/api/monitoring/traffic-statistics")
    val ms = getDoc("/api/monitoring/month_statistics")
    val totSec = tr.txt("TotalConnectTime").toLongOrNull() ?: 0L
    val totData = (tr.txt("TotalDownload").toLongOrNull() ?: 0L) + (tr.txt("TotalUpload").toLongOrNull() ?: 0L)
    val monSec = ms.txt("MonthDuration").toLongOrNull() ?: 0L
    val monData = (ms.txt("CurrentMonthDownload").toLongOrNull() ?: 0L) + (ms.txt("CurrentMonthUpload").toLongOrNull() ?: 0L)
    return listOf("Total Router Uptime : ${fmtDur(totSec)}", "Total Size Of Data : ${fmtBytes(totData)}",
        "Monthly Uptime : ${fmtDur(monSec)}", "Monthly Total Data : ${fmtBytes(monData)}")
}
