package me.rerere.rikkahub.ui.pages.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.SelectedQuote
import me.rerere.rikkahub.ui.components.ui.BitmapComposer
import java.io.FileOutputStream

internal const val MAX_QUOTE_CARD_CHARACTERS = 220
private const val QUOTE_SHARE_MOTTO = "知行合一"
private val QuoteShareFontFamily = FontFamily(Font(R.font.zhi_mang_xing))

internal enum class QuoteShareTheme(
    @DrawableRes val backgroundRes: Int,
    val contentColor: Color,
    val accentColor: Color,
) {
    WARM(
        backgroundRes = R.drawable.quote_share_background_warm,
        contentColor = Color(0xFF5A3918),
        accentColor = Color(0xFF9B6A2D),
    ),
    NIGHT(
        backgroundRes = R.drawable.quote_share_background_night,
        contentColor = Color(0xFFF4EBDD),
        accentColor = Color(0xFFCDA979),
    ),
}

@Composable
internal fun QuoteShareSheet(
    quote: SelectedQuote,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var theme by remember(quote) { mutableStateOf(QuoteShareTheme.WARM) }
    var sharingImage by remember(quote) { mutableStateOf(false) }
    val canShareImage = canShareQuoteAsImage(quote.text)

    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.quote_share_title),
                style = MaterialTheme.typography.titleLarge,
            )

            QuoteShareCard(
                quote = quote,
                theme = theme,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp)),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = theme == QuoteShareTheme.WARM,
                    onClick = { theme = QuoteShareTheme.WARM },
                    label = { Text(stringResource(R.string.quote_share_theme_warm)) },
                )
                FilterChip(
                    selected = theme == QuoteShareTheme.NIGHT,
                    onClick = { theme = QuoteShareTheme.NIGHT },
                    label = { Text(stringResource(R.string.quote_share_theme_night)) },
                )
            }

            if (!canShareImage) {
                Text(
                    text = stringResource(
                        R.string.quote_share_too_long,
                        MAX_QUOTE_CARD_CHARACTERS,
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }

            Button(
                onClick = {
                    val hostActivity = activity
                    if (hostActivity == null) {
                        Toast.makeText(
                            context,
                            R.string.quote_share_image_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@Button
                    }
                    sharingImage = true
                    scope.launch {
                        val result = shareQuoteImage(
                            context = context,
                            activity = hostActivity,
                            scope = scope,
                            density = density,
                            quote = quote,
                            theme = theme,
                        )
                        sharingImage = false
                        if (result.isSuccess) {
                            onDismissRequest()
                        } else {
                            Toast.makeText(
                                context,
                                R.string.quote_share_image_failed,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
                enabled = canShareImage && !sharingImage,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (sharingImage) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.quote_share_image))
                }
            }

            OutlinedButton(
                onClick = {
                    shareQuoteText(context, quote.text)
                    onDismissRequest()
                },
                enabled = !sharingImage,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.quote_share_text))
            }
        }
    }
}

@Composable
internal fun QuoteShareCard(
    quote: SelectedQuote,
    theme: QuoteShareTheme,
    modifier: Modifier = Modifier,
) {
    val quoteFontSize = quoteFontSizeFor(quote.text)

    Box(modifier = modifier) {
        Image(
            painter = painterResource(theme.backgroundRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 30.dp, vertical = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = quoteDateLabel(quote),
                    color = theme.accentColor,
                    fontSize = quoteFontSize,
                    lineHeight = quoteFontSize,
                    fontFamily = QuoteShareFontFamily,
                    letterSpacing = 1.sp,
                )
                Text(
                    text = quoteTimestampLabel(quote),
                    color = theme.accentColor.copy(alpha = 0.82f),
                    fontSize = quoteFontSize * (1f / 3f),
                    lineHeight = quoteFontSize * (1f / 3f),
                    fontFamily = QuoteShareFontFamily,
                    letterSpacing = 0.4.sp,
                    modifier = Modifier.padding(start = 7.dp, bottom = 2.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            Text(
                text = quote.text,
                color = theme.contentColor,
                fontSize = quoteFontSize,
                lineHeight = quoteFontSize * 1.6f,
                fontFamily = QuoteShareFontFamily,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.weight(1f))

            HorizontalDivider(color = theme.accentColor.copy(alpha = 0.24f))
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "知行",
                    color = theme.accentColor,
                    fontSize = 22.sp,
                    lineHeight = 22.sp,
                    fontFamily = QuoteShareFontFamily,
                )
                Text(
                    text = "· $QUOTE_SHARE_MOTTO",
                    color = theme.accentColor.copy(alpha = 0.86f),
                    fontSize = 11.sp,
                    lineHeight = 11.sp,
                    fontFamily = QuoteShareFontFamily,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                )
            }
        }
    }
}

internal fun quoteFontSizeFor(text: String): TextUnit = when {
    text.length <= 42 -> 24.sp
    text.length <= 80 -> 21.sp
    text.length <= 130 -> 18.sp
    text.length <= 180 -> 15.sp
    else -> 13.sp
}

internal fun quoteDateLabel(quote: SelectedQuote): String =
    "%02d / %02d".format(quote.createdAt.month.ordinal + 1, quote.createdAt.day)

internal fun quoteTimestampLabel(quote: SelectedQuote): String = "%02d:%02d:%02d".format(
    quote.createdAt.hour,
    quote.createdAt.minute,
    quote.createdAt.second,
)

internal fun canShareQuoteAsImage(text: String): Boolean =
    text.length <= MAX_QUOTE_CARD_CHARACTERS

private suspend fun shareQuoteImage(
    context: Context,
    activity: Activity,
    scope: CoroutineScope,
    density: Density,
    quote: SelectedQuote,
    theme: QuoteShareTheme,
): Result<Unit> {
    var bitmap: Bitmap? = null
    return try {
        bitmap = BitmapComposer(scope).composableToBitmap(
            activity = activity,
            width = 360.dp,
            height = 360.dp,
            screenDensity = density,
        ) {
            QuoteShareCard(
                quote = quote,
                theme = theme,
                modifier = Modifier.fillMaxSize(),
            )
        }

        val file = context.appTempFolder.resolve("quote-share-${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Failed to encode quote share image"
            }
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(
                intent,
                context.getString(R.string.chat_page_export_share_via),
            )
        )
        Result.success(Unit)
    } catch (exception: Exception) {
        exception.printStackTrace()
        Result.failure(exception)
    } finally {
        bitmap?.recycle()
    }
}

private fun shareQuoteText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(
        Intent.createChooser(
            intent,
            context.getString(R.string.chat_page_export_share_via),
        )
    )
}
