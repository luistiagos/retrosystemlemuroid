#include <dlfcn.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "libretro.h"

static unsigned video_calls;
static unsigned nonzero_frames;
static unsigned audio_batches;
static char *variable_keys[128];
static char *variable_values[128];
static unsigned variable_count;
static bool variables_dirty;

static bool environment(unsigned command, void *data)
{
    static const char *directory = "/data/local/tmp";

    switch (command) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
            return true;
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        case RETRO_ENVIRONMENT_GET_CONTENT_DIRECTORY:
            *(const char **)data = directory;
            return true;
        case RETRO_ENVIRONMENT_SET_VARIABLES: {
            const struct retro_variable *received = data;
            for (unsigned i = 0; received[i].key && variable_count < 128; i++) {
                const char *start = strstr(received[i].value, "; ");
                if (!start) continue;
                start += 2;
                const char *end = strchr(start, '|');
                size_t length = end ? (size_t)(end - start) : strlen(start);
                variable_keys[variable_count] = strdup(received[i].key);
                variable_values[variable_count] = strndup(start, length);
                variable_count++;
            }
            variables_dirty = true;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            struct retro_variable *requested = data;
            for (unsigned i = 0; i < variable_count; i++) {
                if (!strcmp(variable_keys[i], requested->key)) {
                    requested->value = variable_values[i];
                    return true;
                }
            }
            return false;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *(bool *)data = variables_dirty;
            variables_dirty = false;
            return true;
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            *(int *)data = 3;
            return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *(bool *)data = false;
            return true;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            *(unsigned *)data = 0;
            return true;
        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            *(unsigned *)data = RETRO_LANGUAGE_ENGLISH;
            return true;
        default:
            return false;
    }
}

static void video(const void *data, unsigned width, unsigned height, size_t pitch)
{
    video_calls++;
    if (data) {
        const uint8_t *bytes = data;
        uint64_t sum = 0;
        for (unsigned y = 0; y < height; y++)
            for (size_t x = 0; x < pitch; x++)
                sum += bytes[y * pitch + x];
        if (sum)
            nonzero_frames++;
        if (video_calls <= 3 || video_calls == 60 || video_calls == 180)
            printf("video %u: %ux%u pitch=%zu sum=%llu\n", video_calls, width, height,
                   pitch, (unsigned long long)sum);
    }
}

static void audio(int16_t left, int16_t right) { (void)left; (void)right; }
static size_t audio_batch(const int16_t *data, size_t frames)
{
    (void)data;
    audio_batches++;
    return frames;
}
static void input_poll(void) {}
static int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id)
{
    (void)port; (void)device; (void)index; (void)id;
    return 0;
}

#define LOAD(name) typeof(name) *p_##name = (typeof(name) *)dlsym(handle, #name)

int main(int argc, char **argv)
{
    if (argc != 3) {
        fprintf(stderr, "usage: %s CORE ROM\n", argv[0]);
        return 2;
    }

    void *handle = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        fprintf(stderr, "dlopen: %s\n", dlerror());
        return 3;
    }

    LOAD(retro_set_environment);
    LOAD(retro_set_video_refresh);
    LOAD(retro_set_audio_sample);
    LOAD(retro_set_audio_sample_batch);
    LOAD(retro_set_input_poll);
    LOAD(retro_set_input_state);
    LOAD(retro_init);
    LOAD(retro_deinit);
    LOAD(retro_load_game);
    LOAD(retro_unload_game);
    LOAD(retro_run);

    FILE *file = fopen(argv[2], "rb");
    if (!file) return 4;
    fseek(file, 0, SEEK_END);
    long size = ftell(file);
    rewind(file);
    void *rom = malloc((size_t)size);
    if (!rom || fread(rom, 1, (size_t)size, file) != (size_t)size) return 5;
    fclose(file);

    p_retro_set_environment(environment);
    p_retro_set_video_refresh(video);
    p_retro_set_audio_sample(audio);
    p_retro_set_audio_sample_batch(audio_batch);
    p_retro_set_input_poll(input_poll);
    p_retro_set_input_state(input_state);
    p_retro_init();

    struct retro_game_info game = { argv[2], rom, (size_t)size, NULL };
    if (!p_retro_load_game(&game)) {
        fprintf(stderr, "retro_load_game failed\n");
        return 6;
    }
    for (unsigned i = 0; i < 300; i++) {
        p_retro_run();
        usleep(16667);
    }

    printf("result: video=%u nonzero=%u audio_batches=%u\n",
           video_calls, nonzero_frames, audio_batches);
    p_retro_unload_game();
    p_retro_deinit();
    free(rom);
    dlclose(handle);
    return 0;
}
