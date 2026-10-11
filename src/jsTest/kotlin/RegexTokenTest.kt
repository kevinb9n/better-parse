import com.github.h0tk3y.betterParse.lexer.DefaultTokenizer
import com.github.h0tk3y.betterParse.lexer.literalToken
import com.github.h0tk3y.betterParse.lexer.regexToken
import kotlin.test.Test
import kotlin.test.assertEquals

class RegexTokenTest {
    @Test
    fun regexOptionsArePreservedWhenTokenizing() {
        val newline = literalToken("newline", "\n")
        val word = regexToken("word", Regex("^hello", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)))
        val tokenizer = DefaultTokenizer(listOf(word, newline))

        val tokens = tokenizer.tokenize("HELLO\nHello").toList()
        assertEquals(listOf(word, newline, word), tokens.map { it.type })
        assertEquals(listOf("HELLO", "\n", "Hello"), tokens.map { it.text })
    }

    @Test
    fun matchingRetainsContextBeforeTheCurrentOffset() {
        val colon = literalToken("colon", ":")
        val word = regexToken("word", "(?<=:)[a-z]+(?=!)")
        val bang = literalToken("bang", "!")
        val tokens = DefaultTokenizer(listOf(word, colon, bang)).tokenize(":hello!").toList()

        assertEquals(listOf(colon, word, bang), tokens.map { it.type })
        assertEquals("hello", tokens[1].text)
        assertEquals(1, tokens[1].offset)
        assertEquals(0, regexToken("^hello").match(":hello", 1))
        assertEquals(5, regexToken("hello$").match(":hello", 1))
    }

    @Test
    fun sharingARegexDoesNotChangeItsSearchBehavior() {
        val regex = Regex("[a-z]+", RegexOption.IGNORE_CASE)
        val first = regexToken("first", regex)
        val second = regexToken("second", regex)

        repeat(3) {
            assertEquals(0, first.match("123ABC", 0))
            assertEquals(3, second.match("123ABC", 3))
            assertEquals(0, first.match("123ABC", 6))
            assertEquals("ABC", regex.find("123ABC")?.value)
        }
    }
}
