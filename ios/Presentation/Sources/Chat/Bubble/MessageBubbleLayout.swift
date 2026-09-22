import UIKit

enum MessageBubbleLayout {
    /// Display size from message `width`/`height` (pixels), fitted into max box.
    static func imageSize(width: Int?, height: Int?) -> CGSize {
        let maxW: CGFloat = 220
        let maxH: CGFloat = 260
        let minSide: CGFloat = 120
        guard let w = width, let h = height, w > 0, h > 0 else {
            return CGSize(width: 180, height: 180)
        }
        let ratio = CGFloat(w) / CGFloat(h)
        var outW = min(maxW, CGFloat(w))
        var outH = outW / ratio
        if outH > maxH {
            outH = maxH
            outW = outH * ratio
        }
        if outW < minSide, outH < minSide {
            if ratio >= 1 {
                outW = minSide
                outH = minSide / ratio
            } else {
                outH = minSide
                outW = minSide * ratio
            }
        }
        return CGSize(width: outW.rounded(), height: outH.rounded())
    }

    static let stickerSide: CGFloat = 140
}
