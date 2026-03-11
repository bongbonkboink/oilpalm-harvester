package com.example.oilpalmharvester

import com.chaquo.python.Python

object RNSBridge {
    private val py     = Python.getInstance()
    private val worker get() = py.getModule("rns_worker")

    fun start(btService: BluetoothService): String {
        val pyBt = py.getModule("bt_wrapper").callAttr("BtWrapper", btService)
        return worker.callAttr("start", pyBt).toString()
    }

    fun sendCsv(destHashHex: String, csvText: String, filename: String): String {
        return worker.callAttr("send_csv", destHashHex, csvText, filename).toString()
    }

    fun sendPhoto(destHashHex: String, photoPath: String, recordId: Long): String {
        return worker.callAttr("send_photo", destHashHex, photoPath, recordId).toString()
    }

    fun sendMessage(destHashHex: String, text: String): String {
        return worker.callAttr("send_message", destHashHex, text).toString()
    }

    fun getAddress(): String =
        worker.callAttr("get_address").toString()

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
        try { worker.callAttr("set_contact", hashHex, name).toString() }
        catch (e: Exception) { "Error: ${e.message}" }

    fun getContact(hashHex: String): String =
        try { worker.callAttr("get_contact", hashHex).toString() }
        catch (e: Exception) { "" }
}
