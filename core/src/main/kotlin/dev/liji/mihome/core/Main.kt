package dev.liji.mihome.core

/**
 * 桌面验证入口。Stage 2/3 会在这里长出 login-pw / login-qr / probe / devices / spec /
 * controls / get / set 等子命令——目的是让整条小米协议在 Mac 上跑通、真的把灯点亮，
 * 之后才开始写 Android 代码。
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "selftest" -> {
            val nonce = MiCrypto.randomNonce()
            val deviceId = MiCrypto.randomDeviceId()
            println("nonce      = $nonce")
            println("deviceId   = $deviceId")
            println("signedNonce= ${MiCrypto.signedNonce("MDEyMzQ1Njc4OWFiY2RlZg==", nonce)}")
        }
        else -> println("usage: selftest | (login-pw|login-qr|probe|devices|spec|controls|get|set 待实现)")
    }
}
