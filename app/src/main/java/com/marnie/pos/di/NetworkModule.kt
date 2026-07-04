package com.marnie.pos.di

import android.content.Context
import com.marnie.pos.BuildConfig
import com.marnie.pos.data.remote.api.AuthApi
import com.marnie.pos.data.remote.api.SyncApi
import com.marnie.pos.data.remote.ws.RealtimeSyncClient
import com.marnie.pos.security.AuthInterceptor
import com.marnie.pos.security.CryptoManager
import com.marnie.pos.security.TokenAuthenticator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator,
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            // Body-level logging only in debug builds — request/response bodies
            // can contain tokens or customer PII and must never hit release logs.
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }

        val builder = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .authenticator(tokenAuthenticator)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        // Certificate pinning: set CERT_PIN_SHA256 via Codemagic build config once
        // the production backend's certificate is known. Left unset in local/dev
        // builds so it doesn't break testing against self-signed certs.
        if (BuildConfig.CERT_PIN_SHA256.isNotBlank() && BuildConfig.API_HOST.isNotBlank()) {
            builder.certificatePinner(
                CertificatePinner.Builder()
                    .add(BuildConfig.API_HOST, "sha256/${BuildConfig.CERT_PIN_SHA256}")
                    .build()
            )
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideSyncApi(retrofit: Retrofit): SyncApi = retrofit.create(SyncApi::class.java)

    @Provides
    @Singleton
    fun provideRealtimeSyncClient(
        okHttpClient: OkHttpClient,
        cryptoManager: CryptoManager,
        @ApplicationContext context: Context,
    ): RealtimeSyncClient {
        val wsUrl = BuildConfig.API_BASE_URL.replaceFirst("http", "ws") + "api/v1/realtime"
        return RealtimeSyncClient(okHttpClient, cryptoManager, context, wsUrl)
    }
}
