// Regression test for docs/bugs/open/2026-10-23-pokemon-ds-roda-acelerado-com-acelerar-desligado-moto-g86.md
//
// Drives libretrodroid::FPSSync the way LibretroDroid::step() does, with a simulated vsync, and
// measures how many emulated frames per second it asks for. The game is created on a 60 Hz panel
// (vsync mode); the panel then refreshes at 60, 90 or 120 Hz. Emulation must stay at the content
// rate in all of them.

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <thread>

#include "fpssync.h"

using Clock = std::chrono::steady_clock;

static const double CONTENT_FPS = 59.8261; // melonDS / DeSmuME
static const double SCREEN_FPS_AT_CREATE = 60.0;

struct Result {
    double emulatedFps;
    double timeStretchFactor;
};

// stallEvery > 0 inserts a 100 ms stall every stallEvery vsyncs, followed by 3 back-to-back calls:
// what BufferQueue does after a slow frame. That must not be mistaken for a faster panel.
static Result simulate(double panelHz, double seconds, int stallEvery = 0) {
    libretrodroid::FPSSync sync(CONTENT_FPS, SCREEN_FPS_AT_CREATE);
    auto period = std::chrono::duration_cast<Clock::duration>(std::chrono::duration<double>(1.0 / panelHz));

    auto begin = Clock::now();
    auto measureFrom = begin + std::chrono::seconds(1);
    auto end = begin + std::chrono::duration_cast<Clock::duration>(std::chrono::duration<double>(seconds));
    auto nextVSync = begin;

    unsigned long frames = 0;
    unsigned long calls = 0;
    int burst = 0;
    Clock::time_point measuredStart{};
    while (Clock::now() < end) {
        unsigned requested = std::min(sync.advanceFrames(), 2u); // LibretroDroid::step()
        sync.wait();

        auto now = Clock::now();
        if (now >= measureFrom) {
            if (measuredStart == Clock::time_point{}) measuredStart = now;
            frames += requested;
        }

        calls++;
        if (burst > 0) {
            burst--;
            continue;
        }
        if (stallEvery > 0 && calls % stallEvery == 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
            burst = 3;
            nextVSync = Clock::now();
            continue;
        }

        // eglSwapBuffers: blocks until the next vsync tick.
        do { nextVSync += period; } while (nextVSync <= Clock::now());
        std::this_thread::sleep_until(nextVSync);
    }

    double measuredSeconds = std::chrono::duration<double>(Clock::now() - measuredStart).count();
    return { frames / measuredSeconds, sync.getTimeStretchFactor() };
}

static int failures = 0;

static void check(const char* name, bool ok, const Result& r) {
    std::printf("%s %-40s emulated %.2f fps, time stretch %.4f\n", ok ? "PASS" : "FAIL", name, r.emulatedFps, r.timeStretchFactor);
    if (!ok) failures++;
}

static bool nearContent(double fps, double tolerance) {
    return std::abs(fps - CONTENT_FPS) / CONTENT_FPS <= tolerance;
}

int main() {
    const double vsyncStretch = CONTENT_FPS / SCREEN_FPS_AT_CREATE;

    // 60 Hz panel: keeps vsync pacing (one frame per vsync, audio stretched).
    Result r60 = simulate(60.0, 4.0);
    check("60 Hz panel keeps vsync", nearContent(r60.emulatedFps, 0.03) && r60.timeStretchFactor == vsyncStretch, r60);

    Result rStall = simulate(60.0, 4.0, 30);
    check("60 Hz panel with stalls keeps vsync", rStall.timeStretchFactor == vsyncStretch, rStall);

    // Panel rate raised after the game started (MTK touch boost / adaptive refresh).
    Result r90 = simulate(90.0, 4.0);
    check("90 Hz panel runs at content rate", nearContent(r90.emulatedFps, 0.03), r90);

    Result r120 = simulate(120.0, 4.0);
    check("120 Hz panel runs at content rate", nearContent(r120.emulatedFps, 0.03), r120);

    std::printf("%s\n", failures == 0 ? "ALL PASSED" : "FAILED");
    return failures == 0 ? 0 : 1;
}
