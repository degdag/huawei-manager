package com.nasser.huaweimanager

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var hilink: HilinkClient
    private val handler = Handler(Looper.getMainLooper())
    private var isPolling = false

    // Views
    private lateinit var tvHeaderIp: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvModel: TextView
    private lateinit var tvService: TextView
    private lateinit var tvPci: TextView
    private lateinit var tvCellId: TextView
    private lateinit var tvTac: TextView
    private lateinit var tvEnbid: TextView
    private lateinit var tvSpeedLive: TextView
    private lateinit var tvDataVolume: TextView
    private lateinit var tvRssiVal: TextView
    private lateinit var tvRscpVal: TextView
    private lateinit var tvEcioVal: TextView
    private lateinit var tvRsrpVal: TextView
    private lateinit var tvRsrqVal: TextView
    private lateinit var tvSinrVal: TextView
    private lateinit var tvUsers: TextView
    private lateinit var tvDiagnostic1: TextView

    // Band checkboxes
    private lateinit var cbFdd28: CheckBox
    private lateinit var cbFdd3: CheckBox
    private lateinit var cbFdd1: CheckBox
    private lateinit var cbFdd7: CheckBox
    private lateinit var cbFdd20: CheckBox
    private lateinit var cbTdd40: CheckBox
    private lateinit var cbTdd41: CheckBox
    private lateinit var cbTdd38: CheckBox
    private lateinit var cbTdd42: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("hm_settings", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("router_ip", "http://192.168.8.1") ?: "http://192.168.8.1"
        hilink = HilinkClient(savedIp)

        initViews()
        bindEvents()

        lifecycleScope.launch {
            hilink.fetchSessionInfo()
            startLoop()
        }
    }

    private fun initViews() {
        tvHeaderIp = findViewById(R.id.tvHeaderIp)
        tvStatus = findViewById(R.id.tvStatus)
        tvModel = findViewById(R.id.tvModel)
        tvService = findViewById(R.id.tvService)
        tvPci = findViewById(R.id.tvPci)
        tvCellId = findViewById(R.id.tvCellId)
        tvTac = findViewById(R.id.tvTac)
        tvEnbid = findViewById(R.id.tvEnbid)
        tvSpeedLive = findViewById(R.id.tvSpeedLive)
        tvDataVolume = findViewById(R.id.tvDataVolume)
        tvRssiVal = findViewById(R.id.tvRssiVal)
        tvRscpVal = findViewById(R.id.tvRscpVal)
        tvEcioVal = findViewById(R.id.tvEcioVal)
        tvRsrpVal = findViewById(R.id.tvRsrpVal)
        tvRsrqVal = findViewById(R.id.tvRsrqVal)
        tvSinrVal = findViewById(R.id.tvSinrVal)
        tvUsers = findViewById(R.id.btnUsers)
        tvDiagnostic1 = findViewById(R.id.tvDiagnostic1)

        cbFdd28 = findViewById(R.id.cbFdd28)
        cbFdd3 = findViewById(R.id.cbFdd3)
        cbFdd1 = findViewById(R.id.cbFdd1)
        cbFdd7 = findViewById(R.id.cbFdd7)
        cbFdd20 = findViewById(R.id.cbFdd20)
        cbTdd40 = findViewById(R.id.cbTdd40)
        cbTdd41 = findViewById(R.id.cbTdd41)
        cbTdd38 = findViewById(R.id.cbTdd38)
        cbTdd42 = findViewById(R.id.cbTdd42)

        tvHeaderIp.text = hilink.gatewayIp
    }

    private fun bindEvents() {
        tvHeaderIp.setOnClickListener { showRouterIpDialog() }
        findViewById<Button>(R.id.btnLoginDialog).setOnClickListener { showLoginDialog() }
        findViewById<Button>(R.id.btnRouterOptions).setOnClickListener { showRouterOptionsDialog() }
        findViewById<TextView>(R.id.btnSettingsGear).setOnClickListener { showRouterIpDialog() }

        findViewById<Button>(R.id.btnApplyBands).setOnClickListener {
            val list = mutableListOf<Int>()
            if (cbFdd1.isChecked) list.add(1)
            if (cbFdd3.isChecked) list.add(3)
            if (cbFdd7.isChecked) list.add(7)
            if (cbFdd20.isChecked) list.add(20)
            if (cbFdd28.isChecked) list.add(28)
            if (cbTdd38.isChecked) list.add(38)
            if (cbTdd40.isChecked) list.add(40)
            if (cbTdd41.isChecked) list.add(41)
            if (cbTdd42.isChecked) list.add(42)

            lifecycleScope.launch {
                val ok = hilink.lockBands(list)
                Toast.makeText(this@MainActivity, if (ok) "Bands Applied!" else "Lock Failed", Toast.LENGTH_SHORT).show()
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
            setText(prefs.getString("pass", "dns12345"))
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
                    val ok = hilink.login(u, p)
                    Toast.makeText(this@MainActivity, if (ok) "Login Successful!" else "Login Failed", Toast.LENGTH_SHORT).show()
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
                if (!ip.startsWith("http")) ip = "http://$ip"
                hilink.gatewayIp = ip
                prefs.edit().putString("router_ip", ip).apply()
                tvHeaderIp.text = ip
                Toast.makeText(this, "IP Updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showRouterOptionsDialog() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_router_options, null)
        val dlg = AlertDialog.Builder(this).setView(v).create()

        v.findViewById<Button>(R.id.btnOptRestart).setOnClickListener {
            lifecycleScope.launch { hilink.controlDevice(1) }
            dlg.dismiss()
        }
        v.findViewById<Button>(R.id.btnOptShutdown).setOnClickListener {
            lifecycleScope.launch { hilink.controlDevice(2) }
            dlg.dismiss()
        }
        dlg.show()
    }

    private fun startLoop() {
        isPolling = true
        handler.post(object : Runnable {
            override fun run() {
                if (!isPolling) return
                lifecycleScope.launch {
                    val t = hilink.getTelemetry()
                    updateUi(t)
                }
                handler.postDelayed(this, 1500)
            }
        })
    }

    private fun updateUi(t: TelemetryData) {
        tvStatus.text = t.status
        tvModel.text = t.model
        tvService.text = t.service
        tvPci.text = t.pci
        tvCellId.text = t.cellId
        tvTac.text = t.tac
        tvEnbid.text = t.enbid

        tvSpeedLive.text = "D : ${t.dlRate}   U : ${t.ulRate}   T : ${t.totalRate}  (${t.uptime})"
        tvDataVolume.text = "D : ${t.dlRate} (${t.dlBytes})   U : ${t.ulRate} (${t.ulBytes})"

        tvRssiVal.text = t.rssi
        tvRscpVal.text = t.rscp
        tvEcioVal.text = t.ecio
        tvRsrpVal.text = t.rsrp
        tvRsrqVal.text = t.rsrq
        tvSinrVal.text = t.sinr

        tvUsers.text = "Users ${t.usersCount}"
        tvDiagnostic1.text = "BandWork : ${t.bandWork}  Mode : ${t.mode}  NetMode : ${t.netMode}"
    }

    override fun onDestroy() {
        super.onDestroy()
        isPolling = false
        handler.removeCallbacksAndMessages(null)
    }
}
