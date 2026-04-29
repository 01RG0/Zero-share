package com.ultrashare

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ultrashare.host.HostService
import com.ultrashare.viewer.ViewerActivity
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val projectionManager: MediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startHostService(result.resultCode, result.data!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MainScreen(
                onHostClick = { captureLauncher.launch(projectionManager.createScreenCaptureIntent()) },
                onViewerClick = { startViewerActivity() }
            )
        }
    }

    private fun startHostService(resultCode: Int, data: Intent) {
        val intent = HostService.startIntent(
            this, resultCode, data, "192.168.49.1", UUID.randomUUID().toString()
        )
        startForegroundService(intent)
    }

    private fun startViewerActivity() {
        val intent = Intent(this, ViewerActivity::class.java).apply {
            putExtra("hostIp", "192.168.49.1")
        }
        startActivity(intent)
    }
}

@Composable
fun MainScreen(onHostClick: () -> Unit, onViewerClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(onClick = onHostClick) {
                Text("Host Screen Share")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onViewerClick) {
                Text("View Screen Share")
            }
        }
    }
}
