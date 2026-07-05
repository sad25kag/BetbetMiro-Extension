package com.sad25kag

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.sad25kag.settings.SettingsFragment
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SuperStreamPlugin: Plugin() {
    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences("SuperStream", Context.MODE_PRIVATE)
        registerMainAPI(SuperStream(sharedPref))

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = SettingsFragment(this, sharedPref)
            frag.show(activity.supportFragmentManager, "Frag")
        }
    }
}
