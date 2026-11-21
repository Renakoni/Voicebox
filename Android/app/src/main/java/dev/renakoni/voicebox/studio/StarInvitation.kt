package dev.renakoni.voicebox.studio

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.renakoni.voicebox.R

@Composable
internal fun StarInvitation(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.github_star_action), onClick = onClick)
        .heightIn(min = 56.dp).padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.pixel_star), null, Modifier.size(26.dp))
        Text(stringResource(R.string.github_star), modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}
