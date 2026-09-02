package com.myvpn.android.data
import android.content.Context
import android.security.keystore.*
import android.util.Base64
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.nio.ByteBuffer
import java.security.*
import java.util.UUID
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec
private val Context.ds by preferencesDataStore("myvpn_device")
class DeviceIdentityStore(private val c:Context){private val install=stringPreferencesKey("install");private val secret=stringPreferencesKey("secret");private val at=stringPreferencesKey("access");private val rt=stringPreferencesKey("refresh");suspend fun identity():Pair<String,String>{val p=c.ds.data.first();val i=p[install]?:UUID.randomUUID().toString();val s=p[secret]?:enc(newSecret()).also{encryptedSecret->c.ds.edit{preferences->preferences[secret]=encryptedSecret}};if(p[install]==null)c.ds.edit{it[install]=i};return i to dec(s)};suspend fun session():Session?{val p=c.ds.data.first();return if(p[at]==null||p[rt]==null)null else Session(dec(p[at]!!),dec(p[rt]!!),0)};suspend fun save(s:Session){c.ds.edit{it[at]=enc(s.accessToken);it[rt]=enc(s.refreshToken)}};suspend fun clear(){c.ds.edit{it.remove(at);it.remove(rt)}};private fun newSecret()=ByteArray(32).also{SecureRandom().nextBytes(it)}.let{Base64.encodeToString(it,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)};private fun key():SecretKey{val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)};(ks.getKey("myvpn_aes",null)as?SecretKey)?.let{return it};return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder("myvpn_aes",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey()};private fun enc(v:String):String{val x=Cipher.getInstance("AES/GCM/NoPadding");x.init(Cipher.ENCRYPT_MODE,key());val b=x.doFinal(v.toByteArray());return Base64.encodeToString(ByteBuffer.allocate(4+x.iv.size+b.size).putInt(x.iv.size).put(x.iv).put(b).array(),Base64.NO_WRAP)};private fun dec(v:String):String{val b=Base64.decode(v,Base64.NO_WRAP);val n=ByteBuffer.wrap(b).int;val iv=b.copyOfRange(4,4+n);val x=Cipher.getInstance("AES/GCM/NoPadding");x.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv));return String(x.doFinal(b.copyOfRange(4+n,b.size)))}}
