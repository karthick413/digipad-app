package com.example.digipad

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.example.digipad.ui.InkView
import com.example.digipad.ui.theme.DigiPadTheme
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.FileOutputStream
import java.io.IOException

@Serializable
data class RecognizedWord(val text: String)

class MainActivity : ComponentActivity() {

    private val BASE_URL = "http://127.0.0.1:5000"
    private val FINALIZE_DELAY = 2500L
    private val ACTION_USB_PERMISSION = "com.example.digipad.USB_PERMISSION"

    private lateinit var usbManager: UsbManager
    private var usbOutputStream: FileOutputStream? = null
    private var fileDescriptor: ParcelFileDescriptor? = null

    private var recognizer: DigitalInkRecognizer? = null
    private var isModelDownloaded by mutableStateOf(false)
    private var usbStatus by mutableStateOf(false)

    private var currentWordPreview by mutableStateOf("")
    private var finalizedText by mutableStateOf("")
    private var isWaiting by mutableStateOf(false)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var inkViewRef: InkView? = null

    private val client = HttpClient(Android) {
        install(ContentNegotiation) { json() }
        install(Logging) { level = LogLevel.INFO }
    }

    private val finalizeRunnable = Runnable { finalizeAndClear() }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val accessory: UsbAccessory? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
                        }
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            accessory?.let { openAccessory(it) }
                        }
                    }
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> closeAccessory()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            enableEdgeToEdge()
            usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

            val usbFilter = IntentFilter(ACTION_USB_PERMISSION)
            usbFilter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbReceiver, usbFilter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(usbReceiver, usbFilter)
            }

            handleUsbIntent(intent)
            mainHandler.postDelayed({ setupMLKit() }, 500)

            setContent {
                DigiPadTheme {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        MainScreen()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Crash in onCreate", e)
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent) {
        val accessory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        }
        if (accessory != null) {
            if (usbManager.hasPermission(accessory)) openAccessory(accessory)
            else {
                val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
                usbManager.requestPermission(accessory, pi)
            }
        }
    }

    private fun openAccessory(acc: UsbAccessory) {
        try {
            fileDescriptor = usbManager.openAccessory(acc)
            if (fileDescriptor != null) {
                usbOutputStream = FileOutputStream(fileDescriptor!!.fileDescriptor)
                usbStatus = true
            }
        } catch (e: Exception) { Log.e("USB", "Open error") }
    }

    private fun closeAccessory() {
        try { fileDescriptor?.close() } catch (e: Exception) {}
        usbOutputStream = null
        usbStatus = false
    }

    private fun setupMLKit() {
        val identifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US") ?: return
        val model = DigitalInkRecognitionModel.builder(identifier).build()
        recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        RemoteModelManager.getInstance().isModelDownloaded(model).addOnSuccessListener { downloaded ->
            if (downloaded) isModelDownloaded = true
            else RemoteModelManager.getInstance().download(model, DownloadConditions.Builder().build()).addOnSuccessListener { isModelDownloaded = true }
        }
    }

    private fun finalizeAndClear() {
        val word = currentWordPreview
        if (word.isNotEmpty()) {
            finalizedText += (if (finalizedText.isEmpty()) "" else " ") + word
            sendData("$word ")
            sendToServer(word)
            playSuccessChime()
            inkViewRef?.clear()
            currentWordPreview = ""
        }
        isWaiting = false
    }

    private fun sendToServer(text: String) {
        lifecycleScope.launch {
            try { client.post("$BASE_URL/save_text") { contentType(ContentType.Application.Json); setBody(RecognizedWord(text)) } } catch (e: Exception) {}
        }
    }

    private fun sendData(text: String) {
        val bytes = text.toByteArray()
        usbOutputStream?.let { try { it.write(bytes); it.flush() } catch (e: IOException) {} }
    }

    private fun playSuccessChime() {
        try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).startTone(ToneGenerator.TONE_PROP_ACK, 150) } catch (e: Exception) {}
    }

    private fun recognizeInk(ink: Ink) {
        if (!isModelDownloaded) return
        recognizer?.recognize(ink)?.addOnSuccessListener { result ->
            if (result.candidates.isNotEmpty()) {
                currentWordPreview = result.candidates[0].text
                mainHandler.removeCallbacks(finalizeRunnable)
                mainHandler.postDelayed(finalizeRunnable, FINALIZE_DELAY)
                isWaiting = true
            }
        }
    }

    @Composable
    fun MainScreen() {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail(modifier = Modifier.fillMaxHeight(), containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                NavigationRailItem(selected = false, onClick = { inkViewRef?.clear(); currentWordPreview = ""; finalizedText = ""; isWaiting = false }, icon = { Icon(Icons.Default.Clear, "Clear") }, label = { Text("Clear") })
                Spacer(modifier = Modifier.weight(1f))
                Box(modifier = Modifier.padding(16.dp).size(24.dp).background(if (usbStatus) Color.Green else Color.Red, MaterialTheme.shapes.small)) { 
                    Icon(Icons.Default.Usb, "USB", tint = Color.White, modifier = Modifier.size(16.dp).align(Alignment.Center)) 
                }
            }
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text("DigiPad", style = MaterialTheme.typography.labelLarge)
                Box(modifier = Modifier.fillMaxWidth().height(220.dp).background(Color.White)) {
                    AndroidView(factory = { context -> InkView(context, null).apply { onStrokeFinished = { ink -> recognizeInk(ink) }; inkViewRef = this } }, modifier = Modifier.fillMaxSize())
                    if (isWaiting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter))
                }
                Spacer(modifier = Modifier.height(20.dp))
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Current word:", style = MaterialTheme.typography.labelSmall)
                        Text(text = currentWordPreview.ifEmpty { "..." }, style = MaterialTheme.typography.headlineLarge)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Card(modifier = Modifier.fillMaxWidth().weight(1f), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Saved text (Sent to all):", style = MaterialTheme.typography.labelSmall)
                        Text(text = finalizedText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) {}
        client.close()
    }
}
