/// Bonjour/mDNS publisher for FenixHub announcements.
///
/// Publishes `_fenixhub._tcp.` service with TXT records containing chunked
/// announcement JSON, so existing FenixHub desktop/Android peers can discover
/// iOS content on the local network.
///
/// Uses Foundation's `NetService` for publishing.
///
/// Rust source-of-truth:
///   crates/fenix-hub-daemon/src/mdns.rs
///
/// Android source-of-truth:
///   android/app/src/main/java/com/fenixhub/mobile/network/NsdController.kt

import Foundation

class BonjourPublisher: NSObject, NetServiceDelegate {
    private var netService: NetService?
    private let serviceType: String
    private let port: Int32

    /// Initialize a Bonjour publisher.
    /// - Parameters:
    ///   - serviceType: Bonjour service type (defaults to `_fenixhub._tcp.`)
    ///   - port: TCP port the HTTP server is listening on
    init(serviceType: String = MDNS_SERVICE_TYPE, port: Int32) {
        self.serviceType = serviceType.hasSuffix(".") ? serviceType : serviceType + "."
        self.port = port
        super.init()
    }

    /// Publish an announcement over Bonjour.
    /// - Parameter announcement: the content announcement to advertise
    /// - Throws: if TXT encoding or NetService publish fails
    func publish(announcement: Announcement) throws {
        stop()

        let txtRecord = try AnnouncementCodec.encode(announcement)
        let netService = NetService(
            domain: "local.",
            type: serviceType,
            name: "\(announcement.deviceName)-\(announcement.contentId.prefix(8))",
            port: port
        )

        // Build TXT record data (format: key=value pairs, each preceded by a length byte).
        var txtData = Data()
        for (key, value) in txtRecord.sorted(by: { $0.key < $1.key }) {
            let entry = "\(key)=\(value)"
            var entryLen = UInt8(entry.utf8.count)
            txtData.append(&entryLen, count: 1)
            txtData.append(entry.data(using: .utf8)!)
        }
        netService.setTXTRecord(txtData)

        netService.delegate = self
        netService.publish()
        self.netService = netService
    }

    /// Stop publishing and remove the service from the network.
    func stop() {
        netService?.stop()
        netService = nil
    }

    /// Re-publish an announcement (useful for periodic re-announcement).
    func republish(announcement: Announcement) throws {
        try publish(announcement: announcement)
    }

    // MARK: - NetServiceDelegate

    func netServiceDidPublish(_ sender: NetService) {
        print("Bonjour published: \(sender.name) type=\(sender.type) port=\(sender.port)")
    }

    func netService(_ sender: NetService, didNotPublish errorDict: [String: NSNumber]) {
        print("Bonjour publish failed: \(errorDict)")
    }
}
