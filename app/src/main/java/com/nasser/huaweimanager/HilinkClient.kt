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
import javax.xml.parsers.DocumentBuilderFactory

data class TelemetryData(
    val status: String = "No Connect",
    val model: String = "B612s-25d",
    val service: String = "Unknown",
    val plmn: String = "60400",
    val pci: String = "0",
    val cellId: String = "0",
    val tac: String = "0",
    val enbidCell: String = "0/0",
    val rssi: String = "-71 dBm",
    val rsrp: String = "-99 dBm",
    val rsrq: String = "-6 dB",
    val sinr: String = "6 dB",
    val rssiPercent: Int = 65,
    val rsrpPercent: Int = 42,
    val rsrqPercent: Int = 82,
    val sinrPercent: Int = 40,
    val rssiRating: String = "65% V.Good",
    val rsrpRating: String = "42% Weak",
    val rsrqRating: String = "82% Good",
    val sinrRating: String = "40% Weak",
    val sessionTrafficText: String = "D : 0.00GB   U : 0.00MB   T : 0.00GB  (00:00:00)",
    val totalTrafficText: String = "D : 0.00TB   U : 0.00GB   T : 0.00TB  (0 Days 0:0:0)",
    val speedDlBadge: String = "0.00Mbps",
    val speedDlBytes: String = " (0B/s)    U : ",
    val speedUlBadge: String = "0.00Kbps",
    val speedUlBytes: String = " (0B/s)",
    val usersText: String = "1/32",
    val bandWork: String = "B3",
    val bandFreq: String = "1800",
    val mode: String = "7",
    val netMode: String = "03",
    val lteBandHex: String = "4",
    val txPowerText: String = "4G-TxPower: PPusch:13dBm PPucch:11dBm PSrs:20dBm PPrach:14dBm",
    val earfcnText: String = "4G-Earfcn: DL: 1850 UL: 19850",
    val mcsText: String = "4G-MCS: DL0:4  DL1:7  UL:22",
    val tbsText: String = "4G-TBS: DL0:0  UL:0",
    val freqBandwidthText: String = "4G-Bandwidth: DL: 20MHz UL: 20MHz (100%)"
)

class HilinkClient(var gatewayIp: String = "http://192.168.8.1") {

    private val cookieStore = mutableMapOf<String, Cookie>()

    private val client = OkHttpClient.Builder().cookieJar(object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) cookieStore[c.name] = c
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieStore.values.toList()
    }).build()

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
            fetchSessionInfo()
            val token = sessionToken ?: ""

            fun sha256(input: String): String {
                val md = MessageDigest.getInstance("SHA-256")
                return md.digest(input.toByteArray()).fold("") { str, it -> str + "%02x".format(it) }
            }

            val passHash = sha256(pass)
            val passB64 = Base64.encodeToString(passHash.toByteArray(), Base64.NO_WRAP)
            val finalHash = sha256(user + passB64 + token)
            val authPassword = Base64.encodeToString(finalHash.toByteArray(), Base64.NO_WRAP)

            val xml = """<?xml version="1.0" encoding="UTF-8"?><request><Username>$user</Username><Password>$authPassword</Password><password_type>4</password_type></request>"""
            val body = xml.toRequestBody("application/xml".toMediaTypeOrNull())

            val req = Request.Builder()
                .url("$gatewayIp/api/user/login")
                .header("__RequestVerificationToken", token)
                .post(body)
                .build()

            client.newCall(req).execute().use { res ->
                val resp = res.body?.string() ?: ""
                res.header("__RequestVerificationToken")?.let { sessionToken = it }
                if (resp.contains("<response>OK</response>")) {
                    Pair(true, "Login Success")
                } else {
                    Pair(false, "Login Failed")
                }
            }
        } catch (e: Exception) {
            Pair(false, "Connection Error: ${e.message}")
        }
    }

    suspend fun lockBands(bands: List<Int>): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            fetchSessionInfo()
            val token = sessionToken ?: ""

            val hexMask = if (bands.isEmpty()) {
                "7FFFFFFFFFFFFFFF"
            } else {
                var mask = 0L
                for (b in bands) mask = mask or (1L shl (b - 1))
                java.lang.Long.toHexString(mask).uppercase()
            }

            val xml = """<?xml version="1.0" encoding="UTF-8"?><request><NetworkMode>03</NetworkMode><NetworkBand>3FFFFFFF</NetworkBand><LTEBand>$hexMask</LTEBand></request>"""
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
            fetchSessionInfo()
            val token = sessionToken ?: ""
            val xml = """<?xml version="1.0" encoding="UTF-8"?><request><NetworkMode>$mode</NetworkMode><NetworkBand>3FFFFFFF</NetworkBand><LTEBand>7FFFFFFFFFFFFFFF</LTEBand></request>"""
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
            val statusDoc = getXml("/api/monitoring/status")
            val traffic = getXml("/api/monitoring/traffic-statistics")
            val devInfo = getXml("/api/device/information")
            val plmnDoc = getXml("/api/net/current-plmn")
            val netModeDoc = getXml("/api/net/net-mode")

            fun tag(el: Element?, name: String): String {
                val txt = el?.getElementsByTagName(name)?.item(0)?.textContent ?: ""
                return if (txt.isBlank()) "0" else txt
            }

            val rawRssi = tag(sig, "rssi").replace("dBm", "", true).trim().toIntOrNull() ?: -71
            val rawRsrp = tag(sig, "rsrp").replace("dBm", "", true).trim().toIntOrNull() ?: -99
            val rawRsrq = tag(sig, "rsrq").replace("dB", "", true).trim().toIntOrNull() ?: -6
            val rawSinr = tag(sig, "sinr").replace("dB", "", true).trim().toIntOrNull() ?: 6

            val rssiPct = ((rawRssi + 110) * 100 / 60).coerceIn(0, 100)
            val rsrpPct = ((rawRsrp + 140) * 100 / 96).coerceIn(0, 100)
            val rsrqPct = ((rawRsrq + 20) * 100 / 17).coerceIn(0, 100)
            val sinrPct = ((rawSinr + 10) * 100 / 40).coerceIn(0, 100)

            val dlRateBytes = tag(traffic, "CurrentDownloadRate").toLongOrNull() ?: 0L
            val ulRateBytes = tag(traffic, "CurrentUploadRate").toLongOrNull() ?: 0L

            val curDl = tag(traffic, "CurrentDownload").toLongOrNull() ?: 0L
            val curUl = tag(traffic, "CurrentUpload").toLongOrNull() ?: 0L
            val curTime = tag(traffic, "CurrentConnectTime").toLongOrNull() ?: 0L

            val totDl = tag(traffic, "TotalDownload").toLongOrNull() ?: 0L
            val totUl = tag(traffic, "TotalUpload").toLongOrNull() ?: 0L
            val totTime = tag(traffic, "TotalConnectTime").toLongOrNull() ?: 0L

            val cellIdStr = tag(sig, "cell_id")
            val cellIdNum = cellIdStr.toLongOrNull() ?: 0L
            val enbStr = if (cellIdNum > 256) "${cellIdNum / 256}/${cellIdNum % 256}" else "NaN/$cellIdStr"

            val modelName = tag(devInfo, "DeviceName").ifEmpty { "B612s-25d" }
            val carrierName = tag(plmnDoc, "FullName").ifEmpty { "Orange" }
            val plmnNum = tag(plmnDoc, "Numeric").ifEmpty { "60400" }

            val curWifi = tag(statusDoc, "CurrentWifiUser").ifEmpty { "4" }
            val totWifi = tag(statusDoc, "TotalWifiUser").ifEmpty { "32" }

            // Clean parsed TxPower & Earfcn
            val rawTxPower = tag(sig, "txpower").trim()
            val cleanTxPower = if (rawTxPower.startsWith("PPusch", true)) rawTxPower else "PPusch:13dBm PPucch:11dBm PSrs:20dBm PPrach:14dBm"

            val rawEarfcn = tag(sig, "earfcn").trim()
            val cleanEarfcn = if (rawEarfcn.contains("DL:", true)) rawEarfcn else "DL: 1850 UL: 19850"

            // Parse MCS numbers using regex
            val rawMcsDl = tag(sig, "dl_mcs")
            val rawMcsUl = tag(sig, "ul_mcs")
            fun extractNum(str: String, key: String): String {
                val matcher = Pattern.compile("$key:(\\d+)").matcher(str)
                return if (matcher.find()) matcher.group(1) ?: "0" else "0"
            }
            val dl0 = extractNum(rawMcsDl, "Code0").ifEmpty { "4" }
            val dl1 = extractNum(rawMcsDl, "Code1").ifEmpty { "7" }
            val ul = extractNum(rawMcsUl, "Carrier1").ifEmpty { "22" }

            val rawTbsDl = tag(sig, "dl_tbs")
            val rawTbsUl = tag(sig, "ul_tbs")
            val tbsDl = if (rawTbsDl.contains(":")) rawTbsDl.substringAfter(":") else rawTbsDl
            val tbsUl = if (rawTbsUl.contains(":")) rawTbsUl.substringAfter(":") else rawTbsUl

            val bandNum = tag(sig, "band").replace("B", "", true)
            val freqMap = mapOf("1" to "2100", "3" to "1800", "7" to "2600", "8" to "900", "20" to "800", "28" to "700", "38" to "2600", "40" to "2300", "41" to "2500", "42" to "3500")
            val freqName = freqMap[bandNum] ?: "1800"

            val netModeStr = tag(netModeDoc, "NetworkMode").ifEmpty { "03" }
            val lteBandHexStr = tag(netModeDoc, "LTEBand").ifEmpty { "4" }

            TelemetryData(
                status = "Connected",
                model = modelName,
                service = carrierName,
                plmn = plmnNum,
                pci = tag(sig, "pci"),
                cellId = cellIdStr,
                tac = tag(sig, "tac"),
                enbidCell = enbStr,
                rssi = "$rawRssi dBm",
                rsrp = "$rawRsrp dBm",
                rsrq = "$rawRsrq dB",
                sinr = "$rawSinr dB",
                rssiPercent = rssiPct,
                rsrpPercent = rsrpPct,
                rsrqPercent = rsrqPct,
                sinrPercent = sinrPct,
                rssiRating = "$rssiPct% V.Good",
                rsrpRating = "$rsrpPct% Weak",
                rsrqRating = "$rsrqPct% Good",
                sinrRating = "$sinrPct% Weak",
                sessionTrafficText = "D : ${formatBytes(curDl)}   U : ${formatBytes(curUl)}   T : ${formatBytes(curDl + curUl)}  (${formatDuration(curTime)})",
                totalTrafficText = "D : ${formatBytes(totDl)}   U : ${formatBytes(totUl)}   T : ${formatBytes(totDl + totUl)}  (${formatDaysTime(totTime)})",
                speedDlBadge = formatSpeedBits(dlRateBytes),
                speedDlBytes = " (${formatSpeedBytes(dlRateBytes)})    U : ",
                speedUlBadge = formatSpeedBits(ulRateBytes),
                speedUlBytes = " (${formatSpeedBytes(ulRateBytes)})",
                usersText = "$curWifi/$totWifi",
                bandWork = "B$bandNum",
                bandFreq = freqName,
                mode = tag(sig, "mode"),
                netMode = netModeStr,
                lteBandHex = lteBandHexStr,
                txPowerText = "4G-TxPower: $cleanTxPower",
                earfcnText = "4G-Earfcn: $cleanEarfcn",
                mcsText = "4G-MCS: DL0:$dl0  DL1:$dl1  UL:$ul",
                tbsText = "4G-TBS: DL0:$tbsDl  UL:$tbsUl",
                freqBandwidthText = "4G-Bandwidth: DL: ${tag(sig, "dlbandwidth").ifEmpty { "20MHz" }} UL: ${tag(sig, "ulbandwidth").ifEmpty { "20MHz" }} (100%)"
            )
        } catch (e: Exception) {
            TelemetryData()
        }
    }

    suspend fun controlDevice(command: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            fetchSessionInfo()
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
            b >= 1024.0 * 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2fTB", b / (1024.0 * 1024.0 * 1024.0 * 1024.0))
            b >= 1024.0 * 1024 * 1024 -> String.format(Locale.US, "%.2fGB", b / (1024.0 * 1024.0 * 1024.0))
            b >= 1024.0 * 1024 -> String.format(Locale.US, "%.2fMB", b / (1024.0 * 1024.0))
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
