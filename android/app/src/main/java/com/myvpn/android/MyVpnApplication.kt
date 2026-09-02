package com.myvpn.android
import android.app.Application
import com.myvpn.android.data.*
import com.myvpn.android.vpn.*
class MyVpnApplication:Application(){val auth by lazy{AuthRepository(ApiFactory.create(),DeviceIdentityStore(this))};val access by lazy{VpnAccessRepository(ApiFactory.create(),auth)};val engine:VpnEngine by lazy{LibXrayVpnEngine(this)}}
