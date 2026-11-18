package com.libretv.tv.data

import com.libretv.tv.domain.model.BackupBundle
import kotlinx.serialization.json.Json

/**
 * 备份包的编解码。
 *
 * 备份是**给人看的文件**：格式要稳、坏文件要能给出人话错误，而不是
 * 抛一个 `SerializationException` 给用户看。
 */
object BackupCodec {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 编码成 JSON 文本。 */
    fun encode(bundle: BackupBundle): String = json.encodeToString(BackupBundle.serializer(), bundle)

    /**
     * 解码 JSON 文本。
     *
     * 版本不在支持列表里直接拒绝——宁可让观众知道「这个备份来自更新的版本」，
     * 也不要导进来一半才发现字段对不上。
     */
    fun decode(text: String): Result<BackupBundle> {
        val body = text.trim()
        if (body.isEmpty()) return Result.failure(BackupFormatException("备份文件是空的"))
        val bundle = runCatching { json.decodeFromString(BackupBundle.serializer(), body) }
            .getOrElse {
                return Result.failure(BackupFormatException("这不是有效的备份文件（JSON 解析失败）"))
            }
        if (bundle.version !in BackupBundle.SUPPORTED_VERSIONS) {
            return Result.failure(
                BackupFormatException("备份版本 ${bundle.version} 不受支持，请升级应用后再导入")
            )
        }
        return Result.success(bundle)
    }
}

/** 备份格式错误（给人看的提示）。 */
class BackupFormatException(message: String) : Exception(message)
