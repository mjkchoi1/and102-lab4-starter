package com.codepath.articlesearch

import android.content.*
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.appcompat.widget.SearchView
import com.codepath.articlesearch.databinding.ActivityMainBinding
import com.codepath.asynchttpclient.AsyncHttpClient
import com.codepath.asynchttpclient.callback.JsonHttpResponseHandler
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.Headers
import org.json.JSONException

fun createJson() = Json {
    isLenient = true
    ignoreUnknownKeys = true
    useAlternativeNames = false
}

private const val TAG = "MainActivity/"
private const val SEARCH_API_KEY = BuildConfig.API_KEY
private const val BASE_ARTICLE_SEARCH_URL = "https://api.nytimes.com/svc/search/v2/articlesearch.json"

class MainActivity : AppCompatActivity() {
    private val articles = mutableListOf<DisplayArticle>()
    private lateinit var articlesRecyclerView: RecyclerView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    private val PREF_USE_CACHE = "use_cache"
    private lateinit var articleAdapter: ArticleAdapter

    // Network monitoring
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback
    private var isConnected: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        // Initialize SharedPreferences
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)

        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        articlesRecyclerView = findViewById(R.id.articles)

        // Initialize ArticleAdapter
        articleAdapter = ArticleAdapter(this, articles)
        articlesRecyclerView.adapter = articleAdapter

        // Set up RecyclerView LayoutManager and Decoration
        articlesRecyclerView.layoutManager = LinearLayoutManager(this).also {
            val dividerItemDecoration = DividerItemDecoration(this, it.orientation)
            articlesRecyclerView.addItemDecoration(dividerItemDecoration)
        }

        // Load data from the database or network based on user preference
        loadArticles()

        // Set up SwipeRefreshLayout
        swipeRefreshLayout.setOnRefreshListener {
            fetchArticlesFromNetwork()
        }

        // Initialize ConnectivityManager and NetworkCallback
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!isConnected) {
                    isConnected = true
                    runOnUiThread {
                        Snackbar.make(
                            binding.swipeRefreshLayout,
                            "Back Online",
                            Snackbar.LENGTH_SHORT
                        ).show()
                        fetchArticlesFromNetwork()
                    }
                }
            }

            override fun onLost(network: Network) {
                if (isConnected) {
                    isConnected = false
                    runOnUiThread {
                        Snackbar.make(
                            binding.swipeRefreshLayout,
                            "You are offline",
                            Snackbar.LENGTH_INDEFINITE
                        ).show()
                    }
                }
            }
        }

        // Register the network callback
        val networkRequest = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister the network callback to prevent memory leaks
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        // Initialize SearchView
        val searchItem = menu?.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView

        searchView?.queryHint = "Search Articles"

        // Handle search query submission
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let {
                    searchArticles(it)
                    searchView.clearFocus()
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                // Optional: Implement real-time filtering
                return true
            }
        })

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                // Navigate to SettingsActivity
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadArticles() {
        val useCache = sharedPreferences.getBoolean(PREF_USE_CACHE, true)
        if (useCache) {
            // Load data from the database
            lifecycleScope.launch {
                (application as ArticleApplication).db.articleDao().getAll().collect { databaseList ->
                    databaseList.map { entity ->
                        DisplayArticle(
                            entity.headline,
                            entity.articleAbstract,
                            entity.byline,
                            entity.mediaImageUrl
                        )
                    }.also { mappedList ->
                        articles.clear()
                        articles.addAll(mappedList)
                        articleAdapter.notifyDataSetChanged()
                    }
                }
            }
        } else {
            // Fetch data from the network
            fetchArticlesFromNetwork()
        }
    }

    private fun fetchArticlesFromNetwork(query: String? = null) {
        swipeRefreshLayout.isRefreshing = true
        val client = AsyncHttpClient()
        val url = if (query.isNullOrEmpty()) {
            "$BASE_ARTICLE_SEARCH_URL?api-key=$SEARCH_API_KEY"
        } else {
            "$BASE_ARTICLE_SEARCH_URL?q=$query&api-key=$SEARCH_API_KEY"
        }

        client.get(url, object : JsonHttpResponseHandler() {
            override fun onFailure(
                statusCode: Int,
                headers: Headers?,
                response: String?,
                throwable: Throwable?
            ) {
                swipeRefreshLayout.isRefreshing = false
                Log.e(TAG, "Failed to fetch articles: $statusCode, response: $response", throwable)
                runOnUiThread {
                    Snackbar.make(
                        binding.swipeRefreshLayout,
                        "Failed to fetch articles",
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onSuccess(statusCode: Int, headers: Headers, json: JSON) {
                try {
                    json.jsonObject?.let { jsonObject ->
                        val parsedJson = createJson().decodeFromString(
                            SearchNewsResponse.serializer(),
                            jsonObject.toString()
                        )
                        parsedJson.response?.docs?.let { list ->
                            lifecycleScope.launch(IO) {
                                if (query.isNullOrEmpty()) {
                                    (application as ArticleApplication).db.articleDao().deleteAll()
                                }
                                (application as ArticleApplication).db.articleDao().insertAll(list.map {
                                    ArticleEntity(
                                        headline = it.headline?.main,
                                        articleAbstract = it.abstract,
                                        byline = it.byline?.original,
                                        mediaImageUrl = it.mediaImageUrl
                                    )
                                })
                                // Update UI on the main thread
                                runOnUiThread {
                                    swipeRefreshLayout.isRefreshing = false
                                    Snackbar.make(
                                        binding.swipeRefreshLayout,
                                        "Articles updated",
                                        Snackbar.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    } ?: run {
                        swipeRefreshLayout.isRefreshing = false
                        Log.e(TAG, "JSON Object is null.")
                        runOnUiThread {
                            Snackbar.make(
                                binding.swipeRefreshLayout,
                                "No articles found",
                                Snackbar.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    swipeRefreshLayout.isRefreshing = false
                    Log.e(TAG, "Exception occurred while processing JSON", e)
                    runOnUiThread {
                        Snackbar.make(
                            binding.swipeRefreshLayout,
                            "Error processing articles",
                            Snackbar.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })
    }

    private fun searchArticles(query: String) {
        // Fetch articles based on the search query
        fetchArticlesFromNetwork(query)
    }

    override fun onResume() {
        super.onResume()
        // Reload articles based on updated settings
        loadArticles()
    }
}
