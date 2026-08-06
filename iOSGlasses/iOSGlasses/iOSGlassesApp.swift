import SwiftUI

@main
struct iOSGlassesApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(model)
                .onChange(of: scenePhase) { _, phase in
                    model.scenePhaseChanged(to: phase)
                }
        }
    }
}

struct ContentView: View {
    var body: some View {
        TabView {
            HomeView()
                .tabItem { Label("Home", systemImage: "house.fill") }
            GalleryView()
                .tabItem { Label("Gallery", systemImage: "photo.on.rectangle") }
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape.fill") }
            DeveloperView()
                .tabItem { Label("Developer", systemImage: "wrench.and.screwdriver.fill") }
        }
    }
}
