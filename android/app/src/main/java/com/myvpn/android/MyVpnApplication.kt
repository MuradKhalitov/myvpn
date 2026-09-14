package com.myvpn.android
import android.app.Application
import com.myvpn.android.data.*
import com.myvpn.android.vpn.*
class MyVpnApplication:Application(){private val api by lazy{ApiFactory.create()};private val store by lazy{DeviceIdentityStore(this)};val phoneAuth by lazy{PhoneAuthRepository(api,store)};val access by lazy{VpnAccessRepository(api,phoneAuth)};val payments by lazy{PaymentRepository(api,phoneAuth)};val appVersion by lazy{AppVersionRepository(api)};val engine:VpnEngine by lazy{LibXrayVpnEngine(this)}
    val vpnAppSelectionStore by lazy { VpnAppSelectionStore(getSharedPreferences("myvpn_vpn_apps", MODE_PRIVATE)) }
    val launchableApps by lazy { LaunchableAppsRepository(packageManager, packageName) }
}
