package com.wangzan101.mika.core.cap.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 读脚本前置脚本的只读约束,真跑一遍:在 Node 里用一套假 DOM 装上 [ReadScriptProtocol.PRELUDE],
 * 看改页面、点、跳转、任何网络请求、读秘密、改内置对象都被拦下,读照常。机器上没有 Node 时跳过。
 */
class ReadScriptEnforcementTest {
    companion object {
        private val cases = linkedMapOf<String, Pair<String, JsonObject?>>()
        private val pages = mutableMapOf<String, String>()
        private lateinit var cache: JsonObject

        /** [page]:跑之前页面自己做的事(harness.js 的 pageActions),跑完还原。 */
        private fun case(name: String, code: String, args: JsonObject? = null, page: String? = null) { cases[name] = code to args; page?.let { pages[name] = it } }

        internal fun node(): String? = listOf("node", "/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node").firstOrNull { n ->
            runCatching { ProcessBuilder(n, "--version").redirectErrorStream(true).start().let { it.waitFor(10, TimeUnit.SECONDS) && it.exitValue() == 0 } }.getOrDefault(false)
        }

        @BeforeClass @JvmStatic fun runAll() {
            case("count", "document.querySelectorAll('li').length")
            case("texts", "[...document.querySelectorAll('li')].map(e => e.textContent)")
            case("attrs", "const out = []; document.querySelectorAll('li').forEach(e => out.push(e.getAttribute('data-id'))); return out;")
            case("title", "document.title")
            case("setText", "document.querySelector('li').textContent = 'x'; return 1;")
            case("setTitle", "document.title = 'x'")
            case("append", "document.body.appendChild(document.querySelector('li'))")
            case("create", "document.createElement('div')")
            case("click", "document.querySelector('li').click()")
            case("setAttr", "document.querySelector('li').setAttribute('a', 'b')")
            case("callbackSet", "document.querySelectorAll('li').forEach(e => e.setAttribute('a', 'b')); return 1;")
            case("viaCall", "const li = document.querySelector('li'); return li.setAttribute.call(li, 'a', 'b');")
            case("viaReflect", "const li = document.querySelector('li'); return Reflect.apply(li.setAttribute, li, ['a', 'b']);")
            case("viaProto", "const p = Object.getPrototypeOf(document.querySelector('li')); return p === null;")
            case("defineProp", "Object.defineProperty(document.querySelector('li'), 'x', { value: 1 })")
            case("setValue", "document.querySelector('input').value = 'x'")
            case("navigateHref", "location.href = 'https://evil.test/'")
            case("navigateAssign", "location.assign('https://evil.test/')")
            case("openWindow", "window.open('https://evil.test/')")
            case("cookie", "document.cookie")
            case("password", "document.querySelector('input').value")
            case("hiddenAttr", "document.querySelectorAll('input')[1].getAttribute('value')")
            case("visibleValue", "document.querySelectorAll('input')[2].value")
            case("formHtml", "document.querySelector('form').outerHTML")
            case("byValue", "document.querySelector('input[value^=h]')")
            for ((i, sel) in SelectorPolicyTest.BY_VALUE.withIndex()) case("byValue$i", "document.querySelector(args.s)", buildJsonObject { put("s", sel) })
            for ((i, sel) in SelectorPolicyTest.NOT_BY_VALUE.withIndex()) case("notByValue$i", "document.querySelector(args.s)", buildJsonObject { put("s", sel) })
            case("selectorObject", "document.querySelector({ toString: () => 'input[value^=h]' })")
            case("selectorArray", "document.querySelector(['input[value^=h]'])")
            case("selectorFlip", "let n = 0; return document.querySelectorAll({ toString: () => (n++ ? 'input[value^=h]' : 'li') }).length;")
            case("attrFlip", "let n = 0; return document.querySelectorAll('input')[1].getAttribute({ toString: () => (n++ ? 'value' : 'x') });")
            case("textareas", "const [code, note] = document.querySelectorAll('textarea'); return [code.value, code.textLength, code.outerText, code.innerText, code.name, note.value, note.textLength, note.outerText];")
            case("areaText", "const [code, note] = document.querySelectorAll('textarea'); const c = code.firstChild, n = note.firstChild; return [c.data, c.nodeValue, c.textContent, c.wholeText, c.length, c.substringData(0, 3), code.childNodes.length, n.data, n.substringData(0, 5)];")
            case("areaCdata", "const c = document.querySelector('iframe').contentDocument.body.children[0].children[3].firstChild; return [c.nodeType, c.data, c.nodeValue, c.textContent, c.wholeText, c.length];")
            case("areaContainers", "const f = document.querySelector('form'); const b = document.body.textContent; return [f.textContent, f.innerText, b.includes('otp-in-textarea'), b.includes('leave at door'), document.querySelector('div').shadowRoot.textContent, document.querySelector('iframe').contentDocument.body.textContent];")
            // 拿着子文本节点、value 属性节点先不读,等页面换掉字、删掉属性(节点脱离文档)以后再读
            case("holdArea", "state.area = document.querySelector('div').shadowRoot.childNodes[2].firstChild; return 1;")
            case("heldArea", "const n = state.area; return [n.parentNode === null, n.data, n.nodeValue, n.textContent, n.wholeText, n.length, n.substringData(0, 3)];", page = "typeBack")
            case("holdAreaIter", "for (const n of document.querySelector('div').shadowRoot.childNodes[2].childNodes) state.area2 = n; return 1;")
            case("heldAreaIter", "const n = state.area2; return [n.parentNode === null, n.data, n.textContent];", page = "typeBack")
            case("holdAttr", "state.attr = document.querySelector('input').attributes.getNamedItem('value'); return 1;")
            case("heldAttr", "const a = state.attr; return [a.ownerElement === null, a.value, a.nodeValue, a.textContent];", page = "dropValue")
            case("areaDesc", "const a = document.querySelector('a'), s = document.querySelector('select'), o = s.options[1]; return [a.text, o.text, o.label, o.value, s.value, s.selectedIndex, s.options[0].value];")
            case("areaShown", "const p = document.querySelector('p'), s = document.querySelector('select'); return [p.innerText, p.outerText, p.textContent, s.innerText, s.options[1].innerText];")
            case("areaDesc2", "const s = document.querySelectorAll('select')[1], o = s.options[0], out = document.querySelector('output'); return [s.value, o.value, o.text, o.label, out.value, out.defaultValue];")
            case("noSelection", "const s = document.querySelector('select'); return [s.selectedIndex, s.value];", page = "unselect")
            // 拿到节点时框还不是秘密的(先读得出),页面随后把框变成秘密、把字写进这个节点,节点再脱离文档
            case("holdLate", "state.late = document.querySelectorAll('textarea')[1].firstChild; state.lateAttr = document.querySelectorAll('input')[2].attributes.getNamedItem('value'); return [state.late.data, state.lateAttr.value];")
            case("heldLate", "const n = state.late, a = state.lateAttr; return [n.parentNode === null, n.data, n.textContent, a.ownerElement === null, a.value, a.nodeValue];", page = "lateSecret")
            case("frameFields", "const f = document.querySelector('iframe').contentDocument.body.children[0]; return [f.children[0].value, f.children[1].value, f.children[0].selectionEnd, f.children[1].type, f.children[2].value];")
            case("frameHtml", "document.querySelector('iframe').contentDocument.body.innerHTML")
            case("shadowHtml", "document.querySelector('div').shadowRoot.innerHTML")
            case("shadowValue", "document.querySelector('div').shadowRoot.childNodes[0].value")
            case("toggledValue", "document.querySelector('input').value", page = "showPassword")
            case("toggledHtml", "document.querySelector('form').outerHTML", page = "showPassword")
            case("templateHtml", "document.querySelector('form').innerHTML")
            case("svgTemplate", "const h = document.body.innerHTML; return [h.includes('<circle'), h.includes('hunter2'), h.includes('tpl-secret')];")
            case("attrNode", "const a = document.querySelector('input').attributes; return [a.getNamedItem('value').value, Array.from(a, (x) => x.name + '=' + x.value), Array.from(document.querySelectorAll('input')).find((x) => x.name === 'q').attributes.getNamedItem('value').value];")
            case("frameAttr", "const f = document.querySelector('iframe').contentDocument.body.children[0].children; return [f[1].attributes[2].name, f[1].attributes[2].value, f[2].attributes[2].value];")
            case("fieldProps", "const xs = document.querySelectorAll('input'); return [xs[3].valueAsNumber, xs[0].selectionEnd, xs[0].type, xs[3].name, xs[0].tagName, xs[2].selectionEnd];")
            case("post", "await fetch('/api', { method: 'POST' })")
            case("body", "await fetch('/api', { body: 'x' })")
            case("crossOrigin", "await fetch('https://evil.test/api')")
            case("get", "const r = await fetch('/api/items?page=2'); return { status: r.status, url: r.url, data: await r.json() };")
            case("plainFetch", "fetch('/api/items')")
            case("xhr", "const x = new window.XMLHttpRequest(); x.open('GET', '/api'); x.send(); return 1;")
            case("beacon", "window.navigator.sendBeacon('/log', 'x')")
            case("image", "new window.Image()")
            case("socket", "new window.WebSocket('wss://shop.test/s')")
            case("events", "new window.EventSource('/stream')")
            case("readNavigator", "window.navigator.userAgent")
            case("stateSet", "state.n = 41; return 1;")
            case("stateGet", "state.n + 1")
            case("args", "args.q + '!'", buildJsonObject { put("q", "shoes") })
            case("argsFrozen", "args.q = 'x'; return args.q;", buildJsonObject { put("q", "shoes") })
            case("hiddenGlobals", "[typeof process, typeof globalThis, typeof self, typeof setTimeout]")
            case("stringCode", "(() => {}).constructor('return 1')()")
            case("patchBuiltin", "WeakMap.prototype.get = () => 1; return 1;")
            case("rebindGlobal", "String = () => 'x'; return document.querySelectorAll('input')[1].getAttribute('value');")
            case("afterRebind", "[String(1), document.querySelectorAll('input')[1].getAttribute('value')]")
            case("big", "'x'.repeat(100)")
            case("syntax", "return (")
            case("errorLine", "const a = 1;\nnull.x;")
        }

        private fun results(): JsonObject {
            if (::cache.isInitialized) return cache
            val n = node()
            assumeTrue("no node on this machine", n != null)
            val dir = kotlin.io.path.createTempDirectory("read-script").toFile()
            val prelude = File(dir, "prelude.js").apply { writeText(ReadScriptProtocol.PRELUDE) }
            val list = File(dir, "cases.json").apply {
                writeText(buildJsonArray {
                    for ((name, c) in cases) add(buildJsonObject {
                        put("name", name); put("code", c.first); c.second?.let { put("args", it) }; pages[name]?.let { put("page", it) }
                        if (name == "big") put("resultChars", 10)
                    })
                }.toString())
            }
            val harness = File(dir, "harness.js").apply { writeText(ReadScriptEnforcementTest::class.java.getResource("/read-script/harness.js")!!.readText()) }
            val p = ProcessBuilder(n, harness.path, prelude.path, list.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue("node timed out", p.waitFor(60, TimeUnit.SECONDS))
            cache = runCatching { Json.parseToJsonElement(out).jsonObject }.getOrElse { error("harness output: $out") }
            dir.deleteRecursively()
            return cache
        }
    }

    private fun r(name: String): JsonObject = results().getValue(name).jsonObject
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun ok(name: String): String { val o = r(name); assertEquals("$name: $o", "true", o.s("ok")); return o.s("json")!! }
    private fun blocked(name: String, code: String) {
        val o = r(name)
        assertEquals("$name: $o", "false", o.s("ok"))
        assertEquals("$name: $o", code, o.s("error"))
        assertEquals("$name changed the page", 0, o.getValue("mutations").jsonPrimitive.int)
        assertEquals("$name clicked", 0, o.getValue("clicks").jsonPrimitive.int)
        assertEquals("$name navigated", 0, o.getValue("navigations").jsonPrimitive.int)
    }

    @Test fun readsTheRealDom() {
        assertEquals("2", ok("count"))
        assertEquals("""["apple","pear"]""", ok("texts"))
        assertEquals("""["1","2"]""", ok("attrs"))
        assertEquals("\"Shop\"", ok("title"))
        assertEquals("true", ok("viaProto"))
        assertEquals("\"shoes\"", ok("visibleValue"))
    }

    @Test fun mutationIsBlocked() {
        for (n in listOf("setText", "setTitle", "append", "create", "click", "setAttr", "callbackSet", "viaCall", "viaReflect", "defineProp", "setValue")) blocked(n, "read_only")
    }

    @Test fun navigationIsBlocked() {
        for (n in listOf("navigateHref", "navigateAssign", "openWindow")) blocked(n, "read_only")
    }

    @Test fun secretsStayHidden() {
        blocked("cookie", "read_only")
        assertEquals("\"***\"", ok("password"))
        assertEquals("\"***\"", ok("hiddenAttr"))
        val html = ok("formHtml")
        assertTrue(html, "hunter2" !in html && "tok-123" !in html && "482913" !in html && "otp-in-textarea" !in html && "shoes" in html && "leave at door" in html)
        blocked("byValue", "invalid_selector")
        // 秘密输入框接口上不在白名单里的属性(能算出值或长度的)也是 ***;照读的只有页面定好的设定,别的元素照常
        assertEquals("""["***","***","password","otp","INPUT",5]""", ok("fieldProps"))
        assertEquals("""["***","***","***","***","code","leave at door",13,"leave at door"]""", ok("textareas"))
    }

    @Test fun framesShadowRootsAndCopiesStayHidden() {
        // 同源 iframe 里的节点属于那个框架自己的一套接口:按原生 getter 认,值、隐藏字段、量长度的属性照样 ***,普通框照读
        assertEquals("""["***","***","***","hidden","frame-q"]""", ok("frameFields"))
        val frame = ok("frameHtml")
        assertTrue(frame, "tok-frame" !in frame && "frame-q" in frame)
        // shadow root 的 innerHTML 也打码
        val shadow = ok("shadowHtml")
        assertTrue(shadow, "shadow-default" !in shadow && "shadow-token" !in shadow && "password" in shadow)
        assertEquals("\"***\"", ok("shadowValue"))
        // 读取世界建起之后才被改成 text 的密码框:值和 HTML 里都还是 ***(打码看原件,副本上没有这份记录)
        assertEquals("\"***\"", ok("toggledValue"))
        val toggled = Json.parseToJsonElement(ok("toggledHtml")).jsonPrimitive.content
        assertTrue(toggled, "hunter2" !in toggled && "type=\"text\"" in toggled)
        // template 里的输入框也打码;SVG 里同名的元素没有内容,读 HTML 照常
        val template = ok("templateHtml")
        assertTrue(template, "tpl-secret" !in template && "<template>" in template)
        assertEquals("[true,false,false]", ok("svgTemplate"))
        // 属性节点:秘密输入框的 value 读出来是 ***,普通框照读
        assertEquals("""["***",["type=password","value=***"],"shoes"]""", ok("attrNode"))
        assertEquals("""["value","***","frame-q"]""", ok("frameAttr"))
        // 打码用的副本不建在页面文档里:读 HTML 不会构造页面的自定义元素,原件也不改
        val html = listOf("formHtml", "frameHtml", "shadowHtml", "toggledHtml", "templateHtml", "svgTemplate")
        for (n in html) assertEquals(n, 0, r(n).getValue("pageCode").jsonPrimitive.int)
        for (n in html) assertEquals(n, "false", r(n).s("pageChanged"))
    }

    @Test fun secretTextareaTextStaysHidden() {
        // 秘密 textarea 里的字是它的初始值:子文本节点(含 CDATA 段)读出来是 ***;外层元素、shadow root、同源 iframe 里的 textContent,
        // 没渲染出来的外层元素的 innerText,都在副本上打码再取;普通 textarea 的字照读
        assertEquals("""["***","***","***","***","***","***",1,"leave at door","leave"]""", ok("areaText"))
        assertEquals("""[4,"***","***","***","***","***"]""", ok("areaCdata"))
        assertEquals("""["***leave at doorcard","***leave at doorcard",false,true,"***","***"]""", ok("areaContainers"))
        // 拿在手里的子文本节点、value 属性节点:页面换掉字、删掉属性以后节点脱离文档,字还在上面,读出来也是 ***
        assertEquals("""[true,"***","***","***","***","***","***"]""", ok("heldArea"))
        assertEquals("""[true,"***","***"]""", ok("heldAreaIter"))
        assertEquals("""[true,"***","***","***"]""", ok("heldAttr"))
        // 拿到节点时框还不是秘密的:框后来变成秘密、字写进同一个节点、节点脱离文档以后,按那个框算
        assertEquals("""["leave at door","shoes"]""", ok("holdLate"))
        assertEquals("""[true,"***","***",true,"***","***"]""", ok("heldLate"))
        // a、option 的 text 一类按子孙的文字算;select 的 value 按页面上选中的那一项取
        assertEquals("""["Link ***","Card ***","Card ***","Card ***","Card ***",1,"One"]""", ok("areaDesc"))
        // option 带 value 属性时 value 是那个属性;output 的 value、defaultValue 也按子孙的文字算;没有选中项时 select 的 value 是空的
        assertEquals("""["v-attr","v-attr","Pick ***","Pick ***","Out ***","Out ***"]""", ok("areaDesc2"))
        assertEquals("""[-1,""]""", ok("noSelection"))
        // 排版出来的元素的 innerText 不含 textarea 里的字,照原样给;选项按自己的 text 写出来,这时在副本上取
        assertEquals("""["Code: ","Code: ","Code: ***","OneCard ***","Card ***"]""", ok("areaShown"))
        for (n in listOf("areaText", "areaCdata", "areaContainers", "heldArea", "heldAreaIter", "heldAttr", "heldLate", "areaDesc", "areaDesc2", "noSelection", "areaShown")) {
            assertEquals(n, 0, r(n).getValue("pageCode").jsonPrimitive.int)
            assertEquals(n, "false", r(n).s("pageChanged"))
        }
    }

    @Test fun valueSelectorsAreRefused() {
        // 转义、命名空间、注释、url(、:has() 里的写法都拦(和 Kotlin 版用同一批);别的选择器照常交给页面
        for (i in SelectorPolicyTest.BY_VALUE.indices) blocked("byValue$i", "invalid_selector")
        for (i in SelectorPolicyTest.NOT_BY_VALUE.indices) assertTrue("notByValue$i: ${r("notByValue$i")}", r("notByValue$i").s("error") != "invalid_selector")
        // 选择器、属性名不是字符串时只变一次字符串:检查的和交给页面的是同一个,toString 每次给不一样的也没用
        blocked("selectorObject", "invalid_selector")
        blocked("selectorArray", "invalid_selector")
        // 检查时转一次、交给浏览器时再转一次的话,toString 第二次换成按 value 选的就过去了
        assertEquals("2", ok("selectorFlip"))
        assertEquals("null", ok("attrFlip"))
    }

    @Test fun noNetworkAtAll() {
        // 同源 GET 也不行;别的发请求的路子(XHR、sendBeacon、图片、WebSocket、EventSource)一样报 blocked_network,一个请求也没发出去
        for (n in listOf("post", "body", "crossOrigin", "get", "plainFetch", "xhr", "beacon", "image", "socket", "events")) {
            blocked(n, "blocked_network")
            assertTrue(n, r(n).s("message").orEmpty().contains("web_capture"))
            assertEquals(n, 0, (r(n).getValue("requests") as JsonArray).size)
        }
        // 读页面已有的东西照常
        assertEquals("\"test\"", ok("readNavigator"))
    }

    @Test fun stateAndArgs() {
        ok("stateSet")
        assertEquals("42", ok("stateGet"))
        assertEquals("\"shoes!\"", ok("args"))
        assertEquals("script_error", r("argsFrozen").s("error"))
    }

    @Test fun noWayOut() {
        assertEquals("""["undefined","undefined","undefined","undefined"]""", ok("hiddenGlobals"))
        blocked("stringCode", "read_only")
        assertEquals("script_error", r("patchBuiltin").s("error"))
        // 全局名改不了:改写 String 能让属性名的检查、秘密输入框的判断失效,之后的读脚本也一样
        assertEquals("script_error", r("rebindGlobal").s("error"))
        assertEquals("""["1","***"]""", ok("afterRebind"))
    }

    @Test fun resultsAreCappedAndErrorsLocated() {
        assertEquals("\"xxxxxxxxx", ok("big"))
        assertEquals("true", r("big").s("truncated"))
        assertEquals("script_error", r("syntax").s("error"))
        assertEquals("2", r("errorLine").s("line"))
    }
}
