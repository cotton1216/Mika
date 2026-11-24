package com.wangzan101.mika.core.cap.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random
import java.util.concurrent.TimeUnit

class SelectorPolicyTest {
    companion object {
        /**
         * 都是按 value 属性选元素的写法(在浏览器里对着 value 不同的两个输入框,命中的不一样)。
         * ReadScriptEnforcementTest 拿同一批去跑页面里的 JS 版,两边要一样。
         */
        val BY_VALUE = listOf(
            """input[value^="h"]""",
            """input[ VALUE ]""",
            """input[v\61 lue^="h"]""",
            """input[\76 alue]""",
            """input[v\000061lue^="h"]""",
            """input[\v\61 l\u\65 ^="h"]""",
            """input[*|value^="h"]""",
            """input[|value]""",
            """input[/* x */value^="h"]""",
            """input[value/* x */^="h"]""",
            """input[value^='h' i]""",
            """form:has(input[value^="h"])""",
            """input:is([value^="h"])""",
            // 引号里的注释记号不算数:只按字面去注释的话,中间那段 [value 就被当成注释吃掉了
            """input:not([data-x="/*"])[value^="h"]:not([data-y="*/"])""",
            // 引号里转义后面的换行算在转义里,续行的反斜杠加换行也不结束这段字
            "input:not([data-x=\"\\41\n\"])[value^=\"h\"]",
            "input:not([data-x=\"\\41\r\n\"])[value^=\"h\"]",
            "input:not([data-x=\"a\\\nb\"])[value^=\"h\"]",
            // 没写完的引号和方括号,浏览器读到结尾自己补上
            """input[value^="h""",
            // 不带引号的 url( 到 ) 为止是一个记号,里面的注释记号、引号不算数;:is() 丢掉这一项,后面的照样生效
            """input:is(url(/*),[value^="h"])""",
            """input:is(url(x"),[value^="h"])""",
            """input:where(u\72 l(/*),[value^="h"])""",
            """input:is(URL( /*),[value^="h"])""",
        )

        /** 不按 value 属性选的,照常放行。 */
        val NOT_BY_VALUE = listOf(
            "li.item",
            """input[name="q"]""",
            "input[data-value]",
            "input[value-x]",
            "input[valuex]",
            ".value",
            "#value",
            """input[type="value"]""",
            """[title="[value"]""",
            """[title='[value^=h]']""",
            """[data-x|="value"]""",
            "input:not([type=hidden])",
            """[d\61 ta-id]""",
            """[title="/*"], [data-x="*/"]""",
            """a[data-url="x"]""",
            "a.url",
            // 引号里的 url( 不算:url( 要在还原之后找
            """[style*="url("]""",
        )
    }

    @Test fun valueSelectorsAreCaught() {
        for (s in BY_VALUE) assertTrue(s, SelectorPolicy.selectsByValue(s))
    }

    @Test fun otherSelectorsPass() {
        for (s in NOT_BY_VALUE) assertFalse(s, SelectorPolicy.selectsByValue(s))
    }

    @Test fun readsLikeCss() {
        assertEquals("[value]", SelectorPolicy.plain("""[v\61 lue]"""))
        assertEquals("[value]", SelectorPolicy.plain("""[v\000061lue]"""))
        assertEquals("[value]", SelectorPolicy.plain("""[\v\61 l\u\65 ]"""))
        assertEquals("a b", SelectorPolicy.plain("a/* [value] */b"))
        assertEquals("a ", SelectorPolicy.plain("a/* not closed"))
        assertEquals("""[title=""][x=""]""", SelectorPolicy.plain("""[title="[value\"/*"][x='\27']"""))
        // 换行一律按 \n 算;没转义的换行让引号里的字到此为止
        assertEquals("[a=\"\"\n[value]", SelectorPolicy.plain("[a=\"x\r\n[value]"))
        // 0、代理区、超出范围的码点换成替换字符;末尾单独的反斜杠也是
        assertEquals("\uFFFD\uFFFD\uFFFD\uFFFD", SelectorPolicy.plain("""\0 \D800 \110000 \"""))
    }

    /** 页面里的 JS 版和 Kotlin 版判断一样:上面两组,加一批在属性名前后随机拼上容易出错的片段的。机器上没有 Node 时跳过。 */
    @Test fun jsVersionAgrees() {
        val node = ReadScriptEnforcementTest.node()
        assumeTrue("no node on this machine", node != null)
        val names = listOf("value", "VaLuE", "v\\61 lue", "\\76 alue", "v\\000061lue", "*|value", "|value", " value", "valu", "valuex", "value-x", "data-value", "val/**/ue", "va\\\nlue")
        val parts = listOf(
            "[", "]", "v", "e", "\\61 ", "\\76", "\\", "\"", "'", "/*", "*/", "*|", "|", " ", "\t", "\n", "\r", "\r\n", "\u000C",
            "x", "=\"h\"", "^", "(", ")", ":is(", "\\\n", "\\41\n", "-", "0", "\u00E9", "url(", "URL(", "u\\72 l(",
        )
        val rnd = Random(7)
        fun noise(n: Int) = buildString { repeat(rnd.nextInt(n)) { append(parts[rnd.nextInt(parts.size)]) } }
        val random = List(3000) { noise(4) + (if (rnd.nextBoolean()) "[" else "") + noise(2) + names[rnd.nextInt(names.size)] + noise(4) }
        val all = BY_VALUE + NOT_BY_VALUE + random
        val dir = kotlin.io.path.createTempDirectory("selector-policy").toFile()
        val input = File(dir, "all.json").apply { writeText(JsonArray(all.map(::JsonPrimitive)).toString()) }
        val script = File(dir, "check.js").apply {
            writeText("'use strict';\n" + SelectorPolicy.JS + "\nconst all = JSON.parse(require('fs').readFileSync(process.argv[2], 'utf8'));\nprocess.stdout.write(JSON.stringify(all.map(selectsByValue)));\n")
        }
        val p = ProcessBuilder(node, script.path, input.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("node timed out", p.waitFor(60, TimeUnit.SECONDS))
        dir.deleteRecursively()
        val js = runCatching { Json.parseToJsonElement(out).jsonArray.map { it.jsonPrimitive.boolean } }.getOrElse { error("node output: $out") }
        assertEquals(all.size, js.size)
        for ((i, s) in all.withIndex()) assertEquals(s, SelectorPolicy.selectsByValue(s), js[i])
        // 随机拼的里两种结果都要有不少,不然比的只是一边
        val hits = random.count { SelectorPolicy.selectsByValue(it) }
        assertTrue("$hits of ${random.size}", hits > 300 && random.size - hits > 300)
    }
}
