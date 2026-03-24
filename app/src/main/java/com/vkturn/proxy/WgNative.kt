package com.vkturn.proxy

object WgNative {
    init {
        System.loadLibrary("wg-go")
    }

    external fun turnOn(fd: Int, config: String): Int
    external fun turnOff(): Int
}
