package net.ankio.auto.adapter.ezb

import org.ezbook.server.constant.BillType
import java.math.BigDecimal

/** Pure mapping shared by Android and JVM tests. v2.0.1 IDs are strings. */
object EzbMapping {
    data class Account(val id: String, val name: String, val currency: String)
    data class Category(val id: String, val name: String, val parentId: String,
                        val parentName: String, val type: Int)
    data class Tag(val id: String, val name: String)
    data class Parent(val id: String, val name: String, val type: Int)
    data class Catalog(val accounts: List<Account>, val categories: List<Category>, val tags: List<Tag>,
                       val parents: List<Parent> = emptyList())
    data class Bill(val id: Long, val type: BillType, val money: Double, val time: Long,
                    val parent: String, val child: String, val from: String, val to: String,
                    val shop: String = "", val item: String = "", val remark: String = "",
                    val fee: Double = 0.0, val tags: List<String> = emptyList(), val currency: String = "CNY")

    fun type(type: BillType): Int = when (type) {
        BillType.Expend, BillType.ExpendReimbursement, BillType.ExpendLending, BillType.ExpendRepayment -> 3
        BillType.Income, BillType.IncomeLending, BillType.IncomeRepayment,
        BillType.IncomeRefund, BillType.IncomeReimbursement -> 2
        BillType.Transfer -> 4
    }
    fun cents(money: Double): Long {
        require(money.isFinite() && money > 0 && money * 100 < Long.MAX_VALUE.toDouble()) { "金额无效" }
        return Math.round(money * 100).also { require(it > 0) { "金额不足 1 分" } }
    }
    fun seconds(time: Long, now: Long): Long = (if (time > 0) time else now) / 1000
    fun category(categories: List<Category>, type: Int, parent: String, child: String, defaultId: String): Category {
        val eligible = categories.filter { it.type == type - 1 && it.parentId != "0" }
        val children = eligible.filter { it.parentName == parent }
        return children.firstOrNull { it.name == child }
            ?: children.firstOrNull { it.name == "其他" }
            ?: children.firstOrNull()
            ?: eligible.firstOrNull { it.id == defaultId }
            ?: throw IllegalArgumentException("没有匹配的${when(type) { 2 -> "收入"; 4 -> "转账"; else -> "支出" }}二级分类，请在服务器创建并同步，或选择同类型默认分类")
    }
    private fun exact(accounts: List<Account>, name: String): Account? {
        val matches = accounts.filter { it.name == name }
        require(matches.size <= 1) { "账户名称重复，请在服务器使用唯一账户名" }
        return matches.singleOrNull()
    }
    fun accounts(accounts: List<Account>, from: String, to: String, defaultId: String, transfer: Boolean): Pair<Account, Account?> {
        val source = exact(accounts, from) ?: if (transfer) null else accounts.firstOrNull { it.id == defaultId }
        requireNotNull(source) { if (transfer) "转账转出账户未匹配，请同步资产并检查账户名" else "账户未匹配，请配置默认账户" }
        val dest = if (transfer) exact(accounts, to) else null
        require(!transfer || dest != null) { "转账转入账户未匹配，请同步资产并检查账户名" }
        require(dest == null || source.id != dest.id) { "转账不能使用同一账户" }
        return source to dest
    }
    private fun takeCharacters(text: String, count: Int): String =
        text.substring(0, text.offsetByCodePoints(0, minOf(count, text.codePointCount(0, text.length))))
    fun comment(shop: String, item: String, remark: String, fee: Double): String {
        require(fee.isFinite()) { "手续费无效" }
        val base = listOf(shop, item, remark).map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" ")
        val suffix = if (fee != 0.0) "（优惠/手续费 ${BigDecimal.valueOf(fee).stripTrailingZeros().toPlainString()} 元）" else ""
        return takeCharacters(base, 255 - suffix.codePointCount(0, suffix.length)) + suffix
    }
    fun request(bill: Bill, catalog: Catalog, defaultAccount: String, defaultCategory: String,
                now: Long, utcOffset: Int): Map<String, Any> {
        val type = type(bill.type)
        val (source, dest) = accounts(catalog.accounts, bill.from, bill.to, defaultAccount, type == 4)
        require(source.currency == bill.currency && (dest == null || dest.currency == source.currency)) {
            "账单与账户币种不一致，暂不自动换汇"
        }
        val category = category(catalog.categories, type, bill.parent, bill.child, defaultCategory)
        val amount = cents(bill.money)
        return linkedMapOf<String, Any>(
            "type" to type, "categoryId" to category.id,
            "time" to seconds(bill.time, now), "utcOffset" to utcOffset,
            "sourceAccountId" to source.id, "sourceAmount" to amount,
            "tagIds" to catalog.tags.filter { it.name in bill.tags }.map { it.id }.distinct(),
            "comment" to comment(bill.shop, bill.item, bill.remark, bill.fee),
            "clientSessionId" to "autoacc-${bill.id}-${bill.time}"
        ).apply {
            if (dest != null) { put("destinationAccountId", dest.id); put("destinationAmount", amount) }
        }
    }
}
