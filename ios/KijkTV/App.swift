import AVFoundation
import Compression
import UIKit
import WebKit

/*
 KijkTV voor iPhone.
 De app toont dezelfde KijkTV als op de LG-tv (map "web", gekopieerd uit webos/app) in een WKWebView.
 De app zelf doet wat een webpagina niet mag:
  - gegevens ophalen bij de IPTV-server en Firebase (geen CORS-beperking, ook http),
  - de instellingen en geschiedenis bewaren (UserDefaults),
  - een link openen in een andere app (VLC).
 JavaScript roept dit aan met window.webkit.messageHandlers.ktv.postMessage({method, params}).
*/

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Geluid ook met de stille-modus-schakelaar aan, en doorspelen via AirPlay.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        let w = UIWindow(frame: UIScreen.main.bounds)
        w.backgroundColor = .black
        w.rootViewController = WebViewController()
        w.makeKeyAndVisible()
        window = w
        // Tv kijken: het scherm niet laten uitgaan.
        application.isIdleTimerDisabled = true
        return true
    }

    func application(_ application: UIApplication,
                     supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        .landscape
    }
}

/** Bewaart wat de app opslaat (login, profielen, geschiedenis). */
enum Store {
    private static let key = "ktvStore"

    static func all() -> [String: String] {
        UserDefaults.standard.dictionary(forKey: key) as? [String: String] ?? [:]
    }

    static func set(_ k: String, _ v: String?) {
        var d = all()
        if let v = v { d[k] = v } else { d.removeValue(forKey: k) }
        UserDefaults.standard.set(d, forKey: key)
    }

    /** Script dat de opgeslagen gegevens klaarzet voordat KijkTV start. */
    static func bootScript() -> String {
        let data = (try? JSONSerialization.data(withJSONObject: all(), options: [])) ?? Data("{}".utf8)
        let json = String(decoding: data, as: UTF8.self)
        return "window.KTV_NATIVE_STORE = \(json);"
    }
}

final class WebViewController: UIViewController, WKScriptMessageHandlerWithReply, WKNavigationDelegate, WKUIDelegate {
    private var web: WKWebView!

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .landscape }
    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge { .all }

    override func loadView() {
        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = []
        cfg.allowsAirPlayForMediaPlayback = true
        cfg.allowsPictureInPictureMediaPlayback = true
        cfg.websiteDataStore = .default()
        let uc = cfg.userContentController
        uc.addUserScript(WKUserScript(source: Store.bootScript(), injectionTime: .atDocumentStart, forMainFrameOnly: true))
        uc.addScriptMessageHandler(self, contentWorld: .page, name: "ktv")

        web = WKWebView(frame: .zero, configuration: cfg)
        web.isOpaque = false
        web.backgroundColor = .black
        web.scrollView.backgroundColor = .black
        web.scrollView.isScrollEnabled = false
        web.scrollView.bounces = false
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.navigationDelegate = self
        web.uiDelegate = self
        if #available(iOS 16.4, *) { web.isInspectable = true }

        let container = UIView()
        container.backgroundColor = UIColor(red: 13 / 255, green: 17 / 255, blue: 23 / 255, alpha: 1)
        container.addSubview(web)
        web.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            web.topAnchor.constraint(equalTo: container.topAnchor),
            web.bottomAnchor.constraint(equalTo: container.bottomAnchor),
            // Links en rechts niet onder de "notch".
            web.leadingAnchor.constraint(equalTo: container.safeAreaLayoutGuide.leadingAnchor),
            web.trailingAnchor.constraint(equalTo: container.safeAreaLayoutGuide.trailingAnchor),
        ])
        view = container
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        guard let dir = Bundle.main.url(forResource: "web", withExtension: nil) else { return }
        web.loadFileURL(dir.appendingPathComponent("index.html"), allowingReadAccessTo: dir)
    }

    // Als de app na lange tijd terugkomt en de pagina is weggegooid: opnieuw laden.
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        webView.reload()
    }

    // MARK: - Opdrachten vanuit KijkTV

    func userContentController(_ userContentController: WKUserContentController,
                               didReceive message: WKScriptMessage,
                               replyHandler: @escaping @MainActor @Sendable (Any?, String?) -> Void) {
        guard let body = message.body as? [String: Any], let method = body["method"] as? String else {
            replyHandler(nil, "Ongeldige opdracht")
            return
        }
        let p = body["params"] as? [String: Any] ?? [:]
        switch method {
        case "fetch":
            Net.fetch(p) { result, error in
                Task { @MainActor in replyHandler(result, error) }
            }
        case "storeSet":
            if let k = p["k"] as? String { Store.set(k, p["v"] as? String) }
            replyHandler(["ok": true], nil)
        case "ping":
            replyHandler(["ok": true, "platform": "ios"], nil)
        case "openExternal":
            guard let s = p["url"] as? String, let u = URL(string: s) else {
                replyHandler(["ok": false], nil)
                return
            }
            UIApplication.shared.open(u, options: [:]) { ok in replyHandler(["ok": ok], nil) }
        default:
            replyHandler(nil, "Onbekende opdracht")
        }
    }
}

/** Gegevens ophalen bij de IPTV-server of Firebase. */
enum Net {
    static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.httpAdditionalHeaders = ["User-Agent": "KijkTV/1.0 (iPhone)"]
        c.timeoutIntervalForResource = 180
        return URLSession(configuration: c)
    }()

    static func fetch(_ p: [String: Any], done: @escaping ([String: Any]?, String?) -> Void) {
        guard let s = p["url"] as? String, let url = URL(string: s) else {
            done(nil, "Ongeldige link")
            return
        }
        var req = URLRequest(url: url)
        req.httpMethod = ((p["method"] as? String) ?? "GET").uppercased()
        if let t = p["timeout"] as? NSNumber { req.timeoutInterval = max(5, t.doubleValue / 1000) } else { req.timeoutInterval = 45 }
        if let b = p["body"] as? String {
            req.httpBody = Data(b.utf8)
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }
        session.dataTask(with: req) { data, response, error in
            if let error = error as NSError? {
                done(nil, error.code == NSURLErrorTimedOut ? "Time-out: de server reageert niet" : error.localizedDescription)
                return
            }
            var d = data ?? Data()
            // .gz-bestanden (programmagids) zonder content-encoding zelf uitpakken.
            if d.count > 18, d[d.startIndex] == 0x1f, d[d.startIndex + 1] == 0x8b, let unpacked = Gzip.decompress(d) {
                d = unpacked
            }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            done(["status": status, "body": String(decoding: d, as: UTF8.self)], nil)
        }.resume()
    }
}

/** Gzip uitpakken met het Compression-framework van Apple. */
enum Gzip {
    static func decompress(_ data: Data) -> Data? {
        let b = [UInt8](data)
        guard b.count > 18, b[0] == 0x1f, b[1] == 0x8b, b[2] == 8 else { return nil }
        let flags = b[3]
        var i = 10
        if flags & 4 != 0 {
            guard i + 2 <= b.count else { return nil }
            i += 2 + (Int(b[i]) | (Int(b[i + 1]) << 8))
        }
        if flags & 8 != 0 { while i < b.count && b[i] != 0 { i += 1 }; i += 1 }
        if flags & 16 != 0 { while i < b.count && b[i] != 0 { i += 1 }; i += 1 }
        if flags & 2 != 0 { i += 2 }
        guard i < b.count - 8 else { return nil }
        return inflate(Data(b[i..<(b.count - 8)]))
    }

    private static func inflate(_ input: Data) -> Data? {
        let bufSize = 1 << 16
        let dst = UnsafeMutablePointer<UInt8>.allocate(capacity: bufSize)
        defer { dst.deallocate() }
        let stream = UnsafeMutablePointer<compression_stream>.allocate(capacity: 1)
        defer { stream.deallocate() }
        var status = compression_stream_init(stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB)
        guard status != COMPRESSION_STATUS_ERROR else { return nil }
        defer { compression_stream_destroy(stream) }
        return input.withUnsafeBytes { (raw: UnsafeRawBufferPointer) -> Data? in
            guard let src = raw.bindMemory(to: UInt8.self).baseAddress else { return nil }
            stream.pointee.src_ptr = src
            stream.pointee.src_size = input.count
            var out = Data()
            repeat {
                stream.pointee.dst_ptr = dst
                stream.pointee.dst_size = bufSize
                status = compression_stream_process(stream, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
                if status == COMPRESSION_STATUS_ERROR { return nil }
                out.append(dst, count: bufSize - stream.pointee.dst_size)
            } while status == COMPRESSION_STATUS_OK
            return out
        }
    }
}
