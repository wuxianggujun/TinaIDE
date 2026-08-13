from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit


CURRENT_FACT_DOCUMENTS = (
    "README.md",
    "README_EN.md",
    "docs/README.md",
    "docs/快速开始.md",
    "docs/开发指南.md",
    "docs/架构概览.md",
    "docs/模块功能说明.md",
    "docs/documentation-status.md",
    "docs/game-engine-plugin-sdl.md",
    "docs/linux-distro-self-hosted-runtime.md",
    "docs/registry/GitHub-Registry.md",
    "docs/toolchain-build-guide.md",
    "docs/proguard-rules-reference.md",
    "docs/guides/MT-Data-Files-Provider.md",
)

HELP_ASSET_DIRECTORY = "feature/help/src/main/assets/help"
HELP_ENGLISH_ASSET_DIRECTORY = HELP_ASSET_DIRECTORY + "/en"
HELP_REPOSITORY = (
    "feature/help/src/main/java/com/wuxianggujun/tinaide/core/help/HelpRepository.kt"
)
REGISTRY_FACT_DOCUMENTS = (
    "README.md",
    "README_EN.md",
    "docs/documentation-status.md",
    "docs/registry/GitHub-Registry.md",
)
REGISTRY_PATHS = (
    "plugins/index.v3.json",
    "plugins/index.v2.json",
    "packages/index.v2.json",
    "linux-distro/manifest.v1.json",
)

FENCED_CODE_PATTERN = re.compile(r"```.*?```|~~~.*?~~~", re.DOTALL)
INLINE_CODE_PATTERN = re.compile(r"`[^`\n]+`")
MARKDOWN_LINK_PATTERN = re.compile(r"!?\[[^\]]*]\(([^)]+)\)")
HELP_FILE_PATTERN = re.compile(r'fileName\s*=\s*"([^"]+\.md)"')
SDK_ASSIGNMENT_PATTERN = re.compile(r"\b(compileSdk|minSdk|targetSdk)\s*=\s*(\d+)")
SDK_DOCUMENT_PATTERN = re.compile(r"\b(compileSdk|minSdk|targetSdk)\s*[:=]\s*(\d+)")
INLINE_REPOSITORY_PATH_PATTERN = re.compile(
    r"`((?:app|core|feature|tools|build-logic|docs|server|docker|gradle|external)/[^`\r\n]+)`"
)
INLINE_PATH_EXCEPTIONS = {
    "tools/tina-lsp-proxy.py",
    "tools/tina-lsp-proxy-kt",
}
STALE_SOURCE_PATHS = (
    "app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspEditorManager.kt",
    "app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/BuiltinLanguageServiceSession.kt",
    "app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/CMakeLanguageServiceSession.kt",
    "app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/MakeLanguageServiceSession.kt",
    "app/src/main/java/com/wuxianggujun/tinaide/ui/compose/state/editor/LspSemanticTokenDecoder.kt",
)


def find_repo_root(start: Path) -> Path:
    current = start.resolve()
    for candidate in (current, *current.parents):
        if (candidate / "settings.gradle.kts").is_file() and (candidate / "tools").is_dir():
            return candidate
    raise RuntimeError(f"Unable to find repository root from {start}")


def markdown_target(raw_value: str) -> str:
    value = raw_value.strip()
    if value.startswith("<") and ">" in value:
        return value[1 : value.index(">")].strip()
    return value.split(maxsplit=1)[0] if value else ""


def check_markdown_links(root: Path) -> list[str]:
    failures: list[str] = []
    documents = [root / "README.md", root / "README_EN.md"]
    documents.extend(sorted((root / "docs").rglob("*.md")))
    documents.extend(sorted((root / HELP_ASSET_DIRECTORY).glob("*.md")))
    documents.extend(sorted((root / HELP_ENGLISH_ASSET_DIRECTORY).glob("*.md")))

    for document in documents:
        if not document.is_file():
            failures.append(f"missing current document: {document.relative_to(root)}")
            continue

        content = document.read_text(encoding="utf-8")
        searchable = INLINE_CODE_PATTERN.sub("", FENCED_CODE_PATTERN.sub("", content))
        for match in MARKDOWN_LINK_PATTERN.finditer(searchable):
            target = markdown_target(match.group(1))
            if not target or target.startswith("#"):
                continue

            parsed = urlsplit(target)
            if parsed.scheme or parsed.netloc:
                continue

            path_text = unquote(parsed.path).replace("\\", "/")
            if not path_text:
                continue

            candidate = (
                root / path_text.lstrip("/")
                if path_text.startswith("/")
                else document.parent / path_text
            ).resolve()
            try:
                candidate.relative_to(root)
            except ValueError:
                failures.append(
                    f"link escapes repository: {document.relative_to(root)} -> {target}"
                )
                continue

            if not candidate.exists():
                failures.append(
                    f"broken local link: {document.relative_to(root)} -> {target}"
                )

    return failures


def check_inline_repository_paths(root: Path) -> list[str]:
    failures: list[str] = []
    for relative_path in CURRENT_FACT_DOCUMENTS:
        document = root / relative_path
        if not document.is_file():
            continue
        content = document.read_text(encoding="utf-8")
        for raw_path in INLINE_REPOSITORY_PATH_PATTERN.findall(content):
            if raw_path in INLINE_PATH_EXCEPTIONS:
                continue
            if any(
                marker in raw_path
                for marker in ("*", "<", ">", "{", "}", "$", "...")
            ):
                continue
            if any(character.isspace() for character in raw_path):
                continue
            if not (root / raw_path).exists():
                failures.append(
                    f"missing inline repository path: {relative_path} -> {raw_path}"
                )

    searchable_documents = sorted((root / "docs").rglob("*.md"))
    skills_directory = root / ".agents" / "skills"
    if skills_directory.is_dir():
        searchable_documents.extend(sorted(skills_directory.rglob("*.md")))
    for document in searchable_documents:
        content = document.read_text(encoding="utf-8")
        for stale_path in STALE_SOURCE_PATHS:
            if stale_path in content:
                failures.append(
                    f"stale moved source path: {document.relative_to(root)} -> {stale_path}"
                )
    return failures


def check_help_catalog(root: Path) -> list[str]:
    help_directory = root / HELP_ASSET_DIRECTORY
    english_help_directory = root / HELP_ENGLISH_ASSET_DIRECTORY
    repository = root / HELP_REPOSITORY
    if not help_directory.is_dir():
        return [f"missing help asset directory: {HELP_ASSET_DIRECTORY}"]
    if not english_help_directory.is_dir():
        return [f"missing English help asset directory: {HELP_ENGLISH_ASSET_DIRECTORY}"]
    if not repository.is_file():
        return [f"missing help repository: {HELP_REPOSITORY}"]

    asset_names = {path.name for path in help_directory.glob("*.md")}
    english_asset_names = {path.name for path in english_help_directory.glob("*.md")}
    registered_names = set(
        HELP_FILE_PATTERN.findall(repository.read_text(encoding="utf-8"))
    )

    failures: list[str] = []
    for file_name in sorted(asset_names - registered_names):
        failures.append(f"help asset is not registered: {file_name}")
    for file_name in sorted(registered_names - asset_names):
        failures.append(f"registered help asset is missing: {file_name}")
    for file_name in sorted(asset_names - english_asset_names):
        failures.append(f"English help asset is missing: {file_name}")
    for file_name in sorted(english_asset_names - asset_names):
        failures.append(f"English help asset has no default counterpart: {file_name}")
    return failures


def check_sdk_facts(root: Path) -> list[str]:
    build_file = root / "app/build.gradle.kts"
    assignments = dict(
        SDK_ASSIGNMENT_PATTERN.findall(build_file.read_text(encoding="utf-8"))
    )
    required_keys = {"compileSdk", "minSdk", "targetSdk"}
    missing_keys = required_keys - assignments.keys()
    if missing_keys:
        return [f"unable to read SDK values: {', '.join(sorted(missing_keys))}"]

    failures: list[str] = []
    for relative_path in (
        "README.md",
        "README_EN.md",
        "docs/documentation-status.md",
    ):
        document = root / relative_path
        normalized = document.read_text(encoding="utf-8").replace("`", "")
        documented = dict(SDK_DOCUMENT_PATTERN.findall(normalized))
        for key in sorted(required_keys):
            actual_value = assignments[key]
            if documented.get(key) != actual_value:
                failures.append(
                    f"stale SDK fact in {relative_path}: expected {key}={actual_value}"
                )
    return failures


def check_registry_facts(root: Path) -> list[str]:
    failures: list[str] = []
    for relative_path in REGISTRY_FACT_DOCUMENTS:
        content = (root / relative_path).read_text(encoding="utf-8")
        for registry_path in REGISTRY_PATHS:
            if registry_path not in content:
                failures.append(
                    f"stale Registry facts in {relative_path}: missing {registry_path}"
                )
    return failures


def check_changelog(root: Path) -> list[str]:
    changelog = (root / "CHANGELOG.md").read_text(encoding="utf-8")
    headings = re.findall(r"^## \[([^]]+)]", changelog, re.MULTILINE)
    failures: list[str] = []
    if not headings or headings[0] != "Unreleased":
        failures.append("CHANGELOG.md must keep Unreleased as the first version section")

    preface = changelog.split("## [", maxsplit=1)[0]
    for stale_path in ("server/CHANGELOG.md", "server/ops"):
        if stale_path in preface:
            failures.append(f"CHANGELOG.md preface references missing path: {stale_path}")
    return failures


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="backslashreplace")

    root = find_repo_root(Path(__file__).parent)
    failures = [
        *check_markdown_links(root),
        *check_inline_repository_paths(root),
        *check_help_catalog(root),
        *check_sdk_facts(root),
        *check_registry_facts(root),
        *check_changelog(root),
    ]

    if failures:
        print("FAILED: documentation checks did not pass.")
        for failure in failures:
            print(f"- {failure}")
        return 1

    document_count = len(CURRENT_FACT_DOCUMENTS)
    help_count = len(list((root / HELP_ASSET_DIRECTORY).glob("*.md")))
    english_help_count = len(
        list((root / HELP_ENGLISH_ASSET_DIRECTORY).glob("*.md"))
    )
    print(
        "OK: documentation checks passed "
        f"({document_count} current documents, {help_count} default help assets, "
        f"{english_help_count} English help assets)."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
