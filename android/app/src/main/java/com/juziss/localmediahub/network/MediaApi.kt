package com.juziss.localmediahub.network

import com.juziss.localmediahub.data.BrowseResult
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.PaginatedMediaFiles
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit API interface matching all Server endpoints.
 */
interface MediaApi {

    // ── Folders ───────────────────────────────────────────────
    @GET("api/v1/folders")
    suspend fun getFolders(): List<Folder>

    @GET("api/v1/folders/{path}/browse")
    suspend fun browseFolder(
        @Path(value = "path", encoded = true) path: String,
    ): BrowseResult

    // ── Videos ────────────────────────────────────────────────
    @GET("api/v1/videos")
    suspend fun getVideos(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
    ): PaginatedMediaFiles

    // ── Images ────────────────────────────────────────────────
    @GET("api/v1/images")
    suspend fun getImages(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
    ): PaginatedMediaFiles

    // ── Streaming / Thumbnails ────────────────────────────────
    @Streaming
    @GET("api/v1/videos/{path}/stream")
    suspend fun streamVideo(
        @Path(value = "path", encoded = true) path: String,
        @Header("Range") range: String? = null,
    ): Response<ResponseBody>

    @GET("api/v1/images/{path}/thumbnail")
    suspend fun getThumbnail(
        @Path(value = "path", encoded = true) path: String,
    ): ResponseBody

    @GET("api/v1/images/{path}/original")
    suspend fun getOriginalImage(
        @Path(value = "path", encoded = true) path: String,
    ): ResponseBody

    // ── Health check ──────────────────────────────────────────
    @GET(".")
    suspend fun healthCheck(): Response<Map<String, String>>
}
