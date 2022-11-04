package com.smx.autoport

import com.smx.autoport.coreboot.Chip
import com.smx.autoport.coreboot.PnpDevice
import com.smx.autoport.coreboot.chip
import common.IntRangeEx.Companion.exclusive
import java.lang.RuntimeException
import java.lang.StringBuilder
import java.nio.charset.StandardCharsets

data class Register(val index: Int, val value: UByte?, val default: UByte?)

data class LDN(
    val index: Int,
    val names: Collection<String>,
    val registers: Map<Int, Register>
)

data class SuperIO(val name: String, val id: Int, val addr: Int,
                   val ldns: Map<Int, LDN>)

class SuperIOKnowledge(private val args: Array<String>) {
    private fun decodeValue(value: String): UByte? {
        when (value) {
            "NA", "RR", "MM" -> return null
        }
        if(value.startsWith("0x")) return Integer.decode(value).toUByte()
        return value.toInt(16).toUByte()
    }

    private fun parse(out:Sequence<String>): SuperIO {
        val iter = out.iterator()
        val (sioName, sioId, sioAddr) = iter.asSequence()
            .dropWhile { !it.startsWith("Found ") }
            .first()
            .let {
                val (name, idString, atString) = Regex("Found (.*) \\(id=(.*)\\) at (.*)")
                    .matchEntire(it)?.destructured ?: throw IllegalArgumentException()
                val id = Integer.decode(idString)
                val addr = Integer.decode(atString)
                Triple(name, id, addr)
            }

        val ldns = iter.asSequence().dropWhile { !it.startsWith("LDN ") }
            .chunked(4) {
                val (ldnLine, idxLine, valLine, defLine) = it
                val (ldnIndex, ldnNames) = ldnLine.let {
                    val (_, idxString, other) = it.split(' ', limit = 3)
                    val names = other
                        .substring(IntRange.exclusive(1, other.length - 1))
                        .split(Regex(",\\s+"))
                    Pair(Integer.decode(idxString), names)
                }
                val indices = idxLine.split(Regex("\\s+"))
                    .also { if(it.first() != "idx")
                        throw IllegalArgumentException() }
                    .drop(1)
                    .map { it.toInt(16) }

                val values = valLine.split(Regex("\\s+"))
                    .also { if(it.first() != "val")
                        throw IllegalArgumentException() }
                    .drop(1)
                    .map { decodeValue(it) }
                val defaults = defLine.split(Regex("\\s+"))
                    .also { if(it.first() != "def")
                        throw IllegalArgumentException() }
                    .drop(1)
                    .map { decodeValue(it) }

                val regs = indices.mapIndexed { index, itm -> Pair(index, itm) }
                    .associate { p ->
                        val(i, idx) = p
                        // <30, [0f, 00]>
                        Pair(idx, Register(idx, values[i], defaults[i]))
                    }

                LDN(ldnIndex, ldnNames, regs)
            }.associateBy { it.index }

        return SuperIO(sioName, sioId, sioAddr, ldns)
    }


    fun collect() : SuperIO {
        val proc = ProcessBuilder()
            .command(*args, "-d")
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .start()

        return proc.inputStream.bufferedReader(StandardCharsets.UTF_8)
            .useLines {
                parse(it)
            }
    }
}

fun maskExtractOnes(mask: UByte): Map<Int, Boolean> {
    val maskInt = mask.toInt()
    return IntRange.exclusive(0, UByte.SIZE_BITS)
        .associateWith { bitIndex ->
            (maskInt shr bitIndex) and 1 == 1 }
        .filterValues { it }
}

fun isPortRegister(regIndex: Int): Boolean {
    return regIndex in 0x60..0x63
}

fun ldnRegToDeviceTree(device: PnpDevice, ldn:LDN, reg: Register){
    val value = reg.value ?: return
    if(isPortRegister(reg.index)){
        when (reg.index) {
            0x60, 0x62 -> {
                if(!ldn.registers.containsKey(reg.index + 1)){
                    throw RuntimeException("Expected register ${reg.index + 1}, but not found")
                }
                val byte0Reg = ldn.registers[reg.index + 1]
                if(byte0Reg?.value == null){
                    throw RuntimeException("Expected register ${reg.index + 1}, but value not found")
                }

                val port = ((value.toUInt() shl 8) or byte0Reg.value.toUInt()).toUShort()
                device.io(reg.index, port.toInt())
            }
        }
    } else {
        // treat as irq
        device.irq(reg.index, reg.value.toInt())
    }
}

val idToRegs = hashMapOf(
    // GPIO7
    107 to arrayOf(0xE0, 0xE1),
    // GPIO3
    109 to arrayOf(0xE4, 0xE5, 0xEA),
    // GPIO4
    209 to arrayOf(0xF0, 0xF1),
    // GPIO5
    309 to arrayOf(0xF4, 0xF5)
)

fun ldnRegsToDeviceTree(device: PnpDevice, ldn: LDN){
    val isMultiDevice = ldn.names.size > 1
    if(!isMultiDevice){
        ldn.registers.values
            .forEach {
                ldnRegToDeviceTree(device, ldn, it)
            }
    } else if(idToRegs.containsKey(device.id)){
        val regs = idToRegs[device.id] ?: return
        ldn.registers.keys
            .intersect(regs.toSet())
            .map { ldn.registers[it] }
            .forEach {
                if(it != null) {
                    ldnRegToDeviceTree(device, ldn, it)
                }
            }
    }

}

fun ldnToDeviceTree(chip: Chip, sio: SuperIO, ldn: LDN){
    val enableMask = ldn.registers[0x30]
    if(enableMask?.value == null){
        // no register, let's assume disabled
        chip.pnpDevice(sio.addr, ldn.index, false)
        return
    }

    // convert the mask to a bit set
    val enabledDevices = maskExtractOnes(enableMask.value)

    enabledDevices.keys.map { idx ->
        // 108, 208, 308...
        val subDeviceId = (0x100  * idx) + ldn.index
        chip.pnpDevice(sio.addr, subDeviceId){
            ldnRegsToDeviceTree(this, ldn)
        }
    }
}

fun sioToDeviceTree(sio: SuperIO): Chip {
    val root = chip("superio/common"){
        pnpDevice(sio.addr, 0){
            val name = sio.name.lowercase().replace(' ', '/')
            chip("superio/$name"){
                sio.ldns.map { ldnToDeviceTree(this, sio, it.value) }
            }
        }
    }
    return root
}

fun main(args: Array<String>){
    val sio = SuperIOKnowledge(args).collect()
    val sb = StringBuilder()
    sioToDeviceTree(sio).render(sb, "")
    println(sb.toString())
}