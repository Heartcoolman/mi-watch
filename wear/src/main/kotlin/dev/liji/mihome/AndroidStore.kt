package dev.liji.mihome

import android.content.Context
import dev.liji.mihome.core.Store

/**
 * 用普通 SharedPreferences，不用 EncryptedSharedPreferences：
 * Jetpack Security 已废弃且无维护后继；它会拖入 Tink 依赖树（对本机只能走
 * aliyun 镜像的构建是新的失败面）；最关键的是它的失败模式更糟——Keystore
 * 密钥失效会让读取抛异常，唯一恢复手段是清空重新登录，正好是最想避免的事。
 *
 * 真正管用的防护在别处：清单里 allowBackup=false + dataExtractionRules 全排除，
 * 以及 Flog 对凭证脱敏。
 */
class AndroidStore(ctx: Context) : Store {
    private val sp = ctx.getSharedPreferences("mihome", Context.MODE_PRIVATE)

    override fun get(k: String): String? = sp.getString(k, null)?.takeIf { it.isNotEmpty() }

    override fun set(k: String, v: String?) {
        sp.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply()
    }
}
