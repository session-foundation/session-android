package org.thoughtcrime.securesms.ui.components

import android.content.res.Resources
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BulletSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.theme.LocalColors

private val TAG = AnnotatedString::class.java.simpleName

// Utilities for AnnotatedStrings, like converting the old view system's SpannableString to AnnotatedString
@Composable
@ReadOnlyComposable
private fun resources(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}

@Composable
fun annotatedStringResource(
    @StringRes id: Int,
    highlightColor: Color = LocalColors.current.accent
): AnnotatedString {
    val resources = resources()
    val density = LocalDensity.current
    return remember(id) {
        val text = resources.getText(id)
        spannableStringToAnnotatedString(text, density, highlightColor)
    }
}

@Composable
fun annotatedStringResource(
    text: CharSequence,
    highlightColor: Color = LocalColors.current.accent
): AnnotatedString {
    val density = LocalDensity.current
    return remember(text.hashCode()) {
        spannableStringToAnnotatedString(text, density, highlightColor)
    }
}

private fun spannableStringToAnnotatedString(
    text: CharSequence,
    density: Density,
    highlightColor: Color
): AnnotatedString {
    val annotatedStringBuilder = AnnotatedString.Builder()

    // make sure we have a Spanned (a string without html tag but with the icon wouldn't be considered a Spanned)
    val spannedText: Spanned = if (text is Spanned) text else SpannableStringBuilder.valueOf(text)

    var currentIndex = 0

    // Build a regex pattern to match any of the placeholders
    val placeholderPattern = Regex(inlineContentMap().keys.joinToString("|") { Regex.escape(it) })
    val matches = placeholderPattern.findAll(spannedText)
    val matchIterator = matches.iterator()

    while (currentIndex < spannedText.length) {
        val nextMatch = if (matchIterator.hasNext()) matchIterator.next() else null
        val startOfPlaceholder = nextMatch?.range?.first ?: spannedText.length
        val endOfPlaceholder = nextMatch?.range?.last?.plus(1) ?: spannedText.length

        // Append text before the placeholder
        if (currentIndex < startOfPlaceholder) {
            val textSegment = spannedText.subSequence(currentIndex, startOfPlaceholder)
            appendAnnotatedTextSegment(annotatedStringBuilder, spannedText, textSegment, currentIndex, density, highlightColor)
        }

        // Append inline content instead of the placeholder
        if (nextMatch != null) {
            val placeholderText = nextMatch.value

            if (inlineContentMap().containsKey(placeholderText)) {
                // Use the placeholder text as the ID
                annotatedStringBuilder.appendInlineContent(placeholderText, placeholderText)
            } else {
                // If no matching inline content, append the placeholder text as is
                annotatedStringBuilder.append(placeholderText)
            }
            currentIndex = endOfPlaceholder
        } else {
            currentIndex = spannedText.length
        }
    }

    return annotatedStringBuilder.toAnnotatedString()
}

private fun appendAnnotatedTextSegment(
    builder: AnnotatedString.Builder,
    spannedText: Spanned,
    textSegment: CharSequence,
    segmentStartIndex: Int,
    density: Density,
    highlightColor: Color
) {
    val segmentLength = textSegment.length
    val segmentEndIndex = segmentStartIndex + segmentLength

    builder.append(textSegment)

    // Process spans in the segment
    val spans = spannedText.getSpans(segmentStartIndex, segmentEndIndex, CharacterStyle::class.java)

    for (span in spans) {
        val spanStart = maxOf(spannedText.getSpanStart(span), segmentStartIndex)
        val spanEnd = minOf(spannedText.getSpanEnd(span), segmentEndIndex)

        val start = builder.length - segmentLength + (spanStart - segmentStartIndex)
        val end   = builder.length - segmentLength + (spanEnd - segmentStartIndex)

        when (span) {
            is StyleSpan -> {
                val spanStyle = when (span.style) {
                    Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    Typeface.BOLD_ITALIC -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                    else -> SpanStyle()
                }
                builder.addStyle(spanStyle, start, end)
            }

            is TypefaceSpan -> {
                builder.addStyle(
                    SpanStyle(
                        fontFamily = when (span.family) {
                            FontFamily.SansSerif.name -> FontFamily.SansSerif
                            FontFamily.Serif.name -> FontFamily.Serif
                            FontFamily.Monospace.name -> FontFamily.Monospace
                            FontFamily.Cursive.name -> FontFamily.Cursive
                            else -> FontFamily.Default
                        }
                    ),
                    start,
                    end
                )
            }

            is BulletSpan -> {
                Log.d("StringResources", "BulletSpan not supported yet")
                builder.addStyle(SpanStyle(), start, end)
            }

            is AbsoluteSizeSpan -> {
                val fontSize = with (density) {
                    if (span.dip) {
                        // Convert Dp to Sp
                        (span.size.dp.toPx() / fontScale).sp

                        //dpToSp(span.size.dp.value)

                    } else {
                        // Size is already in pixels; convert pixels to Sp
                        (span.size / fontScale).sp
                    }
                }
                // if (span.dip) span.size.dp.toSp() else it.size.toSp()),
                builder.addStyle(SpanStyle(fontSize = fontSize), start, end)
            }

            is RelativeSizeSpan -> builder.addStyle(
                SpanStyle(fontSize = span.sizeChange.em),
                start,
                end
            )

            is StrikethroughSpan -> builder.addStyle(
                SpanStyle(textDecoration = TextDecoration.LineThrough),
                start,
                end
            )
            is UnderlineSpan -> builder.addStyle(
                SpanStyle(textDecoration = TextDecoration.Underline),
                start,
                end
            )
            is SuperscriptSpan -> builder.addStyle(
                SpanStyle(baselineShift = BaselineShift.Superscript),
                start,
                end
            )

            is SubscriptSpan -> builder.addStyle(
                SpanStyle(baselineShift = BaselineShift.Subscript),
                start,
                end
            )

            // Note: We take anything like `<font color="0">foo</font>` and use the current
            // theme accent colour for it (the colour specified in the font tag is ignored).
            is ForegroundColorSpan -> {
                builder.addStyle(SpanStyle(color = highlightColor), start, end)
            }

            else -> {
                Log.w(TAG, "Unrecognised span: " + span + " - using default style.")
                builder.addStyle(SpanStyle(), start, end)
            }
        }
    }
}

// External link icon ID & inline content.
// When we see "{icon}" in the string we substitute with the external link icon, or
// whichever icon is suitable for the given string.
val iconExternalLink = "[external-icon]"

// Add any additional mappings between a given tag and an icon or image here.
fun inlineContentMap(
    textSize: TextUnit = 15.sp,
    imageColor: Color? = null
) = mapOf(
    iconExternalLink to InlineTextContent(
        Placeholder(
            width = textSize,
            height = textSize,
            placeholderVerticalAlign = PlaceholderVerticalAlign.Center
        )
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_square_arrow_up_right),
            colorFilter = ColorFilter.tint(imageColor ?: LocalColors.current.accentText),
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
        )
    }
)