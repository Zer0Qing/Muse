package io.zer0.muse.transformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 11.4: PebbleTemplateEngine ?????
 *
 * ??????:???? / ??? / if-elif-else / for ?? / ???
 * ? JVM ??,? Android ???
 */
class PebbleTemplateEngineTest {

    private val engine = PebbleTemplateEngine()

    @Test
    fun `variable interpolation renders`() {
        val out = engine.render("Hello {{ name }}", mapOf("name" to "Alice"))
        assertEquals("Hello Alice", out)
    }

    @Test
    fun `missing variable renders empty`() {
        val out = engine.render("Hello {{ name }}", emptyMap())
        assertEquals("Hello ", out)
    }

    @Test
    fun `nested map access via dot notation`() {
        val out = engine.render(
            "{{ user.name }} is {{ user.age }}",
            mapOf("user" to mapOf("name" to "Bob", "age" to 30)),
        )
        assertEquals("Bob is 30", out)
    }

    @Test
    fun `upper filter converts to uppercase`() {
        val out = engine.render("{{ text | upper }}", mapOf("text" to "hello"))
        assertEquals("HELLO", out)
    }

    @Test
    fun `lower filter converts to lowercase`() {
        val out = engine.render("{{ text | lower }}", mapOf("text" to "HELLO"))
        assertEquals("hello", out)
    }

    @Test
    fun `trim filter strips whitespace`() {
        val out = engine.render("[{{ text | trim }}]", mapOf("text" to "  hello  "))
        assertEquals("[hello]", out)
    }

    @Test
    fun `default filter provides fallback for null`() {
        val out = engine.render(
            "{{ name | default('Anonymous') }}",
            mapOf("name" to null),
        )
        assertEquals("Anonymous", out)
    }

    @Test
    fun `default filter leaves non-null value unchanged`() {
        val out = engine.render(
            "{{ name | default('Anonymous') }}",
            mapOf("name" to "Alice"),
        )
        assertEquals("Alice", out)
    }

    @Test
    fun `length filter returns string length`() {
        val out = engine.render("{{ text | length }}", mapOf("text" to "hello"))
        assertEquals("5", out)
    }

    @Test
    fun `if true renders then branch`() {
        val out = engine.render(
            "{% if show %}visible{% endif %}",
            mapOf("show" to true),
        )
        assertEquals("visible", out)
    }

    @Test
    fun `if false skips then branch`() {
        val out = engine.render(
            "{% if show %}visible{% endif %}",
            mapOf("show" to false),
        )
        assertEquals("", out)
    }

    @Test
    fun `if-else renders correct branch`() {
        val template = "{% if x > 5 %}big{% else %}small{% endif %}"
        assertEquals("big", engine.render(template, mapOf("x" to 10)))
        assertEquals("small", engine.render(template, mapOf("x" to 3)))
    }

    @Test
    fun `if-elif-else renders correct branch`() {
        // Phase 12 fix: now supports elif with numeric == and string comparisons
        val template = "{% if x > 5 %}big{% elif x == 3 %}three{% elif x == 0 %}zero{% else %}other{% endif %}"
        assertEquals("big", engine.render(template, mapOf("x" to 10)))
        assertEquals("three", engine.render(template, mapOf("x" to 3)))
        assertEquals("zero", engine.render(template, mapOf("x" to 0)))
        assertEquals("other", engine.render(template, mapOf("x" to 2)))
        assertEquals("other", engine.render(template, mapOf("x" to -1)))

        // 同时测试原始的字符串比较用例
        val template2 = "{% if x == 'two' %}two{% else %}other{% endif %}"
        assertEquals("two", engine.render(template2, mapOf("x" to "two")))
        assertEquals("other", engine.render(template2, mapOf("x" to "other")))
    }

    @Test
    fun `elif with numeric greater-than comparison`() {
        val template = "{% if x == 0 %}zero{% elif x > 2 %}big{% elif x > 0 %}mid{% else %}other{% endif %}"
        assertEquals("zero", engine.render(template, mapOf("x" to 0)))
        assertEquals("big", engine.render(template, mapOf("x" to 5)))
        assertEquals("mid", engine.render(template, mapOf("x" to 1)))
        assertEquals("other", engine.render(template, mapOf("x" to -1)))
    }

    @Test
    fun `for loop iterates list`() {
        val out = engine.render(
            "{% for item in items %}{{ item }}{% endfor %}",
            mapOf("items" to listOf("a", "b", "c")),
        )
        assertEquals("abc", out)
    }

    @Test
    fun `for loop with loop index variable`() {
        val out = engine.render(
            "{% for item in items %}{{ loop.index }}:{{ item }};{% endfor %}",
            mapOf("items" to listOf("a", "b", "c")),
        )
        assertEquals("1:a;2:b;3:c;", out)
    }

    @Test
    fun `for loop with loop first and last`() {
        val out = engine.render(
            "{% for item in items %}{% if loop.first %}[{% endif %}{{ item }}{% if loop.last %}]{% endif %}{% endfor %}",
            mapOf("items" to listOf("a", "b", "c")),
        )
        assertEquals("[abc]", out)
    }

    @Test
    fun `nested for loop renders correctly`() {
        val template = buildString {
            append("{% for row in matrix %}")
            append("{% for cell in row %}{{ cell }}{% endfor %}")
            append("|")
            append("{% endfor %}")
        }
        val out = engine.render(
            template,
            mapOf("matrix" to listOf(listOf("a", "b"), listOf("c", "d"))),
        )
        assertEquals("ab|cd|", out)
    }

    @Test
    fun `comment is stripped from output`() {
        val out = engine.render(
            "before{# this is a comment #}after",
            emptyMap(),
        )
        assertEquals("beforeafter", out)
    }

    @Test
    fun `string equality comparison`() {
        val template = "{% if color == 'red' %}stop{% else %}go{% endif %}"
        assertEquals("stop", engine.render(template, mapOf("color" to "red")))
        assertEquals("go", engine.render(template, mapOf("color" to "green")))
    }

    @Test
    fun `and or not logical operators`() {
        val template = "{% if a and not b %}A{% elif a or b %}B{% else %}C{% endif %}"
        assertEquals("A", engine.render(template, mapOf("a" to true, "b" to false)))
        assertEquals("B", engine.render(template, mapOf("a" to true, "b" to true)))
        assertEquals("B", engine.render(template, mapOf("a" to false, "b" to true)))
        assertEquals("C", engine.render(template, mapOf("a" to false, "b" to false)))
    }

    @Test
    fun `is empty test on null`() {
        val template = "{% if x is empty %}empty{% else %}not empty{% endif %}"
        assertEquals("empty", engine.render(template, mapOf("x" to null)))
        assertEquals("empty", engine.render(template, mapOf("x" to "")))
        assertEquals("not empty", engine.render(template, mapOf("x" to "data")))
    }

    @Test
    fun `is defined test`() {
        val template = "{% if x is defined %}yes{% else %}no{% endif %}"
        assertEquals("yes", engine.render(template, mapOf("x" to "value")))
        assertEquals("no", engine.render(template, emptyMap()))
    }

    @Test
    fun `chained filters`() {
        val out = engine.render(
            "{{ text | trim | upper }}",
            mapOf("text" to "  hello  "),
        )
        assertEquals("HELLO", out)
    }

    @Test
    fun `join filter concatenates list with separator`() {
        val out = engine.render(
            "{{ items | join(', ') }}",
            mapOf("items" to listOf("a", "b", "c")),
        )
        assertEquals("a, b, c", out)
    }

    @Test
    fun `set variable inside template`() {
        // Phase 12: {% set %} ?????
        val out = engine.render("{% set x = 'hello' %}{{ x | upper }}", emptyMap())
        assertEquals("HELLO", out)
    }

    @Test
    fun `set variable then use in if`() {
        val template = "{% set score = 85 %}{% if score > 60 %}pass{% else %}fail{% endif %}"
        assertEquals("pass", engine.render(template, emptyMap()))
    }

    @Test
    fun `set inside for loop`() {
        val out = engine.render(
            "{% for item in items %}{% set msg = item | default('x') %}{{ msg }}{% endfor %}",
            mapOf("items" to listOf("a", "b", "c")),
        )
        assertEquals("abc", out)
    }

    @Test
    fun `literal string in template`() {
        val out = engine.render("{{ 'world' }}", emptyMap())
        assertEquals("world", out)
    }

    @Test
    fun `numeric literal in template`() {
        val out = engine.render("{{ 42 }}", emptyMap())
        assertEquals("42", out)
    }

    @Test
    fun `boolean literal in template`() {
        val out = engine.render("{{ true }} {{ false }}", emptyMap())
        assertEquals("true false", out)
    }

    @Test
    fun `null literal renders empty`() {
        val out = engine.render("before{{ null }}after", emptyMap())
        assertEquals("beforeafter", out)
    }
}
