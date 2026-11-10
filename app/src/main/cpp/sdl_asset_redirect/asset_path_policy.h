#pragma once

#include <cstdlib>
#include <cstring>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

namespace tina::sdl_assets {

inline bool within(const std::string &path, const std::string &root) {
    return path == root || (path.size() > root.size() && path.compare(0, root.size(), root) == 0 &&
                           path[root.size()] == '/');
}

inline std::string canonical_resource(const std::string &root, const std::string &relative) {
    char *resolved = realpath((root + "/" + relative).c_str(), nullptr);
    if (!resolved) return {};
    std::string path(resolved);
    free(resolved);
    struct stat info {};
    if (!within(path, root) || stat(path.c_str(), &info) != 0 || !S_ISREG(info.st_mode)) return {};
    return path;
}

// Pure path policy shared by both libc hooks and the device regression executable.
inline std::string resolve(const std::string &files_dir, const std::string &project_root,
                           const std::string &asset_dir, const char *path, const char *mode) {
    if (!path || !mode || mode[0] != 'r' || strchr(mode, '+') || files_dir.empty() || project_root.empty()) return {};
    const std::string requested(path);
    if (requested.size() <= files_dir.size() || !within(requested, files_dir)) return {};
    const auto relative = requested.substr(files_dir.size() + 1);
    if (relative.empty() || relative[0] == '/') return {};
    for (size_t begin = 0; begin < relative.size();) {
        const auto end = relative.find('/', begin);
        if (relative.substr(begin, end == std::string::npos ? end : end - begin) == "..") return {};
        if (end == std::string::npos) break;
        begin = end + 1;
    }
    // Never shadow an existing save or redirect a read/write open into source assets.
    if (access(path, F_OK) == 0) return {};
    if (!asset_dir.empty() && within(asset_dir, project_root)) {
        auto resolved = canonical_resource(asset_dir, relative);
        if (!resolved.empty()) return resolved;
    }
    return canonical_resource(project_root, relative);
}

} // namespace tina::sdl_assets
