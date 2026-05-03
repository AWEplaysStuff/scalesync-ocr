package com.example.scalesyncocr.api

data class ExtractedScaleData(
    val weight: ValueUnit?,
    val bodyFatPercentage: ValueUnit?,
    val muscleMassPercentage: ValueUnit?,
    val boneMass: ValueUnit?,
    val bodyWaterPercentage: ValueUnit?,
    val proteinPercentage: ValueUnit?,
    val bmi: Double?,
    val basalMetabolicRate: ValueUnit?
)

data class ValueUnit(
    val value: Double,
    val unit: String? = null
)