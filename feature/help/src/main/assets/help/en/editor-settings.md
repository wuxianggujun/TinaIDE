# Editor Settings

The Editor settings page is organized into font, display, editing behavior, and performance sections.

## Font

- Font size ranges from 8sp to 48sp.
- You can restore the default font or import a custom font file.
- Imported fonts are copied and validated before the path is saved.

If a custom font is rejected, verify the font file first.

## Display

Available controls include:

- editor theme;
- line numbers and word wrap;
- minimap (a zoomed-out overview of the whole document on the right side; tap or drag to jump);
- Git change indicators;
- rainbow brackets and their maximum line count;
- code folding;
- whitespace display.

Built-in theme values include GRAY, DARK, LIGHT, and AUTO. Theme plugins may add more choices.

The rainbow-bracket line limit accepts values from 0 to 200000. A value of 0 means unlimited.

Whitespace display can be disabled, limited to boundaries, or enabled for all whitespace.

### Git change indicators

When enabled, colored stripes appear at the left edge of the line-number gutter marking lines added (green), modified (orange), and deleted (red, half height) against HEAD.

A few edge cases are worth knowing up front:

- The baseline is **the file content in the HEAD commit vs the current in-memory buffer**, not the file on disk. Staged, unstaged and unsaved changes are therefore covered in a single pass, and line numbers do not shift depending on whether the file has been saved.
- Because the baseline lives in memory, **saving is not a refresh trigger**. Changes are recomputed about 400 ms after an edit, and once more when you return to the editor from another screen (for example after committing or staging in the Git panel).
- It only applies to files inside a Git repository; projects outside a repository show no markers at all.
- The stripes are drawn inside the gutter, so **turning line numbers off hides the stripes as well**.

## Editing behavior

- Automatic indentation
- Insert spaces instead of a Tab character
- Tab width

Tab width cycles through 2, 4, and 8. It is not a free-form number field.

## Performance

The hardware-acceleration switch can help isolate device-specific rendering or scrolling issues. Change it separately from other settings so you can compare behavior.

## Related documentation

- [Settings overview](settings-overview.md)
- [Appearance settings](appearance-settings.md)
- [Editor basics](editor-basics.md)
- [Keyboard settings](keyboard-settings.md)
