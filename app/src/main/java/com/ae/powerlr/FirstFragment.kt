package com.ae.powerlr

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.ae.powerlr.databinding.FragmentFirstBinding
import com.dsi.ant.plugins.antplus.pcc.AntPlusBikePowerPcc
import com.dsi.ant.plugins.antplus.pcc.AntPlusHeartRatePcc
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceState
import com.dsi.ant.plugins.antplus.pcc.defines.EventFlag
import com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle
import java.math.BigDecimal
import java.util.EnumSet

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    // ANT+ Variables
    private var powerReleaseHandle: PccReleaseHandle<AntPlusBikePowerPcc>? = null
    private var bikePowerPcc: AntPlusBikePowerPcc? = null
    
    private var hrReleaseHandle: PccReleaseHandle<AntPlusHeartRatePcc>? = null
    private var heartRatePcc: AntPlusHeartRatePcc? = null
    
    // BLE Peripheral
    private var bleHrPeripheral: BleHrPeripheral? = null
    
    // Default ANT+ Device IDs
    private var powerAntId = 20623
    private var hrAntId = 22184

    // Data for moving average
    private val powerSamples = ArrayDeque<Triple<Long, Int, Int>>() // Timestamp (ms), Power (W), Right Percentage
    private val AVERAGE_WINDOW_MS = 30000L // 30 seconds

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bleHrPeripheral = BleHrPeripheral(requireContext())

        binding.textviewStatus.text = "Ready to connect"
        
        binding.editTextPowerAntId.setText(powerAntId.toString())
        binding.editTextHrAntId.setText(hrAntId.toString())

        binding.buttonStart.setOnClickListener {
            val powerIdInput = binding.editTextPowerAntId.text.toString().toIntOrNull()
            val hrIdInput = binding.editTextHrAntId.text.toString().toIntOrNull()
            
            if (powerIdInput != null) {
                powerAntId = powerIdInput
            }
            if (hrIdInput != null) {
                hrAntId = hrIdInput
            }
            
            binding.buttonStart.isEnabled = false
            binding.buttonStop.isEnabled = true
            binding.editTextPowerAntId.isEnabled = false
            binding.editTextHrAntId.isEnabled = false
            
            startAntConnections()
            startBlePeripheral()
        }
        
        binding.buttonStop.setOnClickListener {
            closeAntConnections()
            stopBlePeripheral()
            binding.buttonStart.isEnabled = true
            binding.buttonStop.isEnabled = false
            binding.editTextPowerAntId.isEnabled = true
            binding.editTextHrAntId.isEnabled = true
            binding.textviewStatus.text = "Disconnected"
        }
    }

    private fun startAntConnections() {
        binding.textviewStatus.text = "Connecting..."
        connectToPowerMeter()
        connectToHeartRateMonitor()
    }
    
    private fun closeAntConnections() {
        powerReleaseHandle?.close()
        powerReleaseHandle = null
        bikePowerPcc = null
        
        hrReleaseHandle?.close()
        hrReleaseHandle = null
        heartRatePcc = null
    }
    
    private fun startBlePeripheral() {
        try {
            bleHrPeripheral?.startServer()
            bleHrPeripheral?.startAdvertising()
            updateStatus("ANT+ & BLE Started")
        } catch (e: SecurityException) {
            updateStatus("BLE Permission Error")
        } catch (e: Exception) {
            updateStatus("BLE Start Error: ${e.message}")
        }
    }
    
    private fun stopBlePeripheral() {
        try {
            bleHrPeripheral?.stopAdvertising()
            bleHrPeripheral?.stopServer()
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun connectToPowerMeter() {
        powerReleaseHandle = AntPlusBikePowerPcc.requestAccess(
            requireContext(), 
            powerAntId,    
            0, // searchProximityThreshold
            { result, resultCode, initialDeviceState ->
                when (resultCode) {
                    RequestAccessResult.SUCCESS -> {
                        bikePowerPcc = result
                        updateStatus("Power Connected: $initialDeviceState")
                        subscribeToPowerEvents(result)
                    }
                    else -> {
                        updateStatus("Power Error: $resultCode")
                        binding.textviewDebug.text = "Power Fail: $resultCode"
                    }
                }
            },
            { deviceState ->
                activity?.runOnUiThread {
                    if (deviceState == DeviceState.DEAD) {
                        bikePowerPcc = null
                        updateStatus("Power Disconnected")
                    } else {
                         updateStatus("Power State: $deviceState")
                    }
                }
            }
        )
    }
    
    private fun connectToHeartRateMonitor() {
        hrReleaseHandle = AntPlusHeartRatePcc.requestAccess(
            requireContext(),
            hrAntId,
            0,
            { result, resultCode, initialDeviceState ->
                when (resultCode) {
                    RequestAccessResult.SUCCESS -> {
                        heartRatePcc = result
                        updateStatus("HR Connected: $initialDeviceState")
                        subscribeToHeartRateEvents(result)
                    }
                    else -> {
                        updateStatus("HR Error: $resultCode")
                    }
                }
            },
            { deviceState ->
                activity?.runOnUiThread {
                    if (deviceState == DeviceState.DEAD) {
                        heartRatePcc = null
                    }
                }
            }
        )
    }

    private fun updateStatus(text: String) {
        activity?.runOnUiThread {
            // Append or update status depending on length, or just show latest relevant info
            binding.textviewStatus.text = text
        }
    }

    private fun subscribeToPowerEvents(pcc: AntPlusBikePowerPcc) {
        pcc.subscribeCalculatedPowerEvent(
            object : AntPlusBikePowerPcc.ICalculatedPowerReceiver {
                override fun onNewCalculatedPower(
                    estTimestamp: Long,
                    eventFlags: EnumSet<EventFlag>,
                    dataSource: AntPlusBikePowerPcc.DataSource,
                    calculatedPower: BigDecimal
                ) {
                    handlePower(calculatedPower.toInt())
                }
            }
        )
        
        pcc.subscribePedalPowerBalanceEvent(
            object : AntPlusBikePowerPcc.IPedalPowerBalanceReceiver {
                override fun onNewPedalPowerBalance(
                    estTimestamp: Long,
                    eventFlags: EnumSet<EventFlag>,
                    rightPedalIndicator: Boolean,
                    pedalPowerBalance: Int
                ) {
                    handleBalance(rightPedalIndicator, pedalPowerBalance)
                }
            }
        )
    }
    
    private fun subscribeToHeartRateEvents(pcc: AntPlusHeartRatePcc) {
        pcc.subscribeHeartRateDataEvent(
            object : AntPlusHeartRatePcc.IHeartRateDataReceiver {
                override fun onNewHeartRateData(
                    estTimestamp: Long,
                    eventFlags: EnumSet<EventFlag>,
                    computedHeartRate: Int,
                    heartBeatCount: Long,
                    heartBeatEventTime: BigDecimal,
                    dataState: AntPlusHeartRatePcc.DataState
                ) {
                    activity?.runOnUiThread {
                        binding.textviewHeartRate.text = "$computedHeartRate\nBPM"
                    }
                    // Broadcast via BLE
                    bleHrPeripheral?.notifyHeartRate(computedHeartRate)
                }
            }
        )
    }
    
    // Temporary variables to sync (simple approach)
    private var lastPower = 0
    
    private fun handlePower(power: Int) {
        lastPower = power
    }
    
    private fun handleBalance(rightPedalIndicator: Boolean, pedalPowerBalance: Int) {
        if (rightPedalIndicator) {
            addSample(System.currentTimeMillis(), lastPower, pedalPowerBalance)
        } else {
            if (pedalPowerBalance != -1) { 
                 addSample(System.currentTimeMillis(), lastPower, pedalPowerBalance)
            }
        }
    }

    private fun addSample(timestamp: Long, power: Int, rightPercentage: Int) {
        synchronized(powerSamples) {
            powerSamples.addLast(Triple(timestamp, power, rightPercentage))
            
            // Remove old samples
            while (powerSamples.isNotEmpty() && timestamp - powerSamples.first().first > AVERAGE_WINDOW_MS) {
                powerSamples.removeFirst()
            }
            
            calculateAndDisplayAverage()
        }
    }

    private fun calculateAndDisplayAverage() {
        if (powerSamples.isEmpty()) return

        var totalPower = 0.0
        var totalRightPower = 0.0
        
        for (sample in powerSamples) {
            val p = sample.second.toDouble()
            val rPct = sample.third.toDouble()
            
            totalPower += p
            totalRightPower += p * (rPct / 100.0)
        }

        if (totalPower <= 0.001) {
             // Avoid division by zero if coasting
             return
        }

        val avgRightPct = (totalRightPower / totalPower) * 100.0
        val avgLeftPct = 100.0 - avgRightPct

        activity?.runOnUiThread {
            binding.textviewRightPower.text = "R: ${String.format("%.1f", avgRightPct)}%"
            binding.textviewLeftPower.text = "L: ${String.format("%.1f", avgLeftPct)}%"
        }
    }
    
    private fun updateDebugInfo(info: String) {
        activity?.runOnUiThread {
            binding.textviewDebug.text = info
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        closeAntConnections()
        stopBlePeripheral()
        _binding = null
    }
}
