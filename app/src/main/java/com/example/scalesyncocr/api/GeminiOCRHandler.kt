package com.example.scalesyncocr.api

import android.graphics.Bitmap
import com.example.scalesyncocr.BuildConfig
import com.example.scalesyncocr.data.ScaleData
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.gson.Gson

class GeminiOCRHandler {

    private val gson = Gson()
    
    private val generativeModel = GenerativeModel(
        modelName = "gemini-3.1-flash-lite-preview",
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    private val prompt = """
        Analyze the provided image of a body composition scale report.
        Extract all health metrics and return ONLY a valid JSON object with NO additional text or markdown.

        Metrics to extract:
                1. Weight in kg (numeric only, e.g., 84.80)
                2. Body Fat percentage (numeric only, e.g., 13.5)
                3. Muscle Mass percentage (numeric only, e.g., 69.7)
                4. Bone mineral mass in kg (numeric only, e.g., 3.7)
                5. Body water percentage (numeric only, e.g., 64.2)
                6. Protein percentage (numeric only, e.g., 17.1)
                7. BMI (numeric only, e.g., 21.9)
                8. Basal metabolic rate in kcal (numeric only, e.g., 1955)

        Rules:
                - Do not extract, infer, or return any date or time field.
        - If the image uses commas as decimal separators, convert to dots.
        - Strip all unit suffixes — return raw numbers only.
        - Return ONLY this exact JSON structure:
        {
          "weight": { "value": 84.80, "unit": "kg" },
          "bodyFatPercentage": { "value": 13.5 },
          "muscleMassPercentage": { "value": 69.7 },
          "boneMass": { "value": 3.7, "unit": "kg" },
          "bodyWaterPercentage": { "value": 64.2 },
          "proteinPercentage": { "value": 17.1 },
          "bmi": 21.9,
          "basalMetabolicRate": { "value": 1955, "unit": "kcal" }
        }
    """.trimIndent()

    suspend fun extractData(bitmap: Bitmap): ScaleData {
        val inputContent = content {
            image(bitmap)
            text(prompt)
        }

        val response = generativeModel.generateContent(inputContent)
        
        val rawText = response.text ?: throw IllegalStateException("Gemini returned empty response")

        val jsonText = rawText
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val extracted = gson.fromJson(jsonText, ExtractedScaleData::class.java)
            ?: throw IllegalStateException("Failed to parse Gemini response as JSON")

        return ScaleData(
            weight = extracted.weight?.value ?: 0.0,
            bodyFatPercentage = extracted.bodyFatPercentage?.value ?: 0.0,
            muscleMassPercentage = extracted.muscleMassPercentage?.value ?: 0.0,
            boneMass = extracted.boneMass?.value ?: 0.0,
            bodyWaterPercentage = extracted.bodyWaterPercentage?.value ?: 0.0,
            proteinPercentage = extracted.proteinPercentage?.value ?: 0.0,
            bmi = extracted.bmi ?: 0.0,
            basalMetabolicRate = extracted.basalMetabolicRate?.value?.toInt() ?: 0
        )
    }
}