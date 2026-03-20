package com.fenixhub.mobile.model

data class PeerContent(
    val peerIp: String,
    val port: Int,
    val announcement: Announcement,
)
