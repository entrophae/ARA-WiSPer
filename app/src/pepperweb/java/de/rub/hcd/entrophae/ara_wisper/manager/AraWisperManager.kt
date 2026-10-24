package de.rub.hcd.entrophae.ara_wisper.manager

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import ch.fhnw.pepper_realtime.ui.ChatActivity
import ch.fhnw.pepper_realtime.ui.ChatViewModel
import de.rub.hcd.entrophae.ara_wisper.core.ChatActivityAdapter
import de.rub.hcd.entrophae.ara_wisper.server.AraWisperServer

object AraWisperManager : Application.ActivityLifecycleCallbacks {
    private var webServer: AraWisperServer? = null

    var currentChatActivity: ChatActivity? = null
        private set

    lateinit var app: Application
        private set

    lateinit var speechManager: SpeechManager
        private set

    fun init(application: Application) {
        app = application
        speechManager = SpeechManager(app)
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

    fun applyMuteState() {
        val micState = appPrefs.getBoolean(ProxySettingsRepository.Settings.MIC_STATUS, false)

        currentChatActivity?.runOnUiThread {
            try {
                val chatActivityAdapter = ChatActivityAdapter(currentChatActivity!!)
                if (micState) {
                    Log.i(ProxySettingsRepository.TAG+"TEST", "unmuting")
                    currentChatActivity?.unmuteMicrophone()
                } else {
                    Log.i(ProxySettingsRepository.TAG+"TEST", "muting")
                    currentChatActivity?.muteMicrophone()
                }
            } catch (e: Exception) {
                Log.e(ProxySettingsRepository.TAG+"TEST", "applymuteState", e)
            }
        }

        Log.i(ProxySettingsRepository.TAG, "Applied mic state from preferences")
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is ChatActivity) {
            val viewModel = ViewModelProvider(activity)[ChatViewModel::class.java]

            webServer?.stopServer()

            webServer = AraWisperServer(8080)
            webServer?.startServer(viewModel)
        }
    }

    override fun onActivityStarted(activity: Activity) {
        /*if (activity is ChatActivity) {
            val rootView = activity.findViewById<ViewGroup>(R.id.content)

            // Place Headless UI directly on top of the chat screen
            if (rootView.findViewWithTag<ComposeView>("admin_ui") == null) {
                val adminView = ComposeView(activity).apply {
                    tag = "admin_ui"
                    setContent {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.DarkGray),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("ARA-WiSPer Server Running\n(Headless Mode)", color = Color.White)
                        }
                    }
                }
                rootView.addView(adminView)
            }
        }*/
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activity is ChatActivity) {
            webServer?.stopServer()
            webServer = null
            currentChatActivity = null
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity is ChatActivity) {
            currentChatActivity = activity
        }
    }
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}