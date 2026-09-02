package com.myvpn.android.data
import com.myvpn.android.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.*
interface MyVpnApi { @POST("api/v1/device/register") suspend fun register(@Body r:DeviceRegisterRequest):AuthResponse; @POST("api/v1/auth/refresh") suspend fun refresh(@Body r:RefreshRequest):AuthResponse; @GET("api/v1/vpn/access") suspend fun access(@Header("Authorization") bearer:String):VpnAccessResponse }
class AuthRepository(private val api:MyVpnApi,private val store:DeviceIdentityStore){ private val mutex=Mutex(); @Volatile private var token:String?=null; suspend fun authenticate():Session{val i=store.identity(); val r=api.register(DeviceRegisterRequest(i.first,i.second)); return Session(r.accessToken,r.refreshToken,r.expiresIn).also{token=it.accessToken;store.save(it)}}; suspend fun accessToken()=token?:store.session()?.accessToken?.also{token=it}?:authenticate().accessToken; suspend fun refreshOrReauth()=mutex.withLock{runCatching{store.session()?.let{api.refresh(RefreshRequest(it.refreshToken))}}.getOrNull()?.let{Session(it.accessToken,it.refreshToken,it.expiresIn).also{v->token=v.accessToken;store.save(v)}.accessToken}?:run{store.clear();authenticate().accessToken}}}
interface VpnAccessSource { suspend fun current(): VpnAccessResponse }
class VpnAccessRepository(private val api:MyVpnApi,private val auth:AuthRepository): VpnAccessSource { override suspend fun current():VpnAccessResponse{val t=auth.accessToken();return try{api.access("Bearer $t")}catch(e:HttpException){if(e.code()!=401)throw e;api.access("Bearer ${auth.refreshOrReauth()}")}}}
object ApiFactory{fun create():MyVpnApi{val j=Json{ignoreUnknownKeys=true};return Retrofit.Builder().baseUrl(BuildConfig.API_BASE_URL).client(OkHttpClient.Builder().build()).addConverterFactory(j.asConverterFactory("application/json".toMediaType())).build().create(MyVpnApi::class.java)}}
