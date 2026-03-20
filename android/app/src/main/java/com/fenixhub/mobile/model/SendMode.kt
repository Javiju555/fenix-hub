package com.fenixhub.mobile.model

sealed interface SendMode {
    data object Broadcast : SendMode
    data class Direct(val targetDevice: String) : SendMode
}
