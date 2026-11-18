package cn.edu.ustc.tokenworks

import java.util.Locale

internal enum class CodeTone { KEYWORD, STRING, COMMENT, NUMBER }
internal data class CodeSpan(val start: Int, val end: Int, val tone: CodeTone)

private val common = "as break case catch class const continue def do else enum except false finally for from fun function if import in interface let new null object package private public raise return static struct super switch this throw true try type val var void when while with yield".split(' ').toSet()
private val python = common + setOf("and", "assert", "async", "await", "del", "elif", "False", "global", "is", "lambda", "None", "nonlocal", "not", "or", "pass", "True")
private val shell = setOf("case", "do", "done", "elif", "else", "esac", "export", "fi", "for", "function", "if", "in", "local", "return", "then", "while")

/** Lightweight display lexer for known fences only. All offsets are into the unmodified source. */
internal fun chatCodeSpans(source: String, language: String?): List<CodeSpan> {
    val lang = language?.takeWhile { !it.isWhitespace() }?.lowercase(Locale.ROOT)
    val words = when (lang) {
        "py", "python" -> python
        "sh", "bash", "zsh", "shell" -> shell
        "json" -> setOf("true", "false", "null")
        "kt", "kotlin", "java", "js", "javascript", "ts", "typescript", "jsx", "tsx", "c", "cpp", "c++", "cs", "csharp", "go", "rs", "rust", "swift" -> common
        else -> return emptyList()
    }
    val hashComments = words === python || words === shell
    val slashComments = !hashComments && lang != "json"
    return buildList {
        var i = 0
        val end = minOf(source.length, 65_536)
        while (i < end && size < 4096) {
            val start = i
            val c = source[i++]
            val next = source.getOrNull(i)
            val tone = when {
                (hashComments && c == '#') || (slashComments && c == '/' && next == '/') -> {
                    while (i < end && source[i] != '\n') i++
                    CodeTone.COMMENT
                }
                slashComments && c == '/' && next == '*' -> {
                    i++
                    while (i < end && !(source[i - 1] == '*' && source[i] == '/')) i++
                    if (i < end) i++
                    CodeTone.COMMENT
                }
                c == '"' || c == '\'' || c == '`' -> {
                    while (i < end) {
                        val q = source[i++]
                        if (q == c) break
                        if (q == '\\' && i < end) i++
                    }
                    CodeTone.STRING
                }
                c.isDigit() -> {
                    while (i < end && (source[i].isLetterOrDigit() || source[i] in "._")) i++
                    CodeTone.NUMBER
                }
                c.isLetter() || c == '_' -> {
                    while (i < end && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                    CodeTone.KEYWORD.takeIf { i - start <= 12 && source.substring(start, i) in words }
                }
                else -> null
            }
            if (tone != null) add(CodeSpan(start, i, tone))
        }
    }
}
