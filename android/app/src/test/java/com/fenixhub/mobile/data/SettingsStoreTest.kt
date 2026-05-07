package com.fenixhub.mobile.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

    @Test
    fun `overrideMeshSession replaces current without persisting`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(ctx)

        val original = store.current().groupId

        store.overrideMeshSession(
            groupId = "MESH-TEST-ID",
            groupKeyHex = "a".repeat(64),
        )

        assertEquals("MESH-TEST-ID", store.current().groupId)
        assertEquals("a".repeat(64), store.current().groupKeyHex)

        store.clearMeshSessionOverride()

        assertEquals(original, store.current().groupId)
    }
}
