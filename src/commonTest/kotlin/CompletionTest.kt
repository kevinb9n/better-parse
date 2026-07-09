import com.github.h0tk3y.betterParse.combinators.and
import com.github.h0tk3y.betterParse.combinators.map
import com.github.h0tk3y.betterParse.combinators.optional
import com.github.h0tk3y.betterParse.combinators.or
import com.github.h0tk3y.betterParse.combinators.separatedTerms
import com.github.h0tk3y.betterParse.combinators.zeroOrMore
import com.github.h0tk3y.betterParse.grammar.Grammar
import com.github.h0tk3y.betterParse.lexer.Token
import com.github.h0tk3y.betterParse.lexer.literalToken
import com.github.h0tk3y.betterParse.parser.Parser
import com.github.h0tk3y.betterParse.parser.completionAtEnd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionTest : Grammar<Nothing>() {
    override val rootParser: Parser<Nothing> get() = throw NoSuchElementException()

    val a by literalToken("a")
    val b by literalToken("b")
    val comma by literalToken(",")
    val space by literalToken(" ", ignore = true)

    @Test fun requiredNextTokenAtEof() {
        val completion = (a and b).completionAtEnd(tokenizer.tokenize("a"))

        assertFalse(completion.acceptsEnd)
        assertEquals(setOf(b), completion.expectedTokens)
    }

    @Test fun optionalContinuationAfterSuccessfulPrefix() {
        val completion = (a and optional(b)).completionAtEnd(tokenizer.tokenize("a"))

        assertTrue(completion.acceptsEnd)
        assertEquals(setOf(b), completion.expectedTokens)
    }

    @Test fun repeatedContinuationAfterSuccessfulPrefix() {
        val completion = zeroOrMore(a).completionAtEnd(tokenizer.tokenize("aaa"))

        assertTrue(completion.acceptsEnd)
        assertEquals(setOf(a), completion.expectedTokens)
    }

    @Test fun separatedContinuationAfterTerm() {
        val completion = separatedTerms(a, comma).completionAtEnd(tokenizer.tokenize("a"))

        assertTrue(completion.acceptsEnd)
        assertEquals(setOf(comma), completion.expectedTokens)
    }

    @Test fun separatedTermAfterSeparator() {
        val completion = separatedTerms(a, comma).completionAtEnd(tokenizer.tokenize("a,"))

        assertFalse(completion.acceptsEnd)
        assertEquals(setOf(a), completion.expectedTokens)
    }

    @Test fun alternativesKeepEofExpectationsBeforeFirstSuccess() {
        val completion = ((a and b).map { } or a.map { }).completionAtEnd(tokenizer.tokenize("a"))

        assertTrue(completion.acceptsEnd)
        assertEquals(setOf(b), completion.expectedTokens)
    }

    @Test fun ignoredWhitespaceAtEof() {
        val completion = (a and optional(b)).completionAtEnd(tokenizer.tokenize("a "))

        assertTrue(completion.acceptsEnd)
        assertEquals(setOf(b), completion.expectedTokens)
    }
}
