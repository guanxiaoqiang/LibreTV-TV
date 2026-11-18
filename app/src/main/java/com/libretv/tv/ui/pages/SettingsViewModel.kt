package com.libretv.tv.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.libretv.tv.di.AppContainer
import com.libretv.tv.di.SettingsSnapshot
import com.libretv.tv.domain.model.BackupBundle
import com.libretv.tv.domain.model.BackupMergeOptions
import com.libretv.tv.domain.model.BackupMergePlan
import com.libretv.tv.domain.model.BadgeTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 设置页 ViewModel。
 *
 * 设置是应用级的，所以状态直接来自容器；这一页只是它的编辑界面。
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<SettingsSnapshot> = container.settings

    fun setAdFilterEnabled(enabled: Boolean) = container.setAdFilterEnabled(enabled)

    fun setDebugLogEnabled(enabled: Boolean) = container.setDebugLogEnabled(enabled)

    fun setPosterBadgesEnabled(enabled: Boolean) = container.setPosterBadgesEnabled(enabled)

    fun setBadgeVisible(tag: BadgeTag, visible: Boolean) = container.setBadgeVisible(tag, visible)

    /** 跳过标记总开关。 */
    fun setSkipMarksEnabled(enabled: Boolean) = container.setSkipMarksEnabled(enabled)

    /** 导出备份（文件写好后交给系统分享）。 */
    fun exportBackup() = container.exportBackupFile()

    private val _importState = MutableStateFlow<BackupImportState>(BackupImportState.Idle)

    /** 导入状态：预览差异 / 出错 / 完成。 */
    val importState: StateFlow<BackupImportState> = _importState.asStateFlow()

    /**
     * 解析一份备份文件并算出差异。
     *
     * 只解析不落盘：观众在预览里看清「会新增什么、会跳过什么」再决定。
     */
    fun importBackupText(text: String) {
        viewModelScope.launch {
            val bundle = container.decodeBackup(text).getOrElse { error ->
                _importState.value = BackupImportState.Error(error.message ?: "备份文件无法解析")
                return@launch
            }
            _importState.value = BackupImportState.Preview(
                bundle = bundle,
                options = BackupMergeOptions(),
                plan = container.previewBackup(bundle, BackupMergeOptions())
            )
        }
    }

    /** 改合并选项（覆盖历史 / 覆盖收藏 / 导入设置）后重算预览。 */
    fun setImportOptions(options: BackupMergeOptions) {
        val current = _importState.value as? BackupImportState.Preview ?: return
        _importState.value = current.copy(
            options = options,
            plan = container.previewBackup(current.bundle, options)
        )
    }

    /** 确认导入。 */
    fun confirmImport() {
        val current = _importState.value as? BackupImportState.Preview ?: return
        viewModelScope.launch {
            val plan = container.applyBackup(current.bundle, current.options)
            _importState.value = BackupImportState.Done(plan)
            container.message(
                "导入完成：新增源 ${plan.addedSourceCount} 个、历史 ${plan.addedHistoryCount} 条、收藏 ${plan.addedFavoriteCount} 条"
            )
        }
    }

    /** 关闭导入预览。 */
    fun dismissImport() {
        _importState.value = BackupImportState.Idle
    }
}

/** 备份导入的状态机。 */
sealed class BackupImportState {

    /** 没在导入。 */
    object Idle : BackupImportState()

    /** 解析失败。 */
    data class Error(val message: String) : BackupImportState()

    /** 解析成功，等观众确认。 */
    data class Preview(
        val bundle: BackupBundle,
        val options: BackupMergeOptions,
        val plan: BackupMergePlan
    ) : BackupImportState()

    /** 导入完成。 */
    data class Done(val plan: BackupMergePlan) : BackupImportState()
}
