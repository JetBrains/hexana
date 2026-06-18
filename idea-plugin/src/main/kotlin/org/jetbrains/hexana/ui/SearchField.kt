package org.jetbrains.hexana.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Reusable search text field with a leading search icon and a trailing clear button.
 */
@Composable
internal fun SearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp, vertical = 4.dp),
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(
                AllIconsKeys.Actions.Search,
                contentDescription = null,
                iconClass = AllIcons::class.java,
                modifier = Modifier.size(16.dp),
            )
        },
        trailingIcon = if (value.text.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange(TextFieldValue("")) }) {
                    Icon(
                        AllIconsKeys.Actions.Close,
                        contentDescription = "Clear search",
                        iconClass = AllIcons::class.java,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        } else {
            null
        },
    )
}
