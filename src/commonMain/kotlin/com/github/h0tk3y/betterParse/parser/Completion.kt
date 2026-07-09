package com.github.h0tk3y.betterParse.parser

import com.github.h0tk3y.betterParse.combinators.AndCombinator
import com.github.h0tk3y.betterParse.combinators.MapCombinator
import com.github.h0tk3y.betterParse.combinators.OptionalCombinator
import com.github.h0tk3y.betterParse.combinators.OrCombinator
import com.github.h0tk3y.betterParse.combinators.RepeatCombinator
import com.github.h0tk3y.betterParse.combinators.SeparatedCombinator
import com.github.h0tk3y.betterParse.combinators.SkipParser
import com.github.h0tk3y.betterParse.grammar.ParserReference
import com.github.h0tk3y.betterParse.lexer.Token
import com.github.h0tk3y.betterParse.lexer.TokenMatchesSequence

/**
 * Describes syntactic continuations available after parsing from a specific input position.
 *
 * [expectedTokens] contains token types that could be accepted at [farthestPosition]. [acceptsEnd]
 * is true when the parser can complete successfully at [farthestPosition].
 */
public data class CompletionResult(
    public val acceptsEnd: Boolean,
    public val expectedTokens: Set<Token>,
    public val farthestPosition: Int
)

/** Returns token types that can continue this parser at the end of [tokens]. */
public fun <T> Parser<T>.completionAtEnd(
    tokens: TokenMatchesSequence,
    fromPosition: Int = 0
): CompletionResult {
    val analyzer = CompletionAnalyzer(tokens)
    return analyzer.complete(this, fromPosition).toResult()
}

private class CompletionAnalyzer(private val tokens: TokenMatchesSequence) {
    private val active = hashSetOf<Pair<Parser<*>, Int>>()

    fun complete(parser: Parser<*>, fromPosition: Int): Outcome {
        val key = parser to fromPosition
        if (!active.add(key)) return Outcome.failure(fromPosition)
        return try {
            when (parser) {
                is EmptyParser -> Outcome.success(fromPosition)
                is Token -> completeToken(parser, fromPosition)
                is MapCombinator<*, *> -> complete(parser.innerParser, fromPosition)
                is AndCombinator<*> -> completeAnd(parser, fromPosition)
                is OrCombinator<*> -> completeOr(parser, fromPosition)
                is OptionalCombinator<*> -> completeOptional(parser, fromPosition)
                is RepeatCombinator<*> -> completeRepeat(parser, fromPosition)
                is ParserReference<*> -> complete(parser.parser, fromPosition)
                is SeparatedCombinator<*, *> -> completeSeparated(parser, fromPosition)
                else -> completeFallback(parser, fromPosition)
            }
        } finally {
            active.remove(key)
        }
    }

    private fun completeToken(token: Token, fromPosition: Int): Outcome =
        when (val result = token.tryParse(tokens, fromPosition)) {
            is Parsed -> Outcome.success(result.nextPosition)
            is ErrorResult -> Outcome.fromError(result, fromPosition)
        }

    private fun completeAnd(combinator: AndCombinator<*>, fromPosition: Int): Outcome {
        var nextPosition = fromPosition
        var aggregate = Outcome.failure(fromPosition)
        for (consumer in combinator.consumersImpl) {
            val parser =
                when (consumer) {
                    is Parser<*> -> consumer
                    is SkipParser -> consumer.innerParser
                    else -> throw IllegalArgumentException()
                }
            val outcome = complete(parser, nextPosition)
            aggregate = aggregate.merge(outcome)
            if (!outcome.accepts) return aggregate.withoutAcceptance()
            nextPosition = outcome.nextPosition
        }
        return aggregate.merge(Outcome.success(nextPosition))
    }

    private fun completeOr(combinator: OrCombinator<*>, fromPosition: Int): Outcome {
        var aggregate = Outcome.failure(fromPosition)
        for (parser in combinator.parsers) {
            val outcome = complete(parser, fromPosition)
            aggregate = aggregate.merge(outcome)
            if (outcome.accepts) return aggregate
        }
        return aggregate
    }

    private fun completeOptional(combinator: OptionalCombinator<*>, fromPosition: Int): Outcome {
        val inner = complete(combinator.parser, fromPosition)
        return inner.merge(Outcome.success(fromPosition))
    }

    private fun completeRepeat(combinator: RepeatCombinator<*>, fromPosition: Int): Outcome {
        var nextPosition = fromPosition
        var count = 0
        var aggregate = Outcome.failure(fromPosition)
        while (combinator.atMost == -1 || count < combinator.atMost) {
            val outcome = complete(combinator.parser, nextPosition)
            aggregate = aggregate.merge(outcome)
            if (!outcome.accepts || outcome.nextPosition == nextPosition) {
                return if (count >= combinator.atLeast) {
                    aggregate.merge(Outcome.success(nextPosition))
                } else {
                    aggregate
                }
            }
            nextPosition = outcome.nextPosition
            count++
        }
        return aggregate.merge(Outcome.success(nextPosition))
    }

    private fun completeSeparated(combinator: SeparatedCombinator<*, *>, fromPosition: Int): Outcome {
        val first = complete(combinator.termParser, fromPosition)
        var aggregate = first
        if (!first.accepts) {
            return if (combinator.acceptZero) aggregate.merge(Outcome.success(fromPosition)) else aggregate
        }

        var nextPosition = first.nextPosition
        while (true) {
            val separator = complete(combinator.separatorParser, nextPosition)
            aggregate = aggregate.merge(separator)
            if (!separator.accepts) return aggregate.merge(Outcome.success(nextPosition))

            val term = complete(combinator.termParser, separator.nextPosition)
            aggregate = aggregate.merge(term)
            if (!term.accepts) return aggregate.merge(Outcome.success(nextPosition))

            if (term.nextPosition == nextPosition) return aggregate.merge(Outcome.success(nextPosition))
            nextPosition = term.nextPosition
        }
    }

    private fun completeFallback(parser: Parser<*>, fromPosition: Int): Outcome =
        when (val result = parser.tryParse(tokens, fromPosition)) {
            is Parsed -> Outcome.success(result.nextPosition)
            is ErrorResult -> Outcome.fromError(result, fromPosition)
        }
}

private data class Outcome(
    val accepts: Boolean,
    val nextPosition: Int,
    val expectedByPosition: Map<Int, Set<Token>>
) {
    fun merge(other: Outcome): Outcome {
        val farthest = maxOf(farthestPosition(), other.farthestPosition())
        return Outcome(
            accepts && nextPosition == farthest || other.accepts && other.nextPosition == farthest,
            farthest,
            mapOf(farthest to (tokensAt(farthest) + other.tokensAt(farthest))).filterValues { it.isNotEmpty() }
        )
    }

    fun toResult(): CompletionResult {
        val farthest = farthestPosition()
        return CompletionResult(accepts && nextPosition == farthest, tokensAt(farthest), farthest)
    }

    fun withoutAcceptance(): Outcome = copy(accepts = false)

    private fun farthestPosition(): Int = maxOf(nextPosition, expectedByPosition.keys.maxOrNull() ?: nextPosition)

    private fun tokensAt(position: Int): Set<Token> = expectedByPosition[position].orEmpty()

    companion object {
        fun success(position: Int): Outcome = Outcome(true, position, emptyMap())

        fun failure(position: Int): Outcome = Outcome(false, position, emptyMap())

        fun fromError(error: ErrorResult, position: Int): Outcome {
            val expected = expectedTokens(error)
            return if (expected.isEmpty()) failure(position) else Outcome(false, position, mapOf(position to expected))
        }

        private fun expectedTokens(error: ErrorResult): Set<Token> =
            when (error) {
                is UnexpectedEof -> setOf(error.expected)
                is MismatchedToken -> setOf(error.expected)
                is AlternativesFailure -> error.errors.flatMap { expectedTokens(it) }.toSet()
                else -> emptySet()
            }
    }
}
