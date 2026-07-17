import Foundation
import Observation
import Security

// MARK: - Keychain (API key storage)

enum Keychain {
    private static let service = "com.sinrglab.iOSGlasses"

    static func get(_ account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    @discardableResult
    static func set(_ value: String, for account: String) -> Bool {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let data = Data(value.utf8)
        let update: [String: Any] = [kSecValueData as String: data]
        let status = SecItemUpdate(base as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var add = base
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
        }
        return status == errSecSuccess
    }

    static func delete(_ account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}

// MARK: - Settings

@MainActor
@Observable
final class SettingsStore {
    static let models = ["gpt-realtime-2.1", "gpt-realtime-2.1-mini"]
    static let efforts = ["minimal", "low", "medium", "high"]
    static let voices = ["marin", "cedar", "alloy"]

    private static let apiKeyAccount = "openai-api-key"

    /// OpenAI API key — kept in the Keychain, never in UserDefaults.
    private(set) var apiKey: String

    var model: String {
        didSet { UserDefaults.standard.set(model, forKey: "realtime.model") }
    }
    var reasoningEffort: String {
        didSet { UserDefaults.standard.set(reasoningEffort, forKey: "realtime.effort") }
    }
    var voice: String {
        didSet { UserDefaults.standard.set(voice, forKey: "realtime.voice") }
    }

    init() {
        apiKey = Keychain.get(Self.apiKeyAccount) ?? ""
        let d = UserDefaults.standard
        model = d.string(forKey: "realtime.model") ?? Self.models[0]
        reasoningEffort = d.string(forKey: "realtime.effort") ?? "low"
        voice = d.string(forKey: "realtime.voice") ?? Self.voices[0]
    }

    func setAPIKey(_ key: String) {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        apiKey = trimmed
        if trimmed.isEmpty {
            Keychain.delete(Self.apiKeyAccount)
        } else {
            Keychain.set(trimmed, for: Self.apiKeyAccount)
        }
    }
}
