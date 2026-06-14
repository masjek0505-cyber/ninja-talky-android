package com.ninja.talky

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private lateinit var etIp: EditText
    private lateinit var etName: EditText
    private lateinit var etChannel: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnPtt: Button
    private lateinit var tvStatus: TextView

    private var tcpSocket: Socket? = null
    private var udpSocket: DatagramSocket? = null
    private var clientId: String? = null
    private var serverIp: String = ""
    private var serverTcpPort = 5001
    private var serverUdpPort = 5000

    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etIp = findViewById(R.id.etIp)
        etName = findViewById(R.id.etName)
        etChannel = findViewById(R.id.etChannel)
        btnConnect = findViewById(R.id.btnConnect)
        btnPtt = findViewById(R.id.btnPtt)
        tvStatus = findViewById(R.id.tvStatus)

        // Minta permission mic
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        btnConnect.setOnClickListener { connectToServer() }
        
        btnPtt.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> startRecording()
                android.view.MotionEvent.ACTION_UP -> stopRecording()
            }
            true
        }

        setupAudioPlayer()
        scope.launch { startUdpListener() }
    }

    private fun connectToServer() {
        scope.launch {
            try {
                serverIp = etIp.text.toString()
                tcpSocket = Socket(serverIp, serverTcpPort)
                udpSocket = DatagramSocket() // OS yg pilih port UDP random

                val out = PrintWriter(tcpSocket!!.getOutputStream(), true)
                val loginMsg = """{"type":"login","name":"${etName.text}","udpPort":${udpSocket!!.localPort},"channel":"${etChannel.text}"}"""
                out.println(loginMsg)

                val reader = BufferedReader(InputStreamReader(tcpSocket!!.getInputStream()))
                val response = reader.readLine()
                
                if (response.contains("login_ok")) {
                    clientId = response.split("\"id\":\"")[1].split("\"")[0]
                    runOnUiThread {
                        tvStatus.text = "Status: Connected"
                        btnConnect.isEnabled = false
                        btnPtt.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { tvStatus.text = "Gagal connect: ${e.message}" }
            }
        }
    }

    private fun startRecording() {
        if (isRecording) return
        isRecording = true
        btnPtt.text = "LEPAS BUAT STOP"

        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
        audioRecord!!.startRecording()

        scope.launch {
            val buffer = ByteArray(bufferSize)
            while (isRecording) {
                val read = audioRecord!!.read(buffer, 0, buffer.size)
                if (read > 0) {
                    // Kirim suara mentah PCM via UDP ke server
                    val packet = DatagramPacket(buffer, read, InetAddress.getByName(serverIp), serverUdpPort)
                    udpSocket?.send(packet)
                }
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        btnPtt.text = "TEKAN BICARA"
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun setupAudioPlayer() {
        audioTrack = AudioTrack(
            android.media.AudioManager.STREAM_MUSIC,
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            audioFormat,
            bufferSize,
            AudioTrack.MODE_STREAM
        )
        audioTrack!!.play()
    }

    private fun startUdpListener() {
        val buffer = ByteArray(2048)
        while (true) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                udpSocket?.receive(packet)
                // Terima suara dari server, langsung mainin
                audioTrack?.write(packet.data, 0, packet.length)
            } catch (e: Exception) {
                // Socket ditutup
                break
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        tcpSocket?.close()
        udpSocket?.close()
        audioTrack?.stop()
        audioTrack?.release()
    }
}
