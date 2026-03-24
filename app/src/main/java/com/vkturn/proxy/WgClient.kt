package com.vkturn.proxy

class WgClient(private val vkturnEndpoint: String) {

    private var device: Device? = null  

    fun start(config: String) {
        device = Device()
        device!!.parseConfig(config)  
        device!!.up()
        device!!.setNetworkInterface(vkturnEndpoint) 
    }

    fun stop() {
        device?.down()
    }
}
