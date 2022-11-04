package com.smx.autoport.coreboot

import java.lang.StringBuilder

interface Element {
    fun renderHead(builder: StringBuilder, indent: String)
    fun renderHeadPost(builder: StringBuilder, indent: String)
    fun render(builder: StringBuilder, indent: String)
}

@DslMarker
annotation class CorebootNodeMarker

@CorebootNodeMarker
abstract class Node(val name: String, val comment: String = "") : Element {
    private val children = arrayListOf<Element>()

    protected fun <T: Element> initNode(node: T, init: T.() -> Unit): T {
        node.init()
        children.add(node)
        return node
    }

    protected fun renderString(builder: StringBuilder, text: String){
        builder.append("\"$text\"")
    }

    override fun renderHead(builder: StringBuilder, indent: String) {}
    override fun renderHeadPost(builder: StringBuilder, indent: String) {}

    override fun render(builder: StringBuilder, indent: String) {
        // tag name
        builder.append("${indent}${name}")

        // tag head data
        renderHead(builder, indent)
        renderHeadPost(builder, indent)

        if(comment.isNotEmpty()){
            builder.append(" # $comment")
        }
        builder.appendLine()

        for(c in children){
            c.render(builder, "${indent}\t")
        }
    }

    fun comment(text: String, init: CommentElement.() -> Unit = {}){
        initNode(CommentElement(text), init)
    }
}

class CommentElement(val text: String) : Element {
    override fun renderHead(builder: StringBuilder, indent: String) {}
    override fun renderHeadPost(builder: StringBuilder, indent: String) {}

    override fun render(builder: StringBuilder, indent: String) {
        builder.appendLine("${indent}# $text")
    }
}

class Chip(val chipName: String) : EnclosedNode("chip") {
    fun register(name: String, value: String, init: Register.() -> Unit = {}) {
        initNode(Register(name, value), init)
    }

    fun pciDevice(device: Int, function:Int, enabled: Boolean = true, init: PciDevice.() -> Unit = {}){
        initNode(PciDevice(enabled, device, function), init)
    }

    fun pnpDevice(address: Int, id: Int, enabled: Boolean = true, init: PnpDevice.() -> Unit = {}){
        initNode(PnpDevice(enabled, address, id), init)
    }

    override fun renderHead(builder: StringBuilder, indent:String) {
        builder.append(" $chipName")
    }
}

class Register(val regName: String, val regValue: String) : Node("register"){
    override fun renderHead(builder: StringBuilder, indent: String) {
        builder.append(' ')
        renderString(builder, regName)
        builder.append(" = ")
        renderString(builder, regValue)
    }
}

abstract class EnclosedNode(name: String) : Node(name){
    override fun render(builder: StringBuilder, indent: String) {
        super.render(builder, indent)
        builder.appendLine("${indent}end")
    }
}

abstract class Device(val bus: String, val enabled: Boolean) : EnclosedNode("device"){
    override fun renderHead(builder: StringBuilder, indent: String) {
        builder.append(" ${bus}")
    }

    override fun renderHeadPost(builder: StringBuilder, indent: String) {
        val str = when (enabled) {
            true -> "on"
            false -> "off"
        }
        builder.append(" $str")
    }

    fun chip(chipName: String, init: Chip.() -> Unit = {}){
        initNode(Chip(chipName), init)
    }
}
class CpuClusterDevice(enabled: Boolean, cluster: Int) : Device("cpu_cluster", enabled)

class Subsystem(val vendor: Int, val product: Int) : Node("subsystem"){
    override fun renderHead(builder: StringBuilder, indent: String) {
        builder.append(" 0x${vendor.toString(16)} 0x${product.toString(16)}")
    }
}

class PciDevice(enabled: Boolean, val device: Int, val function: Int) : Device("pci", enabled){
    override fun renderHead(builder: StringBuilder, indent: String) {
        super.renderHead(builder, indent)
        builder.append(String.format(" %02x.%x", device, function))
    }

    fun subsystem(vendor: Int, product: Int, init: Subsystem.() -> Unit = {}){
        initNode(Subsystem(vendor, product), init)
    }
}
class Irq(val irqNum: Int, val value: Int) : Node("irq"){
    override fun renderHead(builder: StringBuilder, indent: String) {
        super.renderHead(builder, indent)
        builder.append(String.format(" 0x%02x = 0x%02x", irqNum, value))
    }
}
class Io(val portNum: Int, val value: Int) : Node("io"){
    override fun renderHead(builder: StringBuilder, indent: String) {
        super.renderHead(builder, indent)
        builder.append(String.format("  0x%02x = 0x%04x", portNum, value))
    }
}

class PnpDevice(enabled: Boolean, val address: Int, val id: Int) : Device("pnp", enabled) {
    override fun renderHead(builder: StringBuilder, indent: String) {
        super.renderHead(builder, indent)
        builder.append(String.format(" %02x.%x",
            address, id
        ))
    }

    fun irq(irqNum: Int, value: Int, init: Irq.() -> Unit = {}){
        initNode(Irq(irqNum, value), init)
    }
    fun io(portNum: Int, value: Int, init: Io.() -> Unit = {}){
        initNode(Io(portNum, value), init)
    }
}


class DomainDevice(enabled: Boolean, domain: Int) : Device("domain", enabled)

class RegisterValue {
    companion object {
        fun arrayIndex(name: String, index: Int): String {
            return "$name[$index]"
        }
    }
}


//class Device(bus: String, number: Int) : Node("device")

fun chip(name: String, init: Chip.() -> Unit = {}) : Chip {
    val chip = Chip(name)
    chip.init()
    return chip
}

fun main(){
    val sb = StringBuilder()
    chip("soc/intel/skylake") {
        register("deep_sx_config", "DSX_EN_WAKE_PIN")
        pciDevice(4,0){
            subsystem(0x1849, 0xa131)
        }
    }.render(sb, "")

    println(sb.toString())
}