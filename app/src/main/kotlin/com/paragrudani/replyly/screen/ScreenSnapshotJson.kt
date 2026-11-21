package com.paragrudani.replyly.screen

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant

/** The only way to turn a [ScreenSnapshot] into JSON and back. */
object ScreenSnapshotJson {
    private val json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = false
            explicitNulls = false
            // Old app builds must still read fixtures written by newer ones.
            ignoreUnknownKeys = true
        }

    fun encode(snapshot: ScreenSnapshot): String = json.encodeToString(ScreenSnapshot.serializer(), snapshot)

    fun decode(text: String): ScreenSnapshot = json.decodeFromString(ScreenSnapshot.serializer(), text)
}

internal object BoundsSerializer : KSerializer<Bounds> {
    private val pattern = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.paragrudani.replyly.Bounds", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Bounds,
    ) {
        encoder.encodeString("[${value.left},${value.top}][${value.right},${value.bottom}]")
    }

    override fun deserialize(decoder: Decoder): Bounds {
        val raw = decoder.decodeString()
        val match = pattern.matchEntire(raw) ?: throw SerializationException("Bad bounds: $raw")
        val (l, t, r, b) = match.destructured
        return Bounds(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    }
}

internal object InstantIsoSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.paragrudani.replyly.Instant", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Instant,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}
