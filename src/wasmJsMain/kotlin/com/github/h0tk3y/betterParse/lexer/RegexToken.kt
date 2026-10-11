package com.github.h0tk3y.betterParse.lexer

public actual class RegexToken : Token {
    private val regex: Regex

    public actual constructor(name: String?, patternString: String, ignored: Boolean)
            : this(name, Regex(patternString), ignored)

    public actual constructor(name: String?, regex: Regex, ignored: Boolean)
            : super(name, ignored) {
        this.regex = regex
    }

    actual override fun match(input: CharSequence, fromIndex: Int): Int {
        val match = regex.matchAt(input, fromIndex) ?: return 0
        return match.range.last - match.range.first + 1
    }

    override fun toString(): String = "${name ?: ""} [${regex.pattern}]" + if (ignored) " [ignorable]" else ""
}
