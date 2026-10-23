import Foundation
import Security

/// One-way migration for builds that previously accepted a user-supplied
/// permanent OpenAI credential. There is intentionally no read or write API:
/// upgraded installations can only delete the obsolete Keychain item.
enum LegacySecretCleanup {
    static func removeObsoleteOpenAICredential() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.sinrglab.iOSGlasses",
            kSecAttrAccount as String: "openai-api-key",
        ]
        SecItemDelete(query as CFDictionary)
    }
}
