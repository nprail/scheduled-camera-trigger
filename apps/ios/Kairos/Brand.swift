import SwiftUI
import UIKit

// The Kairos brand system on top of SwiftUI's native controls.
// Colors live in Assets.xcassets (light and dark). Fonts are bundled in Fonts/.
//
// Rules from the brand book:
// - `signal` is the only hue. Use it once per view (the record dot, the playhead, or the single primary action)
//   and never for body text. Text on a signal fill is `onSignal`.
// - Separate surfaces with hairlines, not shadows.
// - Times, durations and config values are set in mono.

extension Color {
    static let ground = Color("Ground")
    static let raised = Color("Raised")
    static let ink = Color("Ink")
    static let muted = Color("Muted")
    static let hairline = Color("Hairline")
    static let signal = Color("Signal")
    static let onSignal = Color("OnSignal")
}

enum Radius {
    static let sm: CGFloat = 4
    static let md: CGFloat = 8
}

enum BrandFont {
    /// Space Grotesk, for the wordmark, headlines and titles.
    static func display(_ size: CGFloat, weight: Int = 600, relativeTo style: UIFont.TextStyle = .title2) -> Font {
        Font(variable(family: "Space Grotesk", size: size, weight: weight, style: style))
    }

    /// JetBrains Mono, for every time, duration and config value.
    static func mono(_ size: CGFloat = 14, weight: Int = 500, relativeTo style: UIFont.TextStyle = .body) -> Font {
        Font(variable(family: "JetBrains Mono", size: size, weight: weight, style: style))
    }

    /// The bundled fonts are variable; pick the weight axis ('wght'). Falls back to the system font if a file is missing.
    private static func variable(family: String, size: CGFloat, weight: Int, style: UIFont.TextStyle) -> UIFont {
        let wght = 0x7767_6874
        let descriptor = UIFontDescriptor(fontAttributes: [
            .family: family,
            UIFontDescriptor.AttributeName(rawValue: "NSCTFontVariationAttribute"): [wght: weight],
        ])
        let base = UIFont(descriptor: descriptor, size: size)
        return UIFontMetrics(forTextStyle: style).scaledFont(for: base)
    }
}

extension View {
    /// Uppercase, tracked label style: section headers and device-state names.
    func kairosLabel() -> some View {
        self.font(.system(size: 12, weight: .medium))
            .tracking(0.96)
            .textCase(.uppercase)
    }

    /// Calm paper background with raised rows, for lists and forms.
    func kairosList() -> some View {
        self.scrollContentBackground(.hidden)
            .background(Color.ground)
            .listRowSeparatorTint(Color.hairline)
    }

    /// A raised row on `ground`.
    func kairosRow() -> some View {
        self.listRowBackground(Color.raised)
    }
}

/// Applies the brand to UIKit-drawn chrome (navigation bars).
func configureBrandAppearance() {
    let nav = UINavigationBarAppearance()
    nav.configureWithOpaqueBackground()
    nav.backgroundColor = UIColor(named: "Ground")
    nav.shadowColor = UIColor(named: "Hairline")
    let ink = UIColor(named: "Ink") ?? .label
    nav.titleTextAttributes = [.foregroundColor: ink]
    nav.largeTitleTextAttributes = [.foregroundColor: ink]
    UINavigationBar.appearance().standardAppearance = nav
    UINavigationBar.appearance().scrollEdgeAppearance = nav
    UINavigationBar.appearance().compactAppearance = nav
}

// MARK: Logo

/// The Kairos mark: a viewfinder (four corner brackets) holding a timeline, with one signal playhead.
/// Pass `small: true` below 32 pt (heavier strokes, no side ticks).
struct KairosMark: View {
    var small = false
    var body: some View {
        Canvas { context, size in
            let s = min(size.width, size.height) / 120
            func pt(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x * s, y: y * s) }
            let ink = Color.ink
            let signal = Color.signal
            let bracketWidth: CGFloat = (small ? 10 : 7) * s
            let round = StrokeStyle(lineWidth: bracketWidth, lineCap: .round, lineJoin: .round)

            var brackets = Path()
            brackets.move(to: pt(8, 34)); brackets.addLine(to: pt(8, 10)); brackets.addLine(to: pt(32, 10))
            brackets.move(to: pt(88, 10)); brackets.addLine(to: pt(112, 10)); brackets.addLine(to: pt(112, 34))
            brackets.move(to: pt(112, 86)); brackets.addLine(to: pt(112, 110)); brackets.addLine(to: pt(88, 110))
            brackets.move(to: pt(32, 110)); brackets.addLine(to: pt(8, 110)); brackets.addLine(to: pt(8, 86))
            context.stroke(brackets, with: .color(ink), style: round)

            if !small {
                var ticks = Path()
                for x in [28, 44, 76, 92] as [CGFloat] {
                    ticks.move(to: pt(x, 64)); ticks.addLine(to: pt(x, 90))
                }
                context.stroke(ticks, with: .color(ink), style: StrokeStyle(lineWidth: 6 * s, lineCap: .round))
            }

            var playhead = Path()
            playhead.move(to: pt(60, 42)); playhead.addLine(to: pt(60, small ? 92 : 90))
            context.stroke(playhead, with: .color(signal), style: StrokeStyle(lineWidth: (small ? 10 : 6) * s, lineCap: .round))

            let r = (small ? 13 : 10) * s
            context.fill(Path(ellipseIn: CGRect(x: 60 * s - r, y: 31 * s - r, width: r * 2, height: r * 2)), with: .color(signal))
        }
        .accessibilityLabel("Kairos")
        .accessibilityAddTraits(.isImage)
    }
}

/// The lowercase wordmark. The brand sets it in Space Grotesk SemiBold with -0.03em tracking.
struct Wordmark: View {
    var size: CGFloat = 28

    var body: some View {
        Text("kairos")
            .font(BrandFont.display(size, weight: 600, relativeTo: .title2))
            .tracking(-0.03 * size)
            .foregroundStyle(Color.ink)
    }
}

/// Mark beside wordmark. The gap is 0.3 of the mark's height.
struct KairosLockup: View {
    var height: CGFloat = 28

    var body: some View {
        HStack(spacing: height * 0.3) {
            KairosMark(small: height < 32)
                .frame(width: height, height: height)
            Wordmark(size: height * 0.95)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Kairos")
    }
}

// MARK: Components

/// The one primary action on a screen: a signal fill with `onSignal` text.
struct SignalButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .foregroundStyle(Color.onSignal)
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .frame(minHeight: 44)
            .background(Color.signal, in: RoundedRectangle(cornerRadius: Radius.md))
            .opacity(isEnabled ? (configuration.isPressed ? 0.85 : 1) : 0.4)
    }
}

/// A small outlined tag, for job states. The state is always in the text; a filled dot marks an active attempt.
struct StateTag: View {
    let text: String
    var active = false

    var body: some View {
        HStack(spacing: 6) {
            if active {
                Circle().fill(Color.ink).frame(width: 8, height: 8)
            }
            Text(text).kairosLabel()
        }
        .foregroundStyle(Color.ink)
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .overlay(RoundedRectangle(cornerRadius: Radius.sm).stroke(Color.hairline, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }
}
