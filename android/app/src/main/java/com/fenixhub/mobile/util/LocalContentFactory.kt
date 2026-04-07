package com.fenixhub.mobile.util

import android.net.Uri
import com.fenixhub.mobile.data.TempClipboardStore
import com.fenixhub.mobile.model.LocalContent

class LocalContentFactory(private val tempStore: TempClipboardStore) {
    fun fromText(text: String): LocalContent = tempStore.createTextContent(text)

    fun fromUri(uri: Uri, deferLargeImagePreview: Boolean = false): LocalContent? =
        tempStore.createFromUri(uri, deferLargeImagePreview)

    fun refreshDeferredImagePreview(item: LocalContent): LocalContent? =
        tempStore.refreshDeferredImagePreview(item)
}
