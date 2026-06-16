/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2020 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */
#include "xe_aaudio_audio_driver.h"

#include <atomic>
#include <cstring>

#include "xenia/apu/apu_flags.h"
#include "xenia/apu/conversion.h"
#include "xenia/base/assert.h"
#include "xenia/base/logging.h"
#include "xenia/base/profiling.h"

namespace xe {
namespace apu {
namespace aaudio {

AAudioAudioDriver::AAudioAudioDriver(Memory* memory,
                                     xe::threading::Semaphore* semaphore)
    : semaphore_(semaphore) {}

AAudioAudioDriver::~AAudioAudioDriver() {
  assert_true(frames_queued_.empty());
  assert_true(frames_unused_.empty());
}

bool AAudioAudioDriver::Initialize() {
  aaudio_result_t result;

  result = AAudio_createStreamBuilder(&builder_);
  if (result != AAUDIO_OK) {
    XELOGE("AAudio_createStreamBuilder failed: {}", result);
    return false;
  }

  AAudioStreamBuilder_setFormat(builder_, AAUDIO_FORMAT_PCM_FLOAT);
  AAudioStreamBuilder_setSampleRate(builder_, host_frame_frequency_);
  AAudioStreamBuilder_setChannelCount(builder_, host_frame_channels_);
  AAudioStreamBuilder_setFramesPerDataCallback(builder_, channel_samples_);
  //AAudioStreamBuilder_setBufferCapacityInFrames(builder_, channel_samples_ * 2);

  AAudioStreamBuilder_setDataCallback(builder_, AudioCallback, this);
  AAudioStreamBuilder_setErrorCallback(builder_, AudioErrorCallback, this);

  AAudioStreamBuilder_setPerformanceMode(builder_, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
  AAudioStreamBuilder_setSharingMode(builder_, AAUDIO_SHARING_MODE_EXCLUSIVE);

  result = AAudioStreamBuilder_openStream(builder_, &stream_);
  if (result != AAUDIO_OK) {
    XELOGE("AAudioStreamBuilder_openStream failed: {}", result);
    return false;
  }

  {
    std::unique_lock<std::mutex> guard(frames_mutex_);
    for (int i = 0; i < 2; i++) {
      float* buffer = new float[x360_frame_samples_];
      frames_unused_.push(buffer);
    }
  }

  stream_initialized_ = true;

  result = AAudioStream_requestStart(stream_);
  if (result != AAUDIO_OK) {
    XELOGE("AAudioStream_requestStart failed: {}", result);
    return false;
  }

  return true;
}

void AAudioAudioDriver::Pause() {
  if (stream_initialized_) {
    AAudioStream_requestPause(stream_);
  }
}

void AAudioAudioDriver::Resume() {
  if (stream_initialized_) {
    AAudioStream_requestStart(stream_);
  }
}

void AAudioAudioDriver::SetVolume(float volume) {
    //FIXME
}

aaudio_data_callback_result_t AAudioAudioDriver::AudioCallback(
    AAudioStream* stream,
    void* userdata,
    void* audioData,
    int32_t numFrames) {
  SCOPE_profile_cpu_f("apu");

  auto driver = static_cast<AAudioAudioDriver*>(userdata);
  float* output_buffer = reinterpret_cast<float*>(audioData);
  const int32_t samples_count = numFrames * 2;

  std::unique_lock<std::mutex> guard(driver->frames_mutex_);

  // Starvation diagnostics: whether the device callback runs at all and
  // whether it finds guest frames to consume (a consume is the only thing
  // that releases the worker semaphore - see the tick-starvation note in
  // SubmitFrame).
  static std::atomic<uint32_t> cb_count{0}, cb_empty{0};
  uint32_t cb_n = cb_count.fetch_add(1);
  if (driver->frames_queued_.empty()) {
    uint32_t empty_n = cb_empty.fetch_add(1);
    if (cb_n < 4 || (cb_n & 0x3FF) == 0) {
      XELOGI("AAudioCB #{} queue empty (empty so far: {})", cb_n, empty_n + 1);
    }
    std::memset(output_buffer, 0, samples_count * sizeof(float));
    // Real hardware ticks the render driver client every 5.33ms whether or
    // not it submitted - consume-driven credits leak one tick of runway per
    // underrun burst and eventually starve the title's audio engine for good
    // (Fable II boot). Grant the credit on empty bursts too; a failed
    // release just means the guest already holds full runway.
    bool released = driver->semaphore_->Release(1, nullptr);
    if (!released && ((cb_n & 0x3FF) == 0)) {
      XELOGI(
          "AAudioCB #{} release FAILED (count at max, worker not consuming) "
          "driver={:p} semaphore={:p}",
          cb_n, (void*)driver, (void*)driver->semaphore_);
    }
  } else {
    if (cb_n < 4 || (cb_n & 0x3FF) == 0) {
      XELOGI("AAudioCB #{} consuming (depth {}, empty so far: {})", cb_n,
             driver->frames_queued_.size(), cb_empty.load());
    }
    auto buffer = driver->frames_queued_.front();
    driver->frames_queued_.pop();

    if (cvars::mute) {
      std::memset(output_buffer, 0, samples_count * sizeof(float));
    } else {
        conversion::sequential_6_BE_to_interleaved_2_LE(
                output_buffer, buffer, channel_samples_);
    }

    driver->frames_unused_.push(buffer);

      auto ret = driver->semaphore_->Release(1, nullptr);
      assert_true(ret);
  }

  return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AAudioAudioDriver::AudioErrorCallback(
    AAudioStream* stream,
    void* userdata,
    aaudio_result_t error) {
  XELOGE("AAudio stream error: {}", error);
}

void AAudioAudioDriver::SubmitFrame(float* samples) {
  // Note: the worker tick is credit-driven (one semaphore release per
  // consumed frame) - a title whose render client skips submissions gets no
  // further ticks once the prime credits drain. Counting submissions here
  // tells the two starvation modes apart.
  static std::atomic<uint32_t> submit_count{0};
  uint32_t submit_n = submit_count.fetch_add(1);
  if (submit_n < 8 || (submit_n & 0x1FF) == 0) {
    XELOGI("AAudio SubmitFrame #{}", submit_n);
  }
    const auto input_frame = samples;
  float* output_frame;

  {
    std::unique_lock<std::mutex> guard(frames_mutex_);
    if (frames_unused_.empty()) {
      output_frame = new float[x360_frame_samples_];
    } else {
      output_frame = frames_unused_.top();
      frames_unused_.pop();
    }
  }

  std::memcpy(output_frame, input_frame, x360_frame_samples_*sizeof(float));

  {
    std::unique_lock<std::mutex> guard(frames_mutex_);
    frames_queued_.push(output_frame);
  }
}

void AAudioAudioDriver::Shutdown() {
  if (stream_) {
    AAudioStream_requestStop(stream_);
    AAudioStream_close(stream_);
    stream_ = nullptr;
  }

  if (builder_) {
    AAudioStreamBuilder_delete(builder_);
    builder_ = nullptr;
  }

  stream_initialized_ = false;

  std::unique_lock<std::mutex> guard(frames_mutex_);
  while (!frames_unused_.empty()) {
    delete[] frames_unused_.top();
    frames_unused_.pop();
  }

  while (!frames_queued_.empty()) {
    delete[] frames_queued_.front();
    frames_queued_.pop();
  }
}

}  // namespace aaudio
}  // namespace apu
}  // namespace xe
