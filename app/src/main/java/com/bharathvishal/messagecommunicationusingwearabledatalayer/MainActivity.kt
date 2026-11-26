package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var currentLanguage by mutableStateOf(AppLanguage.ENGLISH)

    private fun tr(text: String) = AppLanguage.translate(currentLanguage, text)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentLanguage = AppLanguage.get(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            // PEMESA_UI_REFRESH_V1
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
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showLangDialog by remember { mutableStateOf(false) }
                    var showSettingsDialog by remember { mutableStateOf(false) }

                    if (showSettingsDialog) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showSettingsDialog = false },
                            title = { Text(tr("Settings")) },
                            text = {
                                Column {
                                    TextButton(onClick = {
                                        showSettingsDialog = false
                                        showLangDialog = true
                                    }) { Text(tr("Select Language")) }
                                    TextButton(onClick = {
                                        showSettingsDialog = false
                                        showActivityLabelSettings()
                                    }) { Text(tr("Rename activity labels")) }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showSettingsDialog = false }) {
                                    Text(tr("Cancel"))
                                }
                            }
                        )
                    }

                    if (showLangDialog) {
                        LanguageDialog(
                            currentLanguage = currentLanguage,
                            onDismiss = { showLangDialog = false },
                            onLanguageSelected = { lang ->
                                AppLanguage.set(this, lang)
                                currentLanguage = lang
                                Toast.makeText(this, tr("Selected language:") + " " + AppLanguage.languageName(lang), Toast.LENGTH_SHORT).show()
                                showLangDialog = false
                            }
                        )
                    }

                    MainPage(
                        language = currentLanguage,
                        onMenuItemClick = { item ->
                            when (item) {
                                "Exit" -> finish()
                                "Smartwatch" -> showConnectedSmartwatches()
                                "Settings" -> showSettingsDialog = true
                                "About" -> showAboutDialog()
                            }
                        }
                    )

                }
            }
        }
    }

    private fun showActivityLabelSettings() {
        val professionNames = getSharedPreferences("profession_display_names", MODE_PRIVATE)
        val options = ActivityLabels.professions
        val names = options.map { option ->
            professionNames.getString(option.substringBefore(" - "), null) ?: tr(option)
        }
        AlertDialog.Builder(this)
            .setTitle(tr("Select a profession"))
            .setItems(names.toTypedArray()) { _, index ->
                showProfessionActivityLabels(options[index].substringBefore(" - "), names[index])
            }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun showProfessionActivityLabels(profession: String, professionName: String) {
        val preferences = getSharedPreferences(ActivityLabels.PREFERENCES, MODE_PRIVATE)
        val defaults = ActivityLabels.defaults(profession)
        val labels = defaults.mapIndexed { index, label ->
            preferences.getString(ActivityLabels.key(profession, index), null) ?: tr(label)
        }
        val options = labels + tr("Restore default labels")
        AlertDialog.Builder(this)
            .setTitle(professionName)
            .setItems(options.toTypedArray()) { _, index ->
                if (index == defaults.size) {
                    AlertDialog.Builder(this)
                        .setTitle(tr("Restore default labels"))
                        .setMessage(tr("Restore the default activity labels for this profession?"))
                        .setNegativeButton(tr("Cancel")) { _, _ ->
                            showProfessionActivityLabels(profession, professionName)
                        }
                        .setPositiveButton(tr("Confirm")) { _, _ ->
                            preferences.edit().apply {
                                defaults.indices.forEach { remove(ActivityLabels.key(profession, it)) }
                            }.apply()
                            showProfessionActivityLabels(profession, professionName)
                        }
                        .show()
                } else {
                    editActivityLabel(profession, professionName, index)
                }
            }
            .setNegativeButton(tr("Cancel"), null)
            .show()
    }

    private fun editActivityLabel(profession: String, professionName: String, index: Int) {
        val preferences = getSharedPreferences(ActivityLabels.PREFERENCES, MODE_PRIVATE)
        val defaults = ActivityLabels.defaults(profession)
        val key = ActivityLabels.key(profession, index)
        val currentLabel = preferences.getString(key, null) ?: tr(defaults[index])
        val input = android.widget.EditText(this).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(currentLabel)
            selectAll()
        }
        val padding = (24 * resources.displayMetrics.density + 0.5f).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr("Rename label"))
            .setView(container)
            .setNegativeButton(tr("Cancel")) { _, _ ->
                showProfessionActivityLabels(profession, professionName)
            }
            .setPositiveButton(tr("Save"), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newLabel = input.text.toString().trim()
                when {
                    newLabel == currentLabel -> {
                        dialog.dismiss()
                        showProfessionActivityLabels(profession, professionName)
                    }
                    newLabel.isEmpty() -> input.error = tr("Enter a label")
                    newLabel.any { it == ',' || it == ':' || it.code == 10 || it.code == 13 } ->
                        input.error = tr("Do not use commas or colons")
                    defaults.indices.any { other ->
                        if (other == index) false else {
                            val custom = preferences.getString(ActivityLabels.key(profession, other), null)
                            newLabel == (custom ?: defaults[other]) || newLabel == (custom ?: tr(defaults[other]))
                        }
                    } -> input.error = tr("This label already exists")
                    else -> {
                        preferences.edit().putString(key, newLabel).apply()
                        dialog.dismiss()
                        showProfessionActivityLabels(profession, professionName)
                    }
                }
            }
        }
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this@MainActivity)
            .setTitle(tr("About"))
            .setMessage(
                if (currentLanguage == AppLanguage.FRENCH) {
                    "Développé par Djalal Eddine Bouchekif, doctorant au laboratoire IRIT (Institut de recherche en informatique de Toulouse).\n\n" +
                        "Sous la direction de M. Rémi Bastide et de Mme Imen Magdiche.\n\n" +
                        "Ce travail s’inscrit dans le cadre de ma thèse de doctorat en cours à l’école d’ingénieurs ISIS, réalisée dans le cadre du projet PEMESA, cofinancé par la Région Occitanie en France.\n\n" +
                        "Contexte du projet PEMESA :\n" +
                        "Le projet vise à étudier la pénibilité au travail, notamment dans les professions de santé, en exploitant les technologies de l’Internet des objets (IoT), l’intelligence artificielle et l’analyse de données.\n\n" +
                        "Il repose sur la collecte de données inertielles (accéléromètre et gyroscope) pour identifier les activités physiques et sur le suivi de la fréquence cardiaque à l’aide de montres connectées, complétés par les perceptions subjectives des travailleurs recueillies par questionnaire.\n\n" +
                        "L’objectif principal est d’identifier les facteurs clés contribuant à la pénibilité au travail afin d’améliorer les conditions de travail dans le secteur médical."
                } else "Developed by Djalal Eddine Bouchekif, a PhD student at the IRIT Laboratory (Institute of Computer Science Research of Toulouse).\n\n" +
                        "Supervised by Mr. Rémi Bastide and Ms. Imen Magdiche.\n\n" +
                        "This work is part of my ongoing doctoral thesis at the ISIS Engineering School, conducted within the framework of the PEMESA project, co-funded by the Occitanie Region in France.\n\n" +
                        "Context of the PEMESA Project:\n" +
                        "The project aims to study work-related hardship, particularly in healthcare professions, by leveraging Internet of Things (IoT) technologies, artificial intelligence, and data analysis.\n\n" +
                        "It involves collecting inertial data (accelerometer and gyroscope) to identify physical activities and monitoring heart rate through connected watches, complemented by workers’ subjective perceptions collected via questionnaires.\n\n" +
                        "The primary goal is to identify key factors contributing to work-related hardship in order to improve working conditions in the medical sector."
            )
            .setPositiveButton("OK", null)
            .show()
    }


    private fun showConnectedSmartwatches() {
        lifecycleScope.launch {
            val nodeList = withContext(Dispatchers.IO) {
                try {
                    Tasks.await(Wearable.getNodeClient(this@MainActivity).connectedNodes)
                } catch (e: Exception) {
                    Log.e("Wearable", "Error retrieving connected watches", e)
                    null
                }
            }

            if (nodeList != null && nodeList.isNotEmpty()) {
                val deviceNames = nodeList.joinToString("\n") { "• ${it.displayName} (${it.id})" }

                AlertDialog.Builder(this@MainActivity)
                    .setTitle(tr("Connected Watches"))
                    .setIcon(R.drawable.ic_pemesa_round_watch)
                    .setMessage(deviceNames)
                    .setPositiveButton("OK", null)
                    .create()
                    .show()
            } else {
                Toast.makeText(this@MainActivity, tr("No connected watch"), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageDialog(
    currentLanguage: String,
    onDismiss: () -> Unit,
    onLanguageSelected: (String) -> Unit
) {
    val languages = listOf(AppLanguage.ENGLISH, AppLanguage.FRENCH)
    fun tr(text: String) = AppLanguage.translate(currentLanguage, text)
    var selectedLanguage by remember { mutableStateOf(currentLanguage) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Select Language")) },
        text = {
            Column {
                languages.forEach { lang ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .clickable { selectedLanguage = lang }
                    ) {
                        RadioButton(
                            selected = (lang == selectedLanguage),
                            onClick = { selectedLanguage = lang }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = AppLanguage.languageName(lang))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onLanguageSelected(selectedLanguage) }) {
                Text(tr("Confirm"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("Cancel"))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainPage(onMenuItemClick: (String) -> Unit, language: String = AppLanguage.ENGLISH) {
    fun tr(text: String) = AppLanguage.translate(language, text)
    var id by remember { mutableStateOf("") }
    var professionFull by remember { mutableStateOf("") }

    val professions = ActivityLabels.professions

    val context = LocalContext.current

    fun getAbbreviation(fullProf: String): String {
        return fullProf.split(" - ")[0]
    }

    val formOpacity = remember { Animatable(0.82f) }
    LaunchedEffect(Unit) { formOpacity.animateTo(1f, tween(320)) }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.background),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(0f),
            contentScale = ContentScale.Crop
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .zIndex(1f),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SimpleTopAppBar(onMenuItemClick, language)

            Spacer(modifier = Modifier.weight(1f))

            Surface(
                modifier = Modifier.fillMaxWidth().alpha(formOpacity.value),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    OutlinedTextField(
                        value = id,
                        onValueChange = { id = it },
                        label = { Text("ID") },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    )

                    DropdownField(
                        label = tr("Select a profession"),
                        options = professions,
                        selectedOption = professionFull,
                        onOptionSelected = { professionFull = it },
                        displayOption = { tr(it) }
                    )

                    Button(
                        onClick = {
                            val intent = Intent(context, ValidationActivity::class.java).apply {
                                putExtra("id", id)
                                putExtra("profession", getAbbreviation(professionFull))
                                putExtra("Activities", ActivityLabels.defaults(getAbbreviation(professionFull)).take(6).toTypedArray())
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        enabled = id.isNotBlank() && professionFull.isNotBlank()
                    ) {
                        Text(tr("Confirm"), fontSize = 18.sp)
                    }

                    Button(
                        onClick = {
                            id = ""
                            professionFull = ""
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Text(tr("Refresh"), fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownField(
    label: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    displayOption: (String) -> String = { it }
) {
    var expanded by remember { mutableStateOf(false) }
    // PEMESA_PROFESSION_NAMES_V1: preserve profession identifiers when display names change.
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("profession_display_names", android.content.Context.MODE_PRIVATE)
    }
    fun code(option: String): String = option.substringBefore(" - ")
    val customNames = remember(preferences, options) {
        mutableStateMapOf<String, String>().apply {
            options.forEach { option ->
                preferences.getString(code(option), null)?.let { put(option, it) }
            }
        }
    }
    fun displayProfession(option: String): String = customNames[option] ?: displayOption(option)
    val french = AppLanguage.get(context) == AppLanguage.FRENCH
    fun ui(en: String, fr: String): String = if (french) fr else en
    var editingOption by remember { mutableStateOf<String?>(null) }
    var draftName by remember { mutableStateOf("") }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = displayProfession(selectedOption),
            onValueChange = {},
            readOnly = true,
            shape = RoundedCornerShape(14.dp),
            label = { Text(label) },
            trailingIcon = {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
                .padding(bottom = 16.dp)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(displayProfession(option)) },
                    trailingIcon = {
                        IconButton(onClick = {
                            expanded = false
                            draftName = displayProfession(option)
                            editingOption = option
                        }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = ui("Rename profession", "Renommer le métier") +
                                    ": " + displayProfession(option)
                            )
                        }
                    },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }

    editingOption?.let { option ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editingOption = null },
            title = { Text(ui("Rename profession", "Renommer le métier")) },
            text = {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    label = { Text(ui("Profession name", "Nom du métier")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draftName.isNotBlank(),
                    onClick = {
                        val newName = draftName.trim()
                        customNames[option] = newName
                        preferences.edit().putString(code(option), newName).apply()
                        editingOption = null
                    }
                ) { Text(ui("Save", "Enregistrer")) }
            },
            dismissButton = {
                TextButton(onClick = { editingOption = null }) {
                    Text(ui("Cancel", "Annuler"))
                }
            }
        )
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleTopAppBar(onMenuItemClick: (String) -> Unit, language: String = AppLanguage.ENGLISH) {
    fun tr(text: String) = AppLanguage.translate(language, text)
    var expanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text("PEMESA", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            Box {
                IconButton(
                    onClick = { expanded = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Filled.Menu,
                        contentDescription = "Menu",
                        modifier = Modifier.size(32.dp)
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                ) {
                    DropdownMenuItem(
                        text = { Text(tr("Smartwatch")) },
                        onClick = {
                            onMenuItemClick("Smartwatch")
                            expanded = false
                        },
                        leadingIcon = {
                            Icon(painter = painterResource(R.drawable.ic_pemesa_round_watch), contentDescription = tr("Smartwatch"),
                                modifier = Modifier.size(24.dp), tint = Color.Unspecified)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Settings")) },
                        onClick = {
                            onMenuItemClick("Settings")
                            expanded = false
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Settings, contentDescription = tr("Settings"))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("About")) },
                        onClick = {
                            onMenuItemClick("About")
                            expanded = false
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Info, contentDescription = tr("About"))
                        }
                    )
                    Divider()
                    DropdownMenuItem(
                        text = { Text(tr("Exit")) },
                        onClick = {
                            onMenuItemClick("Exit")
                            expanded = false
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.ExitToApp, contentDescription = tr("Exit"))
                        }
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent
        )
    )
}


