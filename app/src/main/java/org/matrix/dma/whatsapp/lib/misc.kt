package org.matrix.dma.whatsapp.lib

import okhttp3.JavaNetCookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit

inline fun makeCookieManager(): CookieManager {
    return CookieManager(null, CookiePolicy.ACCEPT_ALL)
}
val HTTP_CLIENT = OkHttpClient.Builder().cookieJar(JavaNetCookieJar(makeCookieManager()))
    .readTimeout(30L, TimeUnit.SECONDS)
    .build()
val JSON = "application/json; charset=utf-8".toMediaType()
