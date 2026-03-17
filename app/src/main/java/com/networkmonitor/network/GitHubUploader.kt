package com.networkmonitor.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Класс для загрузки логов на GitHub Gist
 */
class GitHubUploader(private val context: Context) {
    
    companion object {
        private const val TAG = "GitHubUploader"
        private const val GITHUB_API_URL = "https://api.github.com"
        
        // GitHub Token - set via UI
        private var githubToken: String? = null
        
        fun setToken(token: String) {
            githubToken = token
        }
        
        fun getToken(): String? = githubToken
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    data class GistFile(
        val content: String
    )
    
    data class CreateGistRequest(
        val description: String,
        @SerializedName("public") val isPublic: Boolean = false,
        val files: Map<String, GistFile>
    )
    
    data class GistResponse(
        val id: String,
        val html_url: String,
        val created_at: String
    )
    
    /**
     * Загружает контент на GitHub Gist
     */
    suspend fun uploadToGist(
        filename: String,
        content: String,
        description: String = "Modem Doctor Log"
    ): String? = withContext(Dispatchers.IO) {
        
        val token = githubToken
        if (token.isNullOrBlank()) {
            Log.e(TAG, "GitHub token not set!")
            return@withContext null
        }
        
        try {
            val gistRequest = CreateGistRequest(
                description = description,
                isPublic = false,
                files = mapOf(filename to GistFile(content))
            )
            
            val json = gson.toJson(gistRequest)
            Log.d(TAG, "Uploading gist: $filename, size: ${content.length} bytes")
            
            val request = Request.Builder()
                .url("$GITHUB_API_URL/gists")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("X-GitHub-Api-Version", "2022-11-28")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val gistResponse = gson.fromJson(responseBody, GistResponse::class.java)
                Log.d(TAG, "Gist created: ${gistResponse.html_url}")
                gistResponse.html_url
            } else {
                Log.e(TAG, "Failed to create gist: ${response.code} - ${response.body?.string()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception uploading to gist", e)
            null
        }
    }
    
    /**
     * Загружает несколько файлов в один Gist
     */
    suspend fun uploadMultipleFiles(
        files: Map<String, String>,
        description: String = "Modem Doctor Log Bundle"
    ): String? = withContext(Dispatchers.IO) {
        
        val token = githubToken
        if (token.isNullOrBlank()) {
            return@withContext null
        }
        
        try {
            val gistFiles = files.mapValues { (_, content) -> GistFile(content) }
            
            val gistRequest = CreateGistRequest(
                description = description,
                isPublic = false,
                files = gistFiles
            )
            
            val json = gson.toJson(gistRequest)
            
            val request = Request.Builder()
                .url("$GITHUB_API_URL/gists")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github+json")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val gistResponse = gson.fromJson(responseBody, GistResponse::class.java)
                gistResponse.html_url
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception uploading multiple files", e)
            null
        }
    }
}
