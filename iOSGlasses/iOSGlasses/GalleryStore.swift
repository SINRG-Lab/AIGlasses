import Foundation
import Observation

/// Photos received from the glasses, persisted as JPEG files under
/// Documents/GlassesPhotos.
@MainActor
@Observable
final class GalleryStore {

    struct Photo: Identifiable, Hashable {
        let id: String       // filename
        let url: URL
        let date: Date
        let bytes: Int
    }

    private(set) var photos: [Photo] = []   // newest first
    private let dir: URL

    init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        dir = docs.appendingPathComponent("GlassesPhotos", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        reload()
    }

    func reload() {
        let fm = FileManager.default
        let files = (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys:
            [.contentModificationDateKey, .fileSizeKey])) ?? []
        photos = files
            .filter { $0.pathExtension.lowercased() == "jpg" }
            .compactMap { url -> Photo? in
                let vals = try? url.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey])
                return Photo(id: url.lastPathComponent,
                             url: url,
                             date: vals?.contentModificationDate ?? .distantPast,
                             bytes: vals?.fileSize ?? 0)
            }
            .sorted { $0.date > $1.date }
    }

    @discardableResult
    func save(jpeg: Data) throws -> Photo {
        let fmt = DateFormatter()
        fmt.dateFormat = "yyyyMMdd-HHmmss-SSS"
        let name = "photo-\(fmt.string(from: Date())).jpg"
        let url = dir.appendingPathComponent(name)
        try jpeg.write(to: url, options: .atomic)
        let photo = Photo(id: name, url: url, date: Date(), bytes: jpeg.count)
        photos.insert(photo, at: 0)
        return photo
    }

    func delete(_ photo: Photo) {
        try? FileManager.default.removeItem(at: photo.url)
        photos.removeAll { $0.id == photo.id }
    }
}
