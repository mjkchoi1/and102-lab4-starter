package com.codepath.articlesearch

// Import statements
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codepath.articlesearch.databinding.ActivityMainBinding
import com.codepath.asynchttpclient.AsyncHttpClient
import com.codepath.asynchttpclient.callback.JsonHttpResponseHandler
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import org.json.JSONException

// Create JSON configuration
fun createJson() = Json {
    isLenient = true
    ignoreUnknownKeys = true
    useAlternativeNames = false
}

private const val TAG = "MainActivity/"
private const val SEARCH_API_KEY = BuildConfig.API_KEY
private const val ARTICLE_SEARCH_URL =
    "https://api.nytimes.com/svc/search/v2/articlesearch.json?api-key=${SEARCH_API_KEY}"

class MainActivity : AppCompatActivity() {
    private val articles = mutableListOf<Article>()
    private lateinit var articlesRecyclerView: RecyclerView
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        articlesRecyclerView = findViewById(R.id.articles)
        val articleAdapter = ArticleAdapter(this, articles)
        articlesRecyclerView.adapter = articleAdapter

        articlesRecyclerView.layoutManager = LinearLayoutManager(this).also {
            val dividerItemDecoration = DividerItemDecoration(this, it.orientation)
            articlesRecyclerView.addItemDecoration(dividerItemDecoration)
        }

        val client = AsyncHttpClient()
        client.get(ARTICLE_SEARCH_URL, object : JsonHttpResponseHandler() {
            override fun onFailure(
                statusCode: Int,
                headers: Headers?,
                response: String?,
                throwable: Throwable?
            ) {
                Log.e(TAG, "Failed to fetch articles: $statusCode")
            }

            override fun onSuccess(statusCode: Int, headers: Headers, json: JSON) {
                Log.i(TAG, "Successfully fetched articles: $json")
                try {
                    // Specify the type parameter explicitly
                    val parsedJson = createJson().decodeFromString<SearchNewsResponse>(json.jsonObject.toString())

                    // Use let with explicit type for list
                    parsedJson.response?.docs?.let { list: List<Article> ->
                        articles.addAll(list)
                        // Reload the screen
                        articleAdapter.notifyDataSetChanged()
                    }

                } catch (e: JSONException) {
                    Log.e(TAG, "Exception: $e")
                }
            }

        })
    }
}


