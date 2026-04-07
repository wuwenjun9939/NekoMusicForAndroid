package com.neko.music.desktoplyric

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.neko.music.R
import com.neko.music.service.MusicPlayerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LrcLine(
    val time: Float, // 时间（秒）
    val text: String, // 歌词文本
    val translation: String? = null // 翻译（可选）
)

class DesktopLyricService : Service() {

    private var windowManager: WindowManager? = null
    private var lyricView: View? = null
    private var isViewAdded = false
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var updateJob: Job? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    
    // 歌词数据
    private var currentLyrics: List<LrcLine> = emptyList()
    private var currentProgress: Long = 0L
    private var currentMusicId: Int = -1
    
    // 拖动相关变量
    private var startX = 0f
    private var startY = 0f
    private var isDragging = false
    private var viewInitialX = 0
    private var viewInitialY = 0
    private val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()

    companion object {
        const val ACTION_SHOW = "com.neko.music.action.SHOW_DESKTOP_LYRIC"
        const val ACTION_HIDE = "com.neko.music.action.HIDE_DESKTOP_LYRIC"
        const val ACTION_UPDATE = "com.neko.music.action.UPDATE_DESKTOP_LYRIC"
        private var instance: DesktopLyricService? = null
        
        fun getInstance(): DesktopLyricService? {
            return instance
        }
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("DesktopLyricService", "Service onCreate")
        instance = this
        createLyricView()
        showLyricView()
        startLyricUpdate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showLyricView()
            ACTION_HIDE -> hideLyricView()
            ACTION_UPDATE -> updateLyricView()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createLyricView() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        val layoutInflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        lyricView = layoutInflater.inflate(R.layout.desktop_lyric_layout, null)
        
        // 添加拖动功能
        lyricView?.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        isDragging = false
                        viewInitialX = layoutParams?.x ?: 0
                        viewInitialY = layoutParams?.y ?: 0
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - startX
                        val dy = event.rawY - startY
                        
                        if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                            if (!isDragging) {
                                isDragging = true
                            }
                            
                            layoutParams?.x = viewInitialX + dx.toInt()
                            layoutParams?.y = viewInitialY + dy.toInt()
                            windowManager?.updateViewLayout(lyricView, layoutParams)
                            return true
                        }
                        return false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isDragging = false
                        return false
                    }
                }
                return false
            }
        })
    }

    private fun showLyricView() {
        if (isViewAdded || lyricView == null) return
        
        try {
            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            
            layoutParams?.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            layoutParams?.y = 100 // 距离顶部100像素
            
            lyricView?.alpha = 1f
            
            windowManager?.addView(lyricView, layoutParams)
            isViewAdded = true
            
            updateLyricView()
            android.util.Log.d("DesktopLyricService", "Lyric view added successfully")
        } catch (e: Exception) {
            android.util.Log.e("DesktopLyricService", "Error showing lyric view", e)
        }
    }

    private fun startLyricUpdate() {
        updateJob = serviceScope.launch {
            while (isActive) {
                updateLyricView()
                delay(100) // 每0.1秒更新一次
            }
        }
    }

    private fun hideLyricView() {
        if (!isViewAdded || lyricView == null || windowManager == null) return
        
        try {
            windowManager?.removeView(lyricView)
            isViewAdded = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateLyricView() {
        val playerManager = MusicPlayerManager.getInstance(this)
        
        val tvLyric = lyricView?.findViewById<TextView>(R.id.desktop_lyric_text)
        val tvTranslation = lyricView?.findViewById<TextView>(R.id.desktop_lyric_translation)
        
        // 获取当前播放进度和歌词
        currentProgress = playerManager.currentPosition.value
        val musicId = playerManager.currentMusicId.value
        
        // 如果音乐改变，重新加载歌词
        if (musicId != null && musicId != currentMusicId) {
            currentMusicId = musicId
            serviceScope.launch {
                loadLyrics(musicId)
            }
        }
        
        // 找到当前应该显示的歌词行
        val currentIndex = currentLyrics.indexOfLast { it.time <= currentProgress / 1000f }
        
        if (currentIndex >= 0 && currentIndex < currentLyrics.size) {
            val currentLine = currentLyrics[currentIndex]
            tvLyric?.text = currentLine.text
            tvTranslation?.text = currentLine.translation ?: ""
            tvTranslation?.visibility = if (currentLine.translation != null) View.VISIBLE else View.GONE
        } else {
            tvLyric?.text = ""
            tvTranslation?.text = ""
            tvTranslation?.visibility = View.GONE
        }
    }

    private suspend fun loadLyrics(musicId: Int) {
        try {
            val musicApi = com.neko.music.data.api.MusicApi(this)
            val result = musicApi.getMusicLyrics(
                com.neko.music.data.model.Music(
                    id = musicId,
                    title = "",
                    artist = "",
                    album = "",
                    duration = 0
                )
            )
            result.fold(
                onSuccess = { lyricsText ->
                    currentLyrics = parseLrcLyrics(lyricsText)
                    android.util.Log.d("DesktopLyricService", "Lyrics loaded: ${currentLyrics.size} lines")
                },
                onFailure = { error ->
                    android.util.Log.e("DesktopLyricService", "Failed to load lyrics", error)
                    currentLyrics = emptyList()
                }
            )
        } catch (e: Exception) {
            android.util.Log.e("DesktopLyricService", "Error loading lyrics", e)
            currentLyrics = emptyList()
        }
    }

    // 解析 LRC 格式歌词（支持双语）
    private fun parseLrcLyrics(lrcText: String): List<LrcLine> {
        val lines = lrcText.lines()
        val result = mutableListOf<LrcLine>()

        for (i in lines.indices) {
            val line = lines[i]
            // 匹配时间戳格式 [mm:ss.xx]
            val timePattern = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2})\\]")
            val timeMatch = timePattern.find(line)

            if (timeMatch != null) {
                val minutes = timeMatch.groupValues[1].toInt()
                val seconds = timeMatch.groupValues[2].toInt()
                val centiseconds = timeMatch.groupValues[3].toInt()
                val time = minutes * 60 + seconds + centiseconds / 100f

                // 提取歌词文本（移除时间标签）
                var text = line.substring(timeMatch.range.last + 1).trim()
                var translation: String? = null

                // 检查下一行是否是翻译
                if (i + 1 < lines.size) {
                    val nextLine = lines[i + 1].trim()
                    if (nextLine.startsWith("{\" ") && nextLine.endsWith("}\"")) {
                        // 提取翻译内容
                        val jsonContent = nextLine.substring(3, nextLine.length - 2).trim()
                        // 移除外层引号和转义引号
                        translation = jsonContent.removeSurrounding("\"").replace("\\\"", "\"")
                    }
                }

                result.add(LrcLine(time, text, translation))
            }
        }

        return result
    }

    override fun onDestroy() {
        super.onDestroy()
        hideLyricView()
        updateJob?.cancel()
        serviceScope.cancel()
        instance = null
    }
}