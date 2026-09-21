package com.lulucamera.app.character

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.ConcurrentHashMap

import com.lulucamera.app.model.CharacterId

/** 正式素材可留空；PNG 从统一素材目录加载，缺失时使用占位配色。 */
data class CharacterDefinition(
    val id: CharacterId,
    val displayName: String,
    val bodyColor: Int,
    val muzzleColor: Int,
    val referenceAsset: String? = null,
    val glbAsset: String? = null,
)

object CharacterCatalog {
    @Volatile var version = "unconfigured-v1"
        private set
    val characters = listOf(
        CharacterDefinition(CharacterId.LULU_A, "噜噜 A", 0xFF9A6545.toInt(), 0xFFD5A277.toInt()),
        CharacterDefinition(CharacterId.LULU_B, "噜噜 B", 0xFFD6B28B.toInt(), 0xFFF2D7B8.toInt()),
    )
    private val sprites = ConcurrentHashMap<CharacterId, Bitmap>()
    @Volatile var loaded = false
        private set
    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        characters.forEach { character ->
            val filename = if (character.id == CharacterId.LULU_A) "lulu_a.png" else "lulu_b.png"
            val bitmap = runCatching {
                val bytes = context.assets.open("characters/$filename").use { it.readBytes() }
                digest.update(bytes)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val options = BitmapFactory.Options()
                while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }.getOrNull()
            if (bitmap != null) sprites[character.id] = bitmap
        }
        if (sprites.isNotEmpty()) version = "png-" + digest.digest().take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
        loaded = true
    }
    fun sprite(id: CharacterId): Bitmap? = sprites[id]
    fun get(id: CharacterId): CharacterDefinition = characters.first { it.id == id }
}
