package com.lambdasoup.choreoblink

import android.Manifest
import android.animation.TimeAnimator
import android.arch.lifecycle.LiveData
import android.arch.lifecycle.Observer
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.support.v4.content.LocalBroadcastManager
import android.support.v7.widget.CardView
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import java.util.*

private const val PERMISSION: String = Manifest.permission.ACCESS_FINE_LOCATION

class TimeSyncView @JvmOverloads constructor(context: Context,
                                             attrs: AttributeSet? = null,
                                             defStyleAttr: Int = 0) :
    CardView(context, attrs, defStyleAttr), Observer<TimeSyncState>, TimeAnimator.TimeListener {

    private val button: Button
    private val text: TextView
    private val real: TextView
    private val progress: ProgressBar

    private val animator = TimeAnimator()

    var listener: Listener? = null

    var lastDelta = 0L

    init {
        LayoutInflater.from(context).inflate(R.layout.card_time, this)
        button = findViewById(R.id.button)
        progress = findViewById(R.id.progress)
        text = findViewById(R.id.text)
        real = findViewById(R.id.real)

        animator.setTimeListener(this)
        animator.start()
    }

    override fun onTimeUpdate(animation: TimeAnimator, totalTime: Long, deltaTime: Long) {
        val real = System.currentTimeMillis() + lastDelta
        val secs = real % 60000 / 1000
        val msecs = real % 1000
        this.real.text = "%02d.%03d".format(Locale.ENGLISH, secs, msecs)
    }

    override fun onChanged(nullableState: TimeSyncState?) {
        val state = nullableState ?: return

        return when (state) {
            TimeSyncState.NeedsPermission -> {
                progress.visibility = INVISIBLE
                text.visibility = INVISIBLE
                button.visibility = VISIBLE
                button.setOnClickListener { listener?.requestPermission(PERMISSION) }
            }
            TimeSyncState.Syncing, TimeSyncState.Idle -> {
                button.visibility = INVISIBLE
                progress.visibility = VISIBLE
                text.visibility = INVISIBLE
            }
            is TimeSyncState.Synced -> {
                button.visibility = INVISIBLE
                text.visibility = VISIBLE
                text.text = String.format(Locale.ENGLISH, "synced (%dms)", state.delta)
                lastDelta = state.delta
                progress.visibility = INVISIBLE
            }
        }
    }

    interface Listener {
        fun requestPermission(permission: String)
    }
}

class TimeSource(context: Context) {

    val state: LiveData<TimeSyncState> = TimeSyncLiveData(context)

}

class TimeSyncLiveData(private val context: Context) : LiveData<TimeSyncState>() {

    init {
        value = TimeSyncState.Idle
    }

    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            update()
        }
    }

    private val listener = object : LocationListener {
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String?) {}
        override fun onProviderDisabled(provider: String?) {}
        override fun onLocationChanged(location: Location?) {
            if (location != null) {
                val delta = System.currentTimeMillis() - location.time
                value = TimeSyncState.Synced(delta)
                return
            }
        }
    }

    private fun update() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            postValue(TimeSyncState.NeedsPermission)
            return
        }

        if (value == TimeSyncState.Idle) {
            postValue(TimeSyncState.Syncing)
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, listener)
        }
    }

    override fun onActive() {
        super.onActive()

        LocalBroadcastManager.getInstance(context).registerReceiver(receiver,
                IntentFilter("${context.packageName}.CHECK_PERMISSIONS"))

        update()
    }

    override fun onInactive() {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
        locationManager.removeUpdates(listener)
        postValue(TimeSyncState.Idle)
        super.onInactive()
    }

}

sealed class TimeSyncState {
    object NeedsPermission : TimeSyncState()
    object Idle : TimeSyncState()
    object Syncing : TimeSyncState()
    data class Synced(val delta: Long) : TimeSyncState()
}