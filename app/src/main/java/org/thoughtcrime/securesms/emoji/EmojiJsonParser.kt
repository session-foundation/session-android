package org.thoughtcrime.securesms.emoji

import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.thoughtcrime.securesms.components.emoji.CompositeEmojiPageModel
import org.thoughtcrime.securesms.components.emoji.Emoji
import org.thoughtcrime.securesms.components.emoji.EmojiPageModel
import org.thoughtcrime.securesms.components.emoji.StaticEmojiPageModel
import java.io.InputStream

typealias UriFactory = (sprite: String, format: String) -> Uri

/**
 * Takes an emoji_data.json file data and parses it into an EmojiSource
 */
object EmojiJsonParser {
  fun parse(
    json: Json,
    body: InputStream,
    uriFactory: UriFactory
  ): Result<ParsedEmojiData> {
    return runCatching {
      @Suppress("OPT_IN_USAGE") val data = json.decodeFromStream<EmojiDataJson>(body)

      val dataPages = getDataPages(data.format, data.emoji, uriFactory)
      ParsedEmojiData(
        metrics = data.metrics,
        densities = data.densities,
        format = data.format,
        displayPages = mergeToDisplayPages(dataPages),
        dataPages = dataPages,
        jumboPages = emptyMap(),
        obsolete = data.obsolete,
      )
    }
  }

  private fun getDataPages(format: String, emoji: Map<String, List<List<String>>>, uriFactory: UriFactory): List<EmojiPageModel> {
    return emoji
      .asSequence()
      .sortedWith { lhs, rhs ->
        val lhsCategory = EmojiCategory.forKey(lhs.key.asCategoryKey())
        val rhsCategory = EmojiCategory.forKey(rhs.key.asCategoryKey())
        val comp = lhsCategory.priority.compareTo(rhsCategory.priority)

        if (comp == 0) {
          val lhsIndex = lhs.key.getPageIndex()
          val rhsIndex = rhs.key.getPageIndex()

          lhsIndex.compareTo(rhsIndex)
        } else {
          comp
        }
      }
      .map { createPage(it.key, format, it.value, uriFactory) }
      .toList()
  }

  private fun createPage(pageName: String, format: String, page: List<List<String>>, uriFactory: UriFactory): EmojiPageModel {
    val category = EmojiCategory.forKey(pageName.asCategoryKey())
    val pageList = page.mapIndexed { i, data ->
      if (data.isEmpty()) {
        throw IllegalStateException("Page index $pageName.$i had no data")
      } else {
        val variations: MutableList<String> = mutableListOf()
        val rawVariations: MutableList<String> = mutableListOf()
        data.forEach {
          variations += it
          rawVariations += it
        }

        Emoji(variations, rawVariations)
      }
    }

    return StaticEmojiPageModel(category, pageList, uriFactory(pageName, format))
  }

  private fun mergeToDisplayPages(dataPages: List<EmojiPageModel>): List<EmojiPageModel> {
    return dataPages.groupBy { it.iconAttr }
      .map { (icon, pages) -> if (pages.size <= 1) pages.first() else CompositeEmojiPageModel(icon, pages) }
  }
}

@Serializable
private class EmojiDataJson(
  val metrics: EmojiMetrics,
  val densities: List<String>,
  val obsolete: List<ObsoleteEmoji>,
  val format: String,
  val emoji: Map<String, List<List<@Serializable(with = StringAsHexUtf16Serializer::class) String>>>,
)


private fun String.asCategoryKey() = replace("(_\\d+)*$".toRegex(), "")
private fun String.getPageIndex() = "^.*_(\\d+)+$".toRegex().find(this)?.let { it.groupValues[1] }?.toInt() ?: throw IllegalStateException("No index.")

data class ParsedEmojiData(
  override val metrics: EmojiMetrics,
  override val densities: List<String>,
  override val format: String,
  override val displayPages: List<EmojiPageModel>,
  override val dataPages: List<EmojiPageModel>,
  override val jumboPages: Map<String, String>,
  override val obsolete: List<ObsoleteEmoji>
) : EmojiData
