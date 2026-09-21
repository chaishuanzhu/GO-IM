import UIKit

/// Vertical-bar waveform matched to SF Symbol `waveform` @22pt/.medium.
///
/// Sized via `intrinsicContentSize` only. Bars are centered on `centerX` and are
/// **not** pinned to leading/trailing — UIStackView installs temporary width/height
/// of 0 while measuring arranged subviews; an edge-to-edge chain would conflict.
final class VoiceWaveformBarsView: UIView {
    private let barCount = 6
    private var bars: [UIView] = []
    private var heightConstraints: [NSLayoutConstraint] = []
    private var displayLink: CADisplayLink?
    private var phase: CGFloat = 0

    private let maxBarHeight: CGFloat = 22
    private let minBarHeight: CGFloat = 5.5
    private let barWidth: CGFloat = 2
    private let barSpacing: CGFloat = 1.5

    private let idleLevels: [CGFloat] = [0.25, 0.64, 1.00, 0.52, 0.79, 0.33]

    var barColor: UIColor = .label {
        didSet { bars.forEach { $0.backgroundColor = barColor } }
    }

    var isAnimating = false {
        didSet {
            guard oldValue != isAnimating else { return }
            if isAnimating {
                startAnimating()
            } else {
                stopAnimating()
                applyIdleHeights(animated: true)
            }
        }
    }

    override var intrinsicContentSize: CGSize {
        CGSize(width: contentWidth, height: maxBarHeight)
    }

    private var contentWidth: CGFloat {
        CGFloat(barCount) * barWidth + CGFloat(max(0, barCount - 1)) * barSpacing
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        setContentHuggingPriority(.required, for: .horizontal)
        setContentCompressionResistancePriority(.required, for: .horizontal)
        setContentHuggingPriority(.required, for: .vertical)
        setContentCompressionResistancePriority(.required, for: .vertical)

        var previous: UIView?
        for _ in 0..<barCount {
            let bar = UIView()
            bar.backgroundColor = barColor
            bar.layer.cornerRadius = barWidth / 2
            bar.translatesAutoresizingMaskIntoConstraints = false
            addSubview(bar)

            let h = bar.heightAnchor.constraint(equalToConstant: minBarHeight)
            heightConstraints.append(h)
            bars.append(bar)

            var constraints: [NSLayoutConstraint] = [
                bar.widthAnchor.constraint(equalToConstant: barWidth),
                h,
                bar.centerYAnchor.constraint(equalTo: centerYAnchor),
            ]
            if let previous {
                constraints.append(
                    bar.leadingAnchor.constraint(equalTo: previous.trailingAnchor, constant: barSpacing)
                )
            }
            NSLayoutConstraint.activate(constraints)
            previous = bar
        }

        if let first = bars.first {
            // Center the strip; do not pin to leading/trailing (avoids vs temporary width == 0).
            NSLayoutConstraint.activate([
                first.leadingAnchor.constraint(equalTo: centerXAnchor, constant: -contentWidth / 2),
            ])
        }

        applyIdleHeights(animated: false)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    deinit {
        displayLink?.invalidate()
    }

    private func applyIdleHeights(animated: Bool) {
        let updates = {
            for (i, c) in self.heightConstraints.enumerated() {
                let level = self.idleLevels[i % self.idleLevels.count]
                c.constant = self.minBarHeight
                    + (self.maxBarHeight - self.minBarHeight) * level
            }
            self.layoutIfNeeded()
        }
        if animated {
            UIView.animate(withDuration: 0.18, animations: updates)
        } else {
            updates()
        }
    }

    private func startAnimating() {
        displayLink?.invalidate()
        let link = CADisplayLink(target: self, selector: #selector(tick))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    private func stopAnimating() {
        displayLink?.invalidate()
        displayLink = nil
        phase = 0
    }

    @objc private func tick(_ link: CADisplayLink) {
        phase += CGFloat(link.duration) * 6.5
        for (i, c) in heightConstraints.enumerated() {
            let base = idleLevels[i % idleLevels.count]
            let wave = sin(phase + CGFloat(i) * 0.9)
            let level = min(1, max(0.15, base + 0.28 * wave))
            c.constant = minBarHeight + (maxBarHeight - minBarHeight) * level
        }
    }
}
