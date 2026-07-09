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
        var positions = setOf(fromPosition)
        var aggregate = Outcome.empty()
        for (consumer in combinator.consumersImpl) {
            val parser =
                when (consumer) {
                    is Parser<*> -> consumer
                    is SkipParser -> consumer.innerParser
                    else -> throw IllegalArgumentException()
                }
            val outcomes = positions.map { complete(parser, it) }
            aggregate = outcomes.fold(aggregate) { acc, outcome -> acc.mergeExpected(outcome) }
            positions = outcomes.flatMap { it.acceptedPositions }.toSet()
            if (positions.isEmpty()) return aggregate
        }
        return aggregate.merge(Outcome.success(positions))
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
        var positions = setOf(fromPosition)
        var count = 0
        var aggregate = Outcome.empty()
        while (combinator.atMost == -1 || count < combinator.atMost) {
            val outcomes = positions.map { complete(combinator.parser, it) }
            aggregate = outcomes.fold(aggregate) { acc, outcome -> acc.mergeExpected(outcome) }
            val nextPositions = outcomes.flatMap { it.acceptedPositions }.toSet()
            if (nextPositions.isEmpty() || nextPositions == positions) {
                return if (count >= combinator.atLeast) {
                    aggregate.merge(Outcome.success(positions))
                } else {
                    aggregate
                }
            }
            positions = nextPositions
            count++
        }
        return aggregate.merge(Outcome.success(positions))
    }

    private fun completeSeparated(combinator: SeparatedCombinator<*, *>, fromPosition: Int): Outcome {
        val first = complete(combinator.termParser, fromPosition)
        var aggregate = first
        if (!first.accepts) {
            return if (combinator.acceptZero) aggregate.merge(Outcome.success(fromPosition)) else aggregate
        }

        var termPositions = first.acceptedPositions
        while (termPositions.isNotEmpty()) {
            aggregate = aggregate.merge(Outcome.success(termPositions))
            val separatorOutcomes = termPositions.map { complete(combinator.separatorParser, it) }
            aggregate = separatorOutcomes.fold(aggregate) { acc, outcome -> acc.mergeExpected(outcome) }
            val separatorPositions = separatorOutcomes.flatMap { it.acceptedPositions }.toSet()
            if (separatorPositions.isEmpty()) return aggregate

            val termOutcomes = separatorPositions.map { complete(combinator.termParser, it) }
            aggregate = termOutcomes.fold(aggregate) { acc, outcome -> acc.mergeExpected(outcome) }
            val nextTermPositions = termOutcomes.flatMap { it.acceptedPositions }.toSet()
            if (nextTermPositions.isEmpty() || nextTermPositions == termPositions) return aggregate
            termPositions = nextTermPositions
        }
        return aggregate
    }

    private fun completeFallback(parser: Parser<*>, fromPosition: Int): Outcome =
        when (val result = parser.tryParse(tokens, fromPosition)) {
            is Parsed -> Outcome.success(result.nextPosition)
            is ErrorResult -> Outcome.fromError(result, fromPosition)
        }
}

private data class Outcome(
    val acceptedPositions: Set<Int>,
    val expectedByPosition: Map<Int, Set<Token>>
) {
    val accepts: Boolean
        get() = acceptedPositions.isNotEmpty()

    fun merge(other: Outcome): Outcome {
        return Outcome(
            acceptedPositions + other.acceptedPositions,
            mergeExpectedMaps(expectedByPosition, other.expectedByPosition)
        )
    }

    fun mergeExpected(other: Outcome): Outcome {
        return Outcome(
            acceptedPositions,
            mergeExpectedMaps(expectedByPosition, other.expectedByPosition)
        )
    }

    fun toResult(): CompletionResult {
        val farthest = farthestPosition()
        return CompletionResult(farthest in acceptedPositions, tokensAt(farthest), farthest)
    }

    private fun farthestPosition(): Int = allPositions().maxOrNull() ?: 0

    private fun allPositions(): Set<Int> = acceptedPositions + expectedByPosition.keys

    private fun tokensAt(position: Int): Set<Token> = expectedByPosition[position].orEmpty()

    companion object {
        fun empty(): Outcome = Outcome(emptySet(), emptyMap())

        fun success(position: Int): Outcome = success(setOf(position))

        fun success(positions: Set<Int>): Outcome = Outcome(positions, emptyMap())

        fun failure(position: Int): Outcome = Outcome(emptySet(), emptyMap())

        fun fromError(error: ErrorResult, position: Int): Outcome {
            val expected = expectedTokens(error)
            return if (expected.isEmpty()) failure(position) else Outcome(emptySet(), mapOf(position to expected))
        }

        private fun expectedTokens(error: ErrorResult): Set<Token> =
            when (error) {
                is UnexpectedEof -> setOf(error.expected)
                is MismatchedToken -> setOf(error.expected)
                is AlternativesFailure -> error.errors.flatMap { expectedTokens(it) }.toSet()
                else -> emptySet()
            }

        private fun mergeExpectedMaps(
            left: Map<Int, Set<Token>>,
            right: Map<Int, Set<Token>>
        ): Map<Int, Set<Token>> =
            (left.keys + right.keys).associateWith { left[it].orEmpty() + right[it].orEmpty() }
                .filterValues { it.isNotEmpty() }
    }
}
