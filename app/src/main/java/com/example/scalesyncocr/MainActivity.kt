package com.example.scalesyncocr

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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

    private val savedTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.getDefault())

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
                Toast.makeText(this, getString(R.string.toast_permissions_not_all), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.toast_health_connect_connected), Toast.LENGTH_SHORT).show()
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
            getString(R.string.api_key_status_empty)
        } else {
            getString(R.string.api_key_status_saved)
        }
    }

    private fun requireApiKey(): String? {
        val apiKey = binding.editApiKey.text?.toString()?.trim().orEmpty()
        if (apiKey.isBlank()) {
            binding.tilApiKey.error = getString(R.string.api_key_missing)
            binding.tvStatus.text = getString(R.string.status_enter_api_key_first)
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
                binding.tvHcStatus.text = getString(R.string.status_hc_not_installed)
                binding.btnConnectHc.text = getString(R.string.health_connect_install)
                binding.btnConnectHc.isEnabled = true
                binding.tvStatus.text = getString(R.string.status_hc_install_hint)
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                binding.tvHcStatus.text = getString(R.string.status_hc_update_required)
                binding.btnConnectHc.text = getString(R.string.health_connect_update)
                binding.btnConnectHc.isEnabled = true
                binding.tvStatus.text = getString(R.string.status_hc_update_hint)
            }
            HealthConnectClient.SDK_AVAILABLE -> {
                lifecycleScope.launch {
                    val hasPerms = runCatching {
                        withContext(Dispatchers.IO) { healthConnectWriter.hasPermissions() }
                    }.getOrDefault(false)

                    if (hasPerms) {
                        binding.tvHcStatus.text = getString(R.string.status_permissions_granted)
                        binding.btnConnectHc.text = getString(R.string.health_connect_manage)
                        binding.btnConnectHc.isEnabled = true
                        binding.tvStatus.text = getString(R.string.status_ready_upload)
                    } else {
                        binding.tvHcStatus.text = getString(R.string.status_permissions_missing)
                        binding.btnConnectHc.text = getString(R.string.health_connect_grant)
                        binding.btnConnectHc.isEnabled = true
                        binding.tvStatus.text = getString(R.string.status_tap_grant)
                    }
                }
            }
        }
    }

    private fun processImage(uri: Uri) {
        lifecycleScope.launch {
            val apiKey = requireApiKey() ?: return@launch
            setLoading(true)
            binding.tvStatus.text = getString(R.string.status_analyzing)
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
                binding.tvResultMeta.text = getString(R.string.result_meta_editable)
                binding.tvResultMeta.visibility = View.VISIBLE
                binding.tvStatus.text = getString(R.string.status_data_detected)

            } catch (e: Exception) {
                val errorMessage = e.localizedMessage ?: getString(R.string.error_unknown)
                if (e.localizedMessage?.contains("api", ignoreCase = true) == true) {
                    binding.tilApiKey.error = getString(R.string.api_key_invalid_or_disabled)
                }
                binding.tvStatus.text = getString(R.string.error_prefix, errorMessage)
                Toast.makeText(this@MainActivity, getString(R.string.error_prefix, errorMessage), Toast.LENGTH_LONG).show()
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

        val weight = parseDoubleField(binding.tilWeight, binding.editWeight, getString(R.string.metric_weight)) ?: return null
        val bodyFat = parseDoubleField(binding.tilBodyFat, binding.editBodyFat, getString(R.string.metric_body_fat)) ?: return null
        val muscleMass = parseDoubleField(binding.tilMuscleMass, binding.editMuscleMass, getString(R.string.metric_muscle_mass)) ?: return null
        val boneMass = parseDoubleField(binding.tilBoneMass, binding.editBoneMass, getString(R.string.metric_bone_mass)) ?: return null
        val bodyWater = parseDoubleField(binding.tilBodyWater, binding.editBodyWater, getString(R.string.metric_body_water)) ?: return null
        val protein = parseDoubleField(binding.tilProtein, binding.editProtein, getString(R.string.metric_protein)) ?: return null
        val bmi = parseDoubleField(binding.tilBmi, binding.editBmi, getString(R.string.metric_bmi)) ?: return null
        val bmr = parseIntField(binding.tilBmr, binding.editBmr, getString(R.string.metric_bmr)) ?: return null

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
            inputLayout.error = getString(R.string.field_required, fieldName)
            return null
        }

        val parsedValue = rawValue.toDoubleOrNull()
        if (parsedValue == null) {
            inputLayout.error = getString(R.string.field_invalid, fieldName)
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
            inputLayout.error = getString(R.string.field_required, fieldName)
            return null
        }

        val parsedValue = rawValue.toIntOrNull()
        if (parsedValue == null) {
            inputLayout.error = getString(R.string.field_invalid, fieldName)
            return null
        }

        return parsedValue
    }

    private fun formatDecimal(value: Double, decimals: Int): String {
        return String.format(Locale.getDefault(), "%1$.${decimals}f", value)
    }

    private fun saveToHealthConnect(data: ScaleData) {
        lifecycleScope.launch {
            setLoading(true)
            binding.tvStatus.text = getString(R.string.status_saving)

            try {
                val result = withContext(Dispatchers.IO) {
                    healthConnectWriter.writeScaleData(data)
                }
                val savedAtText = savedTimeFormatter.format(result.savedInstant.atZone(ZoneId.systemDefault()))
                binding.tvStatus.text = getString(R.string.status_saved)
                binding.tvResultMeta.text = getString(R.string.status_saved_at, savedAtText)
                Toast.makeText(this@MainActivity, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
                binding.tvResultMeta.visibility = View.VISIBLE
            } catch (e: SecurityException) {
                binding.tvStatus.text = getString(R.string.status_permissions_missing_connect)
                healthPermissionsLauncher.launch(healthConnectWriter.permissions)
            } catch (e: Exception) {
                val errorMessage = e.localizedMessage ?: getString(R.string.error_unknown)
                binding.tvStatus.text = getString(R.string.error_prefix, errorMessage)
                Toast.makeText(this@MainActivity, getString(R.string.error_prefix, errorMessage), Toast.LENGTH_LONG).show()
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
