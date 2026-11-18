package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.SupabaseProject
import com.example.data.model.SupabaseSqlTemplates

@Composable
fun AddOrEditProjectDialog(
  existingProject: SupabaseProject? = null,
  isSubmitting: Boolean,
  onDismiss: () -> Unit,
  onSubmit: (name: String, url: String, key: String, onError: (String) -> Unit) -> Unit,
) {
  var projectName by remember(existingProject) {
    mutableStateOf(existingProject?.projectName ?: "")
  }
  var projectUrl by remember(existingProject) {
    mutableStateOf(existingProject?.projectUrl ?: "")
  }
  var publishableKey by remember { mutableStateOf("") }
  var validationError by remember { mutableStateOf<String?>(null) }
  var showSqlGuide by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = { if (!isSubmitting) onDismiss() },
    title = {
      Text(
        text = if (existingProject == null) "Add Supabase Project" else "Update Project",
        style = MaterialTheme.typography.titleLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.verticalScroll(rememberScrollState()),
      ) {
        OutlinedTextField(
          value = projectName,
          onValueChange = {
            projectName = it
            validationError = null
          },
          label = { Text("Project Name") },
          placeholder = { Text("e.g. Production API") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("project_name_input"),
        )

        OutlinedTextField(
          value = projectUrl,
          onValueChange = {
            projectUrl = it
            validationError = null
          },
          label = { Text("Supabase Project URL") },
          placeholder = { Text("https://xyzcompany.supabase.co") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("project_url_input"),
        )

        OutlinedTextField(
          value = publishableKey,
          onValueChange = {
            publishableKey = it
            validationError = null
          },
          label = {
            Text(
              if (existingProject == null) "Publishable Key"
              else "New Publishable Key (optional)"
            )
          },
          placeholder = { Text("sb_publishable_...") },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("publishable_key_input"),
        )

        Surface(
          color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(10.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.Lock,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(16.dp),
            )
            Text(
              text = "Publishable / anon key only.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        OutlinedButton(
          onClick = { showSqlGuide = !showSqlGuide },
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = if (showSqlGuide) "Hide Setup SQL" else "View Setup SQL",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        if (showSqlGuide) {
          SqlSetupCard()
        }

        validationError?.let { err ->
          Surface(
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = err,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.padding(10.dp),
            )
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onSubmit(projectName, projectUrl, publishableKey) { err -> validationError = err }
        },
        enabled = !isSubmitting,
        modifier = Modifier.testTag("save_project_button"),
      ) {
        if (isSubmitting) {
          CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary,
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text("Validating...")
        } else {
          Text(if (existingProject == null) "Save & Check" else "Save")
        }
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, enabled = !isSubmitting) {
        Text(stringResource(R.string.action_cancel))
      }
    },
  )
}

@Composable
fun SqlSetupCard(modifier: Modifier = Modifier) {
  val clipboard = LocalClipboardManager.current
  var copied by remember { mutableStateOf(false) }

  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant,
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(
      verticalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.padding(12.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = "supalive_health.sql",
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        TextButton(
          onClick = {
            clipboard.setText(AnnotatedString(SupabaseSqlTemplates.HEALTH_TABLE_SQL))
            copied = true
          },
          modifier = Modifier.testTag("copy_sql_button"),
        ) {
          Icon(
            painter = painterResource(R.drawable.ic_copy),
            contentDescription = "Copy health-check SQL",
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = if (copied) "Copied!" else stringResource(R.string.action_copy_sql),
            maxLines = 1,
          )
        }
      }
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 4.dp)
      ) {
        SelectionContainer {
          Text(
            text = SupabaseSqlTemplates.HEALTH_TABLE_SQL,
            style =
              MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp,
              ),
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
          )
        }
      }
    }
  }
}

@Composable
fun SqlSetupDialog(onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = stringResource(R.string.sql_setup_title),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.verticalScroll(rememberScrollState()),
      ) {
        Text(
          text = stringResource(R.string.sql_setup_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SqlSetupCard()
      }
    },
    confirmButton = {
      Button(onClick = onDismiss) {
        Text("Done")
      }
    },
  )
}
