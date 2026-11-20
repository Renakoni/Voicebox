package dev.renakoni.voicebox

fun interface BackendServiceListener {
    fun onStateChanged(state: BackendServiceState)
}
