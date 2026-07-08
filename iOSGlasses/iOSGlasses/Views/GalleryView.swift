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
                PhotoViewer(photo: photo) {
                    app.gallery.delete(photo)
                    selected = nil
                } onClose: {
                    selected = nil
                }
            }
        }
    }

    private func thumbnail(_ photo: GalleryStore.Photo) -> some View {
        Button {
            selected = photo
        } label: {
            ZStack {
                if let img = UIImage(contentsOfFile: photo.url.path) {
                    Image(uiImage: img)
                        .resizable()
                        .scaledToFill()
                } else {
                    Color.gray.opacity(0.2)
                    Image(systemName: "photo")
                        .foregroundStyle(.secondary)
                }
            }
            .frame(minWidth: 0, maxWidth: .infinity)
            .aspectRatio(1, contentMode: .fill)
            .clipShape(RoundedRectangle(cornerRadius: 10))
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
}

private struct PhotoViewer: View {
    let photo: GalleryStore.Photo
    let onDelete: () -> Void
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if let img = UIImage(contentsOfFile: photo.url.path) {
                    Image(uiImage: img)
                        .resizable()
                        .scaledToFit()
                } else {
                    Text("Could not load photo")
                        .foregroundStyle(.white)
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Close", action: onClose)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(role: .destructive, action: onDelete) {
                        Image(systemName: "trash")
                    }
                }
                ToolbarItem(placement: .bottomBar) {
                    Text("\(photo.date.formatted(date: .abbreviated, time: .shortened)) · \(photo.bytes / 1024) KB")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }
}
