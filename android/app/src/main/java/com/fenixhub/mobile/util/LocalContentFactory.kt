package com.fenixhub.mobile.util

import android.net.Uri
import com.fenixhub.mobile.data.TempClipboardStore
import com.fenixhub.mobile.model.LocalContent

class LocalContentFactory(private val tempStore: TempClipboardStore) {
    fun fromText(text: String): LocalContent = tempStore.createTextContent(text)

    fun fromUri(uri: Uri): LocalContent? {
        return tempStore.createFromUri(uri)
    }
}
