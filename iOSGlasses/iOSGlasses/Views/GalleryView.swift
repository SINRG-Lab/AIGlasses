import SwiftUI

struct GalleryView: View {
    @Environment(AppModel.self) private var app
    @State private var selected: GalleryStore.Photo?

    private let columns = [GridItem(.adaptive(minimum: 110), spacing: 8)]

    var body: some View {
        NavigationStack {
            Group {
                if app.gallery.photos.isEmpty {
                    ContentUnavailableView(
                        "No photos yet",
                        systemImage: "camera",
                        description: Text("Double-tap the button on the glasses to take a photo.")
                    )
                } else {
                    ScrollView {
                        LazyVGrid(columns: columns, spacing: 8) {
                            ForEach(app.gallery.photos) { photo in
                                thumbnail(photo)
                            }
                        }
                        .padding(8)
                    }
                }
            }
            .navigationTitle("Gallery")
            .fullScreenCover(item: $selected) { photo in
                PhotoPager(initial: photo)
            }
        }
    }

    private func thumbnail(_ photo: GalleryStore.Photo) -> some View {
        Button {
            selected = photo
        } label: {
            ZStack(alignment: .bottomTrailing) {
                Group {
                    if let img = UIImage(contentsOfFile: photo.url.path) {
                        Image(uiImage: img)
                            .resizable()
                            .scaledToFill()
                    } else {
                        ZStack {
                            Color.gray.opacity(0.2)
                            Image(systemName: "photo")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .frame(minWidth: 0, maxWidth: .infinity)
                .aspectRatio(1, contentMode: .fill)
                .clipShape(RoundedRectangle(cornerRadius: 10))

                Text(Self.sizeLabel(photo.bytes))
                    .font(.caption2.weight(.semibold).monospacedDigit())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(.black.opacity(0.55), in: Capsule())
                    .padding(5)
            }
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button(role: .destructive) {
                app.gallery.delete(photo)
            } label: {
                Label("Delete", systemImage: "trash")
            }
        }
    }

    static func sizeLabel(_ bytes: Int) -> String {
        bytes >= 1024 * 1024
            ? String(format: "%.1f MB", Double(bytes) / (1024 * 1024))
            : "\(bytes / 1024) KB"
    }
}

/// Full-screen viewer: swipe left/right to move between photos, swipe down to
/// close (the photo follows the finger and the backdrop fades, Photos-style).
private struct PhotoPager: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss

    let initial: GalleryStore.Photo
    @State private var currentId: String
    @State private var dragOffset: CGFloat = 0

    init(initial: GalleryStore.Photo) {
        self.initial = initial
        _currentId = State(initialValue: initial.id)
    }

    private var current: GalleryStore.Photo? {
        app.gallery.photos.first { $0.id == currentId }
    }

    var body: some View {
        ZStack {
            Color.black
                .opacity(max(0.4, 1.0 - Double(dragOffset) / 500.0))
                .ignoresSafeArea()

            TabView(selection: $currentId) {
                ForEach(app.gallery.photos) { photo in
                    photoPage(photo)
                        .tag(photo.id)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .offset(y: dragOffset)
            .scaleEffect(max(0.85, 1.0 - dragOffset / 1500.0))

            overlayControls
        }
        // Vertical pull runs alongside the pager's horizontal swipe; only a
        // predominantly-downward drag moves the photo.
        .simultaneousGesture(
            DragGesture(minimumDistance: 15)
                .onChanged { v in
                    if dragOffset > 0 || v.translation.height > abs(v.translation.width) {
                        dragOffset = max(0, v.translation.height)
                    }
                }
                .onEnded { _ in
                    if dragOffset > 130 {
                        dismiss()
                    } else {
                        withAnimation(.spring(duration: 0.3)) { dragOffset = 0 }
                    }
                }
        )
        .statusBarHidden()
    }

    private func photoPage(_ photo: GalleryStore.Photo) -> some View {
        Group {
            if let img = UIImage(contentsOfFile: photo.url.path) {
                Image(uiImage: img)
                    .resizable()
                    .scaledToFit()
            } else {
                Text("Could not load photo")
                    .foregroundStyle(.white)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var overlayControls: some View {
        VStack {
            HStack {
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(10)
                        .background(.black.opacity(0.5), in: Circle())
                }
                Spacer()
                Button {
                    deleteCurrent()
                } label: {
                    Image(systemName: "trash")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.red)
                        .padding(10)
                        .background(.black.opacity(0.5), in: Circle())
                }
            }
            .padding(.horizontal)

            Spacer()

            if let photo = current {
                Text(infoLine(photo))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.white.opacity(0.9))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(.black.opacity(0.5), in: Capsule())
                    .padding(.bottom, 24)
            }
        }
        .opacity(dragOffset > 0 ? 0 : 1)   // controls get out of the way mid-drag
    }

    private func infoLine(_ photo: GalleryStore.Photo) -> String {
        var parts: [String] = []
        if let img = UIImage(contentsOfFile: photo.url.path) {
            parts.append("\(Int(img.size.width * img.scale))×\(Int(img.size.height * img.scale))")
        }
        parts.append(GalleryView.sizeLabel(photo.bytes))
        parts.append(photo.date.formatted(date: .abbreviated, time: .shortened))
        return parts.joined(separator: " · ")
    }

    private func deleteCurrent() {
        guard let photo = current else { return }
        let photos = app.gallery.photos
        // Pick the neighbor to land on before the array shrinks.
        let idx = photos.firstIndex(of: photo)
        let next = idx.flatMap { i -> GalleryStore.Photo? in
            if photos.count <= 1 { return nil }
            return i + 1 < photos.count ? photos[i + 1] : photos[i - 1]
        }
        app.gallery.delete(photo)
        if let next {
            currentId = next.id
        } else {
            dismiss()
        }
    }
}
