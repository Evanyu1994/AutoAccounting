package net.ankio.auto.adapter.ezb

import com.google.gson.Gson
import org.ezbook.server.constant.BillType
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EzbMappingTest {
    private val accounts = listOf(EzbMapping.Account("10", "微信", "CNY"), EzbMapping.Account("11", "个人", "CNY"))
    private val categories = listOf(
        EzbMapping.Category("21", "早餐", "20", "餐饮", 2),
        EzbMapping.Category("22", "其他", "20", "餐饮", 2),
        EzbMapping.Category("31", "日用", "30", "购物", 2),
        EzbMapping.Category("41", "薪资", "40", "收入", 1),
        EzbMapping.Category("51", "转账", "50", "转账", 3))
    private val catalog = EzbMapping.Catalog(accounts, categories, listOf(EzbMapping.Tag("60", "午餐")))
    private fun expense() = EzbMapping.Bill(123, BillType.Expend, 0.01, 1800000000000,
        "餐饮", "其他", "微信", "", remark = "【对接测试】AutoAccounting")

    @Test fun allBillTypes() {
        val expected = mapOf(BillType.Expend to 3, BillType.ExpendReimbursement to 3,
            BillType.ExpendLending to 3, BillType.ExpendRepayment to 3, BillType.Income to 2,
            BillType.IncomeLending to 2, BillType.IncomeRepayment to 2, BillType.IncomeRefund to 2,
            BillType.IncomeReimbursement to 2, BillType.Transfer to 4)
        assertEquals(BillType.entries.toSet(), expected.keys)
        expected.forEach { (type, code) -> assertEquals(code, EzbMapping.type(type)) }
    }
    @Test fun amountsRoundToCents() {
        assertEquals(30L, EzbMapping.cents(0.1 + 0.2))
        assertEquals(1L, EzbMapping.cents(0.01))
        assertEquals(124L, EzbMapping.cents(1.236))
    }
    @Test fun invalidAmountsRejected() {
        listOf(0.0, -1.0, 0.001, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE).forEach {
            assertThrows(IllegalArgumentException::class.java) { EzbMapping.cents(it) }
        }
    }
    @Test fun timeUsesSecondsAndFallback() {
        assertEquals(123L, EzbMapping.seconds(123999, 999000))
        assertEquals(999L, EzbMapping.seconds(0, 999000))
        assertEquals(999L, EzbMapping.seconds(-1, 999000))
    }
    @Test fun categoryFourFallbackLevels() {
        assertEquals("21", EzbMapping.category(categories, 3, "餐饮", "早餐", "31").id)
        assertEquals("22", EzbMapping.category(categories, 3, "餐饮", "午餐", "31").id)
        assertEquals("31", EzbMapping.category(categories, 3, "购物", "未知", "21").id)
        assertEquals("21", EzbMapping.category(categories, 3, "不存在", "未知", "21").id)
    }
    @Test fun categoryCannotCrossTypeOrUseParent() {
        assertThrows(IllegalArgumentException::class.java) { EzbMapping.category(categories, 2, "餐饮", "早餐", "21") }
        assertThrows(IllegalArgumentException::class.java) { EzbMapping.category(categories, 3, "未知", "", "20") }
        assertThrows(IllegalArgumentException::class.java) { EzbMapping.category(emptyList(), 4, "", "", "") }
    }
    @Test fun accountFallbackAndTransfer() {
        assertEquals("10", EzbMapping.accounts(accounts, "微信", "", "11", false).first.id)
        assertEquals("11", EzbMapping.accounts(accounts, "未知", "", "11", false).first.id)
        assertEquals("11", EzbMapping.accounts(accounts, "微信", "个人", "", true).second?.id)
        assertThrows(IllegalArgumentException::class.java) { EzbMapping.accounts(accounts, "未知", "个人", "11", true) }
        assertThrows(IllegalArgumentException::class.java) { EzbMapping.accounts(accounts, "微信", "未知", "11", true) }
        assertThrows(IllegalArgumentException::class.java) { EzbMapping.accounts(accounts, "微信", "微信", "11", true) }
        assertThrows(IllegalArgumentException::class.java) { EzbMapping.accounts(accounts, "未知", "", "", false) }
    }
    @Test fun duplicateAccountNamesFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { EzbMapping.accounts(accounts + accounts[0], "微信", "", "11", false) }
    }
    @Test fun commentDeduplicationAndFee() {
        assertEquals("店 茶", EzbMapping.comment(" 店 ", "茶", "店", 0.0))
        assertEquals("店（优惠/手续费 -0.5 元）", EzbMapping.comment("店", "", "", -0.5))
        assertEquals("", EzbMapping.comment("", " ", "", 0.0))
    }
    @Test fun commentLimitPreservesUnicodeAndFee() {
        val text = EzbMapping.comment("😀".repeat(300), "", "", 1.0)
        assertEquals(255, text.codePointCount(0, text.length))
        assertTrue(text.endsWith("（优惠/手续费 1 元）"))
        assertEquals(255, EzbMapping.comment("字".repeat(300), "", "", 0.0).length)
    }
    @Test fun transferAndTags() {
        val bill = expense().copy(type = BillType.Transfer, parent = "转账", child = "转账", to = "个人", tags = listOf("午餐", "未知"))
        val body = EzbMapping.request(bill, catalog, "", "", 1, 480)
        assertEquals(body["sourceAmount"], body["destinationAmount"])
        assertEquals("11", body["destinationAccountId"])
        assertEquals(listOf("60"), body["tagIds"])
        assertEquals("autoacc-123-1800000000000", body["clientSessionId"])
    }
    @Test fun currencyMismatchRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            EzbMapping.request(expense().copy(currency = "USD"), catalog, "", "", 1, 480)
        }
    }
    @Test fun exportActualE2eRequestTemplate() {
        val body = EzbMapping.request(expense(), catalog, "", "", 1, 480)
        assertEquals(3, body["type"])
        assertEquals(1L, body["sourceAmount"])
        assertEquals("22", body["categoryId"])
        assertFalse(body.containsKey("destinationAccountId"))
        // The E2E script consumes the body from the actual Kotlin mapper; only live IDs/time change.
        File("build/ezb-e2e-request.json").apply { parentFile.mkdirs(); writeText(Gson().toJson(body)) }
    }
}
