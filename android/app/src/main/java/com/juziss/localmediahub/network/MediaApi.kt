package com.juziss.localmediahub.network

import com.juziss.localmediahub.data.BrowseResult
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.PaginatedMediaFiles
import com.juziss.localmediahub.data.SearchResult
import com.juziss.localmediahub.data.SystemBrowseResult
import com.juziss.localmediahub.data.Tag
import com.juziss.localmediahub.data.TagCreateRequest
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit API interface matching all Server endpoints.
 *
 * Note: For endpoints with path parameters that may contain "/",
 * we use @GET with a full relative URL (Retrofit supports this
 * by appending the string directly to base URL).
 */
interface MediaApi {

    // ── Folders ───────────────────────────────────────────────
    @GET("api/v1/folders")
    suspend fun getFolders(): List<Folder>

    @GET
    suspend fun browseFolder(
        @Url url: String,
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
    @GET
    suspend fun streamVideo(
        @Url url: String,
        @Header("Range") range: String? = null,
    ): Response<ResponseBody>

    @GET
    suspend fun getThumbnail(
        @Url url: String,
    ): ResponseBody

    @GET
    suspend fun getOriginalImage(
        @Url url: String,
    ): ResponseBody

    // ── Health check ──────────────────────────────────────────
    @GET(".")
    suspend fun healthCheck(): Response<Map<String, String>>

    // ── System browse ─────────────────────────────────────────
    @GET("api/v1/system/drives")
    suspend fun getSystemDrives(): List<String>

    @GET("api/v1/system/browse")
    suspend fun browseSystemPath(@Query("path") path: String): SystemBrowseResult

    // ── Search ──────────────────────────────────────────────
    @GET("api/v1/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("path") path: String? = null,
        @Query("limit") limit: Int = 50,
    ): SearchResult

    // ── Tags ──────────────────────────────────────────────
    @GET("api/v1/tags")
    suspend fun getTags(): List<Tag>

    @POST("api/v1/tags")
    suspend fun createTag(@Body request: TagCreateRequest): Tag

    @DELETE
    suspend fun deleteTag(@Url url: String): retrofit2.Response<Unit>

    @POST
    suspend fun tagFile(@Url url: String): Map<String, String>

    @DELETE
    suspend fun untagFile(@Url url: String): Map<String, String>

    @GET
    suspend fun getTaggedFiles(@Url url: String): List<String>

    @GET("api/v1/tags/{tagId}/media")
    suspend fun getTaggedMedia(@Path("tagId") tagId: String): List<com.juziss.localmediahub.data.MediaFile>

    // Batch endpoint: get tags for multiple files (or all files) in one call
    @GET("api/v1/tags/file-tags")
    suspend fun getFileTags(@Query("path") paths: List<String>): Map<String, List<Tag>>
}
