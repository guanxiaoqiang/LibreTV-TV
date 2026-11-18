package com.libretv.tv.data.local

import android.content.Context
import java.io.File

/**
 * 备份文件落地。
 *
 * 文件写在 cacheDir/backups 下：备份是临时产物，分享完就没用了，不该在
 * 设备上留一份常驻副本。
 */
class BackupFiles(context: Context) {

    private val app = context.applicationContext

    /** 备份文件目录。 */
    val dir: File = File(app.cacheDir, DIR_NAME)

    /**
     * 写出一份备份。
     *
     * 文件名带上导出时间：连续导出两次不会互相覆盖，观众的相册里也能一眼
     * 分清是哪一份。
     */
    fun write(text: String, exportedAt: Long): File {
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "libretv-backup-$exportedAt.json")
        file.writeText(text)
        return file
    }

    private companion object {
        const val DIR_NAME = "backups"
    }
}
