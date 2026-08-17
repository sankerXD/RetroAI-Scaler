#include "ui_panels.h"

#include <algorithm>
#include <cstdlib>

namespace retroai {

void UiPanelFinder::detect(const uint8_t* rgb, int width, int height,
                           std::vector<uint8_t>& out) {
    const size_t pixels = (size_t)width * height;
    out.assign(pixels, 0);
    if (!rgb || width < 8 || height < 8) {
        history_.clear();
        return;
    }

    luma_.resize(pixels);
    for (size_t i = 0; i < pixels; ++i) {
        const uint8_t* p = &rgb[i * 3];
        luma_[i] = (uint8_t)((p[0] * 77 + p[1] * 150 + p[2] * 29) >> 8);
    }

    // ---- Border runs -------------------------------------------------------
    // Boundary y lies between rows y-1 and y, so y=0 and y=height are the
    // screen edges. They count as borders: a bar sitting flush against the
    // bottom of the screen has only its one top edge to be found by.
    horiz_.clear();
    std::vector<uint8_t> flags((size_t)std::max(width, height), 0);

    for (int y = 0; y <= height; ++y) {
        if (y == 0 || y == height) {
            horiz_.push_back({y, 0, width - 1});
            continue;
        }
        const uint8_t* cur = &luma_[(size_t)y * width];
        const uint8_t* prev = cur - width;
        for (int x = 0; x < width; ++x) {
            flags[x] = std::abs((int)cur[x] - (int)prev[x]) > kEdgeStep ? 1 : 0;
        }
        int x = 0;
        while (x < width) {
            if (!flags[x]) { ++x; continue; }
            int j = x;
            while (j + 1 < width && flags[j + 1]) ++j;
            if (j - x + 1 >= kMinWidth) horiz_.push_back({y, x, j});
            x = j + 1;
        }
    }

    vert_.clear();
    vertStart_.assign((size_t)width + 2, 0);
    for (int x = 0; x <= width; ++x) {
        vertStart_[x] = (int)vert_.size();
        if (x == 0 || x == width) {
            vert_.push_back({x, 0, height - 1});
            continue;
        }
        for (int y = 0; y < height; ++y) {
            const uint8_t* row = &luma_[(size_t)y * width];
            flags[y] = std::abs((int)row[x] - (int)row[x - 1]) > kEdgeStep ? 1 : 0;
        }
        int y = 0;
        while (y < height) {
            if (!flags[y]) { ++y; continue; }
            int j = y;
            while (j + 1 < height && flags[j + 1]) ++j;
            if (j - y + 1 >= kMinHeight) vert_.push_back({x, y, j});
            y = j + 1;
        }
    }
    vertStart_[width + 1] = (int)vert_.size();

    // ---- Assemble rectangles ----------------------------------------------
    const float maxArea = kMaxCoverage * (float)width * (float)height;

    auto sideOk = [&](int x, int y0, int y1) {
        const float need = (float)(y1 - y0) * kSideCover;
        const int lo = std::max(0, x - kEndTolerance);
        const int hi = std::min(width, x + kEndTolerance);
        for (int xx = lo; xx <= hi; ++xx) {
            for (int i = vertStart_[xx]; i < vertStart_[xx + 1]; ++i) {
                const Run& r = vert_[i];
                const float span = (float)(std::min(r.to, y1) - std::max(r.from, y0));
                if (span >= need) return true;
            }
        }
        return false;
    };

    raw_.assign(pixels, 0);
    for (size_t i = 0; i < horiz_.size(); ++i) {
        const Run& top = horiz_[i];
        for (size_t j = i + 1; j < horiz_.size(); ++j) {
            const Run& bot = horiz_[j];
            if (bot.pos - top.pos < kMinHeight) continue;
            // The two runs must describe the SAME rectangle, not two unrelated
            // straight edges that happen to line up - a horizon and a floor.
            if (std::abs(top.from - bot.from) > kEndTolerance) continue;
            if (std::abs(top.to - bot.to) > kEndTolerance) continue;

            const int x0 = std::max(top.from, bot.from);
            const int x1 = std::min(top.to, bot.to);
            if (x1 - x0 + 1 < kMinWidth) continue;
            if ((float)(x1 - x0 + 1) * (float)(bot.pos - top.pos) > maxArea) continue;
            if (!sideOk(x0, top.pos, bot.pos)) continue;
            if (!sideOk(x1 + 1, top.pos, bot.pos)) continue;

            for (int y = top.pos; y < bot.pos; ++y) {
                std::fill(raw_.begin() + (size_t)y * width + x0,
                          raw_.begin() + (size_t)y * width + x1 + 1, (uint8_t)255);
            }
        }
    }

    // ---- Feather, then average over time -----------------------------------
    // A hard mask boundary would draw an outline of its own, which is the exact
    // failure the whole pass exists to avoid. Blur it, and blend with the last
    // result so a panel opening or closing fades instead of popping.
    blur(raw_, width, height, out);

    if (history_.size() != pixels) {
        history_ = out;
    } else {
        for (size_t i = 0; i < pixels; ++i) {
            const float v = (float)history_[i] * (1.0f - kBlend) + (float)out[i] * kBlend;
            history_[i] = (uint8_t)(v + 0.5f);
            out[i] = history_[i];
        }
    }
}

void UiPanelFinder::blur(const std::vector<uint8_t>& src, int width, int height,
                         std::vector<uint8_t>& dst) const {
    const int r = 2;
    const int taps = 2 * r + 1;
    std::vector<uint16_t> tmp((size_t)width * height);

    for (int y = 0; y < height; ++y) {
        const uint8_t* row = &src[(size_t)y * width];
        uint16_t* o = &tmp[(size_t)y * width];
        for (int x = 0; x < width; ++x) {
            int sum = 0;
            for (int k = -r; k <= r; ++k) {
                sum += row[std::min(width - 1, std::max(0, x + k))];
            }
            o[x] = (uint16_t)(sum / taps);
        }
    }
    dst.resize((size_t)width * height);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int sum = 0;
            for (int k = -r; k <= r; ++k) {
                const int yy = std::min(height - 1, std::max(0, y + k));
                sum += tmp[(size_t)yy * width + x];
            }
            dst[(size_t)y * width + x] = (uint8_t)(sum / taps);
        }
    }
}

} // namespace retroai
