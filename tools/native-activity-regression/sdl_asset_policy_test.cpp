#include "../../app/src/main/cpp/sdl_asset_redirect/asset_path_policy.h"
#include <cassert>
#include <cstdio>
#include <fstream>
#include <string>
#include <unistd.h>

// Run inside a disposable directory on the Android emulator; no SDL/JNI dependency.
int main(int argc, char **argv) {
    assert(argc == 2);
    const std::string base = argv[1];
    const auto files = base + "/files";
    const auto project = base + "/项目😀";
    const auto assets = project + "/assets";
    const auto custom = project + "/resources";
    for (const auto &dir : {base, files, project, assets, custom}) mkdir(dir.c_str(), 0700);
    auto write = [](const std::string &path, const char *text) { std::ofstream(path) << text; };
    write(project + "/shared.txt", "root");
    write(assets + "/shared.txt", "asset");
    write(custom + "/shared.txt", "custom");
    write(assets + "/中文😀.txt", "UTF-8");
    write(base + "/outside.txt", "outside");
    symlink((base + "/outside.txt").c_str(), (assets + "/escape.txt").c_str());
    auto resolve = [&](const char *relative, const char *mode = "rb", const std::string &root = "") {
        return tina::sdl_assets::resolve(files, project, root.empty() ? assets : root,
                                        (files + "/" + relative).c_str(), mode);
    };
    assert(resolve("shared.txt") == assets + "/shared.txt");
    assert(resolve("shared.txt", "rb", custom) == custom + "/shared.txt");
    assert(resolve("assets/shared.txt") == assets + "/shared.txt");
    assert(resolve("中文😀.txt") == assets + "/中文😀.txt");
    assert(resolve("missing").empty());
    assert(resolve("escape.txt").empty());
    assert(resolve("../outside.txt").empty());
    assert(resolve("shared.txt", "r+").empty());
    assert(resolve("shared.txt", "rb+").empty());
    assert(resolve("shared.txt", "wb").empty());
    assert(resolve("shared.txt", "ab").empty());
    write(files + "/shared.txt", "save");
    assert(resolve("shared.txt").empty());
    assert(tina::sdl_assets::resolve(files, project, assets, "/different/shared.txt", "rb").empty());
    puts("PASS: 13 SDL resource path, UTF-8, write isolation and traversal cases");
}
