# Create a Project

TinaIDE can create projects from built-in templates or import an existing directory or Git repository.

## Entry points

From the Projects page:

- tap the **+** button to create a project;
- choose **Import local directory**;
- choose **Import from Git**.

## New-project wizard

The wizard has two steps:

1. Select a template.
2. Enter the project configuration.

Common options include project name, C++ standard, and the Native API level for NDK templates.

## Templates

### C++ single file

Best for syntax practice, algorithms, and small console programs. It has the smallest structure and does not require CMake.

### CMake executable

Recommended for normal multi-file C/C++ projects. A successful first build normally produces compile_commands.json for clangd.

### CMake library

Useful for reusable modules and projects that separate public headers from implementations.

### Make executable

Use this when you already maintain a Makefile or need direct control over build rules.

### NDK shared library

Designed for Android native libraries and JNI/NDK experiments. The template normally creates both a shared library and a test executable. The default terminal run may execute the test target. Select the shared-library target or SDL mode when you need to validate the library itself.

## Import an existing project

Use local import for projects copied to the device or stored externally. Use Git import to clone a repository. Configure HTTPS credentials or SSH keys under **Settings → Git** before cloning a private repository.

## Recommended first actions

1. Open the main source file and confirm encoding and paths.
2. Run one build.
3. Check that compile_commands.json was generated or refreshed.
4. Review **Settings → Language Server**.
5. Open the terminal at the project root for custom commands.

## Next steps

- [Project settings](project-settings.md)
- [Editor basics](editor-basics.md)
- [Build a project](build-project.md)
- [Code completion](code-completion.md)
- [CMake guide](cmake-guide.md)
