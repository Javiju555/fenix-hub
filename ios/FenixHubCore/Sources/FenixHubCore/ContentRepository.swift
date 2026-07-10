/// Local content repository for published and received items.
///
/// Manages the lifecycle of content items:
/// - Items imported via Share Sheet
/// - Items pulled from peers
/// - Published/unpublished state
///
/// For MVP, all items are stored in memory and in the app's caches directory.
/// Phase 2+ should use Core Data or SwiftData for persistence.

import Foundation

/// A content item managed by the local repository.
struct LocalItem: Identifiable, Equatable {
    let id: String
    let announcement: Announcement
    let data: Data
    let addedAt: Date

    /// File URL in the app's caches directory (nil for in-memory items).
    var fileURL: URL?

    /// Whether this item is currently published to the network.
    var isPublished: Bool = false

    /// Whether this item was received from a peer (vs. created locally).
    var isFromPeer: Bool = false
}

/// Repository for local content items.
class ContentRepository {
    private var items: [String: LocalItem] = [:]
    private let fileManager = FileManager.default

    /// Directory for cached content files.
    private var cacheDir: URL {
        let urls = fileManager.urls(for: .cachesDirectory, in: .userDomainMask)
        let dir = urls[0].appendingPathComponent("fenixhub_content", isDirectory: true)
        try? fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Add an item to the repository (from Share Sheet or local creation).
    @discardableResult
    func add(id: String, announcement: Announcement, data: Data) -> LocalItem {
        let item = LocalItem(
            id: id,
            announcement: announcement,
            data: data,
            addedAt: Date(),
            fileURL: nil,
            isPublished: false,
            isFromPeer: false
        )
        items[id] = item
        return item
    }

    /// Add an item received from a peer.
    @discardableResult
    func addReceived(from peer: String, announcement: Announcement, data: Data) -> LocalItem {
        let item = LocalItem(
            id: announcement.contentId,
            announcement: announcement,
            data: data,
            addedAt: Date(),
            fileURL: saveToCache(id: announcement.contentId, data: data),
            isPublished: false,
            isFromPeer: true
        )
        items[announcement.contentId] = item
        return item
    }

    /// Get item by id.
    func get(id: String) -> LocalItem? {
        items[id]
    }

    /// All items.
    func allItems() -> [LocalItem] {
        Array(items.values).sorted { $0.addedAt > $1.addedAt }
    }

    /// Items available for publishing.
    func publishableItems() -> [LocalItem] {
        allItems().filter { !$0.isPublished }
    }

    /// Published items.
    func publishedItems() -> [LocalItem] {
        allItems().filter { $0.isPublished }
    }

    /// Mark an item as published/unpublished.
    func setPublished(id: String, published: Bool) {
        if var item = items[id] {
            item.isPublished = published
            items[id] = item
        }
    }

    /// Remove an item.
    @discardableResult
    func remove(id: String) -> LocalItem? {
        let item = items.removeValue(forKey: id)
        if let url = item?.fileURL {
            try? fileManager.removeItem(at: url)
        }
        return item
    }

    /// Remove all items.
    func removeAll() {
        for (_, item) in items {
            if let url = item.fileURL {
                try? fileManager.removeItem(at: url)
            }
        }
        items.removeAll()
    }

    // MARK: - Persistence

    /// Save content data to a cache file.
    private func saveToCache(id: String, data: Data) -> URL? {
        let url = cacheDir.appendingPathComponent("\(id).dat")
        do {
            try data.write(to: url)
            return url
        } catch {
            return nil
        }
    }

    /// Load content data from a cache file.
    func loadFromCache(url: URL) -> Data? {
        try? Data(contentsOf: url)
    }
}
