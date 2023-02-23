package org.matrix.dma.whatsapp.lib

import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest

typealias ChatID = String
typealias UserID = String // .user.id

const val HARDCODED_LOCALPART = "demo"
const val HARDCODED_NAMESPACE_PREFIX = "wa_"
const val HARDCODED_APPSERVICE_ID = "whatsapp"
const val MATRIX_NAMESPACE = "org.matrix.dma.whatsapp"

fun getLocalpartForChatId(chatId: ChatID): String {
    return "$HARDCODED_NAMESPACE_PREFIX${whatsappEncodedId(chatId)}"
}

fun getLocalpartForUserId(userId: UserID): String {
    return "$HARDCODED_NAMESPACE_PREFIX${whatsappEncodedId(userId)}"
}

fun whatsappEncodedId(jid: String): String {
    val md5 = MessageDigest.getInstance("MD5")
    md5.update(jid.toByteArray())
    return BigInteger(1, md5.digest()).toString(16)
}

fun getIdEventContent(chatId: ChatID): JSONObject {
    return JSONObject().put("jid", chatId)
}

fun stateIdToChatId(id: JSONObject): ChatID {
    return id.getString("jid")
}