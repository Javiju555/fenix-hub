/// Models for peer-discovered content in the iOS FenixHub app.
///
/// Combines the mDNS-level `PeerDevice` with the content-level
/// `DiscoveredItem` for use in the SwiftUI UI.

import Foundation

/// A content item discovered from a remote peer.
struct DiscoveredItem: Identifiable, Equatable {
    /// Unique id = peer name + content_id.
    var id: String {
        "\(peerId):\(announcement.contentId)"
    }

    /// The peer device that owns this item.
    let peerId: String
    let peerName: String
    let peerHost: String
    let peerPort: UInt16

    /// The announcement describing this item.
    let announcement: Announcement

    /// Whether this item has been pulled/downloaded locally.
    var isDownloaded: Bool = false

    /// Whether a download is in progress.
    var isDownloading: Bool = false

    /// Download progress (0.0–1.0).
    var progress: Double = 0
}

/// Aggregates peers and their discovered items for the UI.
struct PeerContentState {
    var peers: [String: PeerDevice] = [:]
    var discoveredItems: [DiscoveredItem] = []

    mutating func upsertPeer(_ peer: PeerDevice) {
        peers[peer.id] = peer
        rebuildItems()
    }

    mutating func removePeer(id: String) {
        peers.removeValue(forKey: id)
        rebuildItems()
    }

    private mutating func rebuildItems() {
        discoveredItems = peers.flatMap { (_, peer) in
            peer.announcements.map { announcement in
                DiscoveredItem(
                    peerId: peer.id,
                    peerName: peer.name,
                    peerHost: peer.host,
                    peerPort: UInt16(peer.port),
                    announcement: announcement
                )
            }
        }
    }
}
