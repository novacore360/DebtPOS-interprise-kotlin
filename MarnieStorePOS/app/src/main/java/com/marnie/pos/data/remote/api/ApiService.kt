package com.marnie.pos.data.remote.api

import com.marnie.pos.data.remote.dto.*
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface AuthApi {
    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequestDto): AuthResponseDto

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequestDto): AuthResponseDto

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body body: RefreshRequestDto)
}

interface SyncApi {
    @GET("api/v1/sync/pull")
    suspend fun pull(@Query("since") since: String): SyncPullResponseDto

    @POST("api/v1/sync/push")
    suspend fun push(@Body body: SyncPushRequestDto): SyncPushResponseDto
}
