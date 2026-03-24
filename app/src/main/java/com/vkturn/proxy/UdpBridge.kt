package com.vkturn.proxy

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UdpBridge {

    private var running = true

    fun start() {
        Thread {
            try {
                val socket = DatagramSocket(9000, InetAddress.getByName("127.0.0.1"))
                val buf = ByteArray(4096)

                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)

                    println("Получен пакет от WG: ${packet.length} байт")

                    socket.send(packet)
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    fun stop() {
        running = false
    }
}
