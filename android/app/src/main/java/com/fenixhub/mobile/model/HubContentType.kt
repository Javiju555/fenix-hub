package com.fenixhub.mobile.model

enum class HubContentType(val wireValue: String) {
    TEXT("text"),
    IMAGE("image"),
    FILE("file");

    companion object {
        fun fromWireValue(value: String): HubContentType? = entries.firstOrNull { it.wireValue == value }
    }
}
