#!/usr/bin/env python3
"""PEMESA — présentation uniquement, sans dépendance Android supplémentaire."""
from pathlib import Path
import argparse
import re
import sys

BASE = Path('app/src/main/java/com/bharathvishal/messagecommunicationusingwearabledatalayer')
MARK = '// PEMESA_UI_REFRESH_V1'

def once(s, a, b):
    if s.count(a) != 1:
        raise ValueError(f'Motif attendu une fois ({s.count(a)} trouve) : {a[:100]!r}')
    return s.replace(a, b, 1)

def patch_home(s):
    if MARK in s:
        raise ValueError('MainActivity.kt : interface deja modernisee.')
    s = once(s, 'import androidx.compose.foundation.Image', '''import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Image''')
    s = once(s, '            MaterialTheme {', '''            // PEMESA_UI_REFRESH_V1
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF096B69),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFD7F2EE),
                    onPrimaryContainer = Color(0xFF064C4B),
                    secondary = Color(0xFF36566B),
                    background = Color(0xFFF1F6F7),
                    onBackground = Color(0xFF142D3A),
                    surface = Color.White,
                    onSurface = Color(0xFF142D3A),
                    surfaceVariant = Color(0xFFE8F0F2),
                    onSurfaceVariant = Color(0xFF405963),
                    outline = Color(0xFF718991)
                )
            ) {''')
    # Keep the original background artwork and overlay a subtle gradient.
    s = once(s, '''            contentScale = ContentScale.Crop
        )

        Column(''', '''            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(
                    Color(0xFFF1F6F7).copy(alpha = 0.10f),
                    Color(0xFFF1F6F7).copy(alpha = 0.35f),
                    Color(0xFFF1F6F7).copy(alpha = 0.94f)
                ))
            )
        )

        Column(''')
    s = once(s, '    Box(modifier = Modifier.fillMaxSize()) {', '''    val formOpacity = remember { Animatable(0.82f) }
    LaunchedEffect(Unit) { formOpacity.animateTo(1f, tween(320)) }

    Box(modifier = Modifier.fillMaxSize()) {''')
    start = s.index('            OutlinedTextField(\n                value = id,')
    end_marker = '\n        }\n    }\n}\n\n@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nfun DropdownField('
    end = s.index(end_marker, start)
    form = s[start:end]
    # Preserve original fields, state, button actions and conditions. Only presentation changes.
    form = once(form, '                label = { Text("ID") },', '                label = { Text("ID") },\n                shape = RoundedCornerShape(14.dp),')
    form = form.replace('                    .height(56.dp)\n                    .padding(top = 16.dp)', '                    .padding(top = 12.dp)\n                    .height(52.dp)')
    form = once(form, '                enabled = id.isNotBlank() && professionFull.isNotBlank()', '                shape = RoundedCornerShape(14.dp),\n                enabled = id.isNotBlank() && professionFull.isNotBlank()')
    # The second button remains a Button with the original onClick; simply tone it down.
    pos = form.index('                    id = ""')
    tail = form[pos:]
    tail = once(tail, '                    .height(52.dp)\n            ) {', '''                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {''')
    form = form[:pos] + tail
    form = '\n'.join('        ' + line if line else line for line in form.split('\n'))
    s = s[:start] + '''            Surface(
                modifier = Modifier.fillMaxWidth().alpha(formOpacity.value),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
''' + form + '''
                }
            }''' + s[end:]
    s = once(s, '            readOnly = true,\n            label = { Text(label) },', '            readOnly = true,\n            shape = RoundedCornerShape(14.dp),\n            label = { Text(label) },')
    s = once(s, '        title = {},', '        title = { Text("PEMESA", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge) },')
    return s

HELPER = r'''    // PEMESA_UI_REFRESH_V1 — visual styling only, no recording state or messages.
    private fun uiColor(hex: String): Int = android.graphics.Color.parseColor(hex)
    private fun uiDp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun uiRounded(fill: Int, radius: Int = 16, stroke: Int? = null): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            cornerRadius = uiDp(radius).toFloat()
            if (stroke != null) setStroke(uiDp(1), stroke)
        }

    private fun uiStyleButton(button: android.widget.Button, originalColor: Int) {
        // Preserve the existing visual distinction between selected, idle and stop states.
        val fill = when (originalColor) {
            ContextCompat.getColor(this, android.R.color.holo_green_light) -> uiColor("#096B69")
            ContextCompat.getColor(this, android.R.color.darker_gray) -> uiColor("#405B6B")
            ContextCompat.getColor(this, android.R.color.holo_orange_light) -> uiColor("#965018")
            ContextCompat.getColor(this, android.R.color.holo_red_dark) -> uiColor("#B32636")
            else -> originalColor
        }
        val states = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), uiRounded(uiColor("#DCE5E8"), 14))
            addState(intArrayOf(), uiRounded(fill, 14))
        }
        button.backgroundTintList = null
        button.background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33FFFFFF), states,
            uiRounded(android.graphics.Color.WHITE, 14)
        )
        button.setTextColor(android.content.res.ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(uiColor("#526671"), android.graphics.Color.WHITE)
        ))
        button.setAllCaps(false)
        button.minHeight = uiDp(52)
        button.minimumHeight = uiDp(52)
        button.setPadding(uiDp(8), uiDp(10), uiDp(8), uiDp(10))
        button.elevation = 0f
        button.stateListAnimator = null
    }

    private fun uiStatusStyle(fill: String, ink: String) {
        binding.deviceconnectionStatusTv.apply {
            background = uiRounded(uiColor(fill), 18)
            setTextColor(uiColor(ink))
            setPadding(uiDp(12), uiDp(9), uiDp(12), uiDp(9))
        }
    }

    private fun applyUiDesign() {
        binding.root.setBackgroundColor(uiColor("#F1F6F7"))
        uiStatusStyle("#E4EDF0", "#294958")
        binding.timerTextView.apply {
            background = uiRounded(uiColor("#FFFFFF"), 20, uiColor("#D6E3E6"))
            setTextColor(uiColor("#142D3A"))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 28f
            setPadding(uiDp(16), uiDp(16), uiDp(16), uiDp(16))
        }
        binding.ActivityStatusTextView.apply {
            background = uiRounded(uiColor("#D7F2EE"), 14)
            setTextColor(uiColor("#064C4B"))
            setPadding(uiDp(10), uiDp(12), uiDp(10), uiDp(12))
        }
        binding.TirednessValueTextView.setTextColor(uiColor("#142D3A"))
        binding.TirednessSeekBar.progressTintList = android.content.res.ColorStateList.valueOf(uiColor("#096B69"))
        binding.TirednessSeekBar.thumbTintList = android.content.res.ColorStateList.valueOf(uiColor("#096B69"))
        binding.bottomNavigationView.apply {
            setBackgroundColor(uiColor("#FFFFFF"))
            itemIconTintList = android.content.res.ColorStateList.valueOf(uiColor("#36566B"))
            elevation = uiDp(4).toFloat()
        }
        uiStyleButton(binding.startRecordingButton, uiColor("#096B69"))
        uiStyleButton(binding.pauseRecordingButton, uiColor("#76521E"))
        uiStyleButton(binding.stopRecordingButton, uiColor("#B32636"))
        listOf(binding.ActivityButton1, binding.ActivityButton2, binding.ActivityButton3,
            binding.ActivityButton4, binding.ActivityButton5, binding.ActivityButton6).forEach {
            it.textSize = 13f
        }
        // The existing method assigns selected/idle colors, including the unlabeled button.
        updateActivityButtonColor()
        // Short entrance animation. Controls stay present and clickable; no callbacks delayed.
        binding.recordingButtonsContainer.apply {
            alpha = 0.86f
            translationY = uiDp(6).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(260).start()
        }
    }

'''

def patch_validation(s):
    if MARK in s:
        raise ValueError('ValidationActivity.kt : interface deja modernisee.')
    s = once(s, '        setupBottomNavigation()\n    }', '        setupBottomNavigation()\n        applyUiDesign()\n    }')
    # Replace the background setter only; keep all original state comparisons and color choices.
    s, n = re.subn(r'(binding\.stopRecordingButton|binding\.toggleActivityButton|button)\.setBackgroundColor\(([^\n]+)\)', r'uiStyleButton(\1, \2)', s)
    if n != 4:
        raise ValueError(f'4 mises a jour de couleur attendues, {n} trouvees')
    s = once(s, '    private fun updateActivityButtonColor() {', HELPER + '    private fun updateActivityButtonColor() {')
    anchor = '    private fun updateConnectionStatus(isConnected: Boolean, appOpen: Boolean) {'
    s = once(s, anchor, anchor + '''
        when {
            !isConnected -> uiStatusStyle("#FCE7E9", "#922B37")
            appOpen -> uiStatusStyle("#D7F2EE", "#064C4B")
            else -> uiStatusStyle("#FBEBD5", "#744917")
        }''')
    return s

def assert_preserved(before, after, name):
    # Guard commands, callbacks, persistence and state changes by exact original lines.
    pattern = r'communicator\.|putExtra\(|\.setOnClickListener|onClick\s*=|onOptionSelected\s*=|isRecording\s*=|isPaused\s*=|selectedActivity\s*=|getSharedPreferences|\.putString\(|postDelayed\(|\.isEnabled\s*='
    for line in before.splitlines():
        if re.search(pattern, line) and line.strip() not in after:
            raise ValueError(f'{name}: ligne fonctionnelle non conservee : {line.strip()}')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('project', nargs='?', default='.')
    parser.add_argument('--check', action='store_true', help='verifier sans ecrire')
    args = parser.parse_args()
    root = Path(args.project).resolve()
    plan = []
    for name, patch in [('MainActivity.kt', patch_home), ('ValidationActivity.kt', patch_validation)]:
        path = root / BASE / name
        raw = path.read_bytes()
        encoding = 'utf-8-sig' if raw.startswith(b'\xef\xbb\xbf') else 'utf-8'
        before = raw.decode(encoding).replace('\r\n', '\n')
        after = patch(before)
        assert_preserved(before, after, name)
        backup = path.with_name(path.name + '.avant_interface.bak')
        if backup.exists():
            raise ValueError(f'Sauvegarde deja presente : {backup}')
        if b'\r\n' in raw:
            after = after.replace('\n', '\r\n')
        plan.append((path, backup, raw, after.encode(encoding)))
    if args.check:
        print('Verification reussie pour les deux fichiers. Aucun fichier modifie.')
        return
    for path, backup, raw, _ in plan:
        backup.write_bytes(raw)
    try:
        for path, _, _, content in plan:
            path.write_bytes(content)
    except OSError:
        for path, _, raw, _ in plan:
            path.write_bytes(raw)
        raise
    for path, backup, _, _ in plan:
        print('Modifie :', path)
        print('Sauvegarde :', backup)
    print('Termine. Ouvrez Android Studio et compilez le module app.')

if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError) as exc:
        print('Erreur :', exc, file=sys.stderr)
        sys.exit(1)
