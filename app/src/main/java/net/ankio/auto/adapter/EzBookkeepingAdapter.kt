package net.ankio.auto.adapter

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.ankio.auto.App
import net.ankio.auto.adapter.ezb.*
import net.ankio.auto.constant.BookFeatures
import net.ankio.auto.http.api.AssetsAPI
import net.ankio.auto.http.api.BookNameAPI
import net.ankio.auto.http.api.CategoryAPI
import net.ankio.auto.http.api.TagAPI
import net.ankio.auto.storage.Logger
import net.ankio.auto.ui.utils.ToastUtils
import org.ezbook.server.constant.BillAction
import org.ezbook.server.constant.BillType
import org.ezbook.server.constant.Currency
import org.ezbook.server.db.model.*
import org.ezbook.server.tools.MD5HashTable

class EzBookkeepingAdapter : IAppAdapter {
    override val pkg = "com.aerybaby.books"
    override val link = "https://books.aerybaby.com/"
    override val icon = ""
    override val name = "ezBookkeeping"
    override val desc = "自托管记账，账单直接写入你的服务器"
    override fun features() = listOf(BookFeatures.ASSET_MANAGE, BookFeatures.TAG)
    override fun supportSyncAssets() = true
    override fun sleep() = 0L
    override fun syncWaitBills(billAction: BillAction, bookName: String) = Unit

    companion object {
        // adapterList creates new instances: the lock and cache must be shared across all instances.
        private val lock = Any()
        private var cachedConfig: EzbConfig? = null
        private var cachedCatalog: EzbMapping.Catalog? = null
        private fun catalog(config: EzbConfig, refresh: Boolean = false): EzbMapping.Catalog {
            if (refresh || cachedConfig?.url != config.url || cachedConfig?.token != config.token || cachedCatalog == null) {
                cachedCatalog = EzbClient(config).catalog()
                cachedConfig = config
            }
            return requireNotNull(cachedCatalog)
        }
        private fun failure(error: Exception) {
            val reason = when (error) {
                is EzbFailure, is IllegalArgumentException -> error.message ?: "请求未完成"
                else -> "内部处理失败，请重新打开连接设置"
            }
            // No exception object, response body, request, URL or credentials in logs/crash reports.
            Logger.e("ezBookkeeping 同步失败：$reason")
            App.launch { ToastUtils.error("同步到 ezBookkeeping 失败：$reason") }
        }
    }

    override fun syncAssets() {
        App.launchIO {
            try {
                synchronized(lock) {
                    runBlocking(Dispatchers.IO) {
                        val data = catalog(EzbConfigStore.load(), true)
                        val book = BookNameModel().apply { remoteId = "ezb"; name = "默认账本" }
                        BookNameAPI.put(arrayListOf(book), MD5HashTable.md5("ezb"))
                        val assets = ArrayList(data.accounts.map { account ->
                            AssetsModel().apply {
                                name = account.name
                                currency = Currency.valueOf(account.currency)
                            }
                        })
                        val categories = mutableListOf<CategoryModel>()
                        data.parents.forEach { c ->
                            categories.add(CategoryModel().apply {
                                name = c.name; remoteId = c.id; remoteParentId = "-1"
                                remoteBookId = "ezb"; type = localType(c.type)
                            })
                        }
                        data.categories.forEach { c ->
                            categories.add(CategoryModel().apply {
                                name = c.name; remoteId = c.id; remoteParentId = c.parentId
                                remoteBookId = "ezb"; type = localType(c.type)
                            })
                        }
                        AssetsAPI.put(assets, MD5HashTable.md5(Gson().toJson(assets)))
                        CategoryAPI.put(categories, MD5HashTable.md5(Gson().toJson(categories)))
                        TagAPI.batchInsert(data.tags.map { t -> TagModel().apply { name = t.name } })
                        // Existing put APIs swallow errors; verify persistence before showing success.
                        if (AssetsAPI.list().map { it.name }.sorted() != data.accounts.map { it.name }.sorted() ||
                            BookNameAPI.list().none { it.remoteId == "ezb" }) throw EzbFailure("本地资产保存失败，请稍后再同步")
                        if (TagAPI.all().map { it.name }.sorted() != data.tags.map { it.name }.sorted()) throw EzbFailure("本地标签保存失败")
                        for (type in listOf(BillType.Income, BillType.Expend, BillType.Transfer)) {
                            val parents = CategoryAPI.list("ezb", type, "-1")
                            val expected = categories.filter { it.type == type && it.remoteParentId == "-1" }
                            if (parents.map { it.remoteId }.toSet() != expected.map { it.remoteId }.toSet()) throw EzbFailure("本地分类保存失败")
                            for (parent in parents) {
                                if (CategoryAPI.list("ezb", type, parent.remoteId).map { it.remoteId }.toSet() !=
                                    categories.filter { it.remoteParentId == parent.remoteId }.map { it.remoteId }.toSet()) throw EzbFailure("本地子分类保存失败")
                            }
                        }
                        App.launch { ToastUtils.info("已从 ezBookkeeping 同步：账户 ${data.accounts.size} 个、分类 ${categories.size} 个") }
                    }
                }
            } catch (e: Exception) { failure(e) }
        }
    }

    private fun localType(type: Int) = when (type) { 1 -> BillType.Income; 2 -> BillType.Expend; else -> BillType.Transfer }

    override fun syncBill(billInfoModel: BillInfoModel) {
        // Return only after the request finishes, so BillTool's batch loop stays sequential.
        runBlocking(Dispatchers.IO) {
            synchronized(lock) {
                try {
                    val config = EzbConfigStore.load()
                    val (parent, child) = billInfoModel.categoryPair()
                    val bill = EzbMapping.Bill(billInfoModel.id, billInfoModel.type, billInfoModel.money,
                        billInfoModel.time, parent, child, billInfoModel.accountNameFrom, billInfoModel.accountNameTo,
                        billInfoModel.shopName, billInfoModel.shopItem, billInfoModel.remark, billInfoModel.fee,
                        billInfoModel.getTagList(), billInfoModel.currencyCode())
                    var data = catalog(config)
                    val type = EzbMapping.type(bill.type)
                    // Refresh before any fallback, including unknown account/tag names.
                    if (data.categories.none { it.type == type - 1 && it.parentName == parent && it.name == child } ||
                        data.accounts.none { it.name == bill.from } ||
                        (type == 4 && data.accounts.none { it.name == bill.to }) ||
                        bill.tags.any { tag -> data.tags.none { it.name == tag } }) data = catalog(config, true)
                    val body = EzbMapping.request(bill, data, config.accountId, config.categoryId,
                        System.currentTimeMillis(), EzbClient.offset())
                    EzbClient(config).call("transactions/add.json", body)
                    AppAdapterManager.markSynced(billInfoModel)
                } catch (e: Exception) { failure(e) }
            }
        }
    }
}
