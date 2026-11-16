package com.boucle.app.polar

import android.content.Context
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.androidcommunications.api.ble.model.gatt.client.ChargeState
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApi.PolarBleSdkFeature
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.PolarBleDisconnectInfo
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHealthThermometerData
import com.polar.sdk.api.model.PolarHrData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class Connection { DISCONNECTED, CONNECTING, CONNECTED }

data class LinkState(
    val bluetoothOn: Boolean = true,
    val connection: Connection = Connection.DISCONNECTED,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val firmware: String? = null,
    val readyFeatures: Set<PolarBleSdkFeature> = emptySet(),
    val liveHr: Int? = null,
    val lastDisconnectReason: String? = null,
)

/**
 * Seul point de contact avec le Polar BLE SDK : état de la liaison sous forme de [StateFlow],
 * et accès à l'API pour les appels de données. Le bracelet traite les commandes une par une,
 * donc les appelants ne doivent jamais lancer deux appels en parallèle.
 */
class PolarGateway(context: Context) {

    val api: PolarBleApi = PolarBleApiDefaultImpl.defaultImplementation(
        context.applicationContext,
        setOf(
            PolarBleSdkFeature.FEATURE_HR,
            PolarBleSdkFeature.FEATURE_DEVICE_INFO,
            PolarBleSdkFeature.FEATURE_BATTERY_INFO,
            PolarBleSdkFeature.FEATURE_POLAR_DEVICE_TIME_SETUP,
            PolarBleSdkFeature.FEATURE_POLAR_FILE_TRANSFER,
            PolarBleSdkFeature.FEATURE_POLAR_ACTIVITY_DATA,
            PolarBleSdkFeature.FEATURE_POLAR_SLEEP_DATA,
            PolarBleSdkFeature.FEATURE_POLAR_TEMPERATURE_DATA,
            PolarBleSdkFeature.FEATURE_POLAR_TRAINING_DATA,
            PolarBleSdkFeature.FEATURE_POLAR_DEVICE_CONTROL,
        ),
    )

    private val _state = MutableStateFlow(LinkState())
    val state: StateFlow<LinkState> = _state.asStateFlow()

    init {
        api.setAutomaticReconnection(true)
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun blePowerStateChanged(powered: Boolean) {
                _state.update { it.copy(bluetoothOn = powered) }
            }

            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                _state.update {
                    it.copy(
                        connection = Connection.CONNECTING,
                        deviceId = polarDeviceInfo.deviceId,
                        deviceName = polarDeviceInfo.name,
                    )
                }
            }

            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                _state.update {
                    it.copy(
                        connection = Connection.CONNECTED,
                        deviceId = polarDeviceInfo.deviceId,
                        deviceName = polarDeviceInfo.name,
                        lastDisconnectReason = null,
                    )
                }
            }

            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo, info: PolarBleDisconnectInfo) {
                _state.update {
                    it.copy(
                        connection = Connection.DISCONNECTED,
                        readyFeatures = emptySet(),
                        liveHr = null,
                        lastDisconnectReason = info.reason.name,
                    )
                }
            }

            override fun bleSdkFeatureReady(identifier: String, feature: PolarBleSdkFeature) {
                _state.update { it.copy(readyFeatures = it.readyFeatures + feature) }
            }

            override fun batteryLevelReceived(identifier: String, level: Int) {
                _state.update { it.copy(batteryPercent = level) }
            }

            override fun batteryChargingStatusReceived(identifier: String, chargingStatus: ChargeState) {
                val charging = when (chargingStatus) {
                    ChargeState.CHARGING -> true
                    ChargeState.DISCHARGING_ACTIVE, ChargeState.DISCHARGING_INACTIVE -> false
                    ChargeState.UNKNOWN -> null
                }
                _state.update { it.copy(charging = charging) }
            }

            override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {
                if (uuid == FIRMWARE_REVISION_UUID) {
                    _state.update { it.copy(firmware = value.trim()) }
                }
            }

            override fun disInformationReceived(identifier: String, disInfo: DisInfo) = Unit

            override fun htsNotificationReceived(identifier: String, data: PolarHealthThermometerData) = Unit

            override fun hrNotificationReceived(identifier: String, data: PolarHrData.PolarHrSample) {
                _state.update { it.copy(liveHr = data.hr) }
            }
        })
    }

    fun scan(): Flow<PolarDeviceInfo> = api.searchForDevice()

    fun connect(deviceId: String) = api.connectToDevice(deviceId)

    fun disconnect(deviceId: String) = api.disconnectFromDevice(deviceId)

    private companion object {
        val FIRMWARE_REVISION_UUID: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    }
}
