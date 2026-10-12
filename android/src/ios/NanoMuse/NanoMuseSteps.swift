import SwiftUI

/// "Show the agent's steps": whether the tool capsules (a command run, a file read, a page
/// opened…) stay in the chat once a message is finished. On by default since 0.1.37, as on
/// the phone, the web app and the desktop — a stored `false` still wins. While a message is
/// still running its steps show either way, since the phone has no status line under the face
/// to say what it is on. This device only (`@AppStorage`).
enum NanoMuseSteps {
    static let key = "nanomuse.show_steps"
    static let defaultValue = true

    /// Whether to leave this block out of the chat.
    static func hidden(_ kind: AssistantBlockKind, active: Bool, showSteps: Bool) -> Bool {
        guard !showSteps, !active else { return false }
        switch kind {
        case .shellTool, .fileReadTool, .fileWriteTool, .fileEditTool, .browserTool, .readImageTool, .memoryTool:
            return true
        case .text, .thinking, .info:
            return false
        }
    }
}

/// The settings row for it, under Settings → Chat.
struct NanoMuseStepsSection: View {
    @AppStorage(NanoMuseSteps.key) private var showSteps: Bool = NanoMuseSteps.defaultValue

    var body: some View {
        Section {
            Toggle(AppLocalized("Show the agent's steps"), isOn: $showSteps)
        } header: {
            Text(AppLocalized("Steps"))
        } footer: {
            Text(AppLocalized("Every tool it used stays as a capsule in the chat. Off, a finished message keeps to the conversation; the steps of a message still running show either way. This device only."))
        }
    }
}
