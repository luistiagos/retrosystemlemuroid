// Android regression test for the real LibretroDroid shared library.
// Build once with -DTEST_CORE -shared, and once as the test executable.
#ifdef TEST_CORE
#include "libretro.h"
#include <cassert>
static retro_environment_t environment;
static int events;
static void context_destroy() { assert(events == 0); events = 1; }
extern "C" int teardown_events() { return events; }
void retro_init() {
    events = 0;
    retro_hw_render_callback hw{};
    hw.context_type = RETRO_HW_CONTEXT_OPENGLES3;
    hw.context_destroy = context_destroy;
    assert(environment(RETRO_ENVIRONMENT_SET_HW_RENDER, &hw));
}
void retro_unload_game() { assert(events == 1); events = 2; }
void retro_deinit() { assert(events == 2); events = 3; }
void retro_set_environment(retro_environment_t cb) { environment = cb; }
void retro_cheat_reset() {}
void retro_cheat_set(unsigned, bool, const char*) {}
unsigned retro_api_version() { return RETRO_API_VERSION; }
void retro_get_system_info(retro_system_info*) {}
void retro_get_system_av_info(retro_system_av_info*) {}
void retro_set_controller_port_device(unsigned, unsigned) {}
void retro_reset() {}
void retro_run() {}
size_t retro_serialize_size() { return 0; }
bool retro_serialize(void*, size_t) { return false; }
bool retro_unserialize(const void*, size_t) { return false; }
size_t retro_get_memory_size(unsigned) { return 0; }
void* retro_get_memory_data(unsigned) { return nullptr; }
bool retro_load_game(const retro_game_info*) { return false; }
void retro_set_video_refresh(retro_video_refresh_t) {}
void retro_set_audio_sample(retro_audio_sample_t) {}
void retro_set_audio_sample_batch(retro_audio_sample_batch_t) {}
void retro_set_input_poll(retro_input_poll_t) {}
void retro_set_input_state(retro_input_state_t) {}
#else
#include "libretrodroid.h"
#include <cassert>
#include <cstdio>
#include <dlfcn.h>
using namespace libretrodroid;
static int stale_callbacks;
static void stale_context_destroy() { ++stale_callbacks; }
static void create(const char* path) {
    LibretroDroid::getInstance().create(3, path, "/tmp/system", "/tmp/saves", {},
        ShaderManager::Config{}, 60.0f, false, false, false, false, std::nullopt, "en");
}
static void check_clean() {
    auto& environment = Environment::getInstance();
    assert(environment.getHwContextDestroy() == nullptr);
    const char* path = nullptr;
    assert(!Environment::callback_environment(RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY, &path));
}
int main(int argc, char** argv) {
    assert(argc == 2);
    auto& runtime = LibretroDroid::getInstance();
    // This first call crashes in the unpatched library.
    runtime.destroy();
    runtime.destroy();
    check_clean();
    puts("PASS: destroy before create, twice");

    retro_hw_render_callback stale{};
    stale.context_type = RETRO_HW_CONTEXT_OPENGLES3;
    stale.context_destroy = stale_context_destroy;
    assert(Environment::callback_environment(RETRO_ENVIRONMENT_SET_HW_RENDER, &stale));
    runtime.destroy();
    assert(stale_callbacks == 0);
    check_clean();
    puts("PASS: stale hardware callback skipped without core, environment still cleaned");

    bool failed = false;
    try { create("/nonexistent/libretrodroid-regression-core.so"); }
    catch (const std::exception&) { failed = true; }
    assert(failed);
    runtime.destroy();
    runtime.destroy();
    check_clean();
    puts("PASS: cleanup after failed dlopen, twice");

    void* core = dlopen(argv[1], RTLD_NOW);
    assert(core);
    auto events = reinterpret_cast<int (*)()>(dlsym(core, "teardown_events"));
    assert(events);
    for (int session = 0; session < 2; ++session) {
        create(argv[1]);
        assert(events() == 0);
        // Failed game load leaves an existing core requiring teardown.
        bool load_failed = false;
        try { runtime.loadGameFromBytes(nullptr, 0); }
        catch (const std::exception&) { load_failed = true; }
        assert(load_failed);
        runtime.destroy();
        assert(events() == 3);
        check_clean();
        runtime.destroy();
        assert(events() == 3);
    }
    puts("PASS: hardware callback, unload and deinit exactly once; recreate after destroy");
    return 0;
}
#endif
