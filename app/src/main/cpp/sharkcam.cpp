// SharkOS-CE camera helper. Runs as the adb shell user (see CameraDaemon), never inside the app process.
// Talks to the car's camera service (Qualcomm AIS / QCarCam) through BYD's vendor library.
// Struct layouts below are our own declarations of the binary interface, checked against the car's output.
//
// Usage:
//   sc list                          print the car's camera inputs
//   sc snap <inputId> <out.bmp>      grab one frame from one input and save it as a BMP (test tool)
// Safety: always stops, closes and uninitializes, even on timeout or signal. Never takes the master role.
#include <dlfcn.h>
#include <android/dlext.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <string>
#include <vector>

namespace sc {
constexpr int NAME_LEN = 80, MAX_RES = 10, MAX_FMT = 5;
struct Res { uint32_t width, height; float fps; };
struct Input {              // 320 bytes on this firmware (one more word than the public 4.7 layout)
    uint32_t desc;
    char name[NAME_LEN];
    char parent[NAME_LEN];
    Res res[MAX_RES];
    uint32_t numRes;
    uint32_t fmt[MAX_FMT];
    uint32_t numFmt;
    uint32_t flags;
    uint32_t extra;
};
static_assert(sizeof(Input) == 320, "input layout");
struct Plane { uint32_t width, height, stride, size; void* buf; };
struct Buffer { Plane planes[3]; uint32_t nPlanes; };
struct Buffers { uint32_t colorFmt; Buffer* buffers; uint32_t nBuffers; uint32_t flags; };
struct FrameInfo { int32_t idx; uint32_t flags; uint32_t seq; uint64_t ts, tsSys, sof; uint32_t field; };
constexpr uint32_t FMT_UYVY8 = 0x7080102;
constexpr uint32_t BUF_OS_HNDL = 1u << 4;
constexpr uint32_t BUF_CACHE = 1u << 1;

typedef int (*InitFn)(void*);
typedef int (*UninitFn)();
typedef int (*QueryFn)(Input*, unsigned, unsigned*);
typedef void* (*OpenFn)(uint32_t);
typedef int (*HndlFn)(void*);
typedef int (*SBuffersFn)(void*, Buffers*);
typedef int (*GetFrameFn)(void*, FrameInfo*, uint64_t, uint32_t);
typedef int (*ReleaseFn)(void*, uint32_t);
}  // namespace sc

typedef android_namespace_t* (*GetNsFn)(const char*);

static void* g_lib;
static sc::UninitFn g_uninit;
static sc::HndlFn g_stop, g_close;
static void* g_hndl;
static bool g_started, g_inited;

static void* openAis() {
    void* h = dlopen("/vendor/lib64/libais_client.so", RTLD_NOW);
    if (h) return h;
    void* dla = dlopen("libdl_android.so", RTLD_NOW);
    auto getns = (GetNsFn)dlsym(dla ? dla : RTLD_DEFAULT, "android_get_exported_namespace");
    if (!getns) return nullptr;
    android_namespace_t* ns = getns("sphal");
    if (!ns) return nullptr;
    android_dlextinfo ei = {};
    ei.flags = ANDROID_DLEXT_USE_NAMESPACE;
    ei.library_namespace = ns;
    return android_dlopen_ext("/vendor/lib64/libais_client.so", RTLD_NOW, &ei);
}

// Always called on exit paths and from signals: stop, close, uninitialize, so BYD's own cameras are never left held.
static void cleanup() {
    if (g_hndl && g_started && g_stop) g_stop(g_hndl);
    g_started = false;
    if (g_hndl && g_close) g_close(g_hndl);
    g_hndl = nullptr;
    if (g_inited && g_uninit) g_uninit();
    g_inited = false;
}
static void onSignal(int) { cleanup(); _exit(2); }

static bool initAis() {
    g_lib = openAis();
    if (!g_lib) { printf("cannot load camera library\n"); return false; }
    auto init = (sc::InitFn)dlsym(g_lib, "qcarcam_initialize");
    g_uninit = (sc::UninitFn)dlsym(g_lib, "qcarcam_uninitialize");
    g_stop = (sc::HndlFn)dlsym(g_lib, "qcarcam_stop");
    g_close = (sc::HndlFn)dlsym(g_lib, "qcarcam_close");
    alignas(8) char params[64] = {0};
    ((uint32_t*)params)[1] = 0x0407;           // API version 4.7
    *(const char**)(params + 8) = "sharkos";
    int r = init(params);
    if (r != 0) { printf("initialize failed %d\n", r); return false; }
    g_inited = true;
    return true;
}

static int cmdList() {
    if (!initAis()) return 1;
    auto query = (sc::QueryFn)dlsym(g_lib, "qcarcam_query_inputs");
    static sc::Input inputs[16];
    unsigned n = 0;
    int r = query(inputs, 16, &n);
    printf("query=%d n=%u\n", r, n);
    for (unsigned i = 0; i < n && i < 16; i++) {
        const sc::Input& in = inputs[i];
        printf("input id=%u res=%ux%u@%.0f fmt=0x%x flags=%u\n", in.desc, in.res[0].width, in.res[0].height, in.res[0].fps, in.fmt[0], in.flags);
    }
    cleanup();
    return 0;
}

// --- ion buffer allocation (Linux 5.4 qgki legacy ion ABI) ---
struct IonAlloc { uint64_t len; uint32_t heapMask; uint32_t flags; int32_t fd; uint32_t unused; };
#define ION_IOC_ALLOC _IOWR('I', 0, IonAlloc)

static int ionAlloc(int ionFd, size_t len, uint32_t heapMask) {
    IonAlloc a = {};
    a.len = len; a.heapMask = heapMask; a.flags = 1 /* cached */;
    if (ioctl(ionFd, ION_IOC_ALLOC, &a) < 0) return -1;
    return a.fd;
}

static void writeBmpFromUyvy(const char* path, const uint8_t* p, int w, int h, int stride) {
    FILE* f = fopen(path, "wb");
    if (!f) { printf("cannot write %s\n", path); return; }
    int row = (w * 3 + 3) & ~3;
    uint32_t sz = 54 + row * h;
    uint8_t hdr[54] = {'B', 'M'};
    memcpy(hdr + 2, &sz, 4);
    uint32_t off = 54, dib = 40;
    memcpy(hdr + 10, &off, 4); memcpy(hdr + 14, &dib, 4);
    int32_t W = w, H = h;                      // positive height = bottom-up; we write rows bottom-up
    memcpy(hdr + 18, &W, 4); memcpy(hdr + 22, &H, 4);
    hdr[26] = 1; hdr[28] = 24;
    fwrite(hdr, 1, 54, f);
    std::vector<uint8_t> line(row);
    for (int y = h - 1; y >= 0; y--) {
        const uint8_t* s = p + (size_t)y * stride;
        for (int x = 0; x < w; x += 2) {
            int u = s[x * 2] - 128, y0 = s[x * 2 + 1], v = s[x * 2 + 2] - 128, y1 = s[x * 2 + 3];
            for (int k = 0; k < 2; k++) {
                int Y = k ? y1 : y0;
                int r = Y + ((1436 * v) >> 10), g = Y - ((352 * u + 731 * v) >> 10), b = Y + ((1815 * u) >> 10);
                auto cl = [](int c) { return (uint8_t)(c < 0 ? 0 : c > 255 ? 255 : c); };
                line[(x + k) * 3] = cl(b); line[(x + k) * 3 + 1] = cl(g); line[(x + k) * 3 + 2] = cl(r);
            }
        }
        fwrite(line.data(), 1, row, f);
    }
    fclose(f);
}

static int cmdSnap(uint32_t id, const char* out) {
    alarm(20);   // hard stop: the handler releases the camera
    if (!initAis()) return 1;
    auto query = (sc::QueryFn)dlsym(g_lib, "qcarcam_query_inputs");
    static sc::Input inputs[16];
    unsigned n = 0;
    query(inputs, 16, &n);
    const sc::Input* in = nullptr;
    for (unsigned i = 0; i < n && i < 16; i++) if (inputs[i].desc == id) in = &inputs[i];
    if (!in || !in->numRes) { printf("input %u not available\n", id); cleanup(); return 1; }
    int W = in->res[0].width, H = in->res[0].height, stride = W * 2;
    size_t bytes = (size_t)stride * H;
    const int NBUF = 3;

    int ionFd = open("/dev/ion", O_RDONLY);
    if (ionFd < 0) { perror("open /dev/ion"); cleanup(); return 1; }
    std::vector<int> fds(NBUF, -1);
    std::vector<uint8_t*> maps(NBUF, nullptr);
    uint32_t heaps[] = {1u << 25, 1u << 0, 1u << 21};
    uint32_t heapUsed = 0;
    for (uint32_t hm : heaps) {
        int fd = ionAlloc(ionFd, bytes, hm);
        if (fd >= 0) { close(fd); heapUsed = hm; break; }
    }
    if (!heapUsed) { printf("ion alloc failed: %s\n", strerror(errno)); cleanup(); return 1; }
    printf("ion heap mask 0x%x\n", heapUsed);
    sc::Buffer bufs[NBUF] = {};
    for (int i = 0; i < NBUF; i++) {
        fds[i] = ionAlloc(ionFd, bytes, heapUsed);
        maps[i] = (uint8_t*)mmap(nullptr, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fds[i], 0);
        if (fds[i] < 0 || maps[i] == MAP_FAILED) { printf("buffer %d alloc/map failed\n", i); cleanup(); return 1; }
        bufs[i].nPlanes = 1;
        bufs[i].planes[0] = {(uint32_t)W, (uint32_t)H, (uint32_t)stride, (uint32_t)bytes, (void*)(intptr_t)fds[i]};
    }
    sc::Buffers b = {sc::FMT_UYVY8, bufs, NBUF, sc::BUF_OS_HNDL | sc::BUF_CACHE};

    auto open_ = (sc::OpenFn)dlsym(g_lib, "qcarcam_open");
    auto sbuf = (sc::SBuffersFn)dlsym(g_lib, "qcarcam_s_buffers");
    auto start = (sc::HndlFn)dlsym(g_lib, "qcarcam_start");
    auto getf = (sc::GetFrameFn)dlsym(g_lib, "qcarcam_get_frame");
    auto rel = (sc::ReleaseFn)dlsym(g_lib, "qcarcam_release_frame");

    g_hndl = open_(id);
    printf("open=%p\n", g_hndl);
    if (!g_hndl) { cleanup(); return 1; }
    int r = sbuf(g_hndl, &b);
    printf("s_buffers=%d\n", r);
    if (r != 0) { cleanup(); return 1; }
    r = start(g_hndl);
    printf("start=%d\n", r);
    if (r != 0) { cleanup(); return 1; }
    g_started = true;
    int rc = 1;
    for (int tries = 0; tries < 20; tries++) {
        sc::FrameInfo fi = {};
        r = getf(g_hndl, &fi, 1000000000ull, 0);
        if (r != 0) { printf("get_frame=%d\n", r); continue; }
        printf("frame idx=%d seq=%u ts=%llu\n", fi.idx, fi.seq, (unsigned long long)fi.ts);
        if (tries >= 3 && fi.idx >= 0 && fi.idx < NBUF) {      // skip the first few frames while the sensor settles
            writeBmpFromUyvy(out, maps[fi.idx], W, H, stride);
            printf("saved %s (%dx%d)\n", out, W, H);
            rc = 0;
            rel(g_hndl, fi.idx);
            break;
        }
        if (fi.idx >= 0) rel(g_hndl, fi.idx);
    }
    cleanup();
    for (int i = 0; i < NBUF; i++) { munmap(maps[i], bytes); close(fds[i]); }
    close(ionFd);
    return rc;
}

// ======================= recorder =======================
// sc record <rootDir> <segmentSec> <fps> <bitrateKbps> <maxLoopMB> <id:name> [<id:name> ...]
// Writes <rootDir>/loop/<tripId>/<name>/<YYYYmmdd_HHMMSS>.mp4 (1 file per segment, H.264, no audio) and
// <rootDir>/status.json once a second. Stops cleanly on SIGTERM/SIGINT. Oldest loop clips are deleted past maxLoopMB.
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <media/NdkMediaMuxer.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <dirent.h>
#include <time.h>
#include <atomic>
#include <algorithm>
#include <mutex>
#include <thread>
#include <map>

static std::atomic<bool> g_stopRec{false};
static void onStopSignal(int) { g_stopRec = true; }
static std::atomic<int> g_rotateGen{0};
static void onRotateSignal(int) { g_rotateGen++; }

struct CamCfg { uint32_t id; std::string name; };
struct RecCfg { std::string root, trip; int segSec = 60, fps = 15; int bitrate = 3000000; long long maxLoopBytes = 8LL << 30; };
static RecCfg g_cfg;
static std::mutex g_lock;                         // guards qcarcam calls and shared stats
static std::map<std::string, std::atomic<long long>> g_frames;
static std::atomic<long long> g_bytesWritten{0};
static std::string g_lastError;

static void mkdirs(const std::string& p) {
    for (size_t i = 1; i <= p.size(); i++)
        if (i == p.size() || p[i] == '/') mkdir(p.substr(0, i).c_str(), 0775);
}
static std::string stamp() {
    time_t t = time(nullptr); tm lt; localtime_r(&t, &lt);
    char b[32]; strftime(b, sizeof b, "%Y%m%d_%H%M%S", &lt); return b;
}

// Deletes the oldest loop clips until the loop folder is under the byte limit.
static void trimLoop() {
    struct F { std::string path; long long size; };
    std::vector<F> files;
    std::string loop = g_cfg.root + "/loop";
    long long total = 0;
    DIR* d1 = opendir(loop.c_str());
    if (!d1) return;
    while (dirent* t = readdir(d1)) {
        if (t->d_name[0] == '.') continue;
        std::string tp = loop + "/" + t->d_name;
        { struct stat ks; if (stat((tp + "/.keep").c_str(), &ks) == 0) continue; }   // kept trips are never deleted
        DIR* d2 = opendir(tp.c_str());
        if (!d2) continue;
        while (dirent* c = readdir(d2)) {
            if (c->d_name[0] == '.') continue;
            std::string cp = tp + "/" + c->d_name;
            DIR* d3 = opendir(cp.c_str());
            if (!d3) continue;
            while (dirent* f = readdir(d3)) {
                std::string n = f->d_name;
                if (n.size() < 5 || n.substr(n.size() - 4) != ".mp4") continue;
                struct stat st;
                std::string fp = cp + "/" + n;
                if (stat(fp.c_str(), &st) == 0) { files.push_back({fp, (long long)st.st_size}); total += st.st_size; }
            }
            closedir(d3);
        }
        closedir(d2);
    }
    closedir(d1);
    // Also keep a floor of free space so BYD's own apps and updates never run out of storage.
    long long freeBytes = 1LL << 60;
    { struct statvfs sv; if (statvfs(g_cfg.root.c_str(), &sv) == 0) freeBytes = (long long)sv.f_bavail * (long long)sv.f_frsize; }
    const long long MIN_FREE = 6LL << 30;
    bool lowSpace = freeBytes < MIN_FREE;
    if (total <= g_cfg.maxLoopBytes && !lowSpace) return;
    // names embed trip id and time; sort by the clip file name (time) so the oldest goes first
    std::sort(files.begin(), files.end(), [](const F& a, const F& b) {
        std::string an = a.path.substr(a.path.rfind('/') + 1), bn = b.path.substr(b.path.rfind('/') + 1);
        return an < bn;
    });
    for (auto& f : files) {
        if (total <= g_cfg.maxLoopBytes && freeBytes >= MIN_FREE) break;
        if (unlink(f.path.c_str()) == 0) { total -= f.size; freeBytes += f.size; }
    }
}

// Deletes unfinished ".rec" clips left behind when a previous recorder was killed.
static void cleanPartial() {
    std::string loop = g_cfg.root + "/loop";
    DIR* d1 = opendir(loop.c_str());
    if (!d1) return;
    while (dirent* t = readdir(d1)) {
        if (t->d_name[0] == '.') continue;
        std::string tp = loop + "/" + t->d_name;
        DIR* d2 = opendir(tp.c_str());
        if (!d2) continue;
        while (dirent* c = readdir(d2)) {
            if (c->d_name[0] == '.') continue;
            std::string cp = tp + "/" + c->d_name;
            DIR* d3 = opendir(cp.c_str());
            if (!d3) continue;
            while (dirent* f = readdir(d3)) {
                std::string n = f->d_name;
                if (n.size() > 4 && n.substr(n.size() - 4) == ".rec") unlink((cp + "/" + n).c_str());
            }
            closedir(d3);
        }
        closedir(d2);
    }
    closedir(d1);
}

static void writeStatus(const std::vector<CamCfg>& cams, bool running) {
    std::string p = g_cfg.root + "/status.json", tmp = p + ".tmp";
    FILE* f = fopen(tmp.c_str(), "w");
    if (!f) return;
    fprintf(f, "{\"running\":%s,\"pid\":%d,\"trip\":\"%s\",\"segSec\":%d,\"fps\":%d,\"updated\":%lld,\"bytes\":%lld,\"cams\":{",
            running ? "true" : "false", (int)getpid(), g_cfg.trip.c_str(), g_cfg.segSec, g_cfg.fps, (long long)time(nullptr), g_bytesWritten.load());
    bool first = true;
    for (auto& c : cams) { fprintf(f, "%s\"%s\":%lld", first ? "" : ",", c.name.c_str(), g_frames[c.name].load()); first = false; }
    fprintf(f, "}");
    { std::lock_guard<std::mutex> l(g_lock); fprintf(f, ",\"error\":\"%s\"", g_lastError.c_str()); }
    fprintf(f, "}\n");
    fclose(f);
    rename(tmp.c_str(), p.c_str());
}

// Small live-preview picture (1/6 size BMP) so the app can show what each camera sees without decoding video.
static void writePreviewBmp(const std::string& path, const uint8_t* p, int srcW, int srcH, int stride) {
    const int step = 6, w = (srcW / step) & ~1, h = srcH / step;
    std::string tmp = path + ".tmp";
    FILE* f = fopen(tmp.c_str(), "wb");
    if (!f) return;
    int row = (w * 3 + 3) & ~3;
    uint32_t sz = 54 + row * h, off = 54, dib = 40;
    uint8_t hdr[54] = {'B', 'M'};
    memcpy(hdr + 2, &sz, 4); memcpy(hdr + 10, &off, 4); memcpy(hdr + 14, &dib, 4);
    int32_t W = w, H = h;
    memcpy(hdr + 18, &W, 4); memcpy(hdr + 22, &H, 4);
    hdr[26] = 1; hdr[28] = 24;
    fwrite(hdr, 1, 54, f);
    std::vector<uint8_t> line(row, 0);
    for (int y = h - 1; y >= 0; y--) {
        const uint8_t* s = p + (size_t)(y * step) * stride;
        for (int x = 0; x < w; x += 2) {
            int sx = x * step;
            int u = s[sx * 2] - 128, v = s[sx * 2 + 2] - 128;
            int y0 = s[sx * 2 + 1], y1 = s[(sx + step) * 2 + 1];
            for (int k = 0; k < 2; k++) {
                int Y = k ? y1 : y0;
                auto cl = [](int c) { return (uint8_t)(c < 0 ? 0 : c > 255 ? 255 : c); };
                line[(x + k) * 3] = cl(Y + ((1815 * u) >> 10));
                line[(x + k) * 3 + 1] = cl(Y - ((352 * u + 731 * v) >> 10));
                line[(x + k) * 3 + 2] = cl(Y + ((1436 * v) >> 10));
            }
        }
        fwrite(line.data(), 1, row, f);
    }
    fclose(f);
    rename(tmp.c_str(), path.c_str());
}

// Segmented MP4 writer fed from the encoder's output.
struct Segmenter {
    std::string dir; AMediaFormat* fmt = nullptr; AMediaMuxer* mux = nullptr; int fd = -1; int track = -1;
    std::string tmpPath, finalPath; int64_t segStartPts = -1; bool segOpen = false; int seenGen = 0;
    void open() {
        std::string name = stamp();
        finalPath = dir + "/" + name + ".mp4"; tmpPath = dir + "/" + name + ".rec";
        fd = ::open(tmpPath.c_str(), O_CREAT | O_RDWR | O_TRUNC, 0664);
        if (fd < 0) { segOpen = false; return; }
        mux = AMediaMuxer_new(fd, AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);
        track = AMediaMuxer_addTrack(mux, fmt);
        AMediaMuxer_start(mux);
        segOpen = true; segStartPts = -1;
    }
    void close() {
        if (!segOpen) return;
        AMediaMuxer_stop(mux); AMediaMuxer_delete(mux); mux = nullptr; ::close(fd); fd = -1;
        rename(tmpPath.c_str(), finalPath.c_str());
        segOpen = false;
        trimLoop();
    }
    void sample(const uint8_t* data, AMediaCodecBufferInfo* info, int segSec) {
        bool key = (info->flags & 1 /*SYNC_FRAME*/) != 0;
        bool rotateNow = key && seenGen != g_rotateGen.load();
        if (key) seenGen = g_rotateGen.load();
        if (segOpen && key && segStartPts >= 0 && (rotateNow || (info->presentationTimeUs - segStartPts) >= (int64_t)segSec * 1000000)) close();
        if (!segOpen) { if (!key) return; open(); if (!segOpen) return; }
        if (segStartPts < 0) segStartPts = info->presentationTimeUs;
        AMediaCodecBufferInfo out = *info;
        out.presentationTimeUs -= segStartPts;
        AMediaMuxer_writeSampleData(mux, track, data, &out);
        g_bytesWritten += info->size;
    }
};

static void drainEncoder(AMediaCodec* codec, Segmenter& seg, bool untilEos) {
    int waits = 0;
    for (;;) {
        AMediaCodecBufferInfo info;
        ssize_t o = AMediaCodec_dequeueOutputBuffer(codec, &info, untilEos ? 100000 : 0);
        if (o == AMEDIACODEC_INFO_TRY_AGAIN_LATER) { if (untilEos && ++waits < 40) continue; return; }
        if (o == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            if (seg.fmt) AMediaFormat_delete(seg.fmt);
            seg.fmt = AMediaCodec_getOutputFormat(codec);
            continue;
        }
        if (o < 0) continue;
        size_t sz = 0;
        uint8_t* buf = AMediaCodec_getOutputBuffer(codec, o, &sz);
        bool eos = (info.flags & 4 /*END_OF_STREAM*/) != 0;
        if (buf && info.size > 0 && seg.fmt && !(info.flags & 2 /*CODEC_CONFIG*/)) seg.sample(buf + info.offset, &info, g_cfg.segSec);
        AMediaCodec_releaseOutputBuffer(codec, o, false);
        if (eos) return;
    }
}

static void recordCam(CamCfg cam, const sc::Input in) {
    const int srcW = in.res[0].width, srcH = in.res[0].height, srcStride = srcW * 2;
    // Encode at 2/3 size (1920x1300 -> 1280x864) to keep four cameras light on the CPU.
    const int encW = (srcW * 2 / 3) & ~15, encH = ((srcH * 2 / 3)) & ~15;
    std::string dir = g_cfg.root + "/loop/" + g_cfg.trip + "/" + cam.name;
    mkdirs(dir);

    auto open_ = (sc::OpenFn)dlsym(g_lib, "qcarcam_open");
    auto sbuf = (sc::SBuffersFn)dlsym(g_lib, "qcarcam_s_buffers");
    auto start = (sc::HndlFn)dlsym(g_lib, "qcarcam_start");
    auto stop = (sc::HndlFn)dlsym(g_lib, "qcarcam_stop");
    auto close_ = (sc::HndlFn)dlsym(g_lib, "qcarcam_close");
    auto getf = (sc::GetFrameFn)dlsym(g_lib, "qcarcam_get_frame");
    auto rel = (sc::ReleaseFn)dlsym(g_lib, "qcarcam_release_frame");

    const int NBUF = 4;
    size_t bytes = (size_t)srcStride * srcH;
    int ionFd = open("/dev/ion", O_RDONLY);
    std::vector<int> fds(NBUF, -1);
    std::vector<uint8_t*> maps(NBUF, nullptr);
    sc::Buffer bufs[NBUF] = {};
    bool ok = ionFd >= 0;
    for (int i = 0; ok && i < NBUF; i++) {
        fds[i] = ionAlloc(ionFd, bytes, 1u << 25);
        if (fds[i] < 0) { ok = false; break; }
        maps[i] = (uint8_t*)mmap(nullptr, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fds[i], 0);
        if (maps[i] == MAP_FAILED) { ok = false; break; }
        bufs[i].nPlanes = 1;
        bufs[i].planes[0] = {(uint32_t)srcW, (uint32_t)srcH, (uint32_t)srcStride, (uint32_t)bytes, (void*)(intptr_t)fds[i]};
    }
    sc::Buffers b = {sc::FMT_UYVY8, bufs, NBUF, sc::BUF_OS_HNDL | sc::BUF_CACHE};
    void* h = nullptr;
    bool started = false;
    if (ok) {
        std::lock_guard<std::mutex> l(g_lock);
        h = open_(cam.id);
        if (!h || sbuf(h, &b) != 0 || start(h) != 0) { ok = false; g_lastError = "camera " + cam.name + " would not start"; }
        else started = true;
    }
    AMediaCodec* codec = nullptr;
    Segmenter seg; seg.dir = dir;
    if (ok) {
        codec = AMediaCodec_createEncoderByType("video/avc");
        AMediaFormat* f = AMediaFormat_new();
        AMediaFormat_setString(f, AMEDIAFORMAT_KEY_MIME, "video/avc");
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_WIDTH, encW);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_HEIGHT, encH);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_BIT_RATE, g_cfg.bitrate);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_FRAME_RATE, g_cfg.fps);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_COLOR_FORMAT, 21 /*YUV420SemiPlanar*/);
        media_status_t ms = codec ? AMediaCodec_configure(codec, f, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE) : AMEDIA_ERROR_UNKNOWN;
        AMediaFormat_delete(f);
        if (ms != AMEDIA_OK || AMediaCodec_start(codec) != AMEDIA_OK) {
            ok = false; std::lock_guard<std::mutex> l(g_lock); g_lastError = "encoder failed for " + cam.name;
        }
    }
    int encStride = encW, encSlice = encH;
    if (ok) {
        AMediaFormat* inf = AMediaCodec_getInputFormat(codec);
        int32_t v;
        if (inf && AMediaFormat_getInt32(inf, "stride", &v) && v >= encW) encStride = v;
        if (inf && AMediaFormat_getInt32(inf, "slice-height", &v) && v >= encH) encSlice = v;
        if (inf) AMediaFormat_delete(inf);
    }
    int64_t period = 1000000000LL / g_cfg.fps, nextTs = 0, ptsBase = -1, nextPrev = 0;
    mkdirs(g_cfg.root + "/live");
    const std::string previewPath = g_cfg.root + "/live/" + cam.name + ".bmp";
    int misses = 0;
    while (ok && !g_stopRec.load()) {
        sc::FrameInfo fi = {};
        int r = getf(h, &fi, 500000000ull, 0);
        if (r != 0) {
            if (++misses == 6) { std::lock_guard<std::mutex> l(g_lock); g_lastError = "no video from " + cam.name; }
            continue;
        }
        if (misses >= 6) { std::lock_guard<std::mutex> l(g_lock); g_lastError.clear(); }
        misses = 0;
        int64_t ts = (int64_t)fi.ts;
        if (fi.idx < 0 || fi.idx >= NBUF) continue;
        if (ts >= nextPrev) { nextPrev = ts + 1000000000LL; writePreviewBmp(previewPath, maps[fi.idx], srcW, srcH, srcStride); }
        if (ts >= nextTs) {
            nextTs = (nextTs == 0 ? ts : nextTs) + period;
            if (nextTs < ts) nextTs = ts + period;
            ssize_t ib = AMediaCodec_dequeueInputBuffer(codec, 20000);
            if (ib >= 0) {
                size_t isz = 0;
                uint8_t* dst = AMediaCodec_getInputBuffer(codec, ib, &isz);
                size_t need = (size_t)encStride * encSlice * 3 / 2;
                if (dst && isz >= need) {
                    const uint8_t* src = maps[fi.idx];
                    uint8_t* yp = dst; uint8_t* uvp = dst + (size_t)encStride * encSlice;
                    for (int y = 0; y < encH; y++) {
                        const uint8_t* srow = src + (size_t)((y * 3) / 2) * srcStride;
                        uint8_t* drow = yp + (size_t)y * encStride;
                        for (int x = 0; x < encW; x++) drow[x] = srow[((x * 3) / 2) * 2 + 1];
                        if ((y & 1) == 0) {
                            uint8_t* crow = uvp + (size_t)(y / 2) * encStride;
                            for (int x = 0; x < encW; x += 2) { int sx = ((x * 3) / 2) & ~1; crow[x] = srow[sx * 2]; crow[x + 1] = srow[sx * 2 + 2]; }
                        }
                    }
                    if (ptsBase < 0) ptsBase = ts;
                    AMediaCodec_queueInputBuffer(codec, ib, 0, need, (ts - ptsBase) / 1000, 0);
                    g_frames[cam.name]++;
                } else {
                    AMediaCodec_queueInputBuffer(codec, ib, 0, 0, 0, 0);
                }
            }
        }
        rel(h, fi.idx);
        drainEncoder(codec, seg, false);
    }
    if (codec) {
        ssize_t ib = AMediaCodec_dequeueInputBuffer(codec, 200000);
        if (ib >= 0) AMediaCodec_queueInputBuffer(codec, ib, 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
        drainEncoder(codec, seg, true);
        seg.close();
        AMediaCodec_stop(codec); AMediaCodec_delete(codec);
    }
    if (seg.fmt) AMediaFormat_delete(seg.fmt);
    if (h) { std::lock_guard<std::mutex> l(g_lock); if (started) stop(h); close_(h); }
    for (int i = 0; i < NBUF; i++) { if (maps[i] && maps[i] != MAP_FAILED) munmap(maps[i], bytes); if (fds[i] >= 0) close(fds[i]); }
    if (ionFd >= 0) close(ionFd);
}

static int cmdRecord(int argc, char** argv) {
    // argv: record root segSec fps kbps maxMB id:name...
    if (argc < 8) { printf("usage: sc record <root> <segSec> <fps> <kbps> <maxLoopMB> <id:name>...\n"); return 1; }
    g_cfg.root = argv[2]; g_cfg.segSec = atoi(argv[3]); g_cfg.fps = atoi(argv[4]);
    g_cfg.bitrate = atoi(argv[5]) * 1000; g_cfg.maxLoopBytes = atoll(argv[6]) << 20;
    g_cfg.trip = stamp();
    std::vector<CamCfg> cams;
    for (int i = 7; i < argc; i++) {
        std::string a = argv[i]; size_t c = a.find(':');
        if (c == std::string::npos) continue;
        cams.push_back({(uint32_t)atoi(a.substr(0, c).c_str()), a.substr(c + 1)});
    }
    mkdirs(g_cfg.root + "/loop");
    cleanPartial();
    signal(SIGTERM, onStopSignal); signal(SIGINT, onStopSignal); signal(SIGHUP, onStopSignal); signal(SIGUSR1, onRotateSignal);
    if (!initAis()) return 1;
    auto query = (sc::QueryFn)dlsym(g_lib, "qcarcam_query_inputs");
    static sc::Input inputs[16];
    unsigned n = 0;
    query(inputs, 16, &n);
    std::vector<std::thread> threads;
    for (auto& cam : cams) {
        const sc::Input* in = nullptr;
        for (unsigned i = 0; i < n && i < 16; i++) if (inputs[i].desc == cam.id) in = &inputs[i];
        if (!in || !in->numRes) { std::lock_guard<std::mutex> l(g_lock); g_lastError = "camera " + cam.name + " not available"; continue; }
        g_frames[cam.name] = 0;
        threads.emplace_back(recordCam, cam, *in);
    }
    printf("recording trip %s with %zu cameras\n", g_cfg.trip.c_str(), threads.size());
    while (!g_stopRec.load()) { writeStatus(cams, true); sleep(1); }
    for (auto& t : threads) t.join();
    writeStatus(cams, false);
    cleanup();
    return 0;
}

int main(int argc, char** argv) {
    setvbuf(stdout, nullptr, _IONBF, 0);
    if (argc >= 2 && !strcmp(argv[1], "record")) return cmdRecord(argc, argv);
    signal(SIGALRM, onSignal); signal(SIGTERM, onSignal); signal(SIGINT, onSignal); signal(SIGHUP, onSignal);
    if (argc >= 2 && !strcmp(argv[1], "list")) return cmdList();
    if (argc >= 4 && !strcmp(argv[1], "snap")) return cmdSnap((uint32_t)atoi(argv[2]), argv[3]);
    printf("usage: sc list | sc snap <inputId> <out.bmp> | sc record ...\n");
    return 1;
}
