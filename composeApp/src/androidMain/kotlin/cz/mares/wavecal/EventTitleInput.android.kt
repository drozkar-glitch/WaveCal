package cz.mares.wavecal

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
actual fun EventTitleInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isDark: Boolean,
    onDone: () -> Unit,
    modifier: Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnDone by rememberUpdatedState(onDone)

    val textColorArgb = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
    val hintColorArgb = if (isDark) android.graphics.Color.parseColor("#9E9E9E") else android.graphics.Color.parseColor("#757575")
    val borderColor = if (isFocused) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    val containerBg = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF8F9FA)
    val primaryArgb = MaterialTheme.colorScheme.primary.toArgb()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(containerBg)
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 14.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                EditText(ctx).apply {
                    background = null
                    setPadding(0, 12, 0, 12)
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    setSingleLine(true)
                    maxLines = 1
                    // Klasické nativní nastavení pro 3x4 i QWERTY klávesnici: první písmeno velké
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    setTextColor(textColorArgb)
                    setHintTextColor(hintColorArgb)
                    highlightColor = primaryArgb

                    setOnFocusChangeListener { _, hasFocus ->
                        isFocused = hasFocus
                    }

                    setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                            currentOnDone()
                            true
                        } else {
                            false
                        }
                    }

                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            val newText = s?.toString() ?: ""
                            currentOnValueChange(newText)
                        }
                    })
                }
            },
            update = { editText ->
                editText.hint = placeholder
                editText.setTextColor(textColorArgb)
                editText.setHintTextColor(hintColorArgb)
                if (editText.text?.toString() != value) {
                    editText.setText(value)
                    editText.setSelection(value.length)
                }
            }
        )
    }
}
