package com.paragrudani.replyly

import android.app.Application
import android.content.Context
import com.paragrudani.replyly.di.AppGraph

class ReplylyApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

val Context.appGraph: AppGraph get() = (applicationContext as ReplylyApp).graph
