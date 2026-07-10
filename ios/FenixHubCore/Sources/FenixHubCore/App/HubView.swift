/// Main hub view: local items + discovered peer items + publish controls.

import SwiftUI

struct HubView: View {
    @EnvironmentObject var appState: FenixHubState
    @State private var selectedTab: Tab = .local

    enum Tab: String, CaseIterable {
        case local = "Mis archivos"
        case network = "Red"
    }

    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Text("FenixHub")
                    .font(.title2)
                    .bold()
                Spacer()
                if appState.isPublishing {
                    HStack(spacing: 4) {
                        Circle()
                            .fill(.green)
                            .frame(width: 8, height: 8)
                        Text("Publicando")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }
            .padding(.horizontal)
            .padding(.top, 8)

            // Status bar
            if !appState.statusMessage.isEmpty {
                Text(appState.statusMessage)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .padding(.horizontal)
            }

            // Tabs
            Picker("", selection: $selectedTab) {
                ForEach(Tab.allCases, id: \.self) { tab in
                    Text(tab.rawValue).tag(tab)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .padding(.vertical, 8)

            // Content
            switch selectedTab {
            case .local:
                LocalContentView()
            case .network:
                NetworkContentView()
            }

            Spacer()
        }
    }
}

// MARK: - Local content

struct LocalContentView: View {
    @EnvironmentObject var appState: FenixHubState

    var body: some View {
        let items = appState.contentRepo.allItems()

        if items.isEmpty {
            ContentUnavailableView(
                "Sin contenido",
                systemImage: "tray",
                description: Text("Comparte archivos desde Fotos o Archivos usando el botón Compartir")
            )
        } else {
            List {
                ForEach(items) { item in
                    LocalItemRow(item: item)
                }
                .onDelete { indexSet in
                    for index in indexSet {
                        let item = items[index]
                        appState.contentRepo.remove(id: item.id)
                    }
                }
            }
            .listStyle(.plain)
        }
    }
}

struct LocalItemRow: View {
    let item: LocalItem
    @EnvironmentObject var appState: FenixHubState

    var body: some View {
        HStack {
            Image(systemName: iconForType(item.announcement.contentType))
                .foregroundColor(.accentColor)

            VStack(alignment: .leading) {
                Text(item.announcement.fileName ?? item.announcement.preview)
                    .lineLimit(1)
                Text(formattedSize(item.announcement.sizeBytes))
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Spacer()

            if item.isFromPeer {
                Label("", systemImage: "arrow.down.circle.fill")
                    .foregroundColor(.blue)
            }

            Button(item.isPublished ? "Detener" : "Publicar") {
                togglePublish(item)
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
        }
        .padding(.vertical, 4)
    }

    private func iconForType(_ type: ContentType) -> String {
        switch type {
        case .text: return "doc.text"
        case .file: return "doc"
        case .image: return "photo"
        case .video: return "video"
        case .audio: return "music.note"
        case .url: return "link"
        case .folder: return "folder"
        case .empty: return "tray"
        }
    }

    private func formattedSize(_ bytes: UInt64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        return formatter.string(fromByteCount: Int64(bytes))
    }

    private func togglePublish(_ item: LocalItem) {
        if item.isPublished {
            appState.contentRepo.setPublished(id: item.id, published: false)
            appState.isPublishing = appState.contentRepo.publishedItems().count > 0
        } else {
            appState.contentRepo.setPublished(id: item.id, published: true)
            appState.isPublishing = true
        }
    }
}

// MARK: - Network content

struct NetworkContentView: View {
    @EnvironmentObject var appState: FenixHubState

    var body: some View {
        let peers = appState.peerItems

        if peers.isEmpty {
            ContentUnavailableView(
                "Buscando dispositivos...",
                systemImage: "wifi",
                description: Text("Asegúrate de estar en la misma red WiFi que otros dispositivos FenixHub")
            )
        } else {
            List {
                ForEach(peers) { item in
                    PeerItemRow(item: item)
                        .environmentObject(appState)
                }
            }
            .listStyle(.plain)
            .refreshable {
                // Force re-scan.
            }
        }
    }
}

struct PeerItemRow: View {
    let item: DiscoveredItem
    @EnvironmentObject var appState: FenixHubState

    var body: some View {
        HStack {
            Image(systemName: "antenna.radiowaves.left.and.right")
                .foregroundColor(.accentColor)

            VStack(alignment: .leading) {
                Text(item.announcement.fileName ?? item.announcement.preview)
                    .lineLimit(1)
                HStack {
                    Text(item.peerName)
                    Text(formattedSize(item.announcement.sizeBytes))
                }
                .font(.caption)
                .foregroundColor(.secondary)
            }

            Spacer()

            if item.isDownloading {
                ProgressView()
                    .progressViewStyle(.circular)
            } else if item.isDownloaded {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundColor(.green)
            } else {
                Button("Descargar") {
                    pullContent(item)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
            }
        }
        .padding(.vertical, 4)
    }

    private func formattedSize(_ bytes: UInt64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        return formatter.string(fromByteCount: Int64(bytes))
    }

    private func pullContent(_ item: DiscoveredItem) {
        // Placeholder: will trigger FenixHttpClient.
        appState.statusMessage = "Descargando..."
    }
}
