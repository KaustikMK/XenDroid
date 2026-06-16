/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 */

#ifndef XENIA_BASE_FRAME_STATS_H_
#define XENIA_BASE_FRAME_STATS_H_

#include <atomic>
#include <chrono>
#include <cstdint>

namespace xe {

// Guest-frame present timing for the debug overlay.
//
// RecordGuestPresent() is called exactly once per presented GUEST frame (from
// the GPU command processor's IssueSwap), so the reported FPS / frame time
// reflect the actual game frame rate -- NOT the host UI repaint cadence (which
// keeps running at panel refresh even when the guest stalls). The producer is
// single-threaded (the command-processor thread); the published values are read
// from the UI thread via GetFrameStats(). A torn read across the three values
// is harmless for a debug readout.
namespace internal {
inline std::atomic<float>& frame_instant_ms() {
  static std::atomic<float> v{0.0f};
  return v;
}
inline std::atomic<float>& frame_avg_ms() {
  static std::atomic<float> v{0.0f};
  return v;
}
inline std::atomic<float>& frame_fps() {
  static std::atomic<float> v{0.0f};
  return v;
}
}  // namespace internal

// Call once per presented guest frame (single producer thread). Cheap; safe to
// call unconditionally.
inline void RecordGuestPresent() {
  using clock = std::chrono::steady_clock;
  static constexpr size_t kWindow = 120;
  static double history_ms[kWindow] = {};
  static size_t count = 0;
  static size_t next = 0;
  static clock::time_point last{};

  clock::time_point now = clock::now();
  if (last == clock::time_point{}) {
    // First present: just seed the timestamp, no delta yet.
    last = now;
    return;
  }
  double instant_ms =
      std::chrono::duration<double, std::milli>(now - last).count();
  last = now;
  // Discard absurd deltas (first frame after a pause / load) so a single
  // outlier doesn't skew the rolling average for ~2 seconds.
  if (instant_ms > 1000.0) {
    return;
  }

  history_ms[next] = instant_ms;
  next = (next + 1) % kWindow;
  if (count < kWindow) {
    ++count;
  }
  double sum = 0.0;
  for (size_t i = 0; i < count; ++i) {
    sum += history_ms[i];
  }
  double avg_ms = count ? sum / double(count) : 0.0;

  internal::frame_instant_ms().store(float(instant_ms),
                                     std::memory_order_relaxed);
  internal::frame_avg_ms().store(float(avg_ms), std::memory_order_relaxed);
  internal::frame_fps().store(float(avg_ms > 0.0 ? 1000.0 / avg_ms : 0.0),
                              std::memory_order_relaxed);
}

// Read the latest published stats (any thread).
inline void GetFrameStats(float& instant_ms, float& avg_ms, float& fps) {
  instant_ms = internal::frame_instant_ms().load(std::memory_order_relaxed);
  avg_ms = internal::frame_avg_ms().load(std::memory_order_relaxed);
  fps = internal::frame_fps().load(std::memory_order_relaxed);
}

}  // namespace xe

#endif  // XENIA_BASE_FRAME_STATS_H_
