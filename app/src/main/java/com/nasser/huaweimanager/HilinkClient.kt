package com.nasser.huaweimanager

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.regex.Pattern
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

data class TelemetryData(
    val status: String = "No Connect",
    val model: String = "—",
    val service: String = "—",
    val plmn: String = "—",
    val pci: String = "—",
    val cellId: String = "—",
    val tac: String = "—",
    val enbidCell: String = "—",
    val rssi: String = "—",
    val rsrp: String = "—",
    val rsrq: String = "—",
    val sinr: String = "—",
    val rssiPercent: Int = 0,
    val rssiColor: Int = 0,
    val rsrpColor: Int = 0,
    val rsrqColor: Int = 0,
    val sinrColor: Int = 0,
    val rsrpPercent: Int = 0,
    val rsrqPercent: Int = 0,
    val sinrPercent: Int = 0,
    val rssiRating: String = "Unavailable",
    val rsrpRating: String = "Unavailable",
    val rsrqRating: String = "Unavailable",
    val sinrRating: String = "Unavailable",
    val sessionTrafficText: String = "D : —   U : —   T : —  (—)",
    val totalTrafficText: String = "D : —   U : —   T : —  (—)",
    val speedDlBadge: String = "—",
    val speedDlBytes: String = " U : —",
    val speedUlBadge: String = "—",
    val speedUlBytes: String = " —",
    val usersText: String = "—",
    val bandWork: String = "—",
    val bandFreq: String = "—",
    val mode: String = "—",
    val netMode: String = "—",
    val lteBandHex: String = "—",
    val txPowerText: String = "4G-TxPower: Unavailable",
    val earfcnText: String = "4G-Earfcn: Unavailable",
    val mcsText: String = "4G-MCS: Unavailable",
    val tbsText: String = "4G-TBS: Unavailable",
    val freqBandwidthText: String = "4G-Bandwidth: Unavailable"
)

class HilinkClient(var gatewayIp: String = "http://192.168.8.1") {

    private val cookieStore = mutableMapOf<String, Cookie>()

    internal val client = OkHttpClient.Builder().cookieJar(object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) cookieStore[c.name] = c
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieStore.values.toList()
    })
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    var sessionToken: String? = null

    suspend fun fetchSessionInfo(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$gatewayIp/api/webserver/SesTokInfo").build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string() ?: return@withContext false
                val doc = parseXml(body)
                sessionToken = doc.getElementsByTagName("TokInfo").item(0)?.textContent
                val sesInfo = doc.getElementsByTagName("SesInfo").item(0)?.textContent
                if (!sesInfo.isNullOrEmpty() && sesInfo.contains("=")) {
                    val parts = sesInfo.split("=")
                    val host = gatewayIp.removePrefix("http://").removePrefix("https://").substringBefore("/").substringBefore(":")
                    val c = Cookie.Builder().domain(host).name(parts[0].trim()).value(parts[1].trim()).build()
                    cookieStore[c.name] = c
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun login(user: String, pass: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            fun sha256(input: String): String =
                MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            fun b64(s: String) = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)

            var state = ""
            var passType = ""
            client.newCall(Request.Builder().url("$gatewayIp/api/user/state-login").build()).execute().use { r ->
                r.body?.string()?.let { s ->
                    val d = parseXml(s)
                    state = d.getElementsByTagName("State").item(0)?.textContent ?: ""
                    passType = d.getElementsByTagName("password_type").item(0)?.textContent ?: ""
                }
            }
            if (state == "0") {
                fetchSessionInfo()
                return@withContext Pair(true, "Already logged in")
            }
            fetchSessionInfo()
            val token = sessionToken ?: ""
            val pwd = if (passType == "4") b64(sha256(user + b64(sha256(pass)) + token)) else b64(pass)
            val typeTag = if (passType == "4") "<password_type>4</password_type>" else ""
            val xml = """<?xml version="1.0" encoding="UTF-8"?><request><Username>$user</Username><Password>$pwd</Password>$typeTag</request>"""
            val req = Request.Builder()
                .url("$gatewayIp/api/user/login")
                .header("__RequestVerificationToken", token)
                .post(xml.toRequestBody("application/xml".toMediaTypeOrNull()))
                .build()
            client.newCall(req).execute().use { res ->
                val resp = res.body?.string() ?: ""
                res.header("__RequestVerificationToken")?.let { sessionToken = it }
                when {
                    resp.contains("<response>OK</response>") -> { fetchSessionInfo(); Pair(true, "Login Success") }
                    resp.contains("108006") -> Pair(false, "Wrong username or password")
                    resp.contains("108007") -> Pair(false, "Too many attempts, try again in 2 minutes")
                    resp.contains("108003") -> Pair(false, "Another user is logged in, wait 2 minutes or restart")
                    resp.contains("125003") -> Pair(false, "Token error, retry")
                    else -> Pair(false, "Login Failed")
                }
            }
        } catch (e: Exception) {
            Pair(false, "Connection Error: ${e.message}")
        }
    }

    suspend fun lockBands(bands: List<Int>): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            if (!fetchSessionInfo()) {
                return@withContext Pair(false, "Could not obtain router session token")
            }
            val token = sessionToken ?: return@withContext Pair(false, "Missing router session token")

            val hexMask = if (bands.isEmpty()) {
                "7FFFFFFFFFFFFFFF"
            } else {
                var mask = 0L
                for (b in bands) mask = mask or (1L shl (b - 1))
                java.lang.Long.toHexString(mask).uppercase()
            }

            // V10 NetMode POST payload including <networkOption></networkOption>
            val xml = """<?xml version="1.0" encoding="UTF-8"?><request><NetworkMode>03</NetworkMode><NetworkBand>3FFFFFFF</NetworkBand><LTEBand>$hexMask</LTEBand><networkOption></networkOption></request>"""
            val body = xml.toRequestBody("application/xml; charset=UTF-8".toMediaTypeOrNull())

            val req = Request.Builder()
                .url("$gatewayIp/api/net/net-mode")
                .header("__RequestVerificationToken", token)
                .header("X-Requested-With", "XMLHttpRequest")
                .post(body)
                .build()

            client.newCall(req).execute().use { res ->
                val resp = res.body?.string() ?: ""
                res.header("__RequestVerificationToken")?.let { sessionToken = it }
                if (resp.contains("<response>OK</response>")) {
                    Pair(true, "Bands Locked: $hexMask")
                } else {
                    Pair(false, "Lock Denied (Login required)")
                }
            }
        } catch (e: Exception) {
            Pair(false, "Request Error: ${e.message}")
        }
    }

    suspend fun setNetworkMode(mode: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!fetchSessionInfo()) return@withContext false
            val token = sessionToken ?: return@withContext false
            val xml = """<?xml version="1.0" encoding="UTF-8"?><request><NetworkMode>$mode</NetworkMode><NetworkBand>3FFFFFFF</NetworkBand><LTEBand>7FFFFFFFFFFFFFFF</LTEBand><networkOption></networkOption></request>"""
            val body = xml.toRequestBody("application/xml; charset=UTF-8".toMediaTypeOrNull())
            val req = Request.Builder()
                .url("$gatewayIp/api/net/net-mode")
                .header("__RequestVerificationToken", token)
                .post(body)
                .build()
            client.newCall(req).execute().use { it.body?.string()?.contains("<response>OK</response>") == true }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getTelemetry(): TelemetryData = withContext(Dispatchers.IO) {
        try {
            fun getXml(endpoint: String): Element? {
                val req = Request.Builder()
                    .url("$gatewayIp$endpoint")
                    .header("__RequestVerificationToken", sessionToken ?: "")
                    .build()
                return client.newCall(req).execute().use {
                    it.header("__RequestVerificationToken")?.let { t -> sessionToken = t }
                    it.body?.string()?.let { s -> parseXml(s) }
                }
            }

            val sig = getXml("/api/device/signal")
            if (sig == null || sig.tagName == "error") return@withContext TelemetryData()
            val statusDoc = getXml("/api/monitoring/status")
            val traffic = getXml("/api/monitoring/traffic-statistics")
            val devInfo = getXml("/api/device/information")
            val plmnDoc = getXml("/api/net/current-plmn")
            val netModeDoc = getXml("/api/net/net-mode")

            fun tag(el: Element?, name: String): String {
                val txt = el?.getElementsByTagName(name)?.item(0)?.textContent ?: ""
                return if (txt.isBlank()) "0" else txt
            }

            fun cleanMetric(raw: String, unit: String): Int {
                return raw.replace(unit, "", true)
                    .replace("<", "").replace("=", "").replace(">", "")
                    .replace("Unknown", "0").replace("NaN", "0")
                    .trim().toIntOrNull() ?: 0
            }

            fun signalMetric(name: String, unit: String): Int? {
                val raw = tag(sig, name)
                if (sig == null || raw == "0" ||
                    raw.contains("Unknown", ignoreCase = true) ||
                    raw.contains("NaN", ignoreCase = true)
                ) return null

                return raw.replace(unit, "", ignoreCase = true)
                    .replace("<", "").replace("=", "").replace(">", "")
                    .trim().toIntOrNull()
            }

            val rawRssi = signalMetric("rssi", "dBm")
            val rawRsrp = signalMetric("rsrp", "dBm")
            val rawRsrq = signalMetric("rsrq", "dB")
            val rawSinr = signalMetric("sinr", "dB")

            // Thresholds aligned with main.js: colorrssi, colorrsrp, colorrsrq, colorsinr
            val rssiR = rate(rawRssi, -124, -110, -95, -85, -75, -51)
            val rssiPct = rssiR.pct
            val rsrpR = rate(rawRsrp, -124, -110, -95, -85, -75, -51)
            val rsrpPct = rsrpR.pct
            val rsrqR = rate(rawRsrq, -30, -20, -15, -10, -6, 0)
            val rsrqPct = rsrqR.pct
            val sinrR = rate(rawSinr, -15, 3, 10, 15, 22, 30)
            val sinrPct = sinrR.pct

            val dlRateBytes = tag(traffic, "CurrentDownloadRate").toLongOrNull() ?: 0L
            val ulRateBytes = tag(traffic, "CurrentUploadRate").toLongOrNull() ?: 0L

            val curDl = tag(traffic, "CurrentDownload").toLongOrNull() ?: 0L
            val curUl = tag(traffic, "CurrentUpload").toLongOrNull() ?: 0L
            val curTime = tag(traffic, "CurrentConnectTime").toLongOrNull() ?: 0L

            val totDl = tag(traffic, "TotalDownload").toLongOrNull() ?: 0L
            val totUl = tag(traffic, "TotalUpload").toLongOrNull() ?: 0L
            val totTime = tag(traffic, "TotalConnectTime").toLongOrNull() ?: 0L

            // V10 Cell & eNBID calculation (main.js line 1)
            val cellIdStr = tag(sig, "cell_id")
            val cidNum = cellIdStr.toLongOrNull() ?: 0L
            val hex = java.lang.Long.toHexString(cidNum)
            val hex2 = if (hex.length > 2) hex.substring(0, hex.length - 2) else ""
            val enbidStr = if (hex2.isNotEmpty()) java.lang.Long.parseLong(hex2, 16).toString() else "NaN"
            val hex3 = if (hex.length >= 2) hex.substring(hex.length - 2) else hex
            val shortCid = if (hex3.isNotEmpty()) java.lang.Long.parseLong(hex3, 16).toString() else cellIdStr
            val enbidCell = "$enbidStr/$shortCid"

            val modelName = tag(devInfo, "DeviceName").takeIf { it != "0" } ?: "—"
            val carrierName = tag(plmnDoc, "FullName").takeIf { it != "0" } ?: "—"
            val plmnNum = tag(plmnDoc, "Numeric").takeIf { it != "0" } ?: "—"

            val curWifi = tag(statusDoc, "CurrentWifiUser").takeIf { it != "0" } ?: "—"
            val totWifi = tag(statusDoc, "TotalWifiUser").takeIf { it != "0" } ?: "—"

            // V10 TxPower substitution (main.js line 596)
            var rawTx = tag(sig, "txpower").trim()
            val cleanTxPower = if (rawTx.isNotBlank() && rawTx != "0") {
                rawTx.replace("PPusch:", "PPusch: ")
                    .replace("PPucch:", "PPucch: ")
                    .replace("PSrs:", "PSrs: ")
                    .replace("PPrach:", "PPrach: ")
                    .replaceFirst("dBm", ", ")
                    .replaceFirst("dBm", ", ")
                    .replaceFirst("dBm", ", ")
                    .replaceFirst("dBm", "")
            } else {
                "—"
            }

            // V10 Earfcn substitution (main.js line 187)
            val rawEarfcn = tag(sig, "earfcn").trim()
            val cleanEarfcn = if (rawEarfcn.isNotBlank() && rawEarfcn != "0") {
                rawEarfcn.replace("DL:", "DL: ").replace("UL:", " UL: ")
            } else {
                "—"
            }

            // V10 MCS slice extraction (main.js lines 496-498)
            val dlmcs = tag(sig, "dl_mcs")
            var dlmcs0 = "—"
            var dlmcs1 = "—"
            if (dlmcs.contains("Code0:") && dlmcs.contains("Code1:")) {
                try {
                    dlmcs0 = dlmcs.substringAfter("Code0:").substringBefore(" ").trim()
                    dlmcs1 = dlmcs.substringAfter("Code1:").trim()
                } catch (e: Exception) {
                    dlmcs0 = "—"
                    dlmcs1 = "—"
                }
            }
            val ulMcsRaw = tag(sig, "ul_mcs")
            val ulMcs = if (ulMcsRaw.contains(":")) {
                ulMcsRaw.substringAfter(":").trim().takeIf { it.isNotEmpty() && it != "0" } ?: "—"
            } else {
                "—"
            }

            // TBS extraction
            val tbsDlRaw = tag(sig, "dl_tbs")
            val tbsUlRaw = tag(sig, "ul_tbs")
            val tbs0 = if (tbsDlRaw.contains(":")) {
                tbsDlRaw.substringAfter(":").trim().takeIf { it.isNotEmpty() && it != "0" } ?: "—"
            } else {
                "—"
            }
            val tbsUl = if (tbsUlRaw.contains(":")) {
                tbsUlRaw.substringAfter(":").trim().takeIf { it.isNotEmpty() && it != "0" } ?: "—"
            } else {
                "—"
            }

            // Band to Frequency map matching V10
            val bandNum = tag(sig, "band").replace("B", "", true)
            val freqMap = mapOf(
                "1" to "2100", "2" to "1900", "3" to "1800", "4" to "1700",
                "5" to "850", "7" to "2600", "8" to "900", "20" to "800",
                "28" to "700", "38" to "2600", "40" to "2300", "41" to "2500", "42" to "3500"
            )
            val freqName = freqMap[bandNum] ?: "—"

            val netModeStr = tag(netModeDoc, "NetworkMode").takeIf { it != "0" } ?: "—"
            val lteBandHexStr = tag(netModeDoc, "LTEBand").takeIf { it != "0" } ?: "—"

            TelemetryData(
                status = "Connected",
                model = modelName,
                service = carrierName,
                plmn = plmnNum,
                pci = tag(sig, "pci"),
                cellId = cellIdStr,
                tac = tag(sig, "tac"),
                enbidCell = enbidCell,
                rssi = rawRssi?.let { "$it dBm" } ?: "—",
                rsrp = rawRsrp?.let { "$it dBm" } ?: "—",
                rsrq = rawRsrq?.let { "$it dB" } ?: "—",
                sinr = rawSinr?.let { "$it dB" } ?: "—",
                rssiPercent = rssiPct,
                rssiColor = rssiR.color,
                rsrpPercent = rsrpPct,
                rsrpColor = rsrpR.color,
                rsrqPercent = rsrqPct,
                rsrqColor = rsrqR.color,
                sinrPercent = sinrPct,
                sinrColor = sinrR.color,
                rssiRating = if (rawRssi == null) "Unavailable" else "$rssiPct% ${rssiR.label}",
                rsrpRating = if (rawRsrp == null) "Unavailable" else "$rsrpPct% ${rsrpR.label}",
                rsrqRating = if (rawRsrq == null) "Unavailable" else "$rsrqPct% ${rsrqR.label}",
                sinrRating = if (rawSinr == null) "Unavailable" else "$sinrPct% ${sinrR.label}",
                sessionTrafficText = "D : ${formatBytes(curDl)}   U : ${formatBytes(curUl)}   T : ${formatBytes(curDl + curUl)}  (${formatDuration(curTime)})",
                totalTrafficText = "D : ${formatBytes(totDl)}   U : ${formatBytes(totUl)}   T : ${formatBytes(totDl + totUl)}  (${formatDaysTime(totTime)})",
                speedDlBadge = formatSpeedBits(dlRateBytes),
                speedDlBytes = " (${formatSpeedBytes(dlRateBytes)})    U : ",
                speedUlBadge = formatSpeedBits(ulRateBytes),
                speedUlBytes = " (${formatSpeedBytes(ulRateBytes)})",
                usersText = "$curWifi/$totWifi",
                bandWork = "B$bandNum",
                bandFreq = freqName,
                mode = tag(sig, "mode").takeIf { it != "0" } ?: "—",
                netMode = netModeStr,
                lteBandHex = lteBandHexStr,
                txPowerText = "4G-TxPower: $cleanTxPower",
                earfcnText = "4G-Earfcn: $cleanEarfcn",
                mcsText = "4G-MCS: DL0:$dlmcs0  DL1:$dlmcs1  UL:$ulMcs",
                tbsText = "4G-TBS: DL0:$tbs0  UL:$tbsUl",
                freqBandwidthText = "4G-Bandwidth: DL: ${tag(sig, "dlbandwidth").takeIf { it != "0" } ?: "—"} UL: ${tag(sig, "ulbandwidth").takeIf { it != "0" } ?: "—"}"
            )
        } catch (e: Exception) {
            TelemetryData()
        }
    }

    suspend fun controlDevice(command: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!fetchSessionInfo()) return@withContext false
            val xml = """<?xml version="1.0" encoding="UTF-8"?><request><Control>$command</Control></request>"""
            val body = xml.toRequestBody("application/xml".toMediaTypeOrNull())
            val req = Request.Builder()
                .url("$gatewayIp/api/device/control")
                .header("__RequestVerificationToken", sessionToken ?: "")
                .post(body)
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    private fun formatSpeedBits(bytesPerSec: Long): String {
        val bits = bytesPerSec * 8.0
        return when {
            bits >= 1_000_000 -> String.format(Locale.US, "%.2fMbps", bits / 1_000_000.0)
            bits >= 1_000 -> String.format(Locale.US, "%.2fKbps", bits / 1_000.0)
            else -> "${bits.toLong()}bps"
        }
    }

    private fun formatSpeedBytes(bytesPerSec: Long): String {
        val b = bytesPerSec.toDouble()
        return when {
            b >= 1024 * 1024 -> String.format(Locale.US, "%.2fMB/s", b / (1024 * 1024))
            b >= 1024 -> String.format(Locale.US, "%.2fKB/s", b / 1024)
            else -> "${bytesPerSec}B/s"
        }
    }

    private fun formatBytes(bytes: Long): String {
        val b = bytes.toDouble()
        return when {
            b >= 1024.0 * 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2fTB", b / (1024.0 * 1024 * 1024 * 1024))
            b >= 1024.0 * 1024 * 1024 -> String.format(Locale.US, "%.2fGB", b / (1024.0 * 1024 * 1024))
            b >= 1024.0 * 1024 -> String.format(Locale.US, "%.2fMB", b / (1024.0 * 1024))
            b >= 1024.0 -> String.format(Locale.US, "%.2fKB", b / 1024.0)
            else -> "${bytes}B"
        }
    }

    private fun formatDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    private fun formatDaysTime(sec: Long): String {
        val days = sec / 86400
        val rem = sec % 86400
        val h = rem / 3600
        val m = (rem % 3600) / 60
        val s = rem % 60
        return if (days > 0) "$days Days $h:$m:$s" else "%02d:%02d:%02d".format(h, m, s)
    }

    private fun parseXml(content: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)))
        return doc.documentElement
    }
}
