/// First-launch setup screen for device name and group passphrase.

import SwiftUI

struct SetupView: View {
    @EnvironmentObject var appState: FenixHubState

    @State private var deviceName: String = UIDevice.current.name
    @State private var passphrase: String = ""
    @State private var confirmPassphrase: String = ""
    @State private var errorMessage: String?
    @State private var isLoading = false

    var body: some View {
        VStack(spacing: 20) {
            Spacer()

            Image(systemName: "antenna.radiowaves.left.and.right")
                .font(.system(size: 60))
                .foregroundColor(.accentColor)

            Text("FenixHub")
                .font(.largeTitle)
                .bold()

            Text("Comparte archivos en tu red local\nsin internet ni cuentas")
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)

            Spacer()

            VStack(alignment: .leading, spacing: 4) {
                Text("Nombre del dispositivo")
                    .font(.caption)
                    .foregroundColor(.secondary)
                TextField("Ej: iPhone de Javier", text: $deviceName)
                    .textFieldStyle(.roundedBorder)
                    .disableAutocorrection(true)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text("Frase de acceso del grupo")
                    .font(.caption)
                    .foregroundColor(.secondary)
                SecureField("Mínimo 10 caracteres", text: $passphrase)
                    .textFieldStyle(.roundedBorder)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text("Confirmar frase")
                    .font(.caption)
                    .foregroundColor(.secondary)
                SecureField("Repite la frase", text: $confirmPassphrase)
                    .textFieldStyle(.roundedBorder)
            }

            if let error = errorMessage {
                Text(error)
                    .foregroundColor(.red)
                    .font(.caption)
            }

            Button(action: setup) {
                if isLoading {
                    ProgressView()
                        .progressViewStyle(.circular)
                } else {
                    Text("Configurar")
                        .frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(isLoading || !isFormValid)

            Spacer()
        }
        .padding()
    }

    private var isFormValid: Bool {
        !deviceName.trimmingCharacters(in: .whitespaces).isEmpty
            && passphrase.count >= MIN_PASSPHRASE_LEN
            && passphrase == confirmPassphrase
    }

    private func setup() {
        isLoading = true
        errorMessage = nil
        do {
            try appState.completeSetup(
                deviceName: deviceName.trimmingCharacters(in: .whitespaces),
                passphrase: passphrase
            )
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }
}

#if DEBUG
struct SetupView_Previews: PreviewProvider {
    static var previews: some View {
        SetupView()
            .environmentObject(FenixHubState())
    }
}
#endif
