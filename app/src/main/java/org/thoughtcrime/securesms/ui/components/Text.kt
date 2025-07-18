package org.thoughtcrime.securesms.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import network.loki.messenger.R
import org.thoughtcrime.securesms.ui.qaTag
import org.thoughtcrime.securesms.ui.theme.LocalColors
import org.thoughtcrime.securesms.ui.theme.LocalDimensions
import org.thoughtcrime.securesms.ui.theme.LocalType
import org.thoughtcrime.securesms.ui.theme.PreviewTheme
import org.thoughtcrime.securesms.ui.theme.bold
import org.thoughtcrime.securesms.ui.theme.borders
import org.thoughtcrime.securesms.ui.theme.text
import org.thoughtcrime.securesms.ui.theme.textSecondary

@Preview
@Composable
fun PreviewSessionOutlinedTextField() {
    PreviewTheme {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SessionOutlinedTextField(
                text = "text",
                placeholder = "",
            )

            SessionOutlinedTextField(
                text = "text with clear",
                placeholder = "",
                showClear = true
            )

            SessionOutlinedTextField(
                text = "text with clear",
                placeholder = "",
                showClear = true,
                innerPadding = PaddingValues(LocalDimensions.current.smallSpacing)
            )
            SessionOutlinedTextField(
                text = "text with clear \ntest\ntest\ntest\ntest",
                placeholder = "",
                showClear = true,
                innerPadding = PaddingValues(LocalDimensions.current.smallSpacing)
            )


            SessionOutlinedTextField(
                text = "",
                placeholder = "placeholder"
            )

            SessionOutlinedTextField(
                text = "",
                placeholder = "placeholder no clear",
                showClear = true
            )

            SessionOutlinedTextField(
                text = "text",
                placeholder = "",
                error = "error"
            )

            SessionOutlinedTextField(
                text = "text onChange after error",
                placeholder = "",
                error = "error",
                isTextErrorColor = false
            )

            SessionOutlinedTextField(
                text = "Disabled",
                placeholder = "",
                isTextErrorColor = false,
                enabled = false
            )
        }
    }
}

@Composable
fun SessionOutlinedTextField(
    text: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit = {},
    textStyle: TextStyle = LocalType.current.base,
    innerPadding: PaddingValues = PaddingValues(LocalDimensions.current.spacing),
    borderShape: Shape = MaterialTheme.shapes.small,
    placeholder: String = "",
    onContinue: () -> Unit = {},
    error: String? = null,
    isTextErrorColor: Boolean = error != null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    showClear: Boolean = false,
    @StringRes clearQaTag: Int = R.string.qa_input_clear,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default.copy(imeAction = if (singleLine) ImeAction.Done else ImeAction.Default)
) {
    // in order to allow the cursor to be at the end of the text by default
    // we need o handle the TextFieldValue manually here
    var fieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }

    // If caller changes 'text', mirror it and move the caret to end
    LaunchedEffect(text) {
        if (text != fieldValue.text) {
            fieldValue = TextFieldValue(text, TextRange(text.length))
        }
    }

    BasicTextField(
        value = fieldValue,
        onValueChange = { newValue ->
            fieldValue = newValue
            onChange(newValue.text)     // propagate only the text outward
        },
        modifier = modifier,
        textStyle = textStyle.copy(
            color = if (enabled) LocalColors.current.text(isTextErrorColor) else LocalColors.current.textSecondary),
        cursorBrush = SolidColor(LocalColors.current.text(isTextErrorColor)),
        enabled = enabled,
        keyboardOptions = keyboardOptions,

        keyboardActions = KeyboardActions(
            onDone = { onContinue() },
            onGo = { onContinue() },
            onSearch = { onContinue() },
            onSend = { onContinue() },
        ),
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        decorationBox = { innerTextField ->
            Column(modifier = Modifier.animateContentSize()) {
                Box(
                    modifier = Modifier
                        .border(
                            width = LocalDimensions.current.borderStroke,
                            color = LocalColors.current.borders(error != null),
                            shape = borderShape
                        )
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(innerPadding),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier.weight(1f),
                        ) {
                            innerTextField()
                        }

                        if(showClear && text.isNotEmpty()){
                            Image(
                                painterResource(id = R.drawable.ic_x),
                                contentDescription = stringResource(R.string.clear),
                                colorFilter = ColorFilter.tint(
                                    LocalColors.current.textSecondary
                                ),
                                modifier = Modifier.qaTag(clearQaTag)
                                    .padding(start = LocalDimensions.current.smallSpacing)
                                    .size(textStyle.fontSize.value.dp)
                                    .clickable {
                                        onChange("")
                                    }
                            )
                        }
                    }

                    if (placeholder.isNotEmpty() && text.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = textStyle.copy(fontFamily = null),
                            color = LocalColors.current.textSecondary(isTextErrorColor)
                        )
                    }
                }

                AnimatedContent (error) { errorText ->
                    if (errorText != null) {
                        Text(
                            errorText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = LocalDimensions.current.xsSpacing)
                                .qaTag(R.string.qa_input_error),
                            textAlign = TextAlign.Center,
                            style = LocalType.current.base.bold(),
                            color = LocalColors.current.danger
                        )
                    }
                }
            }
        }
    )
}

@Composable
fun AnnotatedTextWithIcon(
    text: String,
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalType.current.base,
    color: Color = Color.Unspecified,
    iconSize: TextUnit = 12.sp
) {
    val myId = "inlineContent"
    val annotated = buildAnnotatedString {
        append(text)
        appendInlineContent(myId, "[icon]")
    }

    val inlineContent = mapOf(
        Pair(
            myId,
            InlineTextContent(
                Placeholder(
                    width = iconSize,
                    height = iconSize,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                )
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    modifier = Modifier.padding(1.dp),
                    tint = color
                )
            }
        )
    )

    Text(
        text = annotated,
        modifier = modifier.fillMaxWidth(),
        style = style,
        color = color,
        textAlign = TextAlign.Center,
        inlineContent = inlineContent
    )
}