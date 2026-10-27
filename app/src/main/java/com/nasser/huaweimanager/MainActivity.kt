package com.nasser.huaweimanager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var hilink: HilinkClient
    private val handler = Handler(Looper.getMainLooper())
    private var isPolling = false

    private lateinit var tvHeaderIp: TextView
    private lateinit var tvCountryFlag: TextView
    private lateinit var tvDiagOperator: TextView
    private lateinit var tvDiagnostic1: TextView
    private lateinit var tvDiagnostic2: TextView
    private lateinit var tvDiagnostic3: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvModel: TextView
    private lateinit var tvService: TextView
    private lateinit var tvPci: TextView
    private lateinit var tvCellId: TextView
    private lateinit var tvTac: TextView
    private lateinit var tvEnbid: TextView
    private lateinit var tvTrafficSession: TextView
    private lateinit var tvTrafficTotal: TextView
    private lateinit var tvSpeedDlBadge: TextView
    private lateinit var tvSpeedDlBytes: TextView
    private lateinit var tvSpeedUlBadge: TextView
    private lateinit var tvSpeedUlBytes: TextView

    private lateinit var tvRssiVal: TextView
    private lateinit var tvRssiEval: TextView
    private lateinit var barRssi: View
    private lateinit var tvRsrpVal: TextView
    private lateinit var tvRsrpEval: TextView
    private lateinit var barRsrp: View
    private lateinit var tvRsrqVal: TextView
    private lateinit var tvRsrqEval: TextView
    private lateinit var barRsrq: View
    private lateinit var tvSinrVal: TextView
    private lateinit var tvSinrEval: TextView
    private lateinit var barSinr: View

    private lateinit var tvTxPower: TextView
    private lateinit var tvEarfcn: TextView
    private lateinit var tvMcs: TextView
    private lateinit var tvTbs: TextView
    private lateinit var tvFreqBandwidth: TextView
    private lateinit var btnUsers: TextView

    private lateinit var cb3G_B8: CheckBox
    private lateinit var cb3G_B1: CheckBox
    private lateinit var cbFdd20: CheckBox
    private lateinit var cbFdd8: CheckBox
    private lateinit var cbFdd3: CheckBox
    private lateinit var cbFdd1: CheckBox
    private lateinit var cbFdd7: CheckBox
    private lateinit var cbTdd40: CheckBox
    private lateinit var cbTdd41: CheckBox
    private lateinit var cbTdd38: CheckBox
    private lateinit var cbTdd42: CheckBox

    private lateinit var optAuto: TextView
    private lateinit var opt4G: TextView
    private lateinit var opt3G: TextView
    private lateinit var opt2G: TextView
    private var selectedMode = "03"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("hm_settings", Context.MODE_PRIVATE)
        
        // Sanitize any previously stored trailing slash
        val rawIp = prefs.getString("router_ip", "http://192.168.8.1") ?: "http://192.168.8.1"
        val cleanIp = rawIp.trim().trimEnd('/')
        prefs.edit().putString("router_ip", cleanIp).apply()

        hilink = HilinkClient(cleanIp)

        initViews()
        bindEvents()

        lifecycleScope.launch {
            NetBinder.bindToWifi(applicationContext)
            hilink.fetchSessionInfo()
            val u = prefs.getString("user", "admin") ?: "admin"
            val p = prefs.getString("pass", "admin") ?: "admin"
            hilink.login(u, p)
            startLoop()
        }
    }

    private fun initViews() {
        tvHeaderIp = findViewById(R.id.tvHeaderIp)
        tvCountryFlag = findViewById(R.id.tvCountryFlag)
        tvDiagOperator = findViewById(R.id.tvDiagOperator)
        tvDiagnostic1 = findViewById(R.id.tvDiagnostic1)
        tvDiagnostic2 = findViewById(R.id.tvDiagnostic2)
        tvDiagnostic3 = findViewById(R.id.tvDiagnostic3)

        tvStatus = findViewById(R.id.tvStatus)
        tvModel = findViewById(R.id.tvModel)
        tvService = findViewById(R.id.tvService)
        tvPci = findViewById(R.id.tvPci)
        tvCellId = findViewById(R.id.tvCellId)
        tvTac = findViewById(R.id.tvTac)
        tvEnbid = findViewById(R.id.tvEnbid)

        tvTrafficSession = findViewById(R.id.tvTrafficSession)
        tvTrafficTotal = findViewById(R.id.tvTrafficTotal)
        tvSpeedDlBadge = findViewById(R.id.tvSpeedDlBadge)
        tvSpeedDlBytes = findViewById(R.id.tvSpeedDlBytes)
        tvSpeedUlBadge = findViewById(R.id.tvSpeedUlBadge)
        tvSpeedUlBytes = findViewById(R.id.tvSpeedUlBytes)

        tvRssiVal = findViewById(R.id.tvRssiVal)
        tvRssiEval = findViewById(R.id.tvRssiEval)
        barRssi = findViewById(R.id.barRssi)

        tvRsrpVal = findViewById(R.id.tvRsrpVal)
        tvRsrpEval = findViewById(R.id.tvRsrpEval)
        barRsrp = findViewById(R.id.barRsrp)

        tvRsrqVal = findViewById(R.id.tvRsrqVal)
        tvRsrqEval = findViewById(R.id.tvRsrqEval)
        barRsrq = findViewById(R.id.barRsrq)

        tvSinrVal = findViewById(R.id.tvSinrVal)
        tvSinrEval = findViewById(R.id.tvSinrEval)
        barSinr = findViewById(R.id.barSinr)

        tvTxPower = findViewById(R.id.tvTxPower)
        tvEarfcn = findViewById(R.id.tvEarfcn)
        tvMcs = findViewById(R.id.tvMcs)
        tvTbs = findViewById(R.id.tvTbs)
        tvFreqBandwidth = findViewById(R.id.tvFreqBandwidth)
        btnUsers = findViewById(R.id.btnUsers)

        cb3G_B8 = findViewById(R.id.cb3G_B8)
        cb3G_B1 = findViewById(R.id.cb3G_B1)
        cbFdd20 = findViewById(R.id.cbFdd20)
        cbFdd8 = findViewById(R.id.cbFdd8)
        cbFdd3 = findViewById(R.id.cbFdd3)
        cbFdd1 = findViewById(R.id.cbFdd1)
        cbFdd7 = findViewById(R.id.cbFdd7)

        cbTdd40 = findViewById(R.id.cbTdd40)
        cbTdd41 = findViewById(R.id.cbTdd41)
        cbTdd38 = findViewById(R.id.cbTdd38)
        cbTdd42 = findViewById(R.id.cbTdd42)

        optAuto = findViewById(R.id.optAuto)
        opt4G = findViewById(R.id.opt4G)
        opt3G = findViewById(R.id.opt3G)
        opt2G = findViewById(R.id.opt2G)

        tvHeaderIp.text = "${hilink.gatewayIp}/"
    }

    private fun bindEvents() {
        tvHeaderIp.setOnClickListener { showRouterIpDialog() }
        findViewById<Button>(R.id.btnHomePage).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(hilink.gatewayIp + "/")))
        }
        btnUsers.setOnClickListener {
            lifecycleScope.launch {
                val hs = hilink.hosts()
                val text = if (hs.isEmpty()) "No devices" else hs.joinToString("\n\n") {
                    "${it.name}\nIP: ${it.ip}\nMAC: ${it.mac}\nTime: ${fmtDur(it.seconds)}"
                }
                AlertDialog.Builder(this@MainActivity).setTitle("Networked Devices")
                    .setMessage(text).setPositiveButton("OK", null).show()
            }
        }
        findViewById<TextView>(R.id.btnAntenna).setOnClickListener { v ->
            lifecycleScope.launch {
                (v as TextView).text = hilink.antennaLabel()
                val items = arrayOf("Auto", "Internal", "External")
                AlertDialog.Builder(this@MainActivity).setTitle("Antenna: ${v.text}")
                    .setItems(items) { _, i ->
                        lifecycleScope.launch {
                            val ok = hilink.setAntenna(items[i])
                            Toast.makeText(this@MainActivity, if (ok) "Antenna switched" else "Antenna not switched", Toast.LENGTH_SHORT).show()
                        }
                    }.show()
            }
        }
        findViewById<TextView>(R.id.btnInfo).setOnClickListener {
            AlertDialog.Builder(this).setTitle("Huawei Manager").setMessage("Version 1.0")
                .setPositiveButton("OK", null).show()
        }
        findViewById<Button>(R.id.btnLoginDialog).setOnClickListener { showLoginDialog() }
        findViewById<Button>(R.id.btnRouterOptions).setOnClickListener { showRouterOptionsDialog() }
        findViewById<TextView>(R.id.btnSettingsGear).setOnClickListener { showRouterIpDialog() }

        fun setModePills(sel: TextView, code: String) {
            listOf(optAuto, opt4G, opt3G, opt2G).forEach {
                it.setBackgroundResource(R.drawable.pill_dark)
                it.setTextColor(Color.parseColor("#AAAAAA"))
            }
            sel.setBackgroundResource(R.drawable.pill_green)
            sel.setTextColor(Color.WHITE)
            selectedMode = code
        }

        optAuto.setOnClickListener { setModePills(optAuto, "00") }
        opt4G.setOnClickListener { setModePills(opt4G, "03") }
        opt3G.setOnClickListener { setModePills(opt3G, "02") }
        opt2G.setOnClickListener { setModePills(opt2G, "01") }

        findViewById<Button>(R.id.btnApplyMode).setOnClickListener {
            lifecycleScope.launch {
                val ok = hilink.setNetworkMode(selectedMode)
                Toast.makeText(this@MainActivity, if (ok) "Mode Applied!" else "Mode Switch Failed", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnHomePage).setOnClickListener { openRouterPage("Home", "html/home.html") }
        findViewById<TextView>(R.id.btnMenu).setOnClickListener { showRouterMenu() }
        findViewById<Button>(R.id.btnApplyBands).setOnClickListener {
            val list = mutableListOf<Int>()
            if (cbFdd1.isChecked) list.add(1)
            if (cbFdd3.isChecked) list.add(3)
            if (cbFdd7.isChecked) list.add(7)
            if (cbFdd8.isChecked) list.add(8)
            if (cbFdd20.isChecked) list.add(20)
            if (cbTdd38.isChecked) list.add(38)
            if (cbTdd40.isChecked) list.add(40)
            if (cbTdd41.isChecked) list.add(41)
            if (cbTdd42.isChecked) list.add(42)

            lifecycleScope.launch {
                val (ok, msg) = if (cb3G_B1.isChecked || cb3G_B8.isChecked) hilink.lock3G(cb3G_B1.isChecked, cb3G_B8.isChecked) else hilink.lockBands(list)
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showLoginDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }
        val etUser = EditText(this).apply {
            hint = "Username"
            setText(prefs.getString("user", "admin"))
        }
        val etPass = EditText(this).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("pass", "admin"))
        }
        layout.addView(etUser)
        layout.addView(etPass)

        AlertDialog.Builder(this)
            .setTitle("Login Information")
            .setView(layout)
            .setPositiveButton("SAVE") { _, _ ->
                val u = etUser.text.toString().trim()
                val p = etPass.text.toString().trim()
                prefs.edit().putString("user", u).putString("pass", p).apply()

                lifecycleScope.launch {
                    val (ok, msg) = hilink.login(u, p)
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showRouterIpDialog() {
        val et = EditText(this).apply {
            setText(hilink.gatewayIp)
            setPadding(50, 40, 50, 20)
        }
        AlertDialog.Builder(this)
            .setTitle("Router Ip Address")
            .setView(et)
            .setPositiveButton("SAVE") { _, _ ->
                var ip = et.text.toString().trim()
                if (!ip.startsWith("http://") && !ip.startsWith("https://")) ip = "http://$ip"
                ip = ip.trimEnd('/')
                
                hilink.gatewayIp = ip
                prefs.edit().putString("router_ip", ip).apply()
                tvHeaderIp.text = "$ip/"
                
                lifecycleScope.launch {
                    hilink.fetchSessionInfo()
                    val u = prefs.getString("user", "admin") ?: "admin"
                    val p = prefs.getString("pass", "admin") ?: "admin"
                    hilink.login(u, p)
                }
                Toast.makeText(this, "IP Updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun confirm(msg: String, action: suspend () -> Boolean) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("YES") { _, _ ->
                lifecycleScope.launch {
                    val ok = action()
                    Toast.makeText(this@MainActivity, if (ok) "Done" else "Failed", Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("NO", null).show()
    }

    private fun showRouterOptionsDialog() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_router_options, null)
        val dlg = AlertDialog.Builder(this).setView(v).create()
        v.findViewById<Button>(R.id.btnOptRestart).setOnClickListener {
            dlg.dismiss(); confirm("Restart the router?") { hilink.restartRouter() }
        }
        v.findViewById<Button>(R.id.btnOptShutdown).setOnClickListener {
            dlg.dismiss(); confirm("Shut down the router?") { hilink.powerOffRouter() }
        }
        v.findViewById<Button>(R.id.btnOptRefreshIp).setOnClickListener {
            lifecycleScope.launch {
                val ok = hilink.refreshIp()
                Toast.makeText(this@MainActivity, if (ok) "IP refreshed" else "Refresh failed", Toast.LENGTH_SHORT).show()
            }
        }
        lifecycleScope.launch {
            val s = hilink.routerStats()
            v.findViewById<TextView>(R.id.tvStatUptime).text = s[0]
            v.findViewById<TextView>(R.id.tvStatTotalData).text = s[1]
            v.findViewById<TextView>(R.id.tvStatMonthlyUptime).text = s[2]
            v.findViewById<TextView>(R.id.tvStatMonthlyData).text = s[3]
        }
        v.findViewById<Button>(R.id.btnOptClearStats).setOnClickListener {
            dlg.dismiss(); confirm("Clear traffic statistics?") { hilink.clearTraffic() }
        }
        dlg.show()
    }

    private val menuPages = listOf(
        "Mobile Connection" to "html/mobileconnection.html",
        "Mobile Network Settings" to "html/mobilenetworksettings.html",
        "Profiles" to "html/profilesmgr.html",
        "Statistics" to "html/statistic.html",
        "SMS Inbox" to "html/smsinbox.html?smsinbox",
        "SMS Sent" to "html/smsinbox.html?smssent",
        "USSD" to "html/ussd.html",
        "Wi-Fi Basic Settings" to "html/wlanbasicsettings.html",
        "Wi-Fi Advanced" to "html/wlanadvanced.html",
        "Wi-Fi MAC Filter" to "html/wlanmacfilter.html",
        "DHCP" to "html/dhcp.html",
        "Virtual Server" to "html/virtualserver.html",
        "Special Application" to "html/specialapplication.html",
        "DMZ" to "html/dmzsettings.html",
        "UPnP" to "html/upnp.html",
        "NAT" to "html/nat.html",
        "MAC Filter" to "html/macfilter.html",
        "IP Filter" to "html/lanipfilter.html",
        "URL Filter" to "html/urlfilter.html",
        "Antenna" to "html/antenna.html",
        "Device Information" to "html/deviceinformation.html",
        "Device Management" to "html/devicemanagement.html",
        "Change Password" to "html/modifypassword.html"
    )

    private fun openRouterPage(title: String, path: String) {
        startActivity(
            android.content.Intent(this, RouterWebActivity::class.java)
                .putExtra("url", hilink.gatewayIp + "/" + path)
                .putExtra("title", title)
                .putStringArrayListExtra("cookies", hilink.cookieStrings())
        )
    }

    private fun showRouterMenu() {
        AlertDialog.Builder(this).setTitle("Router Menu")
            .setItems(menuPages.map { it.first }.toTypedArray()) { _, i ->
                openRouterPage(menuPages[i].first, menuPages[i].second)
            }.show()
    }

    private fun startLoop() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var fails = 0
                while (isActive) {
                    val t = hilink.getTelemetry()
                    updateUi(t)
                    if (t.status == "Connected") {
                        fails = 0
                    } else if (++fails % 3 == 0) {
                        NetBinder.bindToWifi(applicationContext)
                        val u = prefs.getString("user", "admin") ?: "admin"
                        val p = prefs.getString("pass", "dns12345") ?: "dns12345"
                        hilink.login(u, p)
                    }
                    delay(1500)
                }
            }
        }
    }

    private var controlsSynced = false

    private fun syncControls(t: TelemetryData) {
        val pills = listOf(optAuto to "00", opt4G to "03", opt3G to "02", opt2G to "01")
        val sel = pills.firstOrNull { it.second == t.netMode } ?: return
        pills.forEach { (v, _) ->
            v.setBackgroundResource(R.drawable.pill_dark)
            v.setTextColor(Color.parseColor("#AAAAAA"))
        }
        sel.first.setBackgroundResource(R.drawable.pill_green)
        sel.first.setTextColor(Color.WHITE)
        selectedMode = sel.second

        val mask = t.lteBandHex.toLongOrNull(16) ?: return
        if (mask == 0x7FFFFFFFFFFFFFFFL) return   // "all bands" = no lock, leave boxes empty
        mapOf(1 to cbFdd1, 3 to cbFdd3, 7 to cbFdd7, 8 to cbFdd8, 20 to cbFdd20,
              38 to cbTdd38, 40 to cbTdd40, 41 to cbTdd41, 42 to cbTdd42)
            .forEach { (b, cb) -> cb.isChecked = ((mask shr (b - 1)) and 1L) == 1L }
    }

    private fun updateUi(t: TelemetryData) {
        if (!controlsSynced && t.status == "Connected") { controlsSynced = true; syncControls(t) }
        tvStatus.text = t.status
        tvModel.text = t.model
        tvService.text = t.service
        tvPci.text = t.pci
        tvCellId.text = t.cellId
        tvTac.text = t.tac
        tvEnbid.text = t.enbidCell

        tvDiagOperator.text = "Morocco ${t.plmn}"
        tvCountryFlag.text = if (t.plmn.startsWith("604")) "🇲🇦" else "🌐"

        tvDiagnostic1.text = "BandLTEWork : ${t.bandWork}  Mode : ${t.mode}"
        tvDiagnostic2.text = "SetBand : ${t.bandWork}\nSetName : ${t.bandFreq}"
        tvDiagnostic3.text = "NetMode : ${t.netMode}  NetBand : 3FFFFFFF  LTEBand : ${t.lteBandHex}"

        tvTrafficSession.text = t.sessionTrafficText
        tvTrafficTotal.text = t.totalTrafficText

        tvSpeedDlBadge.text = t.speedDlBadge
        tvSpeedDlBytes.text = t.speedDlBytes
        tvSpeedUlBadge.text = t.speedUlBadge
        tvSpeedUlBytes.text = t.speedUlBytes

        tvRssiVal.text = t.rssi
        tvRssiEval.text = t.rssiRating
        setBarWidth(barRssi, t.rssiPercent)
        paint(barRssi, tvRssiEval, t.rssiColor)

        tvRsrpVal.text = t.rsrp
        tvRsrpEval.text = t.rsrpRating
        setBarWidth(barRsrp, t.rsrpPercent)
        paint(barRsrp, tvRsrpEval, t.rsrpColor)

        tvRsrqVal.text = t.rsrq
        tvRsrqEval.text = t.rsrqRating
        setBarWidth(barRsrq, t.rsrqPercent)
        paint(barRsrq, tvRsrqEval, t.rsrqColor)

        tvSinrVal.text = t.sinr
        tvSinrEval.text = t.sinrRating
        setBarWidth(barSinr, t.sinrPercent)
        paint(barSinr, tvSinrEval, t.sinrColor)

        tvTxPower.text = t.txPowerText
        tvEarfcn.text = t.earfcnText
        tvMcs.text = t.mcsText
        tvTbs.text = t.tbsText
        tvFreqBandwidth.text = t.freqBandwidthText
        btnUsers.text = t.usersText
    }

    private fun paint(bar: View, pill: TextView, color: Int) {
        if (color == 0) return
        bar.setBackgroundColor(color)
        pill.background?.mutate()?.setTint(color)
    }

    private fun setBarWidth(view: View, percent: Int) {
        val parent = view.parent as? View ?: return
        parent.post {
            val totalWidth = parent.width
            val targetWidth = (totalWidth * (percent.coerceIn(5, 100) / 100.0)).toInt()
            val lp = view.layoutParams
            lp.width = targetWidth
            view.layoutParams = lp
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isPolling = false
        handler.removeCallbacksAndMessages(null)
    }
}
