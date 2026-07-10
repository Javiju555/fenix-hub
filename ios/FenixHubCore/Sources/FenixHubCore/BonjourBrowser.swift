/// Bonjour/mDNS browser for FenixHub peer discovery.
///
/// Browses `_fenixhub._tcp.` services on the local network and parses
/// TXT records into `Announcement` objects so the iOS app can discover
/// content published by desktop/Android FenixHub peers.
///
/// Uses Foundation's `NetServiceBrowser`.
///
/// Rust source-of-truth:
///   crates/fenix-hub-daemon/src/mdns.rs
///
/// Android source-of-truth:
///   android/app/src/main/java/com/fenixhub/mobile/network/NsdController.kt

import Foundation

/// A peer discovered on the local network.
struct PeerDevice: Identifiable, Equatable {
    let id: String
    let name: String
    let host: String
    let port: Int
    let announcements: [Announcement]

    var serviceName: String { "\(name) (\(host):\(port))" }
}

/// Callbacks for peer discovery events.
protocol BonjourBrowserDelegate: AnyObject {
    func bonjourBrowser(_ browser: BonjourBrowser, didDiscover peer: PeerDevice)
    func bonjourBrowser(_ browser: BonjourBrowser, didUpdate peer: PeerDevice)
    func bonjourBrowser(_ browser: BonjourBrowser, didRemove peer: PeerDevice)
    func bonjourBrowser(_ browser: BonjourBrowser, didEncounter error: Error)
}

/// mDNS service browser for FenixHub peers.
///
/// Usage:
/// ```
/// let browser = BonjourBrowser()
/// browser.delegate = self
/// browser.startBrowsing()
/// ```
class BonjourBrowser: NSObject {
    private var browser: NetServiceBrowser?
    private var discoveredServices: [String: NetService] = [:]
    private var resolvedPeers: [String: PeerDevice] = [:]

    /// Group ID filter — only show peers matching this group. nil = show all.
    var groupIdFilter: String?

    weak var delegate: BonjourBrowserDelegate?

    /// Start browsing for FenixHub services on the local network.
    func startBrowsing() {
        stopBrowsing()

        let browser = NetServiceBrowser()
        browser.delegate = self
        browser.searchForServices(ofType: MDNS_SERVICE_TYPE, inDomain: "local.")
        self.browser = browser
    }

    /// Stop browsing.
    func stopBrowsing() {
        browser?.stop()
        browser = nil
        discoveredServices.removeAll()
        resolvedPeers.removeAll()
    }
}

// MARK: - NetServiceBrowserDelegate

extension BonjourBrowser: NetServiceBrowserDelegate {
    func netServiceBrowser(_ browser: NetServiceBrowser,
                           didFind service: NetService,
                           moreComing: Bool) {
        discoveredServices[service.name] = service
        service.delegate = self
        service.resolve(withTimeout: 5.0)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser,
                           didRemove service: NetService,
                           moreComing: Bool) {
        discoveredServices.removeValue(forKey: service.name)
        if let removed = resolvedPeers.removeValue(forKey: service.name) {
            delegate?.bonjourBrowser(self, didRemove: removed)
        }
    }

    func netServiceBrowser(_ browser: NetServiceBrowser,
                           didNotSearch errorDict: [String: NSNumber]) {
        let error = NSError(domain: "BonjourBrowser",
                           code: -1,
                           userInfo: errorDict)
        delegate?.bonjourBrowser(self, didEncounter: error)
    }
}

// MARK: - NetServiceDelegate

extension BonjourBrowser: NetServiceDelegate {
    func netServiceDidResolveAddress(_ service: NetService) {
        guard let hostName = service.hostName,
              let txtData = service.txtRecordData() else {
            return
        }

        let txt = Self.parseTXTRecord(txtData)

        // Quick group filter before full decode.
        if let filter = groupIdFilter {
            if let rawGroupId = txt["group_id"], rawGroupId != filter {
                return // Foreign group, skip.
            }
        }

        guard let announcement = AnnouncementCodec.decode(from: txt) else {
            return
        }

        let peer = PeerDevice(
            id: announcement.contentId,
            name: announcement.deviceName,
            host: hostName,
            port: service.port,
            announcements: [announcement]
        )

        resolvedPeers[service.name] = peer

        if discoveredServices[service.name] != nil {
            delegate?.bonjourBrowser(self, didDiscover: peer)
        } else {
            delegate?.bonjourBrowser(self, didUpdate: peer)
        }
    }

    func netService(_ service: NetService,
                    didNotResolve errorDict: [String: NSNumber]) {
        let error = NSError(domain: "BonjourBrowser",
                           code: -2,
                           userInfo: errorDict)
        delegate?.bonjourBrowser(self, didEncounter: error)
    }

    /// Parse TXT record data (key=value pairs, each preceded by a length byte).
    static func parseTXTRecord(_ data: Data) -> [String: String] {
        var result: [String: String] = [:]
        var offset = 0
        while offset < data.count {
            let len = Int(data[offset])
            offset += 1
            guard offset + len <= data.count else { break }
            let entry = data[offset..<offset + len]
            offset += len
            if let str = String(data: entry, encoding: .utf8),
               let eqRange = str.range(of: "=") {
                let key = String(str[..<eqRange.lowerBound])
                let value = String(str[eqRange.upperBound...])
                result[key] = value
            }
        }
        return result
    }
}
