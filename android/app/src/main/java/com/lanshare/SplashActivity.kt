package com.lanshare

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences(APP_PREFERENCES, MODE_PRIVATE)
        val darkTheme = preferences
            .getBoolean(APP_DARK_THEME_KEY, false)
        val locale = splashLocaleTag(preferences.getString(APP_LOCALE_KEY, null))
        val localizedContext = localizedAndroidContext(this, locale)
        applySplashSystemBars(darkTheme)
        setContent {
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalLayoutDirection provides localizedLayoutDirection(locale),
            ) {
                LanShareSplash(darkTheme = darkTheme) {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            }
        }
    }

    private fun applySplashSystemBars(darkTheme: Boolean) {
        val palette = splashPalette(darkTheme)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color(palette.backgroundArgb).toArgb()
            window.navigationBarColor = Color(palette.backgroundArgb).toArgb()
            window.decorView.systemUiVisibility = if (darkTheme) {
                0
            } else {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
    }
}

@Composable
private fun LanShareSplash(darkTheme: Boolean, onDone: () -> Unit) {
    val palette = splashPalette(darkTheme)
    LaunchedEffect(Unit) {
        delay(900)
        onDone()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(palette.backgroundArgb)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 34.dp)
                .offset(y = (-34).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 228.dp, height = 104.dp)
                    .background(Color(palette.logoBackgroundArgb), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = Color(0xFFF8FAF7))) {
                            append("Lan-")
                        }
                        withStyle(SpanStyle(color = Color(palette.logoAccentArgb))) {
                            append("Share")
                        }
                    },
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Black,
                    fontSize = 38.sp,
                    lineHeight = 38.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(22.dp))
            Text(
                text = stringResource(R.string.default_homepage_description),
                color = Color(palette.descriptionArgb),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

internal data class SplashPalette(
    val backgroundArgb: Int,
    val logoBackgroundArgb: Int,
    val logoAccentArgb: Int,
    val descriptionArgb: Int,
)

internal fun splashPalette(darkTheme: Boolean): SplashPalette =
    if (darkTheme) {
        SplashPalette(
            backgroundArgb = 0xFF111512.toInt(),
            logoBackgroundArgb = 0xFF1B211D.toInt(),
            logoAccentArgb = 0xFF18A57F.toInt(),
            descriptionArgb = 0xFFB5C2B9.toInt(),
        )
    } else {
        SplashPalette(
            backgroundArgb = 0xFFF5F7F4.toInt(),
            logoBackgroundArgb = 0xFF202421.toInt(),
            logoAccentArgb = 0xFF0F6B55.toInt(),
            descriptionArgb = 0xFF637068.toInt(),
        )
    }

internal fun splashLocaleTag(
    persistedLocale: String?,
    systemLocaleTag: String = java.util.Locale.getDefault().toLanguageTag(),
): String =
    persistedLocale?.takeIf { it.isNotBlank() } ?: androidLocaleFromLanguageTag(systemLocaleTag)
