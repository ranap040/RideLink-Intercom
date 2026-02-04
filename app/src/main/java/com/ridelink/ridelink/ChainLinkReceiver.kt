package com.ridelink.intercom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log

/**
 * PHASE 5: CHAIN LINK FOUNDATION
 * Catches Wi-Fi Direct system events and routes them to the Manager.
 */
class ChainLinkReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val chainLinkManager: ChainLinkManager
) : BroadcastReceiver() {

    private val TAG = "ChainLinkReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                Log.d(TAG, "Wi-Fi P2P Enabled: ${state == WifiP2pManager.WIFI_P2P_STATE_ENABLED}")
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                chainLinkManager.requestPeers()
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                if (networkInfo?.isConnected == true) {
                    Log.d(TAG, "Chain Link: Connected to party. Requesting info...")
                    manager.requestConnectionInfo(channel) { info ->
                        chainLinkManager.handleConnectionInfo(info)
                    }
                }
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {}
        }
    }
}