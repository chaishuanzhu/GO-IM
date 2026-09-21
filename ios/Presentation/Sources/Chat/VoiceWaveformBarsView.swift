import UIKit

/// Vertical-bar waveform matched to SF Symbol `waveform` @22pt/.medium:
/// 6 bars, ~2pt wide, ~1.7pt gaps, idle height profile from the glyph.
final class VoiceWaveformBarsView: UIView {
    private let barCount = 6
    private var bars: [UIView] = []
    private var heightConstraints: [NSLayoutConstraint] = []
    private var displayLink: CADisplayLink?
    private var phase: CGFloat = 0

    /// Measured from SF Symbol `waveform` (pointSize 22, weight medium).
    private let maxBarHeight: CGFloat = 22
    private let minBarHeight: CGFloat = 5.5
    private let barWidth: CGFloat = 2
    private let barSpacing: CGFloat = 1.7

    /// Idle height profile of SF Symbol `waveform` (relative 0…1).
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
        let width = CGFloat(barCount) * barWidth + CGFloat(max(0, barCount - 1)) * barSpacing
        return CGSize(width: width, height: maxBarHeight)
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        setContentHuggingPriority(.required, for: .horizontal)
        setContentCompressionResistancePriority(.required, for: .horizontal)

        let row = UIStackView()
        row.axis = .horizontal
        row.alignment = .center
        row.distribution = .fill
        row.spacing = barSpacing
        row.setContentHuggingPriority(.required, for: .horizontal)
        row.translatesAutoresizingMaskIntoConstraints = false
        addSubview(row)

        for _ in 0..<barCount {
            let bar = UIView()
            bar.backgroundColor = barColor
            bar.layer.cornerRadius = barWidth / 2
            bar.translatesAutoresizingMaskIntoConstraints = false
            bar.setContentHuggingPriority(.required, for: .horizontal)
            bar.setContentCompressionResistancePriority(.required, for: .horizontal)
            let w = bar.widthAnchor.constraint(equalToConstant: barWidth)
            let h = bar.heightAnchor.constraint(equalToConstant: minBarHeight)
            NSLayoutConstraint.activate([w, h])
            heightConstraints.append(h)
            bars.append(bar)
            row.addArrangedSubview(bar)
        }

        let contentWidth = intrinsicContentSize.width
        NSLayoutConstraint.activate([
            row.centerXAnchor.constraint(equalTo: centerXAnchor),
            row.centerYAnchor.constraint(equalTo: centerYAnchor),
            row.widthAnchor.constraint(equalToConstant: contentWidth),
            heightAnchor.constraint(equalToConstant: maxBarHeight),
            widthAnchor.constraint(equalToConstant: contentWidth),
        ])

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
            // Oscillate around each bar's idle level so the silhouette stays familiar.
            let base = idleLevels[i % idleLevels.count]
            let wave = sin(phase + CGFloat(i) * 0.9)
            let level = min(1, max(0.15, base + 0.28 * wave))
            c.constant = minBarHeight + (maxBarHeight - minBarHeight) * level
        }
    }
}
