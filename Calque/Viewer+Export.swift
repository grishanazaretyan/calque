import UIKit

/// Open in another app, Share, Save to Files and Print — the same as on Android.
extension ViewerViewController: UIDocumentInteractionControllerDelegate {

    func exportMenu() -> UIMenu {
        UIMenu(options: .displayInline, children: [
            UIAction(title: "Open in…", image: UIImage(systemName: "arrow.up.forward.app")) { [weak self] _ in
                self?.withCopy { $0.openIn($1) }
            },
            UIAction(title: "Share", image: UIImage(systemName: "square.and.arrow.up")) { [weak self] _ in
                self?.withCopy { $0.share($1) }
            },
            UIAction(title: "Save to Files", image: UIImage(systemName: "folder")) { [weak self] _ in
                self?.withCopy { $0.saveToFiles($1) }
            },
            UIAction(title: "Print", image: UIImage(systemName: "printer")) { [weak self] _ in
                self?.withCopy { $0.printFile($1) }
            },
        ])
    }

    private var pdfName: String {
        var n = url.deletingPathExtension().lastPathComponent.trimmingCharacters(in: .whitespaces)
        if n.isEmpty { n = "drawing" }
        n = n.replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: ":", with: "_")
        return n + ".pdf"
    }

    /// Other apps get a plain copy in our temporary folder, so they never depend on our
    /// access to the original (iCloud, another app's folder, a USB drive).
    private func withCopy(_ then: @escaping (ViewerViewController, URL) -> Void) {
        let src = url
        let name = pdfName
        DispatchQueue.global(qos: .userInitiated).async {
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
            try? FileManager.default.removeItem(at: dir)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let dst = dir.appendingPathComponent(name)
            var ok = false
            let scoped = src.startAccessingSecurityScopedResource()
            var err: NSError?
            NSFileCoordinator().coordinate(readingItemAt: src, options: .withoutChanges, error: &err) { real in
                ok = (try? FileManager.default.copyItem(at: real, to: dst)) != nil
            }
            if scoped { src.stopAccessingSecurityScopedResource() }
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                if ok { then(self, dst) } else { self.tell("Could not read this file") }
            }
        }
    }

    private func tell(_ msg: String) {
        let a = UIAlertController(title: nil, message: msg, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "OK", style: .default))
        present(a, animated: true)
    }

    private func anchor(_ vc: UIViewController) {
        if let p = vc.popoverPresentationController {
            p.sourceView = menuButton
            p.sourceRect = menuButton.bounds
        }
    }

    private func openIn(_ file: URL) {
        let dic = UIDocumentInteractionController(url: file)
        dic.uti = "com.adobe.pdf"
        dic.delegate = self
        docController = dic
        if !dic.presentOpenInMenu(from: menuButton.bounds, in: menuButton, animated: true) {
            tell("No other app can open PDFs")
        }
    }

    private func share(_ file: URL) {
        let vc = UIActivityViewController(activityItems: [file], applicationActivities: nil)
        anchor(vc)
        present(vc, animated: true)
    }

    private func saveToFiles(_ file: URL) {
        let vc = UIDocumentPickerViewController(forExporting: [file], asCopy: true)
        present(vc, animated: true)
    }

    private func printFile(_ file: URL) {
        guard UIPrintInteractionController.canPrint(file) else { tell("This file cannot be printed"); return }
        let pc = UIPrintInteractionController.shared
        let info = UIPrintInfo(dictionary: nil)
        info.outputType = .general
        info.jobName = file.lastPathComponent
        pc.printInfo = info
        pc.printingItem = file
        pc.present(from: menuButton.bounds, in: menuButton, animated: true)
    }

    func documentInteractionControllerDidDismissOpenInMenu(_ controller: UIDocumentInteractionController) {
        docController = nil
    }
}
