package com.example.scalesyncocr.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import com.example.scalesyncocr.data.ScaleData
import java.time.Instant
import java.time.ZoneId

class HealthConnectWriter(private val context: Context) {

    data class WriteResult(
        val savedInstant: Instant,
    )

    val client: HealthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context)
    }

    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
        HealthPermission.getWritePermission(LeanBodyMassRecord::class),
        HealthPermission.getWritePermission(BoneMassRecord::class),
        HealthPermission.getWritePermission(BasalMetabolicRateRecord::class),
        HealthPermission.getWritePermission(BodyWaterMassRecord::class),
    )

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermissions(): Boolean {
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(permissions)
    }

    suspend fun writeScaleData(scaleData: ScaleData): WriteResult {
        if (!hasPermissions()) {
            throw SecurityException("Health Connect permissions not granted")
        }

        val zoneId = ZoneId.systemDefault()
        val effectiveInstant = Instant.now().minusSeconds(5)
        val zoneOffset = zoneId.rules.getOffset(effectiveInstant)

        val records = mutableListOf<Record>()

        records += WeightRecord(
            weight = Mass.kilograms(scaleData.weight),
            time = effectiveInstant,
            zoneOffset = zoneOffset
        )

        records += BodyFatRecord(
            percentage = Percentage(scaleData.bodyFatPercentage),
            time = effectiveInstant,
            zoneOffset = zoneOffset
        )

        // Lean body mass = weight × (1 − bodyFat/100)
        val leanMass = scaleData.weight * (1.0 - scaleData.bodyFatPercentage / 100.0)
        records += LeanBodyMassRecord(
            mass = Mass.kilograms(leanMass),
            time = effectiveInstant,
            zoneOffset = zoneOffset
        )

        records += BoneMassRecord(
            mass = Mass.kilograms(scaleData.boneMass),
            time = effectiveInstant,
            zoneOffset = zoneOffset
        )

        records += BasalMetabolicRateRecord(
            basalMetabolicRate = Power.kilocaloriesPerDay(scaleData.basalMetabolicRate.toDouble()),
            time = effectiveInstant,
            zoneOffset = zoneOffset
        )

        // Body water mass = bodyWater% × weight
        val bodyWaterMass = scaleData.bodyWaterPercentage / 100.0 * scaleData.weight
        records += BodyWaterMassRecord(
            mass = Mass.kilograms(bodyWaterMass),
            time = effectiveInstant,
            zoneOffset = zoneOffset
        )

        client.insertRecords(records)

        return WriteResult(
            savedInstant = effectiveInstant,
        )
    }
}
