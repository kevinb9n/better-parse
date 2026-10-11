import com.github.h0tk3y.betterParse.lexer.DefaultTokenizer
import com.github.h0tk3y.betterParse.lexer.literalToken
import com.github.h0tk3y.betterParse.lexer.noneMatched
import com.github.h0tk3y.betterParse.lexer.regexToken
import kotlin.test.Test
import kotlin.test.assertEquals

class RegexTokenizerTest {
    @Test
    fun alternativesAndGroupsOnlyMatchAtTheCurrentOffset() {
        val number = regexToken("number", "([0-9]+)(\\.[0-9]+)?")
        val word = regexToken("word", "[a-z]+")
        val space = regexToken("space", " +", ignore = true)
        val tokenizer = DefaultTokenizer(listOf(number, word, space))

        val tokens = tokenizer.tokenize("ab 12.5 cd 6").toList()
        assertEquals(listOf(word, space, number, space, word, space, number), tokens.map { it.type })
        assertEquals(listOf("ab", " ", "12.5", " ", "cd", " ", "6"), tokens.map { it.text })
        assertEquals(listOf(0, 2, 3, 7, 8, 10, 11), tokens.map { it.offset })

        val invalid = tokenizer.tokenize("ab ?12").toList()
        assertEquals(noneMatched, invalid.last().type)
        assertEquals("?12", invalid.last().text)
        assertEquals(3, invalid.last().offset)
    }

    @Test
    fun unicodeOffsetsUseTheOriginalCharSequence() {
        val word = regexToken("word", "[éΩ]+")
        val emoji = literalToken("emoji", "🚀")
        val tokenizer = DefaultTokenizer(listOf(word, emoji))
        val input = StringBuilder("é🚀Ωé")

        val tokens = tokenizer.tokenize(input).toList()
        assertEquals(listOf(word, emoji, word), tokens.map { it.type })
        assertEquals(listOf("é", "🚀", "Ωé"), tokens.map { it.text })
        assertEquals(listOf(0, 1, 3), tokens.map { it.offset })
        assertEquals(listOf(1, 2, 2), tokens.map { it.length })
    }

    @Test
    fun emptyRegexMatchesDoNotConsumeInputOrHideLaterTokens() {
        val optionalA = regexToken("optional a", "a*")
        val b = literalToken("b", "b")
        val tokenizer = DefaultTokenizer(listOf(optionalA, b))

        assertEquals(listOf(b, optionalA, b), tokenizer.tokenize("baab").toList().map { it.type })
        assertEquals(noneMatched, tokenizer.tokenize("?").toList().single().type)
        assertEquals(emptyList(), tokenizer.tokenize("").toList())
    }
}
