import Foundation
import Supabase

/// A short-lived credential that can authenticate one OpenAI Realtime
/// WebSocket handshake. The permanent OpenAI credential never reaches iOS.
struct RealtimeClientCredential: Sendable {
    let secret: String
    let expiresAt: Date
    let model: String

    func isUsable(at date: Date = Date(), minimumLifetime: TimeInterval = 15) -> Bool {
        secret.hasPrefix("ek_") && expiresAt.timeIntervalSince(date) >= minimumLifetime
    }
}
enum RealtimeCredentialServiceError: LocalizedError, Sendable {
    case authenticationUnavailable
    case rateLimited
    case networkUnavailable
    case serviceUnavailable
    case invalidResponse
    case expiredCredential

    var errorDescription: String? {
        switch self {
        case .authenticationUnavailable:
            "The secure voice session could not be authenticated. Try the glasses button again."
        case .rateLimited:
            "Too many voice sessions were started. Wait one minute, then use the glasses button again."
        case .networkUnavailable:
            "The phone could not reach the voice service. Check its internet connection and try again."
        case .serviceUnavailable:
            "The voice service is temporarily unavailable. Try the glasses button again shortly."
        case .invalidResponse:
            "The voice service returned an invalid session. Try again shortly."
        case .expiredCredential:
            "The secure voice session expired before it could connect. Try the glasses button again."
        }
    }
}

/// Owns Supabase anonymous authentication and Edge Function invocation.
///
/// Supabase's Apple client persists the anonymous session in Keychain using
/// `kSecAttrAccessibleAfterFirstUnlock` and refreshes access tokens
/// automatically while the app is active. Every credential request also asks
/// `auth.session` for a currently valid token, which covers a BLE background
/// wake after the automatic refresh loop was suspended by iOS.
actor SupabaseRealtimeService {
    private enum Configuration {
        static let projectURL = URL(string: "https://pnwdupcncssdldrnqsld.supabase.co")!
        // A Supabase publishable key identifies the public client. It is not a
        // server secret and cannot mint OpenAI credentials without user auth.
        static let publishableKey = "sb_publishable_00dREe2TfYQDMTpgAM_SOw_y15LyQf7"
        static let functionName = "realtime-session"
        static let approvedModels = Set(["gpt-realtime-2.1", "gpt-realtime-2.1-mini"])
    }

    private struct SessionRequest: Encodable, Sendable {
        let deviceID: String
        let platform = "ios"
        let appVersion: String

        enum CodingKeys: String, CodingKey {
            case deviceID = "device_id"
            case platform
            case appVersion = "app_version"
        }
    }

    private struct SessionResponse: Decodable, Sendable {
        struct Session: Decodable, Sendable {
            let model: String
        }

        let clientSecret: String
        let expiresAt: Double
        let session: Session

        enum CodingKeys: String, CodingKey {
            case clientSecret = "client_secret"
            case expiresAt = "expires_at"
            case session
        }
    }

    private let client: SupabaseClient
    private let request: SessionRequest
    private var authenticationTask: Task<Void, Error>?
    private var credentialTask: Task<RealtimeClientCredential, Error>?

    init(deviceID: String, appVersion: String) {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 20

        let options = SupabaseClientOptions(
            auth: .init(
                storage: KeychainLocalStorage(service: "com.sinrglab.iOSGlasses.supabase"),
                storageKey: "anonymous-session",
                autoRefreshToken: true
            ),
            global: .init(
                headers: ["X-Client-Info": "sinrg-ios-glasses/\(appVersion)"],
                session: URLSession(configuration: configuration)
            )
        )
        client = SupabaseClient(
            supabaseURL: Configuration.projectURL,
            supabaseKey: Configuration.publishableKey,
            options: options
        )
        request = SessionRequest(deviceID: deviceID, appVersion: appVersion)
    }

    /// Restores or creates the anonymous user without consuming a Realtime
    /// session quota. This is safe to prewarm after BLE connects.
    func prepareAuthentication() async throws {
        try await ensureAuthenticated()
    }

    /// Coalesces concurrent button/mic/photo triggers into one quota
    /// reservation and one client-secret request.
    func fetchCredential() async throws -> RealtimeClientCredential {
        if let credentialTask {
            return try await credentialTask.value
        }

        try await ensureAuthenticated()

        // The actor can be re-entered while auth is in flight, so check again.
        if let credentialTask {
            return try await credentialTask.value
        }

        let client = self.client
        let request = self.request
        let task = Task<RealtimeClientCredential, Error> {
            try await Self.requestCredential(client: client, request: request)
        }
        credentialTask = task
        defer { credentialTask = nil }
        return try await task.value
    }

    private func ensureAuthenticated() async throws {
        if let authenticationTask {
            return try await authenticationTask.value
        }

        let client = self.client
        let task = Task<Void, Error> {
            do {
                _ = try await client.auth.session
            } catch AuthError.sessionMissing {
                _ = try await client.auth.signInAnonymously()
            } catch {
                throw Self.map(error)
            }
        }
        authenticationTask = task
        defer { authenticationTask = nil }
        try await task.value
    }

    private static func requestCredential(
        client: SupabaseClient,
        request: SessionRequest
    ) async throws -> RealtimeClientCredential {
        do {
            let response: SessionResponse
            do {
                response = try await invoke(client: client, request: request)
            } catch FunctionsError.httpError(let code, _) where code == 401 {
                // A backgrounded app can wake with a server-rejected JWT. Do
                // exactly one refresh/re-authentication and exactly one retry.
                try await reauthenticate(client)
                do {
                    response = try await invoke(client: client, request: request)
                } catch FunctionsError.httpError(let retryCode, _) where retryCode == 401 {
                    throw RealtimeCredentialServiceError.authenticationUnavailable
                }
            }

            guard Configuration.approvedModels.contains(response.session.model) else {
                throw RealtimeCredentialServiceError.invalidResponse
            }
            let credential = RealtimeClientCredential(
                secret: response.clientSecret,
                expiresAt: Date(timeIntervalSince1970: response.expiresAt),
                model: response.session.model
            )
            guard credential.isUsable() else {
                throw response.clientSecret.hasPrefix("ek_")
                    ? RealtimeCredentialServiceError.expiredCredential
                    : RealtimeCredentialServiceError.invalidResponse
            }
            return credential
        } catch let error as RealtimeCredentialServiceError {
            throw error
        } catch {
            throw map(error)
        }
    }

    private static func invoke(
        client: SupabaseClient,
        request: SessionRequest
    ) async throws -> SessionResponse {
        try await client.functions.invoke(
            Configuration.functionName,
            options: FunctionInvokeOptions(body: request, timeoutInterval: 15)
        )
    }

    private static func reauthenticate(_ client: SupabaseClient) async throws {
        do {
            _ = try await client.auth.refreshSession()
        } catch {
            // The anonymous user cannot be recovered if its refresh token was
            // revoked. Remove only this device's local session, then replace it.
            try? await client.auth.signOut(scope: .local)
            do {
                _ = try await client.auth.signInAnonymously()
            } catch {
                throw map(error, authentication: true)
            }
        }
    }

    private static func map(
        _ error: Error,
        authentication: Bool = false
    ) -> RealtimeCredentialServiceError {
        if let serviceError = error as? RealtimeCredentialServiceError {
            return serviceError
        }
        if case FunctionsError.httpError(let code, _) = error {
            switch code {
            case 401: return .authenticationUnavailable
            case 429: return .rateLimited
            case 500...599: return .serviceUnavailable
            default: return .invalidResponse
            }
        }
        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain {
            return .networkUnavailable
        }
        return authentication ? .authenticationUnavailable : .serviceUnavailable
    }
}
