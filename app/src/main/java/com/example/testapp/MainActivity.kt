package com.example.testapp

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap


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

object HttpClientProvider {
    private var client: OkHttpClient? = null

    fun getClient(context: Context): OkHttpClient {
        if (client == null) {
            client = OkHttpClient().newBuilder()
                //.addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.HEADERS))
                //.addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
                .cookieJar(MyCookieJar())
                .build()
        }
        return client!!
    }
}

class UnreadMessageService : Service() {
    private lateinit var session: OkHttpClient
    private val unreadUrl = "https://cherp.chat/api/chat/list/unread"
    private var previousUnreadData = JSONObject()
    private var previousUnreadTime = "0001-01-01 00:00:00.000000"
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        session = HttpClientProvider.getClient(this)
    }

    fun compareDatetimes(datetime1: String, datetime2: String): Int {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS]")
        val dateTime1 = LocalDateTime.parse(datetime1, formatter)
        val dateTime2 = LocalDateTime.parse(datetime2, formatter)
        return dateTime1.compareTo(dateTime2)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        coroutineScope.launch {
            while (true) {
                try {

                    val unreadData = getJsonResponse2(unreadUrl)
                    val chats = unreadData.getJSONArray("chats")
                    val chatLength = chats.length()

                    if ((unreadData.toString() != previousUnreadData.toString()) && (chatLength > 0)) {

                        val unreadTime = chats.getJSONObject(0).getString("updated")

                        if (compareDatetimes(unreadTime, previousUnreadTime) > 0) {
                            previousUnreadTime = unreadTime
                            val status = chats.getJSONObject(0).getString("status")
                            var chatTitle: String = try {
                                chats.getJSONObject(0).getString("chatTitle")
                            } catch (e: java.lang.Exception) {
                                "An Unnamed Chat"
                            }

                            if (status == "ended") {
                                showNotification("Oh no, $chatTitle ended :(", "Total unreads (including disconnects): $chatLength")
                            } else {

                                var chatType: String = try {
                                    chats.getJSONObject(0).getJSONObject("chatMessage").getString("type")
                                } catch (e: java.lang.Exception) {
                                    "connect"
                                }


                                showNotification(
                                    "New ${chatType.uppercase()} Unread Detected, Total: $chatLength",
                                    "Latest unread from: $chatTitle"
                                )
                            }

                        }
                        previousUnreadData = unreadData
                    }
                } catch (e: java.lang.Exception) {
                    println(e)
                }
                delay(20000)
            }
        }
        startForeground(NOTIFICATION_ID, createNotification("Polling for unread messages"))
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release resources
        coroutineScope.launch {
            session.dispatcher.executorService.shutdown()
            session.connectionPool.evictAll()
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Unread Message Service")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_name)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        return notificationBuilder.build()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "UnreadMessageService"
        const val CHANNEL_NAME = "Unread Message Service"
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

    private fun showNotification(title: String, message: String) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel("unreads", "Cherp Unread Notifications", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, "unreads")
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_unread)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(1, notification)
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

        session = HttpClientProvider.getClient(this)

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
                        showToast("Login failed. Recheck Your credentials, or you may already be logged in.")
                    }
                } else {

                    val loginStatus = JSONObject(loginResp.body?.string() ?: "").getString("status")

                    withContext(Dispatchers.Main) {
                        showToast("Login Status: $loginStatus")
                    }

                    if (loginStatus == "success") {
                        startPollingUnreadMessages()
                    }
                }

        }
    }


    private fun startPollingUnreadMessages() {
        val serviceIntent = Intent(this, UnreadMessageService::class.java)
        startService(serviceIntent)
    }

    private fun getJsonResponse(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-A205U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/111.0.5563.57 Mobile Safari/537.36")
            .build()

        return JSONObject(session.newCall(request).execute().body!!.string())
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

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

