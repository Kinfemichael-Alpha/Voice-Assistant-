package com.dimts.assistant

import android.app.Application

/** One runtime for the whole process; every receiver, service and the console screen share it. */
class DimtsApp : Application() {
    lateinit var runtime: AppRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = AppRuntime(this)
        runtime.start()
    }
}

val android.content.Context.runtime: AppRuntime
    get() = (applicationContext as DimtsApp).runtime
