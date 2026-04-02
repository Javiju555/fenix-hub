package com.fenixhub.mobile.data

import com.fenixhub.mobile.model.LocalContent
import com.fenixhub.mobile.model.PeerContent
import com.fenixhub.mobile.model.SendMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ContentRepository {
    private val localLock = Any()
    private val peerLock = Any()
    private val localItems = linkedMapOf<String, LocalContent>()
    private val peerItems = linkedMapOf<String, PeerContent>()

    private val mutableLocalContent = MutableStateFlow<List<LocalContent>>(emptyList())
    val localContent: StateFlow<List<LocalContent>> = mutableLocalContent.asStateFlow()

    private val mutablePeers = MutableStateFlow<List<PeerContent>>(emptyList())
    val peers: StateFlow<List<PeerContent>> = mutablePeers.asStateFlow()

    private val mutableSelectedLocalContentId = MutableStateFlow<String?>(null)
    val selectedLocalContentId: StateFlow<String?> = mutableSelectedLocalContentId.asStateFlow()

    fun addLocalContent(item: LocalContent) {
        synchronized(localLock) {
            localItems[item.contentId] = item
            mutableSelectedLocalContentId.value = item.contentId
            emitLocalContentLocked()
        }
    }

    fun publish(contentId: String, sendMode: SendMode = SendMode.Broadcast) {
        synchronized(localLock) {
            val current = localItems[contentId] ?: return
            localItems[contentId] = current.copy(isPublished = true, sendMode = sendMode)
            mutableSelectedLocalContentId.value = contentId
            emitLocalContentLocked()
        }
    }

    fun unpublish(contentId: String) {
        synchronized(localLock) {
            val current = localItems[contentId] ?: return
            localItems[contentId] = current.copy(isPublished = false)
            emitLocalContentLocked()
        }
    }

    fun unpublishAll() {
        synchronized(localLock) {
            if (localItems.isEmpty()) return
            localItems.replaceAll { _, current -> current.copy(isPublished = false) }
            emitLocalContentLocked()
        }
    }

    fun publishSelected(sendMode: SendMode = SendMode.Broadcast): LocalContent? {
        val selected = mutableSelectedLocalContentId.value ?: latestLocalContent()?.contentId ?: return null
        publish(selected, sendMode)
        return getLocalContent(selected)
    }

    fun setSelectedLocalContent(contentId: String) {
        mutableSelectedLocalContentId.value = contentId
    }

    fun getLocalContent(contentId: String): LocalContent? = synchronized(localLock) { localItems[contentId] }

    fun removeLocalContent(contentId: String): LocalContent? = synchronized(localLock) {
        val removed = localItems.remove(contentId) ?: return null
        if (mutableSelectedLocalContentId.value == contentId) {
            mutableSelectedLocalContentId.value = localItems.values.maxByOrNull { it.createdAt }?.contentId
        }
        emitLocalContentLocked()
        removed
    }

    fun latestLocalContent(): LocalContent? = synchronized(localLock) {
        localItems.values.maxByOrNull { it.createdAt }
    }

    fun upsertPeer(peer: PeerContent) {
        synchronized(peerLock) {
            peerItems[peer.announcement.contentId] = peer
            emitPeersLocked()
        }
    }

    fun removePeer(contentId: String) {
        synchronized(peerLock) {
            peerItems.remove(contentId)
            emitPeersLocked()
        }
    }

    fun getPeer(contentId: String): PeerContent? = synchronized(peerLock) { peerItems[contentId] }

    fun clearPeers() {
        synchronized(peerLock) {
            peerItems.clear()
            emitPeersLocked()
        }
    }

    private fun emitLocalContentLocked() {
        mutableLocalContent.value = localItems.values.sortedByDescending { it.createdAt }
    }

    private fun emitPeersLocked() {
        mutablePeers.value = peerItems.values.sortedByDescending { it.announcement.createdAt }
    }
}
