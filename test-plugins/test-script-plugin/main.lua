-- Test Script Tools Plugin for TinaIDE API v1.

local function selection_or_document()
    local selection = tina.editor.getSelection()
    if selection and selection.text and #selection.text > 0 then
        return selection.text, true
    end
    return tina.editor.getText(), false
end

function on_test_uppercase()
    local selection = tina.editor.getSelection()
    if selection and selection.text and #selection.text > 0 then
        tina.editor.replaceSelection(string.upper(selection.text))
        tina.ui.showMessage("Converted to uppercase")
    else
        tina.ui.showMessage("No text selected")
    end
end

function on_test_lowercase()
    local selection = tina.editor.getSelection()
    if selection and selection.text and #selection.text > 0 then
        tina.editor.replaceSelection(string.lower(selection.text))
        tina.ui.showMessage("Converted to lowercase")
    else
        tina.ui.showMessage("No text selected")
    end
end

function on_test_trim_lines()
    local text = tina.editor.getText()
    if text then
        local trimmed = text:gsub("[ \t]+\n", "\n"):gsub("[ \t]+$", "")
        tina.editor.setText(trimmed)
        tina.ui.showMessage("Trimmed trailing whitespace")
    end
end

function on_test_sort_lines()
    local text, has_selection = selection_or_document()
    if not text or #text == 0 then
        return
    end

    local lines = {}
    for line in text:gmatch("[^\n]+") do
        table.insert(lines, line)
    end
    table.sort(lines)
    local sorted = table.concat(lines, "\n")
    if has_selection then
        tina.editor.replaceSelection(sorted)
    else
        tina.editor.setText(sorted)
    end
    tina.ui.showMessage("Lines sorted")
end

function on_test_duplicate_line()
    local cursor = tina.editor.getCursorPosition()
    local text = tina.editor.getText()
    if not cursor or not text then
        return
    end

    local lines = {}
    for line in (text .. "\n"):gmatch("(.-)\n") do
        table.insert(lines, line)
    end
    local line_index = (cursor.line or 0) + 1
    local current_line = lines[line_index]
    if current_line then
        table.insert(lines, line_index + 1, current_line)
        tina.editor.setText(table.concat(lines, "\n"))
        tina.ui.showMessage("Line duplicated")
    end
end

function on_test_count_words()
    local text = selection_or_document()
    if not text then
        return
    end

    local word_count = 0
    for _ in text:gmatch("%S+") do
        word_count = word_count + 1
    end
    local line_count = 1
    for _ in text:gmatch("\n") do
        line_count = line_count + 1
    end
    tina.ui.showMessage(string.format(
        "Words: %d | Characters: %d | Lines: %d",
        word_count,
        #text,
        line_count
    ))
end

function on_editor_saved(data)
    tina.log.info("File saved: " .. (data.filePath or data.fileName or "unknown"))
end

function on_editor_opened(data)
    tina.log.info("File opened: " .. (data.filePath or data.fileName or "unknown"))
end

local function register_command(command_id, callback_name, title)
    local ok, err = tina.commands.register(command_id, callback_name, title)
    if not ok then
        tina.log.warn("Failed to register " .. command_id .. ": " .. tostring(err))
    end
end

register_command("test.uppercase", "on_test_uppercase", "Convert to Uppercase")
register_command("test.lowercase", "on_test_lowercase", "Convert to Lowercase")
register_command("test.trimLines", "on_test_trim_lines", "Trim Trailing Whitespace")
register_command("test.sortLines", "on_test_sort_lines", "Sort Lines")
register_command("test.duplicateLine", "on_test_duplicate_line", "Duplicate Current Line")
register_command("test.countWords", "on_test_count_words", "Count Words")

tina.events.on("editor.saved", "on_editor_saved")
tina.events.on("editor.opened", "on_editor_opened")
tina.log.info("Test Script Tools plugin activated")
