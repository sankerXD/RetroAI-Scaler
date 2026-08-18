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
     * @param out   width*height*2, interleaved. Channel 0 is the panel mask,
     *              which the lighting and the focus band both honour. Channel 1
     *              is content that stays PUT while the scene scrolls - a HUD
     *              with no box around it - and only the focus band honours
     *              that one, because a mistake there costs a sharp patch rather
     *              than a hole in the lighting.
     *
     * The mask is feathered so its boundary cannot become an outline of its
     * own, and averaged with the previous call so a panel appearing or
     * vanishing fades rather than pops.
     */
    void detect(const uint8_t* rgb, int width, int height, std::vector<uint8_t>& out);

    /** Forget the temporal history - call when the geometry changes. */
    void reset() { history_.clear(); prevLuma_.clear(); staticAcc_.clear(); }

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

    /**
     * Screen-locked overlay detection, for HUD that has no border to find.
     *
     * A HUD is defined by staying put while the scene moves, so it is only
     * measurable WHILE the scene moves - which is why the accumulator is frozen
     * unless the frame is genuinely scrolling. Without that gate a player
     * standing still makes the whole screen look like a HUD within a second.
     *
     * "Scrolling" is judged by how far the change is SPREAD, not by how much of
     * it there is: a camera pan changes nearly every cell of the frame, whereas
     * a few large sprites animating in place change a lot of pixels but only
     * where they are. Measured on the corpus, the pixel-count test alone marked
     * 31-34% of a static-camera scene as overlay; the spread test takes those
     * to zero while keeping the real detections.
     *
     * KNOWN LIMIT: a zero-parallax background layer is screen-locked too, and
     * by this definition it IS an overlay. Requiring hard edges removes the
     * flat-sky case (24% of that frame down to 9%) but a detailed far layer
     * still reads as HUD. This is why channel 1 drives only the focus band.
     */
    static constexpr int kChangeLevel = 8;     // luma levels that count as a change
    static constexpr int kGridY = 4, kGridX = 6;
    static constexpr float kCellMoving = 0.20f;   // of a cell, to call it moving
    static constexpr float kFrameMoving = 0.80f;  // of the cells, to open the gate
    static constexpr float kStaticRise = 0.10f;
    static constexpr float kStaticFall = 0.50f;
    static constexpr int kDetailLevel = 9;        // edge strength of a graphic
    static constexpr float kDetailCover = 0.12f;  // of a neighbourhood, to keep it

    struct Run { int pos, from, to; };

    /** 5x5 box blur, separable. Softens the mask boundary. */
    void blur(const std::vector<uint8_t>& src, int width, int height,
              std::vector<uint8_t>& dst) const;

    /** Accumulates what stays put while the scene scrolls. */
    void updateStatic(int width, int height);

    std::vector<uint8_t> luma_{};
    std::vector<uint8_t> prevLuma_{};
    std::vector<float> staticAcc_{};
    std::vector<uint8_t> detail_{};
    std::vector<uint8_t> raw_{};
    std::vector<uint8_t> feathered_{};
    std::vector<uint8_t> history_{};
    std::vector<Run> horiz_{};
    std::vector<Run> vert_{};
    /** Index into vert_ of the first run on each column, plus a tail entry. */
    std::vector<int> vertStart_{};
};

} // namespace retroai
