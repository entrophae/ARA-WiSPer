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
import de.rub.hcd.entrophae.ara_wisper.server.AraWisperServer

object AraWisperManager : Application.ActivityLifecycleCallbacks {
    private var webServer: de.rub.hcd.entrophae.ara_wisper.server.AraWisperServer? = null

    lateinit var app: Application
        private set

    lateinit var speechManager: de.rub.hcd.entrophae.ara_wisper.manager.SpeechManager
        private set

    fun init(application: Application) {
        app = application
        speechManager =
            _root_ide_package_.de.rub.hcd.entrophae.ara_wisper.manager.SpeechManager(app)
    }

    val appContext: Context
        get() = app

    val appPrefs: SharedPreferences
        get() = app.getSharedPreferences(
            _root_ide_package_.de.rub.hcd.entrophae.ara_wisper.manager.ProxySettingsRepository.PREFS_NAME,
            Context.MODE_PRIVATE
        )

    val appAssets: AssetManager
        get() = app.assets

    fun appString(id: Int, vararg args: Any): String {
        return app.getString(id, *args)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity is ChatActivity) {
            val viewModel = ViewModelProvider(activity)[ChatViewModel::class.java]
            val activeContext = activity.toolContext

            webServer =
                _root_ide_package_.de.rub.hcd.entrophae.ara_wisper.server.AraWisperServer(8080)
            webServer?.startServer(viewModel, activeContext)
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
        }
    }

    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}