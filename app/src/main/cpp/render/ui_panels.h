#pragma once

#include <cstdint>
#include <vector>

namespace retroai {

/**
 * Finds interface panels - dialogue boxes, status windows, command menus - in a
 * native-resolution frame, so the HD-2D pass can leave them unlit.
 *
 * WHY NOT FROM THE DEPTH MAP
 *
 * Two attempts did (AGENT.md 13.4) and both failed. Measured against the real
 * corpus, the reason is not a threshold: RetroDepth's output is dominated by a
 * vertical near/far ramp, so a panel's depth reports roughly where on the
 * screen it sits and nothing about what it is. A speech bubble halfway up the
 * frame reads mid-depth, exactly like the scene behind it. No cut-off in that
 * signal can separate them.
 *
 * WHY NOT FROM FLATNESS
 *
 * A panel is flat and low-detail, but so is sky, and so is snow - that test
 * masked whole landscapes. It also misses the panels that are textured, like
 * the hatched window Fire Emblem draws.
 *
 * WHAT IS USED INSTEAD
 *
 * The border. Every one of these panels is a closed rectangle: a straight run
 * of high contrast along one row, a matching run below it, and vertical runs
 * joining their ends. Scene art has long straight edges too - horizons, floors,
 * walls - but they do not close into a rectangle with matching ends.
 *
 * This is a defining property of a panel rather than a correlated one, which is
 * the lesson those two failures left behind.
 *
 * Portraits come along for free whenever the artist drew them inside the box,
 * because the whole rectangle is protected, not just its flat parts.
 */
class UiPanelFinder {
public:
    /**
     * @param rgb   interleaved RGB8, width*height*3, top row first.
     * @param out   width*height mask, 255 where lighting must be skipped.
     *
     * The mask is feathered so its boundary cannot become an outline of its
     * own, and averaged with the previous call so a panel appearing or
     * vanishing fades rather than pops.
     */
    void detect(const uint8_t* rgb, int width, int height, std::vector<uint8_t>& out);

    /** Forget the temporal history - call when the geometry changes. */
    void reset() { history_.clear(); }

private:
    /** Luma step across a pixel boundary that counts as a border. */
    static constexpr int kEdgeStep = 40;      // out of 255
    /**
     * A border must run this far, and enclose at least this many rows.
     *
     * 24 rather than 40 so a standalone portrait frame is caught - those are
     * around 31 native pixels wide and were falling through, which is how a
     * speaking character's face ended up with a shaft of light across it.
     * Measured over 209 frames, the drop costs nothing: mean coverage 7.7% to
     * 7.8% and the same 1.0% of frames masked past 45%. Below 20 it starts
     * finding boxes in scene art.
     */
    static constexpr int kMinWidth = 24;
    /**
     * 6, not 10: a status bar is thinner than a dialogue box. The HP/SP/SOUL
     * strip on the handheld measures six rows, so a floor of ten rejected the
     * whole HUD and the tilt-shift softened it into glass. Over the corpus this
     * costs almost nothing - mean coverage 17.5% to 18.1%.
     *
     * Relaxing the end-matching rule instead was tried and is far worse: mean
     * coverage jumps to 29% and a quarter of all frames end up more than half
     * masked, which switches the lighting and the focus band off wholesale.
     */
    static constexpr int kMinHeight = 6;
    /** Fraction of the panel's height its side borders must span. */
    static constexpr float kSideCover = 0.55f;
    /** How far the two horizontal runs may disagree at their ends. */
    static constexpr int kEndTolerance = 3;
    /**
     * A panel is part of the picture, never most of it. The four screen edges
     * are borders too, so without this the whole frame is trivially a
     * rectangle and the pass switches itself off.
     */
    static constexpr float kMaxCoverage = 0.50f;
    /** Weight of the newest detection in the temporal average. */
    static constexpr float kBlend = 0.34f;

    struct Run { int pos, from, to; };

    /** 5x5 box blur, separable. Softens the mask boundary. */
    void blur(const std::vector<uint8_t>& src, int width, int height,
              std::vector<uint8_t>& dst) const;

    std::vector<uint8_t> luma_{};
    std::vector<uint8_t> raw_{};
    std::vector<uint8_t> history_{};
    std::vector<Run> horiz_{};
    std::vector<Run> vert_{};
    /** Index into vert_ of the first run on each column, plus a tail entry. */
    std::vector<int> vertStart_{};
};

} // namespace retroai
