package com.fayor.justtalk

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The launcher icon follows the accent from Профиль → Оформление: one activity-alias per accent,
 * exactly one of them enabled. ".MainActivity" (violet) is the default and the pre-0.11.2 name.
 */
object AppIcon {
    private val ALIASES = mapOf(
        "violet" to ".MainActivity",
        "lime" to ".IconLime",
        "orange" to ".IconOrange",
        "blue" to ".IconBlue",
        "mono" to ".IconMono",
    )

    fun sync(ctx: Context, accent: String) {
        val pm = ctx.packageManager
        val want = ALIASES[accent] ?: ALIASES.getValue("violet")
        val wantName = ComponentName(ctx, ctx.packageName + want)
        if (pm.getComponentEnabledSetting(wantName) == stateFor(want, true)) return
        // enable the new one first so there is never a moment with no launcher icon
        set(pm, wantName, true, want)
        for (alias in ALIASES.values) if (alias != want) set(pm, ComponentName(ctx, ctx.packageName + alias), false, alias)
    }

    // the violet alias is enabled in the manifest, so "default" already means on for it
    private fun stateFor(alias: String, on: Boolean) = when {
        on && alias == ".MainActivity" -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        on -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    private fun set(pm: PackageManager, name: ComponentName, on: Boolean, alias: String) {
        val state = stateFor(alias, on)
        if (pm.getComponentEnabledSetting(name) != state) pm.setComponentEnabledSetting(name, state, PackageManager.DONT_KILL_APP)
    }
}
