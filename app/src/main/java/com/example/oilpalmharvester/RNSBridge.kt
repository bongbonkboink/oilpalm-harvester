package com.example.oilpalmharvester

import com.chaquo.python.Python

object RNSBridge {
    private val py     = Python.getInstance()
    private val worker get() = py.getModule("rns_worker")

    fun start(btService: BluetoothService): String {
        val pyBt = py.getModule("bt_wrapper").callAttr("BtWrapper", btService)
        return worker.callAttr("start", pyBt).toString()
    }

    fun announce(): String =
        try { worker.callAttr("announce").toString() }
        catch (e: Exception) { "Error: ${e.message}" }

    fun sendCsv(destHashHex: String, csvText: String, filename: String): String =
        try { worker.callAttr("send_csv", destHashHex, csvText, filename).toString() }

    fun sendPhoto(destHashHex: String, photoPath: String, recordId: Long): String =
        try { worker.callAttr("send_photo", destHashHex, photoPath, recordId.toString()).toString() }
        catch (e: Exception) { "Error: ${e.message}" }
        catch (e: Exception) { "Error: ${e.message}" }

    fun sendMessage(destHashHex: String, text: String): String =
        try { worker.callAttr("send_message", destHashHex, text).toString() }
        catch (e: Exception) { "Error: ${e.message}" }

        fun getRnodeConfig(): Map<String, Any> =
        try {
            val raw = worker.callAttr("get_rnode_config")
            raw.asMap().entries.associate { (k, v) -> k.toString() to (v.toString() as Any) }
        } catch (e: Exception) { emptyMap() }

    fun saveRnodeConfig(freq: Int, bw: Int, tx: Int, sf: Int, cr: Int): String =
        try { worker.callAttr("save_rnode_config", freq, bw, tx, sf, cr).toString() }
        catch (e: Exception) { "Error: ${e.message}" }

    fun getAddress(): String =
        try { worker.callAttr("get_address").toString() }
        catch (e: Exception) { "" }

    fun getMessages(): List<Map<String, String>> {
        val raw = worker.callAttr("get_messages")
        return raw.asList().map { item ->
            item.asMap().entries.associate { (k, v) -> k.toString() to v.toString() }
        }
    }

    fun getAnnounces(): List<Map<String, String>> {
        val raw = worker.callAttr("get_announces")
        return raw.asList().map { item ->
            item.asMap().entries.associate { (k, v) -> k.toString() to v.toString() }
        }
    }

    fun setContact(hashHex: String, name: String): String =
        try { worker.callAttr("save_contact", hashHex, name).toString() }
        catch (e: Exception) { "Error: ${e.message}" }

    fun getContact(hashHex: String): String =
        try { worker.callAttr("resolve_name", hashHex, "").toString() }
        catch (e: Exception) { "" }
}


