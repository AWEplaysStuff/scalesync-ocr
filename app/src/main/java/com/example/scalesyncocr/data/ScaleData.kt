package com.example.scalesyncocr.data

data class ScaleData(
    val weight: Double,
    val bodyFatPercentage: Double,
    val muscleMassPercentage: Double,
    val boneMass: Double,
    val bodyWaterPercentage: Double,
    val proteinPercentage: Double,
    val bmi: Double,
    val basalMetabolicRate: Int
)
