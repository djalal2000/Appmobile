package com.bharathvishal.messagecommunicationusingwearabledatalayer

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

    private var currentLanguage by mutableStateOf("English")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showLangDialog by remember { mutableStateOf(false) }

                    if (showLangDialog) {
                        LanguageDialog(
                            currentLanguage = currentLanguage,
                            onDismiss = { showLangDialog = false },
                            onLanguageSelected = { lang ->
                                currentLanguage = lang
                                Toast.makeText(this, "Selected language: $lang", Toast.LENGTH_SHORT).show()
                                showLangDialog = false
                            }
                        )
                    }

                    MainPage(
                        onMenuItemClick = { item ->
                            when (item) {
                                "Exit" -> finish()
                                "Smartwatch" -> showConnectedSmartwatches()
                                "Settings" -> showLangDialog = true
                                "About" -> showAboutDialog()
                            }
                        }
                    )

                }
            }
        }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this@MainActivity)
            .setTitle("About")
            .setMessage(
                "Developed by Djalal Eddine Bouchekif, a PhD student at the IRIT Laboratory (Institute of Computer Science Research of Toulouse).\n\n" +
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
                    .setTitle("Connected Watches")
                    .setMessage(deviceNames)
                    .setPositiveButton("OK", null)
                    .create()
                    .show()
            } else {
                Toast.makeText(this@MainActivity, "No connected watch", Toast.LENGTH_SHORT).show()
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
    val languages = listOf("English", "French")
    var selectedLanguage by remember { mutableStateOf(currentLanguage) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Language") },
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
                        Text(text = lang)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onLanguageSelected(selectedLanguage) }) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainPage(onMenuItemClick: (String) -> Unit) {
    var id by remember { mutableStateOf("") }
    var professionFull by remember { mutableStateOf("") }
    var activityList by remember { mutableStateOf(listOf<String>()) }

    val professions = listOf(
        "RN - Registered Nurse",
        "AS - Certified Nursing Assistant",
        "ASH - Environmental Services worker"
    )
    val activityRN = listOf(
        "Measure temperature",
        "Inject medication",
        "Apply dressing",
        "Take blood pressure",
        "Activity 5",
        "Activity 6"
    )

    val activityCNA = listOf(
        "Bed-to-bed transfer",
        "Bed-to-chair transfer",
        "Toilet assistance",
        "Bed making",
        "Office work",
        "Climbing the stairs"
    )

    val activityEVS = listOf(
        "Bed-to-chair transfer",
        "Room cleaning",
        "Floor cleaning",
        "Meal distribution",
        "Medication storage",
        "Climbing the stairs"
    )


    val context = LocalContext.current

    fun getAbbreviation(fullProf: String): String {
        return fullProf.split(" - ")[0]
    }

    LaunchedEffect(professionFull) {
        val abbrev = getAbbreviation(professionFull)
        activityList = when (abbrev) {
            "RN" -> activityRN
            "AS" -> activityCNA
            "ASH" -> activityEVS
            else -> emptyList()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.background),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(0f),
            contentScale = ContentScale.Crop
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .zIndex(1f),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SimpleTopAppBar(onMenuItemClick)

            Spacer(modifier = Modifier.weight(1f))

            OutlinedTextField(
                value = id,
                onValueChange = { id = it },
                label = { Text("ID") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            )

            DropdownField(
                label = "Select a profession",
                options = professions,
                selectedOption = professionFull,
                onOptionSelected = { professionFull = it }
            )

            Button(
                onClick = {
                    val intent = Intent(context, ValidationActivity::class.java).apply {
                        putExtra("id", id)
                        putExtra("profession", getAbbreviation(professionFull))
                        putExtra("Activities", activityList.toTypedArray())
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(top = 16.dp),
                enabled = id.isNotBlank() && professionFull.isNotBlank()
            ) {
                Text("Confirm", fontSize = 18.sp)
            }

            Button(
                onClick = {
                    id = ""
                    professionFull = ""
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(top = 16.dp)
            ) {
                Text("Refresh", fontSize = 18.sp)
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
    onOptionSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedOption,
            onValueChange = {},
            readOnly = true,
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
                    text = { Text(option) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleTopAppBar(onMenuItemClick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    TopAppBar(
        title = {},
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
                        text = { Text("Smartwatch") },
                        onClick = {
                            onMenuItemClick("Smartwatch")
                            expanded = false
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Watch, contentDescription = "Smartwatch")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        onClick = {
                            onMenuItemClick("Settings")
                            expanded = false
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("About") },
                        onClick = {
                            onMenuItemClick("About")
                            expanded = false
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Info, contentDescription = "About")
                        }
                    )
                    Divider()
                    DropdownMenuItem(
                        text = { Text("Exit") },
                        onClick = {
                            onMenuItemClick("Exit")
                            expanded = false
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.ExitToApp, contentDescription = "Exit")
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


