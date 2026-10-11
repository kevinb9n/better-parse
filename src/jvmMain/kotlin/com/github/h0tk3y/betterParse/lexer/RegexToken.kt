package com.github.h0tk3y.betterParse.lexer

import java.util.regex.Matcher

public actual class RegexToken private constructor(
    name: String?,
    ignored: Boolean,
    private val pattern: String,
    private val regex: Regex
) : Token(name, ignored) {

    private val threadLocalMatcher = object : ThreadLocal<Matcher>() {
        override fun initialValue() = regex.toPattern().matcher("")
    }

    private val matcher: Matcher get() = threadLocalMatcher.get()

    public actual constructor(
        name: String?,
        @Language("RegExp", "", "") patternString: String,
        ignored: Boolean
    ) : this(
        name,
        ignored,
        patternString,
        Regex(patternString)
    )

    public actual constructor(
        name: String?,
        regex: Regex,
        ignored: Boolean
    ) : this(
        name,
        ignored,
        regex.pattern,
        regex
    )

    actual override fun match(input: CharSequence, fromIndex: Int): Int {
        matcher.reset(input).region(fromIndex, input.length)
            .useTransparentBounds(true)
            .useAnchoringBounds(false)

        if (!matcher.lookingAt()) {
            return 0
        }

        val end = matcher.end()
        return end - fromIndex
    }

    override fun toString(): String = "${name ?: ""} [$pattern]" + if (ignored) " [ignorable]" else ""
}
