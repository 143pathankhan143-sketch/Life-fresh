package com.example.voice

/**
 * Maps a [VocalDestination] to a NavHost route.
 *
 * The routes are the ones MainActivity already registers
 * (dashboard / leads / ai / reports / settings). AI history lives INSIDE the
 * AI tab, so [VocalDestination.AI_HISTORY] opens the same route and the host
 * asks the chat screen to show its history panel.
 */
object VoiceNavigator {

    const val ROUTE_DASHBOARD = "dashboard"
    const val ROUTE_LEADS = "leads"
    const val ROUTE_AI = "ai"
    const val ROUTE_REPORTS = "reports"
    const val ROUTE_SETTINGS = "settings"

    fun routeFor(destination: VocalDestination): String = when (destination) {
        VocalDestination.DASHBOARD -> ROUTE_DASHBOARD
        VocalDestination.LEADS -> ROUTE_LEADS
        VocalDestination.AI_CHAT -> ROUTE_AI
        VocalDestination.AI_HISTORY -> ROUTE_AI
        VocalDestination.REPORTS -> ROUTE_REPORTS
        VocalDestination.SETTINGS -> ROUTE_SETTINGS
    }

    /** True when the destination has live lead counts worth announcing. */
    fun announcesCounts(destination: VocalDestination): Boolean =
        destination == VocalDestination.LEADS || destination == VocalDestination.DASHBOARD
}
