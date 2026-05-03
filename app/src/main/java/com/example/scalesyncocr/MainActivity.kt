package com.example.scalesyncocr

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.example.scalesyncocr.api.GeminiOCRHandler
import com.example.scalesyncocr.data.ScaleData
import com.example.scalesyncocr.databinding.ActivityMainBinding
import com.example.scalesyncocr.health.HealthConnectWriter
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val savedTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY)

    private lateinit var binding: ActivityMainBinding
    private lateinit var apiKeyStore: GeminiApiKeyStore
    private lateinit var healthConnectWriter: HealthConnectWriter
    private val geminiHandler = GeminiOCRHandler()
    private var pendingScaleData: ScaleData? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processImage(it) }
    }

    private lateinit var healthPermissionsLauncher: androidx.activity.result.ActivityResultLauncher<Set<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiKeyStore = GeminiApiKeyStore(this)
        healthConnectWriter = HealthConnectWriter(this)

        healthPermissionsLauncher = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            updateHcStatusUI()
            if (!granted.containsAll(healthConnectWriter.permissions)) {
                Toast.makeText(this, "Nicht alle Berechtigungen wurden erteilt.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Health Connect verbunden!", Toast.LENGTH_SHORT).show()
                pendingScaleData?.let { saveToHealthConnect(it) }
            }
        }

        setupApiKeyUi()
        setupClickListeners()
        binding.btnSave.isEnabled = false
        binding.tvResultMeta.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        updateHcStatusUI()
    }

    private fun setupClickListeners() {
        binding.btnConnectHc.setOnClickListener {
            onConnectHcClicked()
        }

        binding.btnUpload.setOnClickListener {
            val apiKey = requireApiKey() ?: return@setOnClickListener
            apiKeyStore.saveApiKey(apiKey)
            imagePickerLauncher.launch("image/*")
        }

        binding.btnSave.setOnClickListener {
            if (pendingScaleData == null) {
                return@setOnClickListener
            }

            val data = collectEditedScaleData() ?: return@setOnClickListener
            lifecycleScope.launch {
                val hasPerms = withContext(Dispatchers.IO) { healthConnectWriter.hasPermissions() }
                if (hasPerms) {
                    pendingScaleData = data
                    saveToHealthConnect(data)
                } else {
                    healthPermissionsLauncher.launch(healthConnectWriter.permissions)
                }
            }
        }
    }

    private fun setupApiKeyUi() {
        val existingKey = apiKeyStore.getApiKey()
        if (existingKey.isNotBlank()) {
            binding.editApiKey.setText(existingKey)
        }

        updateApiKeyStatus(existingKey)

        binding.editApiKey.doAfterTextChanged { editable ->
            val apiKey = editable?.toString()?.trim().orEmpty()
            apiKeyStore.saveApiKey(apiKey)
            binding.tilApiKey.error = null
            updateApiKeyStatus(apiKey)
        }
    }

    private fun updateApiKeyStatus(apiKey: String) {
        binding.tvApiKeyStatus.text = if (apiKey.isBlank()) {
            "Trage hier einmal deinen Gemini API-Key ein. Er wird nur lokal auf diesem Geraet gespeichert."
        } else {
            "API-Key gespeichert. Du kannst die APK jetzt direkt benutzen."
        }
    }

    private fun requireApiKey(): String? {
        val apiKey = binding.editApiKey.text?.toString()?.trim().orEmpty()
        if (apiKey.isBlank()) {
            binding.tilApiKey.error = "Gemini API-Key fehlt"
            binding.tvStatus.text = "Trage zuerst deinen Gemini API-Key ein."
            binding.editApiKey.requestFocus()
            return null
        }

        binding.tilApiKey.error = null
        return apiKey
    }

    private fun onConnectHcClicked() {
        val status = HealthConnectClient.getSdkStatus(this)
        when (status) {
            HealthConnectClient.SDK_UNAVAILABLE -> {
                // HC not installed — open Play Store
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                // HC needs update
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
            HealthConnectClient.SDK_AVAILABLE -> {
                // Open HC permission dialog
                healthPermissionsLauncher.launch(healthConnectWriter.permissions)
            }
        }
    }

    private fun updateHcStatusUI() {
        val status = HealthConnectClient.getSdkStatus(this)
        when (status) {
            HealthConnectClient.SDK_UNAVAILABLE -> {
                binding.tvHcStatus.text = "Health Connect ist nicht installiert."
                binding.btnConnectHc.text = "Health Connect installieren"
                binding.btnConnectHc.isEnabled = true
                binding.tvStatus.text = "Installiere zuerst Health Connect und verbinde die App danach mit einem Tippen."
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                binding.tvHcStatus.text = "Health Connect muss aktualisiert werden."
                binding.btnConnectHc.text = "Health Connect aktualisieren"
                binding.btnConnectHc.isEnabled = true
                binding.tvStatus.text = "Aktualisiere Health Connect und komme danach direkt wieder hierher zur Freigabe."
            }
            HealthConnectClient.SDK_AVAILABLE -> {
                lifecycleScope.launch {
                    val hasPerms = runCatching {
                        withContext(Dispatchers.IO) { healthConnectWriter.hasPermissions() }
                    }.getOrDefault(false)

                    if (hasPerms) {
                        binding.tvHcStatus.text = "Verbunden. Alle Berechtigungen erteilt."
                        binding.btnConnectHc.text = "Berechtigungen verwalten"
                        binding.btnConnectHc.isEnabled = true
                        binding.tvStatus.text = "Lade jetzt einen Waagen-Screenshot hoch."
                    } else {
                        binding.tvHcStatus.text = "Berechtigungen noch nicht erteilt."
                        binding.btnConnectHc.text = "Berechtigungen erteilen"
                        binding.btnConnectHc.isEnabled = true
                        binding.tvStatus.text = "Tippe auf \"Berechtigungen erteilen\" um zu starten."
                    }
                }
            }
        }
    }

    private fun processImage(uri: Uri) {
        lifecycleScope.launch {
            val apiKey = requireApiKey() ?: return@launch
            setLoading(true)
            binding.tvStatus.text = "Gemini AI analysiert das Bild..."
            binding.cardResults.visibility = View.GONE
            binding.tvResultMeta.visibility = View.GONE
            binding.btnSave.isEnabled = false

            try {
                val bitmap = withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        android.graphics.ImageDecoder.decodeBitmap(
                            android.graphics.ImageDecoder.createSource(contentResolver, uri)
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    }
                }

                val scaleData = withContext(Dispatchers.IO) {
                    geminiHandler.extractData(bitmap, apiKey)
                }

                pendingScaleData = scaleData
                displayExtractedData(scaleData)
                binding.tvResultMeta.text = "Alle Werte sind direkt editierbar. Passe sie bei Bedarf an und speichere dann sofort."
                binding.tvResultMeta.visibility = View.VISIBLE
                binding.tvStatus.text = "Daten erkannt. Bitte prüfen und speichern."

            } catch (e: Exception) {
                if (e.localizedMessage?.contains("api", ignoreCase = true) == true) {
                    binding.tilApiKey.error = "API-Key ungueltig oder nicht freigeschaltet"
                }
                binding.tvStatus.text = "Fehler: ${e.localizedMessage}"
                Toast.makeText(this@MainActivity, "Fehler: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun displayExtractedData(data: ScaleData) {
        binding.editWeight.setText(formatDecimal(data.weight, 2))
        binding.editBodyFat.setText(formatDecimal(data.bodyFatPercentage, 1))
        binding.editMuscleMass.setText(formatDecimal(data.muscleMassPercentage, 1))
        binding.editBoneMass.setText(formatDecimal(data.boneMass, 1))
        binding.editBodyWater.setText(formatDecimal(data.bodyWaterPercentage, 1))
        binding.editProtein.setText(formatDecimal(data.proteinPercentage, 1))
        binding.editBmi.setText(formatDecimal(data.bmi, 1))
        binding.editBmr.setText(data.basalMetabolicRate.toString())
        binding.cardResults.visibility = View.VISIBLE
        binding.btnSave.isEnabled = true
    }

    private fun collectEditedScaleData(): ScaleData? {
        clearFieldErrors()

        val weight = parseDoubleField(binding.tilWeight, binding.editWeight, "Gewicht") ?: return null
        val bodyFat = parseDoubleField(binding.tilBodyFat, binding.editBodyFat, "Koerperfett") ?: return null
        val muscleMass = parseDoubleField(binding.tilMuscleMass, binding.editMuscleMass, "Muskelmasse") ?: return null
        val boneMass = parseDoubleField(binding.tilBoneMass, binding.editBoneMass, "Knochenmasse") ?: return null
        val bodyWater = parseDoubleField(binding.tilBodyWater, binding.editBodyWater, "Koerperwasser") ?: return null
        val protein = parseDoubleField(binding.tilProtein, binding.editProtein, "Protein") ?: return null
        val bmi = parseDoubleField(binding.tilBmi, binding.editBmi, "BMI") ?: return null
        val bmr = parseIntField(binding.tilBmr, binding.editBmr, "Grundumsatz") ?: return null

        return ScaleData(
            weight = weight,
            bodyFatPercentage = bodyFat,
            muscleMassPercentage = muscleMass,
            boneMass = boneMass,
            bodyWaterPercentage = bodyWater,
            proteinPercentage = protein,
            bmi = bmi,
            basalMetabolicRate = bmr
        )
    }

    private fun clearFieldErrors() {
        listOf(
            binding.tilWeight,
            binding.tilBodyFat,
            binding.tilMuscleMass,
            binding.tilBoneMass,
            binding.tilBodyWater,
            binding.tilProtein,
            binding.tilBmi,
            binding.tilBmr
        ).forEach { inputLayout ->
            inputLayout.error = null
        }
    }

    private fun parseDoubleField(
        inputLayout: TextInputLayout,
        input: TextInputEditText,
        fieldName: String,
    ): Double? {
        val rawValue = input.text?.toString()?.trim()?.replace(',', '.')
        if (rawValue.isNullOrEmpty()) {
            inputLayout.error = "$fieldName fehlt"
            return null
        }

        val parsedValue = rawValue.toDoubleOrNull()
        if (parsedValue == null) {
            inputLayout.error = "$fieldName ist ungueltig"
            return null
        }

        return parsedValue
    }

    private fun parseIntField(
        inputLayout: TextInputLayout,
        input: TextInputEditText,
        fieldName: String,
    ): Int? {
        val rawValue = input.text?.toString()?.trim()
        if (rawValue.isNullOrEmpty()) {
            inputLayout.error = "$fieldName fehlt"
            return null
        }

        val parsedValue = rawValue.toIntOrNull()
        if (parsedValue == null) {
            inputLayout.error = "$fieldName ist ungueltig"
            return null
        }

        return parsedValue
    }

    private fun formatDecimal(value: Double, decimals: Int): String {
        return String.format(Locale.GERMANY, "%1$.${decimals}f", value)
    }

    private fun saveToHealthConnect(data: ScaleData) {
        lifecycleScope.launch {
            setLoading(true)
            binding.tvStatus.text = "Wird in Health Connect gespeichert..."

            try {
                val result = withContext(Dispatchers.IO) {
                    healthConnectWriter.writeScaleData(data)
                }
                val savedAtText = savedTimeFormatter.format(result.savedInstant.atZone(ZoneId.systemDefault()))
                binding.tvStatus.text = "Gespeichert in Health Connect."
                binding.tvResultMeta.text = "Gespeichert mit der aktuellen Geraetezeit: $savedAtText."
                Toast.makeText(this@MainActivity, "Erfolgreich gespeichert!", Toast.LENGTH_SHORT).show()
                binding.tvResultMeta.visibility = View.VISIBLE
            } catch (e: SecurityException) {
                binding.tvStatus.text = "Berechtigungen fehlen. Bitte verbinden."
                healthPermissionsLauncher.launch(healthConnectWriter.permissions)
            } catch (e: Exception) {
                binding.tvStatus.text = "Fehler beim Speichern: ${e.localizedMessage}"
                Toast.makeText(this@MainActivity, "Fehler: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnUpload.isEnabled = !loading
        binding.btnSave.isEnabled = !loading && pendingScaleData != null
        binding.btnConnectHc.isEnabled = !loading
    }
}
