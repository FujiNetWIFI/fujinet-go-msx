#pragma once

#include <jni.h>
#include <android/native_window.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

// Orchestrates one MSX session: openMSX (driven one frame per
// msxhost_core_run_frame() on a worker thread) plus the in-process FujiNet
// runtime, joined over FujiBusPacket-over-SLIP on loopback TCP 1985. Direction
// is reversed vs the SmartPort targets: the FujiNet runtime *listens* and
// openMSX's FujiNet device connects in (src/serial/FujiNet.cc).
class SessionRuntime {
public:
    static SessionRuntime& Get();

    // FujiNet runtime root/config/SD/data paths (used from Phase 4; the core
    // itself boots from embedded ROMs and needs no paths).
    void StartSession(const std::string& runtime_root,
                      const std::string& config_path,
                      const std::string& sd_path,
                      const std::string& data_path);
    void StopSession();
    bool IsRunning() const { return running_.load(); }

    void AttachSurface(JNIEnv* env, jobject surface);
    void DetachSurface(JNIEnv* env);

    void RequestReset();

    // Called (on the emulator thread) by the host's video_refresh sink.
    void OnFrame(const uint32_t* xrgb8888, int width, int height);

private:
    SessionRuntime() = default;
    SessionRuntime(const SessionRuntime&) = delete;
    SessionRuntime& operator=(const SessionRuntime&) = delete;

    void EmulatorThreadMain();
    void RenderThreadMain();
    void PresentTo(ANativeWindow* w, const uint32_t* xrgb8888, int width, int height);
    void SignalRepaint();

    // FujiBusPacket-over-SLIP loopback port. The FujiNet runtime listens here;
    // openMSX's FujiNet device (FUJINET_DEFAULT_PORT) connects in. 1986 (not the
    // FujiNet-PC default 1985) avoids a loopback clash with a co-installed FujiNet
    // Go target (e.g. Apple2) using 1985 on the same device.
    static constexpr int kSlipPort = 1986;

    mutable std::mutex surface_mutex_;
    ANativeWindow* window_ = nullptr;

    std::mutex frame_mutex_;
    std::condition_variable frame_cv_;
    bool frame_dirty_ = false;
    std::vector<uint32_t> last_frame_;
    int last_frame_w_ = 0;
    int last_frame_h_ = 0;
    std::thread render_thread_;
    std::atomic<bool> render_running_{false};

    std::mutex lifecycle_mutex_;
    std::thread emulator_thread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> emu_should_run_{false};

    std::string runtime_root_;
    std::string config_path_;
    std::string sd_path_;
    std::string data_path_;
};
