/// FenixHub iOS app entry point.
///
/// This file is a template for the Xcode project's main app.
/// Once the Xcode project is created, this should be the @main entry point.
///
/// For MVP, the app lifecycle is:
/// 1. Setup screen (first launch: device name + passphrase)
/// 2. Hub screen (list local items + discovered peers)
/// 3. Share Sheet integration via Share Extension
///
/// Build instructions:
/// 1. Create a new Xcode project (iOS App, SwiftUI)
/// 2. Add this package as a local Swift Package dependency
/// 3. Use FenixHubApp.swift as the App entry point

import SwiftUI

// Uncomment once Xcode project is set up:
// @main
struct FenixHubApp: App {
    @StateObject private var appState = FenixHubState()

    var body: some Scene {
        WindowGroup {
            if appState.isSetupComplete {
                HubView()
                    .environmentObject(appState)
            } else {
                SetupView()
                    .environmentObject(appState)
            }
        }
    }
}

/// Global application state.
class FenixHubState: ObservableObject {
    @Published var isSetupComplete: Bool = false
    @Published var identity: GroupIdentity?
    @Published var localItems: [LocalItem] = []
    @Published var peerItems: [DiscoveredItem] = []
    @Published var isPublishing: Bool = false
    @Published var serverPort: UInt16 = 0
    @Published var statusMessage: String = ""

    let contentRepo = ContentRepository()
    let bonjourBrowser = BonjourBrowser()
    var bonjourPublisher: BonjourPublisher?

    func completeSetup(deviceName: String, passphrase: String) throws {
        let identity = try GroupIdentity(passphrase: passphrase, deviceName: deviceName)
        self.identity = identity
        self.isSetupComplete = true
    }
}
