// Reproduz, sem ROM, o que o LibretroDroid faz quando um mesmo processo `:game` hospeda duas
// sessoes do FBNeo: `LibretroDroid::create` faz `dlopen` + `retro_set_environment` + `retro_init`,
// `LibretroDroid::destroy` faz `retro_deinit`, e o `.so` nunca e descarregado (pitfall 13). O
// `dlopen` da segunda sessao devolve a mesma imagem, com os estaticos que o `retro_deinit` deixou.
//
// No FBNeo, `BurnGameListExit` (src/burn/burn.cpp) libera `pszShortName`/`pszFullNameA`/
// `pszFullNameW` sem zerar os ponteiros, e o `BurnLibInit` do segundo `retro_init` chama
// `BurnLibExit` de novo: double free.
//
// Uso: fbneo_reinit_test <core.so> <sessoes>
//   1 = controle (uma sessao por processo, o que o app garante depois da correcao)
//   2 = o cenario do bug
#include <dlfcn.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>

typedef bool (*retro_environment_t)(unsigned command, void *data);

static bool environment(unsigned command, void *data)
{
    (void)command;
    (void)data;
    return false;
}

int main(int argc, char **argv)
{
    if (argc != 3) {
        fprintf(stderr, "usage: %s <core.so> <sessions>\n", argv[0]);
        return 2;
    }
    int sessions = atoi(argv[2]);
    if (sessions < 1) {
        fprintf(stderr, "sessions must be >= 1\n");
        return 2;
    }

    for (int session = 1; session <= sessions; session++) {
        // Mesma sequencia de Core::open + LibretroDroid::create, sem dlclose no fim.
        void *core = dlopen(argv[1], RTLD_LOCAL | RTLD_LAZY);
        if (!core) {
            fprintf(stderr, "dlopen failed: %s\n", dlerror());
            return 2;
        }
        void (*set_environment)(retro_environment_t) =
            (void (*)(retro_environment_t))dlsym(core, "retro_set_environment");
        void (*init)(void) = (void (*)(void))dlsym(core, "retro_init");
        void (*deinit)(void) = (void (*)(void))dlsym(core, "retro_deinit");
        if (!set_environment || !init || !deinit) {
            fprintf(stderr, "missing libretro symbols\n");
            return 2;
        }

        printf("session %d: handle %p, retro_init\n", session, core);
        fflush(stdout);
        set_environment(environment);
        init();

        printf("session %d: retro_deinit\n", session);
        fflush(stdout);
        deinit();
    }

    printf("OK: %d session(s) without abort\n", sessions);
    return 0;
}
