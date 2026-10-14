package net.ankio.auto.ui.dialog

import android.content.Context
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ankio.auto.adapter.ezb.*
import net.ankio.auto.databinding.DialogEzbConfigBinding
import net.ankio.auto.ui.api.BaseSheetDialog
import net.ankio.auto.ui.utils.ToastUtils

class EzbConfigDialog(context: Context) : BaseSheetDialog<DialogEzbConfigBinding>(context) {
    private var saved = EzbConfig()
    private var tested: EzbConfig? = null
    private var accountId = ""
    private var categoryId = ""

    override fun onViewCreated(view: View?) {
        super.onViewCreated(view)
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        try { saved = EzbConfigStore.load() }
        catch (e: EzbFailure) { binding.status.text = e.message }
        binding.serverUrl.setText(saved.url)
        binding.apiToken.setText(saved.token)
        binding.testConnection.setOnClickListener { test() }
        binding.saveConfig.setOnClickListener {
            try {
                val url = EzbClient.normalizeUrl(binding.serverUrl.text.toString())
                val token = binding.apiToken.text.toString().trim()
                if (tested?.url != url || tested?.token != token) throw EzbFailure("请先测试当前地址和令牌")
                if (accountId.isBlank() || categoryId.isBlank()) throw EzbFailure("请选择默认账户和二级分类")
                EzbConfigStore.save(EzbConfig(url, token, accountId, categoryId))
                ToastUtils.info("连接配置已保存，请点击首页刷新按钮同步资产")
                dismiss()
            } catch (e: EzbFailure) { binding.status.text = e.message }
            catch (_: Exception) { binding.status.text = "保存失败，请重试" }
        }
    }

    private fun test() {
        val config: EzbConfig
        try {
            config = EzbConfig(EzbClient.normalizeUrl(binding.serverUrl.text.toString()), binding.apiToken.text.toString().trim())
        } catch (e: EzbFailure) { binding.status.text = e.message; return }
        tested = null
        binding.testConnection.isEnabled = false
        binding.saveConfig.isEnabled = false
        binding.status.text = "正在连接…"
        launch {
            try {
                val data = withContext(Dispatchers.IO) { EzbClient(config).catalog() }
                if (!uiReady()) return@launch
                tested = config
                // IDs are scoped to this connection; never carry defaults to a different server/user.
                val sameConnection = saved.url == config.url && saved.token == config.token
                val account = data.accounts.firstOrNull { sameConnection && it.id == saved.accountId }
                    ?: data.accounts.singleOrNull { it.name == "个人" }
                val category = data.categories.firstOrNull { sameConnection && it.id == saved.categoryId }
                accountId = account?.id ?: ""
                categoryId = category?.id ?: ""
                val accountLabels = data.accounts.map { "${it.name} (${it.currency})" }
                val categoryLabels = data.categories.map {
                    "${when(it.type) { 1 -> "收入"; 2 -> "支出"; else -> "转账" }} · ${it.parentName}-${it.name}"
                }
                binding.defaultAccount.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, accountLabels))
                binding.defaultCategory.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_dropdown_item_1line, categoryLabels))
                binding.defaultAccount.setText(account?.let { accountLabels[data.accounts.indexOf(it)] } ?: "", false)
                binding.defaultCategory.setText(category?.let { categoryLabels[data.categories.indexOf(it)] } ?: "", false)
                binding.defaultAccount.setOnItemClickListener { _, _, position, _ -> accountId = data.accounts[position].id }
                binding.defaultCategory.setOnItemClickListener { _, _, position, _ -> categoryId = data.categories[position].id }
                val count = data.categories.size + data.parents.size
                binding.status.text = "✓ 连接成功：账户 ${data.accounts.size} 个，分类 $count 个"
            } catch (e: EzbFailure) { if (uiReady()) binding.status.text = e.message }
            catch (_: Exception) { if (uiReady()) binding.status.text = "连接未完成，请重试" }
            finally {
                if (uiReady()) {
                    binding.testConnection.isEnabled = true
                    binding.saveConfig.isEnabled = true
                }
            }
        }
    }
}
