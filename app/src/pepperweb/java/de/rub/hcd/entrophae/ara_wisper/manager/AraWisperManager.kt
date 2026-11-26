package de.rub.hcd.entrophae.ara_wisper.manager

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import ch.fhnw.pepper_realtime.ui.ChatActivity
import ch.fhnw.pepper_realtime.ui.ChatViewModel
import de.rub.hcd.entrophae.ara_wisper.core.ChatActivityAdapter
import de.rub.hcd.entrophae.ara_wisper.server.AraWisperServer
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.rub.hcd.entrophae.ara_wisper.network.installConscryptSecurity
import de.rub.hcd.entrophae.ara_wisper.server.manager.DashboardVideoManager
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/**
 * Process-wide lifecycle root and host-application integration singleton.
 *
 * Implements [Application.ActivityLifecycleCallbacks] to wrap `ChatActivity` inside
 * [ChatActivityAdapter], manage the embedded Ktor server ([AraWisperServer]), provide
 * global access to Android resources and long-lived managers ([SpeechManager],
 * [DashboardVideoManager]).
 */
object AraWisperManager : Application.ActivityLifecycleCallbacks {
    private var webServer: AraWisperServer? = null

    var chatActivityAdapter: ChatActivityAdapter? = null
        private set

    lateinit var app: Application
        private set

    lateinit var speechManager: SpeechManager
        private set

    lateinit var dashboardVideoManager: DashboardVideoManager

    var isSessionAllowed = MutableStateFlow(false)

    fun init(application: Application) {
        app = application
        app.registerActivityLifecycleCallbacks(this)
        installConscryptSecurity()
        speechManager = SpeechManager()
    }

    val appContext: Context
        get() = app

    val appPrefs: SharedPreferences
        get() = app.getSharedPreferences(
            ProxySettingsRepository.PREFS_NAME,
            Context.MODE_PRIVATE
        )

    val appAssets: AssetManager
        get() = app.assets

    fun appString(id: Int, vararg args: Any): String {
        return app.getString(id, *args)
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface: NetworkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val interfacesIpAddress = networkInterface.inetAddresses
                while (interfacesIpAddress.hasMoreElements()) {
                    val inetAddress = interfacesIpAddress.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address && inetAddress.isSiteLocalAddress) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (ex: SocketException) {
            ex.printStackTrace()
        }
        return null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is ChatActivity) {
            chatActivityAdapter = ChatActivityAdapter(activity)

            val viewModel = ViewModelProvider(activity)[ChatViewModel::class.java]

            webServer?.stopServer()

            webServer = AraWisperServer()
            webServer?.startServer(viewModel)
        }
    }

    override fun onActivityStarted(activity: Activity) {
        if (activity is ChatActivity) {
            val rootView = activity.findViewById<ViewGroup>(android.R.id.content)

            if (rootView.findViewWithTag<ComposeView>("admin_ui") == null && !isSessionAllowed.value) {
                val adminView = ComposeView(activity).apply {
                    tag = "admin_ui"
                    setContent {
                        val allowed by isSessionAllowed.collectAsState()

                        if (!allowed) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.DarkGray.copy(alpha = 0.8f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("ARA-WiSPer Server Running", color = Color.White)
                                    Text("${getLocalIpAddress()}:${ProxySettingsRepository.port}", color = Color.LightGray, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Button(onClick = {
                                        isSessionAllowed.value = true
                                        chatActivityAdapter?.restartSession()
                                    }) {
                                        Text("Activate LLM Mode")
                                    }
                                }
                            }
                        }
                    }
                }
                rootView.addView(adminView)
            }
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is ChatActivity) {
            webServer?.stopServer()
            webServer = null
            if (chatActivityAdapter?.isFor(activity) == true) {
                chatActivityAdapter = null
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}