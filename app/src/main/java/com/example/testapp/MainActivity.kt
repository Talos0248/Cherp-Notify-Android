package com.example.testapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject



class MyCookieJar : CookieJar {
    private var cookieStore = HashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookieStore[url.host] = cookies
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookies = cookieStore[url.host]
        return cookies ?: ArrayList()
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var usernameEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var loginButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usernameEditText = findViewById(R.id.usernameEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        loginButton = findViewById(R.id.loginButton)

        loginButton.setOnClickListener {
            login()
        }
    }

    private lateinit var csrfName: String
    private lateinit var csrf: String
    private lateinit var session: OkHttpClient

    @OptIn(DelicateCoroutinesApi::class)
    private fun login() {
        val username = usernameEditText.text.toString()
        val password = passwordEditText.text.toString()

        if (username.isEmpty() || password.isEmpty()) {
            showToast("Username or password cannot be empty")
            return
        }

        session = OkHttpClient().newBuilder()
            .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.HEADERS))
            .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
            .cookieJar(MyCookieJar())
            .build()

        // make request for csrf token
        val csrfUrl = "https://cherp.chat/api/csrf"
        GlobalScope.launch(Dispatchers.IO) {
            val csrfData = getJsonResponse(csrfUrl)
            csrfName = csrfData.getString("csrfname")
            csrf = csrfData.getString("csrf")


            // make login request
            val loginUrl = "https://cherp.chat/api/user/login"
            val loginPayload = FormBody.Builder()
                .add("username", username)
                .add("password", password)
                .add("csrfname", csrfName)
                .add("csrf", csrf)
                .build()

            val loginResp = postFormDataResponse(loginUrl, loginPayload)

            if (loginResp.code != 200) {
                withContext(Dispatchers.Main) {
                    showToast("Login failed")
                }
            } else {

                val loginStatus = JSONObject(loginResp.body?.string() ?: "").getString("status")

                withContext(Dispatchers.Main) {
                    showToast("Login Status: $loginStatus")
                }

                if (loginStatus == "success"){
                    startPollingUnreadMessages()
                }
            }
        }
    }


    private fun startPollingUnreadMessages() {
        val unreadUrl = "https://cherp.chat/api/chat/list/unread"
        var previousUnreadData = JSONObject()
        GlobalScope.launch(Dispatchers.IO) {
            while (true) {
                val unreadData = getJsonResponse2(unreadUrl)
                if (unreadData.toString() != previousUnreadData.toString()) {
                    withContext(Dispatchers.Main) {
                        showNotification("Unread messages", unreadData.toString())
                    }
                    previousUnreadData = unreadData
                }
                delay(10_000)
            }
        }
    }

    private fun getJsonResponse(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-A205U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/111.0.5563.57 Mobile Safari/537.36")
            .build()

        return JSONObject(session.newCall(request).execute().body!!.string())
    }


    private fun getJsonResponse2(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .build()
        val response = session.newCall(request).execute()
        val responseBody = response.body!!.string()
        response.body?.close()
        return JSONObject(responseBody)
    }

    private fun postFormDataResponse(url: String, formData: FormBody): Response {
        val request = Request.Builder()
            .url(url)
            .method("POST", formData)
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10; SM-A205U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/111.0.5563.57 Mobile Safari/537.36"
            )
            .build()
        return session.newCall(request).execute()
    }

    private fun showNotification(title: String, message: String) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel("default", "Default Channel", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, "default")
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(1, notification)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
