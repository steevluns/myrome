package com.unscreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FlipService : Service(), SensorEventListener {

    companion object {
        @Volatile
        var running = false

        private const val CHANNEL = "unscreen_channel"
        private const val NOTIF_ID = 1
        private const val HOLD_MS = 500L          // مدة الثبات قبل إيقاظ الشاشة
        private const val DOWN_THRESHOLD = -7.5f  // الشاشة للأسفل
        private const val UP_THRESHOLD = -6.0f    // الخروج من وضع الشاشة للأسفل

        fun start(c: Context) {
            val i = Intent(c, FlipService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, FlipService::class.java))
        }
    }

    private lateinit var sm: SensorManager
    private lateinit var pm: PowerManager
    private lateinit var holdLock: PowerManager.WakeLock
    private var cpuLock: PowerManager.WakeLock? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var rootOk = false
    private var faceDown = false
    private var fired = false

    private val wakeRunnable = Runnable {
        if (faceDown && !fired && !pm.isInteractive) {
            fired = true
            wakeScreen()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }

        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        pm = getSystemService(POWER_SERVICE) as PowerManager
        holdLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "unscreen:hold").apply {
            setReferenceCounted(false)
        }

        // الحساس من نوع wake-up يوقظ المعالج عند الحركة حتى والشاشة مغلقة
        val wakeUpSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        val sensor = wakeUpSensor ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            stopSelf()
            return
        }
        if (wakeUpSensor == null) {
            // لا يوجد حساس wake-up: نبقي المعالج مستيقظاً لضمان القراءة
            cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "unscreen:cpu").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 0)

        worker.execute { rootOk = RootShell.checkRoot() }
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onSensorChanged(event: SensorEvent) {
        val z = event.values[2]
        if (!faceDown && z < DOWN_THRESHOLD) {
            faceDown = true
            fired = false
            // قفل مؤقت قصير لضمان تنفيذ التأخير والمعالج لا ينام
            holdLock.acquire(HOLD_MS + 2000)
            main.postDelayed(wakeRunnable, HOLD_MS)
        } else if (faceDown && z > UP_THRESHOLD) {
            faceDown = false
            fired = false
            main.removeCallbacks(wakeRunnable)
            if (holdLock.isHeld) holdLock.release()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun wakeScreen() {
        worker.execute {
            val ok = rootOk && RootShell.exec("input keyevent 224") // KEYCODE_WAKEUP
            if (!ok) fallbackWake()
        }
    }

    /** بديل بدون روت إذا لم تتوفر الصلاحية. */
    @Suppress("DEPRECATION")
    private fun fallbackWake() {
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "unscreen:wake"
        )
        wl.acquire(1500)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL)
        } else {
            Notification.Builder(this)
        }
        return b.setSmallIcon(R.drawable.ic_stat_unlock)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        if (::sm.isInitialized) sm.unregisterListener(this)
        main.removeCallbacksAndMessages(null)
        if (::holdLock.isInitialized && holdLock.isHeld) holdLock.release()
        cpuLock?.let { if (it.isHeld) it.release() }
        worker.shutdown()
        RootShell.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
