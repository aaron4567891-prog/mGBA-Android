package com.aaron.mgbaandroid

object NativeBridge {
    init { System.loadLibrary("mgba_android") }

    external fun initialize(systemDir: String, saveDir: String): Boolean
    external fun loadRom(data: ByteArray, displayName: String, skipBios: Boolean): Boolean
    external fun unloadRom()
    external fun runFrame(frameCount: Int): IntArray?
    external fun takeAudio(): ShortArray
    external fun setButton(buttonId: Int, pressed: Boolean)
    external fun saveState(): ByteArray?
    external fun loadState(data: ByteArray): Boolean
    external fun readSaveRam(): ByteArray
    external fun writeSaveRam(data: ByteArray): Boolean
    external fun reset()
    external fun setCheat(index: Int, enabled: Boolean, code: String)
    external fun clearCheats()
    external fun videoWidth(): Int
    external fun videoHeight(): Int
    external fun audioRate(): Int
}
