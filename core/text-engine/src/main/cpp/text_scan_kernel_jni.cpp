#include <jni.h>

#include <algorithm>
#include <cwctype>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace {

enum class SignatureHelpContextKind {
    kCallParen,
    kControlParen,
    kOtherParen,
    kTrailingLambda,
    kOtherBrace,
};

enum class SignatureHelpParenKind {
    kCall,
    kControl,
    kOther,
};

struct SignatureHelpDelimiter {
    SignatureHelpContextKind kind;
};

struct SignatureHelpScanToken {
    enum class Kind {
        kIdentifier,
        kSymbol,
        kParenClose,
    };

    static SignatureHelpScanToken Identifier(std::u16string text) {
        SignatureHelpScanToken token;
        token.kind = Kind::kIdentifier;
        token.text = std::move(text);
        return token;
    }

    static SignatureHelpScanToken Symbol(char16_t value) {
        SignatureHelpScanToken token;
        token.kind = Kind::kSymbol;
        token.symbol = value;
        return token;
    }

    static SignatureHelpScanToken ParenClose(SignatureHelpParenKind paren_kind) {
        SignatureHelpScanToken token;
        token.kind = Kind::kParenClose;
        token.paren_kind = paren_kind;
        return token;
    }

    Kind kind = Kind::kSymbol;
    std::u16string text;
    char16_t symbol = 0;
    SignatureHelpParenKind paren_kind = SignatureHelpParenKind::kOther;
};

std::u16string JStringToUtf16(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const jsize length = env->GetStringLength(value);
    const jchar* chars = env->GetStringChars(value, nullptr);
    std::u16string text(reinterpret_cast<const char16_t*>(chars), static_cast<size_t>(length));
    env->ReleaseStringChars(value, chars);
    return text;
}

jintArray ToJIntArray(JNIEnv* env, const std::vector<jint>& values) {
    jintArray result = env->NewIntArray(static_cast<jsize>(values.size()));
    if (result == nullptr || values.empty()) {
        return result;
    }
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

bool IsAsciiAlphaNumeric(char16_t ch) {
    return
        (ch >= u'0' && ch <= u'9') ||
        (ch >= u'a' && ch <= u'z') ||
        (ch >= u'A' && ch <= u'Z');
}

bool IsHighSurrogate(char16_t ch) {
    return ch >= 0xD800 && ch <= 0xDBFF;
}

bool IsLowSurrogate(char16_t ch) {
    return ch >= 0xDC00 && ch <= 0xDFFF;
}

bool IsIdentifierChar(char16_t ch) {
    if (ch == u'_' || IsAsciiAlphaNumeric(ch)) {
        return true;
    }
    if (ch < 0x80) {
        return false;
    }
    return std::iswalnum(static_cast<wint_t>(ch)) != 0;
}

bool IsRenderableWhitespace(char16_t ch) {
    return ch == u' ' || ch == u'\t';
}

bool IsAnyWhitespace(char16_t ch) {
    return std::iswspace(static_cast<wint_t>(ch)) != 0;
}

int EncodeWhitespaceMarker(int column, bool is_tab) {
    return (column << 1) | (is_tab ? 1 : 0);
}

bool IsAnyOf(
    const std::u16string& value,
    std::initializer_list<std::u16string_view> candidates
) {
    for (const auto candidate : candidates) {
        if (value == candidate) {
            return true;
        }
    }
    return false;
}

bool IsControlKeyword(const std::u16string& value) {
    return IsAnyOf(value, {u"if", u"for", u"while", u"when", u"catch"});
}

bool IsDeclarationKeyword(const std::u16string& value) {
    return IsAnyOf(
        value,
        {
            u"fun",
            u"class",
            u"interface",
            u"object",
            u"typealias",
            u"val",
            u"var",
            u"constructor",
            u"init",
            u"get",
            u"set",
        }
    );
}

bool IsNonCallTerminal(const std::u16string& value) {
    return IsControlKeyword(value) ||
        IsDeclarationKeyword(value) ||
        IsAnyOf(value, {u"else", u"do", u"try"});
}

bool EndsWithSignatureHelpControlKeyword(const std::vector<SignatureHelpScanToken>& tokens) {
    if (tokens.empty()) {
        return false;
    }
    const auto& token = tokens.back();
    return token.kind == SignatureHelpScanToken::Kind::kIdentifier &&
        IsControlKeyword(token.text);
}

int SkipTrailingSignatureHelpTypeArguments(
    const std::vector<SignatureHelpScanToken>& tokens,
    int start_index
) {
    int index = start_index;
    if (index < 0 || index >= static_cast<int>(tokens.size())) {
        return index;
    }
    const auto& closing_token = tokens[index];
    if (closing_token.kind != SignatureHelpScanToken::Kind::kSymbol ||
        closing_token.symbol != u'>') {
        return index;
    }

    int depth = 0;
    while (index >= 0) {
        const auto& token = tokens[index];
        if (token.kind == SignatureHelpScanToken::Kind::kSymbol) {
            if (token.symbol == u'>') {
                ++depth;
            } else if (token.symbol == u'<') {
                --depth;
                if (depth == 0) {
                    return index - 1;
                }
            }
        }
        --index;
    }
    return start_index;
}

int FindSignatureHelpCallChainStart(
    const std::vector<SignatureHelpScanToken>& tokens,
    int identifier_index
) {
    int chain_start = identifier_index;
    int cursor = identifier_index - 1;

    while (cursor >= 0) {
        const auto& dot_token = tokens[cursor];
        if (dot_token.kind != SignatureHelpScanToken::Kind::kSymbol ||
            dot_token.symbol != u'.') {
            break;
        }
        --cursor;
        if (cursor >= 0) {
            const auto& safe_call_token = tokens[cursor];
            if (safe_call_token.kind == SignatureHelpScanToken::Kind::kSymbol &&
                safe_call_token.symbol == u'?') {
                --cursor;
            }
        }
        cursor = SkipTrailingSignatureHelpTypeArguments(tokens, cursor);
        if (cursor < 0 ||
            tokens[cursor].kind != SignatureHelpScanToken::Kind::kIdentifier) {
            break;
        }
        chain_start = cursor;
        --cursor;
    }

    return chain_start;
}

bool IsSignatureHelpDeclarationContext(
    const std::vector<SignatureHelpScanToken>& tokens,
    int chain_start
) {
    const int prefix_index = chain_start - 1;
    if (prefix_index >= 0) {
        const auto& prefix = tokens[prefix_index];
        if (prefix.kind == SignatureHelpScanToken::Kind::kIdentifier &&
            IsDeclarationKeyword(prefix.text)) {
            return true;
        }
        if (prefix.kind == SignatureHelpScanToken::Kind::kSymbol &&
            prefix.symbol == u':' &&
            chain_start - 2 >= 0) {
            const auto& owner = tokens[chain_start - 2];
            if (owner.kind == SignatureHelpScanToken::Kind::kIdentifier &&
                IsAnyOf(owner.text, {u"class", u"interface", u"object"})) {
                return true;
            }
        }
    }

    return false;
}

bool EndsWithSignatureHelpCallableExpression(
    const std::vector<SignatureHelpScanToken>& tokens
) {
    if (tokens.empty()) {
        return false;
    }
    const int index = SkipTrailingSignatureHelpTypeArguments(
        tokens,
        static_cast<int>(tokens.size()) - 1
    );
    if (index < 0 || index >= static_cast<int>(tokens.size())) {
        return false;
    }
    const auto& token = tokens[index];

    switch (token.kind) {
        case SignatureHelpScanToken::Kind::kParenClose:
            return token.paren_kind == SignatureHelpParenKind::kCall;
        case SignatureHelpScanToken::Kind::kIdentifier: {
            if (IsNonCallTerminal(token.text)) {
                return false;
            }
            const int chain_start = FindSignatureHelpCallChainStart(tokens, index);
            return !IsSignatureHelpDeclarationContext(tokens, chain_start);
        }
        case SignatureHelpScanToken::Kind::kSymbol:
            return false;
    }
    return false;
}

SignatureHelpContextKind ResolveSignatureHelpParenKind(
    const std::vector<SignatureHelpScanToken>& tokens
) {
    if (EndsWithSignatureHelpCallableExpression(tokens)) {
        return SignatureHelpContextKind::kCallParen;
    }
    if (EndsWithSignatureHelpControlKeyword(tokens)) {
        return SignatureHelpContextKind::kControlParen;
    }
    return SignatureHelpContextKind::kOtherParen;
}

bool StartsSignatureHelpTrailingLambda(const std::vector<SignatureHelpScanToken>& tokens) {
    if (tokens.empty()) {
        return false;
    }
    const auto& token = tokens.back();
    switch (token.kind) {
        case SignatureHelpScanToken::Kind::kParenClose:
            return token.paren_kind == SignatureHelpParenKind::kCall;
        case SignatureHelpScanToken::Kind::kIdentifier:
        case SignatureHelpScanToken::Kind::kSymbol:
            return EndsWithSignatureHelpCallableExpression(tokens);
    }
    return false;
}

std::optional<SignatureHelpParenKind> PopLastSignatureHelpParen(
    std::vector<SignatureHelpDelimiter>* stack
) {
    for (int index = static_cast<int>(stack->size()) - 1; index >= 0; --index) {
        switch ((*stack)[index].kind) {
            case SignatureHelpContextKind::kCallParen:
                stack->erase(stack->begin() + index);
                return SignatureHelpParenKind::kCall;
            case SignatureHelpContextKind::kControlParen:
                stack->erase(stack->begin() + index);
                return SignatureHelpParenKind::kControl;
            case SignatureHelpContextKind::kOtherParen:
                stack->erase(stack->begin() + index);
                return SignatureHelpParenKind::kOther;
            case SignatureHelpContextKind::kTrailingLambda:
            case SignatureHelpContextKind::kOtherBrace:
                break;
        }
    }
    return std::nullopt;
}

void PopLastSignatureHelpBrace(std::vector<SignatureHelpDelimiter>* stack) {
    for (int index = static_cast<int>(stack->size()) - 1; index >= 0; --index) {
        switch ((*stack)[index].kind) {
            case SignatureHelpContextKind::kTrailingLambda:
            case SignatureHelpContextKind::kOtherBrace:
                stack->erase(stack->begin() + index);
                return;
            case SignatureHelpContextKind::kCallParen:
            case SignatureHelpContextKind::kControlParen:
            case SignatureHelpContextKind::kOtherParen:
                break;
        }
    }
}

bool HasActiveSignatureHelpContext(const std::u16string& text_before_cursor) {
    if (text_before_cursor.empty()) {
        return false;
    }

    std::vector<SignatureHelpDelimiter> delimiter_stack;
    std::vector<SignatureHelpScanToken> significant_tokens;
    delimiter_stack.reserve(8);
    significant_tokens.reserve(32);

    bool in_single_quote = false;
    bool in_double_quote = false;
    bool in_raw_string = false;
    bool in_line_comment = false;
    int block_comment_depth = 0;
    bool escaped = false;

    size_t index = 0;
    while (index < text_before_cursor.size()) {
        const char16_t current = text_before_cursor[index];
        const char16_t next =
            index + 1 < text_before_cursor.size() ? text_before_cursor[index + 1] : 0;
        const char16_t third =
            index + 2 < text_before_cursor.size() ? text_before_cursor[index + 2] : 0;

        if (in_line_comment) {
            if (current == u'\n' || current == u'\r') {
                in_line_comment = false;
            }
            ++index;
            continue;
        }
        if (block_comment_depth > 0) {
            if (current == u'/' && next == u'*') {
                ++block_comment_depth;
                index += 2;
                continue;
            }
            if (current == u'*' && next == u'/') {
                --block_comment_depth;
                index += 2;
                continue;
            }
            ++index;
            continue;
        }
        if (in_raw_string) {
            if (current == u'"' && next == u'"' && third == u'"') {
                in_raw_string = false;
                index += 3;
                continue;
            }
            ++index;
            continue;
        }
        if (escaped) {
            escaped = false;
            ++index;
            continue;
        }
        if (in_single_quote) {
            if (current == u'\\') {
                escaped = true;
                ++index;
                continue;
            }
            if (current == u'\'') {
                in_single_quote = false;
            }
            ++index;
            continue;
        }
        if (in_double_quote) {
            if (current == u'\\') {
                escaped = true;
                ++index;
                continue;
            }
            if (current == u'"') {
                in_double_quote = false;
            }
            ++index;
            continue;
        }

        if (current == u'/' && next == u'/') {
            in_line_comment = true;
            index += 2;
            continue;
        }
        if (current == u'/' && next == u'*') {
            block_comment_depth = 1;
            index += 2;
            continue;
        }
        if (current == u'"' && next == u'"' && third == u'"') {
            in_raw_string = true;
            index += 3;
            continue;
        }
        if (IsIdentifierChar(current)) {
            const size_t start = index;
            ++index;
            while (index < text_before_cursor.size() &&
                IsIdentifierChar(text_before_cursor[index])) {
                ++index;
            }
            significant_tokens.push_back(
                SignatureHelpScanToken::Identifier(text_before_cursor.substr(start, index - start))
            );
            continue;
        }

        switch (current) {
            case u'\'':
                in_single_quote = true;
                break;
            case u'"':
                in_double_quote = true;
                break;
            case u'(':
                delimiter_stack.push_back(SignatureHelpDelimiter{
                    ResolveSignatureHelpParenKind(significant_tokens)
                });
                break;
            case u')': {
                const auto kind = PopLastSignatureHelpParen(&delimiter_stack);
                significant_tokens.push_back(
                    SignatureHelpScanToken::ParenClose(
                        kind.value_or(SignatureHelpParenKind::kOther)
                    )
                );
                break;
            }
            case u'{':
                delimiter_stack.push_back(SignatureHelpDelimiter{
                    StartsSignatureHelpTrailingLambda(significant_tokens)
                        ? SignatureHelpContextKind::kTrailingLambda
                        : SignatureHelpContextKind::kOtherBrace
                });
                break;
            case u'}':
                PopLastSignatureHelpBrace(&delimiter_stack);
                break;
            default:
                if (!std::iswspace(static_cast<wint_t>(current))) {
                    significant_tokens.push_back(SignatureHelpScanToken::Symbol(current));
                }
                break;
        }
        ++index;
    }

    return std::any_of(
        delimiter_stack.begin(),
        delimiter_stack.end(),
        [](const SignatureHelpDelimiter& delimiter) {
            return delimiter.kind == SignatureHelpContextKind::kCallParen ||
                delimiter.kind == SignatureHelpContextKind::kTrailingLambda;
        }
    );
}

std::vector<jint> ComputeBracketInfo(int start_depth, const std::u16string& line_text) {
    std::vector<jint> result;
    result.reserve(12);

    int depth = std::max(start_depth, 0);
    for (size_t column = 0; column < line_text.size(); ++column) {
        switch (line_text[column]) {
            case u'(':
            case u'[':
            case u'{':
                result.push_back(static_cast<jint>(column));
                result.push_back(static_cast<jint>(depth));
                result.push_back(1);
                ++depth;
                break;
            case u')':
            case u']':
            case u'}':
                --depth;
                result.push_back(static_cast<jint>(column));
                result.push_back(static_cast<jint>(std::max(depth, 0)));
                result.push_back(0);
                break;
            default:
                break;
        }
    }

    return result;
}

int AdvanceBracketDepthRange(int start_depth, std::u16string_view text) {
    int depth = std::max(start_depth, 0);
    for (const char16_t ch : text) {
        switch (ch) {
            case u'(':
            case u'[':
            case u'{':
                ++depth;
                break;
            case u')':
            case u']':
            case u'}':
                depth = std::max(depth - 1, 0);
                break;
            default:
                break;
        }
    }
    return depth;
}

int AdvanceBracketDepth(int start_depth, const std::u16string& line_text) {
    return AdvanceBracketDepthRange(
        start_depth,
        std::u16string_view(line_text.data(), line_text.size())
    );
}

int AdvanceBracketDepthPrefix(
    int start_depth,
    const std::u16string& line_text,
    int end_column
) {
    const auto safe_end = static_cast<size_t>(
        std::clamp(end_column, 0, static_cast<int>(line_text.size()))
    );
    return AdvanceBracketDepthRange(
        start_depth,
        std::u16string_view(line_text.data(), safe_end)
    );
}

std::vector<jint> ComputeLineBoundaryBracketDepths(
    int start_depth,
    const std::u16string& text
) {
    std::vector<jint> result;
    result.reserve(8);

    int depth = std::max(start_depth, 0);
    result.push_back(static_cast<jint>(depth));
    for (const char16_t ch : text) {
        switch (ch) {
            case u'(':
            case u'[':
            case u'{':
                ++depth;
                break;
            case u')':
            case u']':
            case u'}':
                depth = std::max(depth - 1, 0);
                break;
            default:
                break;
        }
        if (ch == u'\n') {
            result.push_back(static_cast<jint>(depth));
        }
    }
    result.push_back(static_cast<jint>(depth));
    return result;
}

bool IsOpenBracket(char16_t ch) {
    return ch == u'(' || ch == u'[' || ch == u'{';
}

bool IsCloseBracket(char16_t ch) {
    return ch == u')' || ch == u']' || ch == u'}';
}

char16_t MatchingCloseBracket(char16_t ch) {
    switch (ch) {
        case u'(':
            return u')';
        case u'[':
            return u']';
        case u'{':
            return u'}';
        default:
            return 0;
    }
}

char16_t MatchingOpenBracket(char16_t ch) {
    switch (ch) {
        case u')':
            return u'(';
        case u']':
            return u'[';
        case u'}':
            return u'{';
        default:
            return 0;
    }
}

std::vector<jint> FindForwardMatchingBracket(
    const std::u16string& text,
    int open_offset,
    char16_t open_char
) {
    const char16_t close_char = MatchingCloseBracket(open_char);
    if (close_char == 0) {
        return {};
    }

    int depth = 0;
    for (int offset = open_offset; offset < static_cast<int>(text.size()); ++offset) {
        const char16_t current = text[static_cast<size_t>(offset)];
        if (current == open_char) {
            ++depth;
        } else if (current == close_char) {
            --depth;
            if (depth == 0) {
                return {
                    static_cast<jint>(open_offset),
                    static_cast<jint>(offset),
                };
            }
        }
    }
    return {};
}

std::vector<jint> FindBackwardMatchingBracket(
    const std::u16string& text,
    int close_offset,
    char16_t close_char
) {
    const char16_t open_char = MatchingOpenBracket(close_char);
    if (open_char == 0) {
        return {};
    }

    int depth = 0;
    for (int offset = close_offset; offset >= 0; --offset) {
        const char16_t current = text[static_cast<size_t>(offset)];
        if (current == close_char) {
            ++depth;
        } else if (current == open_char) {
            --depth;
            if (depth == 0) {
                return {
                    static_cast<jint>(offset),
                    static_cast<jint>(close_offset),
                };
            }
        }
    }
    return {};
}

std::vector<jint> FindMatchingBracket(const std::u16string& text, int cursor_offset) {
    if (text.empty()) {
        return {};
    }

    const int safe_cursor = std::clamp(cursor_offset, 0, static_cast<int>(text.size()));
    const char16_t at_cursor =
        safe_cursor < static_cast<int>(text.size()) ? text[static_cast<size_t>(safe_cursor)] : 0;
    const char16_t before_cursor =
        safe_cursor > 0 ? text[static_cast<size_t>(safe_cursor - 1)] : 0;

    if (at_cursor != 0 && IsOpenBracket(at_cursor)) {
        return FindForwardMatchingBracket(text, safe_cursor, at_cursor);
    }
    if (before_cursor != 0 && IsCloseBracket(before_cursor)) {
        return FindBackwardMatchingBracket(text, safe_cursor - 1, before_cursor);
    }
    if (at_cursor != 0 && IsCloseBracket(at_cursor)) {
        return FindBackwardMatchingBracket(text, safe_cursor, at_cursor);
    }
    if (before_cursor != 0 && IsOpenBracket(before_cursor)) {
        return FindForwardMatchingBracket(text, safe_cursor - 1, before_cursor);
    }
    return {};
}

struct OpenBracketRecord {
    char16_t bracket;
    int offset;
    int depth;
};

struct OpenBracketGuideRecord {
    char16_t bracket;
    int line;
    int column;
    int depth;
};

struct SnapshotOpenBracketRecord {
    char16_t bracket;
    int offset;
    int line;
    int column;
    int depth;
};

std::vector<jint> FindBracketPairs(const std::u16string& text) {
    if (text.empty()) {
        return {};
    }

    std::vector<jint> result;
    std::vector<OpenBracketRecord> stack;
    stack.reserve(32);
    result.reserve(24);

    for (size_t offset = 0; offset < text.size(); ++offset) {
        const char16_t ch = text[offset];
        if (IsOpenBracket(ch)) {
            stack.push_back(
                OpenBracketRecord{ch, static_cast<int>(offset), static_cast<int>(stack.size())}
            );
            continue;
        }
        if (!IsCloseBracket(ch) || stack.empty()) {
            continue;
        }

        const char16_t expected_open = MatchingOpenBracket(ch);
        const OpenBracketRecord& open = stack.back();
        if (open.bracket != expected_open) {
            continue;
        }

        result.push_back(static_cast<jint>(open.offset));
        result.push_back(static_cast<jint>(offset));
        result.push_back(static_cast<jint>(open.depth));
        stack.pop_back();
    }

    return result;
}

std::vector<jint> FindBracketGuideSpans(
    const std::u16string& text,
    int visible_start_line,
    int visible_end_line,
    bool include_open_spans_at_end
) {
    if (text.empty()) {
        return {};
    }

    const int safe_visible_start = std::max(visible_start_line, 0);
    const int safe_visible_end = std::max(visible_end_line, safe_visible_start);

    std::vector<jint> result;
    std::vector<OpenBracketGuideRecord> stack;
    stack.reserve(32);
    result.reserve(32);

    int line = 0;
    int column = 0;
    for (const char16_t ch : text) {
        if (IsOpenBracket(ch)) {
            stack.push_back(
                OpenBracketGuideRecord{
                    ch,
                    line,
                    column,
                    static_cast<int>(stack.size())
                }
            );
        } else if (IsCloseBracket(ch) && !stack.empty()) {
            const char16_t expected_open = MatchingOpenBracket(ch);
            const OpenBracketGuideRecord open = stack.back();
            if (open.bracket == expected_open) {
                stack.pop_back();
                if (line > open.line &&
                    line >= safe_visible_start &&
                    open.line <= safe_visible_end) {
                    result.push_back(static_cast<jint>(open.line));
                    result.push_back(static_cast<jint>(open.column));
                    result.push_back(static_cast<jint>(line));
                    result.push_back(static_cast<jint>(column));
                    result.push_back(static_cast<jint>(open.depth));
                    result.push_back(0);
                }
            }
        }

        if (ch == u'\n') {
            ++line;
            column = 0;
        } else {
            ++column;
        }
    }

    if (include_open_spans_at_end) {
        for (const auto& open : stack) {
            if (safe_visible_end > open.line && open.line <= safe_visible_end) {
                result.push_back(static_cast<jint>(open.line));
                result.push_back(static_cast<jint>(open.column));
                result.push_back(static_cast<jint>(safe_visible_end));
                result.push_back(-1);
                result.push_back(static_cast<jint>(open.depth));
                result.push_back(1);
            }
        }
    }

    return result;
}

std::vector<jint> ScanBracketSnapshot(
    int start_depth,
    const std::u16string& text,
    int visible_start_line,
    int visible_end_line,
    bool include_open_spans_at_end
) {
    const int safe_start_depth = std::max(start_depth, 0);
    if (text.empty()) {
        return {0, 0, 0, 0};
    }

    const int safe_visible_start = std::max(visible_start_line, 0);
    const int safe_visible_end = std::max(visible_end_line, safe_visible_start);

    std::vector<jint> pairs;
    std::vector<jint> guides;
    std::vector<jint> line_brackets;
    std::vector<jint> line_boundary_depths;
    std::vector<SnapshotOpenBracketRecord> stack;
    pairs.reserve(24);
    guides.reserve(32);
    line_brackets.reserve(24);
    line_boundary_depths.reserve(16);
    stack.reserve(32);

    int depth = safe_start_depth;
    int line = 0;
    int column = 0;
    line_boundary_depths.push_back(static_cast<jint>(depth));
    for (size_t offset = 0; offset < text.size(); ++offset) {
        const char16_t ch = text[offset];
        const bool is_visible_line = line >= safe_visible_start && line <= safe_visible_end;

        if (IsOpenBracket(ch)) {
            if (is_visible_line) {
                line_brackets.push_back(static_cast<jint>(line));
                line_brackets.push_back(static_cast<jint>(column));
                line_brackets.push_back(static_cast<jint>(depth));
                line_brackets.push_back(1);
            }
            stack.push_back(
                SnapshotOpenBracketRecord{
                    ch,
                    static_cast<int>(offset),
                    line,
                    column,
                    safe_start_depth + static_cast<int>(stack.size())
                }
            );
            ++depth;
        } else if (IsCloseBracket(ch)) {
            depth = std::max(depth - 1, 0);
            if (is_visible_line) {
                line_brackets.push_back(static_cast<jint>(line));
                line_brackets.push_back(static_cast<jint>(column));
                line_brackets.push_back(static_cast<jint>(depth));
                line_brackets.push_back(0);
            }
            if (!stack.empty()) {
                const char16_t expected_open = MatchingOpenBracket(ch);
                const SnapshotOpenBracketRecord open = stack.back();
                if (open.bracket == expected_open) {
                    stack.pop_back();
                    pairs.push_back(static_cast<jint>(open.offset));
                    pairs.push_back(static_cast<jint>(offset));
                    pairs.push_back(static_cast<jint>(open.depth));
                    if (line > open.line &&
                        line >= safe_visible_start &&
                        open.line <= safe_visible_end) {
                        guides.push_back(static_cast<jint>(open.line));
                        guides.push_back(static_cast<jint>(open.column));
                        guides.push_back(static_cast<jint>(line));
                        guides.push_back(static_cast<jint>(column));
                        guides.push_back(static_cast<jint>(open.depth));
                        guides.push_back(0);
                    }
                }
            }
        }

        if (ch == u'\n') {
            ++line;
            column = 0;
            line_boundary_depths.push_back(static_cast<jint>(depth));
        } else {
            ++column;
        }
    }

    line_boundary_depths.push_back(static_cast<jint>(depth));

    if (include_open_spans_at_end) {
        for (const auto& open : stack) {
            if (safe_visible_end > open.line && open.line <= safe_visible_end) {
                guides.push_back(static_cast<jint>(open.line));
                guides.push_back(static_cast<jint>(open.column));
                guides.push_back(static_cast<jint>(safe_visible_end));
                guides.push_back(-1);
                guides.push_back(static_cast<jint>(open.depth));
                guides.push_back(1);
            }
        }
    }

    std::vector<jint> result;
    result.reserve(4 + pairs.size() + guides.size() + line_brackets.size() + line_boundary_depths.size());
    result.push_back(static_cast<jint>(pairs.size() / 3));
    result.push_back(static_cast<jint>(guides.size() / 6));
    result.push_back(static_cast<jint>(line_brackets.size() / 4));
    result.push_back(static_cast<jint>(line_boundary_depths.size()));
    result.insert(result.end(), pairs.begin(), pairs.end());
    result.insert(result.end(), guides.begin(), guides.end());
    result.insert(result.end(), line_brackets.begin(), line_brackets.end());
    result.insert(result.end(), line_boundary_depths.begin(), line_boundary_depths.end());
    return result;
}

std::vector<jint> FindWordBounds(const std::u16string& line_text, int column) {
    if (line_text.empty()) {
        return {};
    }

    const int safe_column = std::clamp(column, 0, static_cast<int>(line_text.size()));
    int pivot_index = -1;
    if (safe_column < static_cast<int>(line_text.size()) &&
        IsIdentifierChar(line_text[static_cast<size_t>(safe_column)])) {
        pivot_index = safe_column;
    } else if (safe_column > 0 &&
        IsIdentifierChar(line_text[static_cast<size_t>(safe_column - 1)])) {
        pivot_index = safe_column - 1;
    } else {
        return {};
    }

    int start = pivot_index;
    int end = pivot_index + 1;
    while (start > 0 &&
        IsIdentifierChar(line_text[static_cast<size_t>(start - 1)])) {
        --start;
    }
    while (end < static_cast<int>(line_text.size()) &&
        IsIdentifierChar(line_text[static_cast<size_t>(end)])) {
        ++end;
    }

    return {
        static_cast<jint>(start),
        static_cast<jint>(end),
    };
}

std::vector<jint> FindWholeWordMatches(
    const std::u16string& line_text,
    const std::u16string& word
) {
    if (line_text.empty() || word.empty() || word.size() > line_text.size()) {
        return {};
    }

    std::vector<jint> result;
    size_t search_from = 0;
    while (search_from + word.size() <= line_text.size()) {
        const size_t found = line_text.find(word, search_from);
        if (found == std::u16string::npos) {
            break;
        }

        const bool is_word_boundary_start =
            found == 0 || !IsIdentifierChar(line_text[found - 1]);
        const size_t end = found + word.size();
        const bool is_word_boundary_end =
            end >= line_text.size() || !IsIdentifierChar(line_text[end]);

        if (is_word_boundary_start && is_word_boundary_end) {
            result.push_back(static_cast<jint>(found));
        }
        search_from = found + 1;
    }

    return result;
}

std::vector<jint> FindWhitespaceMarkers(
    const std::u16string& line_text,
    bool boundary_only
) {
    if (line_text.empty()) {
        return {};
    }

    if (!boundary_only) {
        std::vector<jint> result;
        result.reserve(line_text.size() / 4);
        for (size_t column = 0; column < line_text.size(); ++column) {
            const char16_t ch = line_text[column];
            if (!IsRenderableWhitespace(ch)) {
                continue;
            }
            result.push_back(static_cast<jint>(
                EncodeWhitespaceMarker(static_cast<int>(column), ch == u'\t')
            ));
        }
        return result;
    }

    size_t leading_end = 0;
    while (leading_end < line_text.size() &&
        IsRenderableWhitespace(line_text[leading_end])) {
        ++leading_end;
    }
    if (leading_end == 0) {
        return {};
    }
    if (leading_end == line_text.size()) {
        std::vector<jint> result;
        result.reserve(line_text.size());
        for (size_t column = 0; column < line_text.size(); ++column) {
            result.push_back(static_cast<jint>(
                EncodeWhitespaceMarker(static_cast<int>(column), line_text[column] == u'\t')
            ));
        }
        return result;
    }

    size_t trailing_start = line_text.size();
    while (trailing_start > leading_end &&
        IsRenderableWhitespace(line_text[trailing_start - 1])) {
        --trailing_start;
    }

    std::vector<jint> result;
    result.reserve(leading_end + (line_text.size() - trailing_start));
    for (size_t column = 0; column < leading_end; ++column) {
        result.push_back(static_cast<jint>(
            EncodeWhitespaceMarker(static_cast<int>(column), line_text[column] == u'\t')
        ));
    }
    for (size_t column = trailing_start; column < line_text.size(); ++column) {
        result.push_back(static_cast<jint>(
            EncodeWhitespaceMarker(static_cast<int>(column), line_text[column] == u'\t')
        ));
    }
    return result;
}

std::vector<jint> FindTabColumns(const std::u16string& line_text) {
    if (line_text.empty()) {
        return {};
    }

    std::vector<jint> result;
    result.reserve(line_text.size() / 8);
    for (size_t index = 0; index < line_text.size(); ++index) {
        if (line_text[index] == u'\t') {
            result.push_back(static_cast<jint>(index));
        }
    }
    return result;
}

int MeasureVisualColumns(const std::u16string& line_text, int tab_size) {
    if (line_text.empty()) {
        return 0;
    }
    if (line_text.find(u'\t') == std::u16string::npos) {
        return static_cast<int>(line_text.size());
    }

    const int safe_tab_size = std::max(tab_size, 1);
    int visual_columns = 0;
    for (const char16_t ch : line_text) {
        if (ch == u'\t') {
            visual_columns += safe_tab_size - (visual_columns % safe_tab_size);
        } else {
            ++visual_columns;
        }
    }
    return visual_columns;
}

int MeasureVisualColumnsPrefix(
    const std::u16string& line_text,
    int tab_size,
    int end_column
) {
    const int safe_end_column = std::clamp(end_column, 0, static_cast<int>(line_text.size()));
    if (safe_end_column <= 0) {
        return 0;
    }
    if (line_text.find(u'\t') == std::u16string::npos) {
        return safe_end_column;
    }

    const int safe_tab_size = std::max(tab_size, 1);
    int visual_columns = 0;
    for (int index = 0; index < safe_end_column; ++index) {
        if (line_text[static_cast<size_t>(index)] == u'\t') {
            visual_columns += safe_tab_size - (visual_columns % safe_tab_size);
        } else {
            ++visual_columns;
        }
    }
    return visual_columns;
}

std::vector<jint> FindWrapSegmentStarts(
    const std::u16string& line_text,
    int wrap_columns,
    int tab_size
) {
    const int length = static_cast<int>(line_text.size());
    if (length <= 0) {
        return {0};
    }

    const int safe_wrap_columns = std::max(wrap_columns, 1);
    const int safe_tab_size = std::max(tab_size, 1);
    if (line_text.find(u'\t') == std::u16string::npos && length <= safe_wrap_columns) {
        return {0};
    }

    std::vector<jint> starts;
    starts.reserve(8);
    starts.push_back(0);

    int segment_start = 0;
    int visual_column = 0;
    int index = 0;
    while (index < length) {
        const bool is_surrogate_pair =
            IsHighSurrogate(line_text[static_cast<size_t>(index)]) &&
            index + 1 < length &&
            IsLowSurrogate(line_text[static_cast<size_t>(index + 1)]);
        const int code_unit_length = is_surrogate_pair ? 2 : 1;
        const int step = line_text[static_cast<size_t>(index)] == u'\t'
            ? safe_tab_size - (visual_column % safe_tab_size)
            : code_unit_length;

        if (index > segment_start && visual_column + step > safe_wrap_columns) {
            starts.push_back(static_cast<jint>(index));
            segment_start = index;
            visual_column = 0;
            continue;
        }

        visual_column += step;
        index += code_unit_length;

        if (visual_column >= safe_wrap_columns && index < length) {
            starts.push_back(static_cast<jint>(index));
            segment_start = index;
            visual_column = 0;
        }
    }

    return starts;
}

std::vector<jint> BuildVisualColumnPrefix(const std::u16string& line_text, int tab_size) {
    std::vector<jint> prefix(line_text.size() + 1, 0);
    if (line_text.empty()) {
        return prefix;
    }

    const int safe_tab_size = std::max(tab_size, 1);
    int visual_columns = 0;
    for (size_t index = 0; index < line_text.size(); ++index) {
        if (line_text[index] == u'\t') {
            visual_columns += safe_tab_size - (visual_columns % safe_tab_size);
        } else {
            ++visual_columns;
        }
        prefix[index + 1] = static_cast<jint>(visual_columns);
    }
    return prefix;
}

std::vector<jint> ScanLineWhitespace(const std::u16string& line_text, int tab_size) {
    const int safe_tab_size = std::max(tab_size, 1);
    int leading_whitespace_end = 0;
    int leading_indent_columns = 0;
    bool counting_indent_columns = true;

    while (leading_whitespace_end < static_cast<int>(line_text.size())) {
        const char16_t ch = line_text[static_cast<size_t>(leading_whitespace_end)];
        if (!IsAnyWhitespace(ch)) {
            break;
        }

        if (ch == u' ') {
            if (counting_indent_columns) {
                ++leading_indent_columns;
            }
        } else if (ch == u'\t') {
            if (counting_indent_columns) {
                leading_indent_columns +=
                    safe_tab_size - (leading_indent_columns % safe_tab_size);
            }
        } else {
            counting_indent_columns = false;
        }

        ++leading_whitespace_end;
    }

    int trailing_whitespace_start = static_cast<int>(line_text.size());
    while (trailing_whitespace_start > 0 &&
        IsAnyWhitespace(line_text[static_cast<size_t>(trailing_whitespace_start - 1)])) {
        --trailing_whitespace_start;
    }

    int outdent_remove_count = 0;
    if (!line_text.empty()) {
        if (line_text.front() == u'\t') {
            outdent_remove_count = 1;
        } else if (line_text.front() == u' ') {
            const int max = std::min(safe_tab_size, static_cast<int>(line_text.size()));
            while (outdent_remove_count < max &&
                line_text[static_cast<size_t>(outdent_remove_count)] == u' ') {
                ++outdent_remove_count;
            }
        }
    }

    return {
        static_cast<jint>(leading_whitespace_end),
        static_cast<jint>(leading_indent_columns),
        static_cast<jint>(trailing_whitespace_start),
        static_cast<jint>(outdent_remove_count),
    };
}

int FindWordPrefixStart(const std::u16string& line_text, int column) {
    const int safe_column = std::clamp(column, 0, static_cast<int>(line_text.size()));
    int start = safe_column;
    while (start > 0 &&
        IsIdentifierChar(line_text[static_cast<size_t>(start - 1)])) {
        --start;
    }
    return start;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeTextScanKernel_nativeHasActiveSignatureHelpContext(
    JNIEnv* env,
    jobject,
    jstring textBeforeCursor
) {
    return HasActiveSignatureHelpContext(JStringToUtf16(env, textBeforeCursor));
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeTextScanKernel_nativeComputeBracketInfo(
    JNIEnv* env,
    jobject,
    jint startDepth,
    jstring lineText
) {
    return ToJIntArray(env, ComputeBracketInfo(startDepth, JStringToUtf16(env, lineText)));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeTextScanKernel_nativeAdvanceBracketDepth(
    JNIEnv* env,
    jobject,
    jint startDepth,
    jstring lineText
) {
    return AdvanceBracketDepth(startDepth, JStringToUtf16(env, lineText));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeTextScanKernel_nativeAdvanceBracketDepthPrefix(
    JNIEnv* env,
    jobject,
    jint startDepth,
    jstring lineText,
    jint endColumn
) {
    return AdvanceBracketDepthPrefix(startDepth, JStringToUtf16(env, lineText), endColumn);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeTextScanKernel_nativeComputeLineBoundaryBracketDepths(
    JNIEnv* env,
    jobject,
    jint startDepth,
    jstring text
) {
    return ToJIntArray(env, ComputeLineBoundaryBracketDepths(startDepth, JStringToUtf16(env, text)));
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeTextScanKernel_nativeFindMatchingBracket(
    JNIEnv* env,
    jobject,
    jstring text,
    jint cursorOffset
) {
    return ToJIntArray(env, FindMatchingBracket(JStringToUtf16(env, text), cursorOffset));
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeTextScanKernel_nativeFindBracketPairs(
    JNIEnv* env,
    jobject,
    jstring text
) {
    return ToJIntArray(env, FindBracketPairs(JStringToUtf16(env, text)));
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeTextScanKernel_nativeFindBracketGuideSpans(
    JNIEnv* env,
    jobject,
    jstring text,
    jint visibleStartLine,
    jint visibleEndLine,
    jboolean includeOpenSpansAtEnd
) {
    return ToJIntArray(
        env,
        FindBracketGuideSpans(
            JStringToUtf16(env, text),
            visibleStartLine,
            visibleEndLine,
            includeOpenSpansAtEnd == JNI_TRUE
        )
    );
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeTextScanKernel_nativeScanBracketSnapshot(
    JNIEnv* env,
    jobject,
    jint startDepth,
    jstring text,
    jint visibleStartLine,
    jint visibleEndLine,
    jboolean includeOpenSpansAtEnd
) {
    return ToJIntArray(
        env,
        ScanBracketSnapshot(
            startDepth,
            JStringToUtf16(env, text),
            visibleStartLine,
            visibleEndLine,
            includeOpenSpansAtEnd == JNI_TRUE
        )
    );
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeTextScanKernel_nativeFindWordBounds(
    JNIEnv* env,
    jobject,
    jstring lineText,
    jint column
) {
    return ToJIntArray(env, FindWordBounds(JStringToUtf16(env, lineText), column));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeTextScanKernel_nativeFindWordPrefixStart(
    JNIEnv* env,
    jobject,
    jstring lineText,
    jint column
) {
    return FindWordPrefixStart(JStringToUtf16(env, lineText), column);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeTextScanKernel_nativeFindWholeWordMatches(
    JNIEnv* env,
    jobject,
    jstring lineText,
    jstring word
) {
    return ToJIntArray(
        env,
        FindWholeWordMatches(JStringToUtf16(env, lineText), JStringToUtf16(env, word))
    );
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeTextScanKernel_nativeFindWhitespaceMarkers(
    JNIEnv* env,
    jobject,
    jstring lineText,
    jboolean boundaryOnly
) {
    return ToJIntArray(
        env,
        FindWhitespaceMarkers(JStringToUtf16(env, lineText), boundaryOnly == JNI_TRUE)
    );
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeTextScanKernel_nativeFindTabColumns(
    JNIEnv* env,
    jobject,
    jstring lineText
) {
    return ToJIntArray(env, FindTabColumns(JStringToUtf16(env, lineText)));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeTextScanKernel_nativeMeasureVisualColumns(
    JNIEnv* env,
    jobject,
    jstring lineText,
    jint tabSize
) {
    return MeasureVisualColumns(JStringToUtf16(env, lineText), tabSize);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeTextScanKernel_nativeMeasureVisualColumnsPrefix(
    JNIEnv* env,
    jobject,
    jstring lineText,
    jint tabSize,
    jint endColumn
) {
    return MeasureVisualColumnsPrefix(JStringToUtf16(env, lineText), tabSize, endColumn);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeTextScanKernel_nativeFindWrapSegmentStarts(
    JNIEnv* env,
    jobject,
    jstring lineText,
    jint wrapColumns,
    jint tabSize
) {
    return ToJIntArray(env, FindWrapSegmentStarts(JStringToUtf16(env, lineText), wrapColumns, tabSize));
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeTextScanKernel_nativeBuildVisualColumnPrefix(
    JNIEnv* env,
    jobject,
    jstring lineText,
    jint tabSize
) {
    return ToJIntArray(env, BuildVisualColumnPrefix(JStringToUtf16(env, lineText), tabSize));
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_wuxianggujun_tinaide_core_textengine_NativeTextScanKernel_nativeScanLineWhitespace(
    JNIEnv* env,
    jobject,
    jstring lineText,
    jint tabSize
) {
    return ToJIntArray(env, ScanLineWhitespace(JStringToUtf16(env, lineText), tabSize));
}
