package com.example.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import com.example.R

@Composable
fun AuthScreen(
  isDarkTheme: Boolean,
  onToggleTheme: () -> Unit,
  onAuthSuccess: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val credentialManager = remember(context) { CredentialManager.create(context) }

  var isSigningIn by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) {
    attemptAutoSignIn(
      context = context,
      credentialManager = credentialManager,
      onAuthSuccess = onAuthSuccess,
      onUnauthenticated = {},
      scope = scope,
    )
  }

  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.safeDrawing)
  ) {
    Column(
      modifier =
        Modifier.fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp, vertical = 20.dp)
          .widthIn(max = 520.dp)
          .align(Alignment.TopCenter),
      verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      // Brand Header + Theme Toggle
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.primaryContainer,
          modifier = Modifier.size(42.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              painter = painterResource(R.drawable.ic_monitor_heart),
              contentDescription = "Supalive brand icon",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(24.dp),
            )
          }
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = stringResource(R.string.tagline),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        IconButton(
          onClick = onToggleTheme,
          modifier = Modifier.testTag("auth_theme_toggle"),
        ) {
          Icon(
            painter =
              painterResource(if (isDarkTheme) R.drawable.ic_light_mode else R.drawable.ic_dark_mode),
            contentDescription = if (isDarkTheme) "Switch to light mode" else "Switch to dark mode",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      // Compact Hero Banner
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .height(148.dp)
            .clip(RoundedCornerShape(18.dp))
      ) {
        Image(
          painter = painterResource(id = R.drawable.img_supalive_hero),
          contentDescription = "Supalive telemetry illustration",
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
        Box(
          modifier =
            Modifier.fillMaxSize()
              .background(
                Brush.verticalGradient(
                  colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                )
              )
              .padding(16.dp),
          contentAlignment = Alignment.BottomStart,
        ) {
          Text(
            text = "12h Read-Only Health Checks",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      Text(
        text = stringResource(R.string.auth_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Button(
        onClick = {
          errorMessage = null
          isSigningIn = true
          onGoogleSignInClicked(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = {
              isSigningIn = false
              onAuthSuccess()
            },
            onAuthError = { msg ->
              isSigningIn = false
              errorMessage = msg
            },
            scope = scope,
            onAuthCancelled = { isSigningIn = false },
          )
        },
        enabled = !isSigningIn,
        shape = RoundedCornerShape(14.dp),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
          ),
        modifier =
          Modifier.fillMaxWidth()
            .height(52.dp)
            .testTag("google_sign_in_button"),
      ) {
        if (isSigningIn) {
          CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = MaterialTheme.colorScheme.onPrimary,
            strokeWidth = 2.5.dp,
          )
          Spacer(modifier = Modifier.width(12.dp))
          Text("Signing in...", style = MaterialTheme.typography.labelLarge)
        } else {
          Text(
            text = stringResource(R.string.sign_in_with_google),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
          )
        }
      }

      errorMessage?.let { msg ->
        Surface(
          color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
          shape = RoundedCornerShape(12.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = msg,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(12.dp),
          )
        }
      }

      Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier.padding(12.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.Top,
        ) {
          Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(18.dp).padding(top = 2.dp),
          )
          Text(
            text = stringResource(R.string.auth_disclosure_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}
