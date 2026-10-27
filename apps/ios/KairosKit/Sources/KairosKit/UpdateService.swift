import CryptoKit
import Foundation

// Finding, downloading and keeping firmware releases from GitHub (docs/ota-plan.md). Nothing here talks to
// a device, and nothing here runs unless the app asks it to.

public struct HTTPResponse {
    public var status: Int
    public var data: Data
    public var etag: String?

    public init(status: Int, data: Data, etag: String? = nil) {
        self.status = status
        self.data = data
        self.etag = etag
    }
}

public protocol HTTPFetching {
    /// GET `url`. With an `etag`, a "not modified" answer comes back as status 304 with no data.
    func fetch(_ url: URL, etag: String?) async throws -> HTTPResponse
}

public struct URLSessionFetcher: HTTPFetching {
    private let session: URLSession

    public init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 120
        configuration.httpCookieStorage = nil
        session = URLSession(configuration: configuration)
    }

    public func fetch(_ url: URL, etag: String?) async throws -> HTTPResponse {
        var request = URLRequest(url: url)
        request.setValue("Kairos", forHTTPHeaderField: "User-Agent")
        request.setValue("application/octet-stream, application/json", forHTTPHeaderField: "Accept")
        if let etag { request.setValue(etag, forHTTPHeaderField: "If-None-Match") }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return HTTPResponse(status: http.statusCode, data: data, etag: http.value(forHTTPHeaderField: "ETag"))
    }
}

/// What the update check remembers between launches.
public protocol UpdateStore: AnyObject {
    var lastCheck: Date? { get set }
    var manifest: Data? { get set }
    var etag: String? { get set }
    /// The user's setting: check for updates when connecting.
    var checkOnConnect: Bool { get set }
    var includePrereleases: Bool { get set }
}

public final class MemoryUpdateStore: UpdateStore {
    public var lastCheck: Date?
    public var manifest: Data?
    public var etag: String?
    public var checkOnConnect = true
    public var includePrereleases = false
    public init() {}
}

public final class UserDefaultsUpdateStore: UpdateStore {
    private let defaults: UserDefaults
    private enum Key {
        static let lastCheck = "update.lastCheck", manifest = "update.manifest", etag = "update.etag"
        static let onConnect = "update.checkOnConnect", prereleases = "update.includePrereleases"
    }

    public init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    public var lastCheck: Date? {
        get { defaults.object(forKey: Key.lastCheck) as? Date }
        set { defaults.set(newValue, forKey: Key.lastCheck) }
    }
    public var manifest: Data? {
        get { defaults.data(forKey: Key.manifest) }
        set { defaults.set(newValue, forKey: Key.manifest) }
    }
    public var etag: String? {
        get { defaults.string(forKey: Key.etag) }
        set { defaults.set(newValue, forKey: Key.etag) }
    }
    public var checkOnConnect: Bool {
        get { defaults.object(forKey: Key.onConnect) as? Bool ?? true }
        set { defaults.set(newValue, forKey: Key.onConnect) }
    }
    public var includePrereleases: Bool {
        get { defaults.bool(forKey: Key.prereleases) }
        set { defaults.set(newValue, forKey: Key.prereleases) }
    }
}

/// One published release, from the GitHub API.
public struct Release: Equatable, Identifiable {
    public var tag: String
    public var prerelease: Bool
    public var manifestURL: URL?
    public var id: String { tag }

    /// The version without the leading "v".
    public var version: String { tag.hasPrefix("v") ? String(tag.dropFirst()) : tag }
}

public final class UpdateService {
    public static let repository = "nprail/kairos"
    /// The newest release is looked up at most this often (the Check button ignores it).
    public static let minimumInterval: TimeInterval = 10 * 60

    private let fetcher: HTTPFetching
    private let store: UpdateStore
    private let repository: String
    private let now: () -> Date

    public init(
        fetcher: HTTPFetching = URLSessionFetcher(), store: UpdateStore = UserDefaultsUpdateStore(),
        repository: String = UpdateService.repository, now: @escaping () -> Date = Date.init
    ) {
        self.fetcher = fetcher
        self.store = store
        self.repository = repository
        self.now = now
    }

    private func url(_ path: String) -> URL { URL(string: "https://github.com/\(repository)/releases/\(path)")! }

    private static let unreachable = DeviceError(
        code: "network", message: "Could not reach GitHub to look for updates. Check the phone's internet connection.")

    /// The newest stable release's manifest. Within the minimum interval of a successful check it answers
    /// from the saved copy without touching the network; `force` always asks. A "not modified" answer
    /// from GitHub keeps the saved copy.
    public func latest(force: Bool = false) async throws -> (manifest: ReleaseManifest, fromCache: Bool) {
        if !force, let last = store.lastCheck, now().timeIntervalSince(last) < Self.minimumInterval,
           let saved = store.manifest, let manifest = ReleaseManifest.parse(saved)
        {
            return (manifest, true)
        }
        let response: HTTPResponse
        do {
            response = try await fetcher.fetch(url("latest/download/manifest.json"), etag: store.manifest == nil ? nil : store.etag)
        } catch {
            throw Self.unreachable
        }
        if response.status == 304, let saved = store.manifest, let manifest = ReleaseManifest.parse(saved) {
            store.lastCheck = now()
            return (manifest, true)
        }
        guard response.status == 200, let manifest = ReleaseManifest.parse(response.data) else {
            throw DeviceError(code: "network", message: "GitHub did not have a firmware release to offer (status \(response.status)).")
        }
        store.manifest = response.data
        store.etag = response.etag
        store.lastCheck = now()
        return (manifest, false)
    }

    /// Releases, newest first, for the Advanced list. Pre-releases only when asked.
    public func releases(includePrereleases: Bool) async throws -> [Release] {
        let api = URL(string: "https://api.github.com/repos/\(repository)/releases?per_page=30")!
        let response: HTTPResponse
        do { response = try await fetcher.fetch(api, etag: nil) } catch { throw Self.unreachable }
        guard response.status == 200, let list = (try? JSONSerialization.jsonObject(with: response.data)) as? [[String: Any]] else {
            throw DeviceError(code: "network", message: "Could not read the list of releases from GitHub (status \(response.status)).")
        }
        let all: [Release] = list.compactMap { item in
            guard item["draft"] as? Bool != true, let tag = item["tag_name"] as? String else { return nil }
            let assets = item["assets"] as? [[String: Any]] ?? []
            let manifest = assets.first { $0["name"] as? String == "manifest.json" }?["browser_download_url"] as? String
            return Release(tag: tag, prerelease: item["prerelease"] as? Bool ?? false, manifestURL: manifest.flatMap(URL.init(string:)))
        }
        return all.filter { includePrereleases || !$0.prerelease }
    }

    public func manifest(for release: Release) async throws -> ReleaseManifest {
        let source = release.manifestURL ?? url("download/\(release.tag)/manifest.json")
        let response: HTTPResponse
        do { response = try await fetcher.fetch(source, etag: nil) } catch { throw Self.unreachable }
        guard response.status == 200, let manifest = ReleaseManifest.parse(response.data) else {
            throw DeviceError(code: "network", message: "That release has no usable firmware manifest.")
        }
        return manifest
    }

    /// Download an image and check its size and SHA-256 against the manifest.
    public func download(_ image: ReleaseManifest.Image, version: String) async throws -> Data {
        guard let file = image.file, let size = image.size, let sha = image.sha256,
              let source = URL(string: "https://github.com/\(repository)/releases/download/v\(version)/\(file)")
        else { throw DeviceError(code: "network", message: "The release lists no file to download.") }
        let response: HTTPResponse
        do { response = try await fetcher.fetch(source, etag: nil) } catch { throw Self.unreachable }
        guard response.status == 200 else {
            throw DeviceError(code: "network", message: "GitHub did not have the firmware file (status \(response.status)).")
        }
        guard response.data.count == size, Self.sha256Hex(response.data) == sha else {
            throw DeviceError(code: "bad_download", message: "The downloaded firmware didn't match the release. Try again.")
        }
        return response.data
    }

    public static func sha256Hex(_ data: Data) -> String { Data(SHA256.hash(data: data)).hexString }
}

/// Downloaded images, kept on the phone so an update can be installed with no signal. They are named by
/// their SHA-256, and only the most recent few are kept.
public final class FirmwareCache {
    private let directory: URL
    private let keep: Int

    public init(directory: URL, keep: Int = 2) {
        self.directory = directory
        self.keep = keep
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    private func file(_ sha256: String) -> URL { directory.appendingPathComponent("\(sha256).bin") }

    /// A saved image, or nil if there is none or it no longer matches (a damaged file is removed).
    public func image(sha256: String, size: Int) -> Data? {
        guard let data = try? Data(contentsOf: file(sha256)) else { return nil }
        if data.count == size, UpdateService.sha256Hex(data) == sha256 { return data }
        try? FileManager.default.removeItem(at: file(sha256))
        return nil
    }

    public func store(_ data: Data, sha256: String) {
        var url = file(sha256)
        try? data.write(to: url, options: .atomic)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true  // a download can be fetched again; it does not belong in a backup
        try? url.setResourceValues(values)
        trim()
    }

    private func trim() {
        let urls = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        let dated = urls.filter { $0.pathExtension == "bin" }.map { url -> (URL, Date) in
            (url, (try? url.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast)
        }.sorted { $0.1 > $1.1 }
        for (url, _) in dated.dropFirst(keep) { try? FileManager.default.removeItem(at: url) }
    }
}
