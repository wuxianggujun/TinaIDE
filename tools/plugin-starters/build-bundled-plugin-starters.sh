#!/usr/bin/env sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPO_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
# Historical script name kept for now; starter packages are no longer written into APK assets.
OUTPUT_ROOT="$REPO_ROOT/tools/plugin-starters/dist/tinaide.plugin.starters/templates"
SHARED_ROOT="$SCRIPT_DIR/shared"
STAGING_ROOT="$SCRIPT_DIR/.bundle"

mkdir -p "$OUTPUT_ROOT"
rm -rf "$STAGING_ROOT"
mkdir -p "$STAGING_ROOT"

build_template() {
  template_name="$1"
  output_zip="$2"
  source_dir="$SCRIPT_DIR/$template_name"
  staging_dir="$STAGING_ROOT/$template_name"

  sh "$source_dir/validate.sh"

  rm -f "$output_zip"
  rm -rf "$staging_dir"
  mkdir -p "$staging_dir/.tina-starter"
  (
    cd "$source_dir"
    for entry in .* *; do
      [ "$entry" = "." ] && continue
      [ "$entry" = ".." ] && continue
      [ "$entry" = "dist" ] && continue
      [ "$entry" = ".pack" ] && continue
      [ "$entry" = ".bundle" ] && continue
      cp -R "$entry" "$staging_dir/"
    done
  )
  cp "$SHARED_ROOT/validate-core.ps1" "$staging_dir/.tina-starter/validate-core.ps1"
  cp "$SHARED_ROOT/validate_core.py" "$staging_dir/.tina-starter/validate_core.py"
  cp "$SHARED_ROOT/validation-rules.json" "$staging_dir/.tina-starter/validation-rules.json"
  python3 "$SHARED_ROOT/build_deterministic_zip.py" --source "$staging_dir" --output "$output_zip"
  echo "Built $output_zip"
}

build_template "config-basic" "$OUTPUT_ROOT/tina-config-plugin.zip"
build_template "script-command" "$OUTPUT_ROOT/tina-script-command-plugin.zip"
build_template "script-basic" "$OUTPUT_ROOT/tina-script-plugin.zip"
build_template "lsp-basic" "$OUTPUT_ROOT/tina-lsp-plugin.zip"

rm -rf "$STAGING_ROOT"
