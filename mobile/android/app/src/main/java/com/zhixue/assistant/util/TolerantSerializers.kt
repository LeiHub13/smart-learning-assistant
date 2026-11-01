package com.zhixue.assistant.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 后端同一个字段在不同接口可能是 true/false、0/1，也可能带引号
 * （例如 Map 拼装出来的 inHub/enrolled 来自 Integer，实体里又是 Boolean）。
 * 这些容错序列化器保证不会因为类型抖动而整包解析失败。
 */
object TolerantBoolean : KSerializer<Boolean?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("TolerantBoolean", PrimitiveKind.BOOLEAN)

    override fun serialize(encoder: Encoder, value: Boolean?) = encoder.encodeBoolean(value ?: false)

    override fun deserialize(decoder: Decoder): Boolean? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        val element = json.decodeSerializableValue(kotlinx.serialization.json.JsonElement.serializer())
        return when (element) {
            is JsonNull -> null
            is JsonPrimitive -> element.booleanOrNull ?: element.intOrNull?.let { it != 0 }
                ?: element.content.lowercase().let { it == "true" || it == "1" || it == "yes" }
            else -> null
        }
    }
}

object TolerantBooleanNonNull : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("TolerantBooleanNonNull", PrimitiveKind.BOOLEAN)

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)

    override fun deserialize(decoder: Decoder): Boolean = TolerantBoolean.deserialize(decoder) ?: false
}

/** 同理，数量类字段偶尔以字符串下发。 */
object TolerantLong : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("TolerantLong", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long?) = encoder.encodeLong(value ?: 0L)

    override fun deserialize(decoder: Decoder): Long? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeLong()
        val element = json.decodeSerializableValue(kotlinx.serialization.json.JsonElement.serializer())
        return (element as? JsonPrimitive)?.let { it.content.toLongOrNull() }
    }
}

object TolerantInt : KSerializer<Int?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("TolerantInt", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Int?) = encoder.encodeInt(value ?: 0)

    override fun deserialize(decoder: Decoder): Int? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeInt()
        val element = json.decodeSerializableValue(kotlinx.serialization.json.JsonElement.serializer())
        return (element as? JsonPrimitive)?.let { it.content.toIntOrNull() }
    }
}

object TolerantIntNonNull : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("TolerantIntNonNull", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)

    /** 对话厅的 role 在 REST 里是 0/1，在 WebSocket 的 AI 帧里是字符串 "ai"，两者都要能吃下。 */
    override fun deserialize(decoder: Decoder): Int = TolerantInt.deserialize(decoder) ?: 0
}

object TolerantLongNonNull : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("TolerantLongNonNull", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)

    override fun deserialize(decoder: Decoder): Long = TolerantLong.deserialize(decoder) ?: 0L
}
