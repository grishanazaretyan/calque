import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        true
    }

    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let c = UISceneConfiguration(name: "Default", sessionRole: connectingSceneSession.role)
        c.delegateClass = SceneDelegate.self
        return c
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    /// "Open in New Window": carries a bookmark of the file to show in the new window.
    static let openActivity = "ge.calque.viewer.open"
    var window: UIWindow?
    private var home: HomeViewController?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options: UIScene.ConnectionOptions) {
        guard let ws = scene as? UIWindowScene else { return }
        let w = UIWindow(windowScene: ws)
        let h = HomeViewController()
        let nav = UINavigationController(rootViewController: h)
        w.rootViewController = nav
        w.tintColor = Theme.accent
        Theme.apply(to: w)
        w.makeKeyAndVisible()
        window = w
        home = h
        if let u = options.urlContexts.first?.url { DispatchQueue.main.async { h.open(u) } }
        if let a = options.userActivities.first(where: { $0.activityType == Self.openActivity }),
           let bm = a.userInfo?["bookmark"] as? Data {
            var stale = false
            if let u = try? URL(resolvingBookmarkData: bm, options: [], relativeTo: nil, bookmarkDataIsStale: &stale) {
                DispatchQueue.main.async { h.open(u) }
            }
        }
    }

    /// A PDF opened from Files or another app ("Open in Calque").
    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        guard let u = URLContexts.first?.url else { return }
        home?.open(u)
    }
}
