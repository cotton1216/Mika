package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

/**
 * 换地址:公网入口连不上时切到内网入口,Key、登录态、模型、用途都不该受影响;
 * 旧地址要留着,方便切回去。地址格式不对则拒绝,不能把服务商改成连不上的状态。
 */
class BaseUrlSwitchTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val publicUrl = "https://ai.thchengtay.com/aiserver/v1"
    private val internalUrl = "http://10.10.117.5:8088/aiserver/v1"

    private fun store() = ProviderStore(
        tmp.root, AesGcmKeyCipher { key },
        userAgent = "Yuan/1.2.3",
        modelLister = { _, _, _, _ -> ModelListResult.Ok(listOf(ListedModel("gpt-6-astra"))) },
    )

    private fun ProviderStore.ruyi(): Provider = connect(
        "如意", ProviderConfig.Kind.OpenAiCompat, publicUrl, "sk-test", listOf(ListedModel("gpt-6-astra")),
    )

    @Test fun switchingAddressKeepsKeyAndMovesOldOneToHistory() {
        val store = store()
        val p = store.ruyi()
        store.use(ModelRole.Main, p.id, "gpt-6-astra", Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false))

        assertTrue(store.changeBaseUrl(p.id, internalUrl))

        val after = store.provider(p.id)!!
        assertEquals(internalUrl, after.baseUrl)
        assertEquals(listOf(publicUrl), after.savedBaseUrls)
        // Key 按服务商 id 存,换地址不影响;调用用的配置也得跟着换,否则还往旧入口发
        assertTrue(store.hasKey(p.id))
        assertEquals(internalUrl, store.configFor(ModelRole.Main)?.baseUrl)
        assertEquals("gpt-6-astra", store.configFor(ModelRole.Main)?.model)
    }

    @Test fun switchingBackMovesAddressesBothWays() {
        val store = store()
        val p = store.ruyi()
        store.changeBaseUrl(p.id, internalUrl)
        // 末尾斜杠不该被当成另一个地址
        assertTrue(store.changeBaseUrl(p.id, "$publicUrl/"))

        val after = store.provider(p.id)!!
        assertEquals(publicUrl, after.baseUrl)
        assertEquals(listOf(internalUrl), after.savedBaseUrls)
    }

    @Test fun badAddressIsRejectedAndChangesNothing() {
        val store = store()
        val p = store.ruyi()

        assertFalse(store.changeBaseUrl(p.id, "10.10.117.5:8088/aiserver/v1"))
        assertFalse(store.changeBaseUrl(p.id, "   "))
        assertEquals(publicUrl, store.provider(p.id)!!.baseUrl)
        assertTrue(store.provider(p.id)!!.savedBaseUrls.isEmpty())
        assertFalse(store.changeBaseUrl("no-such-provider", internalUrl))
    }

    @Test fun historySurvivesRestartAndIsCapped() {
        val store = store()
        val p = store.ruyi()
        repeat(8) { store.changeBaseUrl(p.id, "https://node$it.example.com/v1") }

        val reopened = store()
        val after = reopened.provider(p.id)!!
        assertNotNull(after)
        assertTrue(after.savedBaseUrls.size <= MAX_SAVED_BASE_URLS)
        // 最近换过的在里面,最老的被挤掉
        assertTrue(after.savedBaseUrls.contains("https://node6.example.com/v1"))
        assertFalse(after.savedBaseUrls.contains(publicUrl))
    }

    /**
     * 上一版换地址不校验,能把「两个地址粘在一起」的串存成 baseUrl。切到新地址时这种串不该被收进历史,
     * 否则详情页的「用过的地址」会一直挂着一条点不了、还挤掉真实地址的垃圾。
     */
    @Test fun brokenAddressNeverEntersHistory() {
        val store = store()
        val broken = "http://10.10.117.5:8088/aiserver/v1http://10.10.117.5:8088/aiserver/v1https://ai.thchengtay.com/aiserver/v1"
        val p = store.connect("如意", ProviderConfig.Kind.OpenAiCompat, broken, "sk-test", listOf(ListedModel("gpt-6-astra")))

        assertTrue(store.changeBaseUrl(p.id, internalUrl))

        assertEquals(internalUrl, store.provider(p.id)!!.baseUrl)
        assertTrue(store.provider(p.id)!!.savedBaseUrls.isEmpty())
        assertFalse(ProviderConnection.isUsableUrl(broken))
        assertTrue(ProviderConnection.isUsableUrl(internalUrl))
    }
}
